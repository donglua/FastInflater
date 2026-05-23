package com.github.donglua.fastinflater

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class FastInflater private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val viewPool = ViewPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    // 单线程避免 LayoutInflater 并发访问 Resources 的线程安全问题（Android 8.x 及以下）
    private val asyncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FastInflater-async").apply { isDaemon = true }
    }

    fun registerPolicy(@LayoutRes layoutId: Int, policy: ViewRecyclePolicy) {
        viewPool.registerPolicy(layoutId, policy)
    }

    init {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                viewPool.clear()
            }
            override fun onLowMemory() = viewPool.clear()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    viewPool.clear()
                } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                    viewPool.trimToSize(1)
                }
            }
        })
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    viewPool.clearForHost(activity)
                }
            }
        )
    }

    fun inflate(
        context: Context,
        @LayoutRes layoutId: Int,
        parent: ViewGroup? = null,
        attachToRoot: Boolean = false
    ): View {
        val pooled = viewPool.obtain(layoutId, context, parent)
        if (pooled != null) {
            PoolStats.recordHit(layoutId)
            InflateTracker.recordInflate(layoutId, 0)
            if (attachToRoot && parent != null) {
                parent.addView(pooled)
            }
            return pooled
        }

        PoolStats.recordMiss(layoutId)

        return InflateTracker.track(layoutId) {
            LayoutInflater.from(context).inflate(layoutId, parent, attachToRoot)
        }
    }

    fun inflate(
        parent: ViewGroup,
        @LayoutRes layoutId: Int,
        attachToRoot: Boolean = false
    ): View = inflate(parent.context, layoutId, parent, attachToRoot)

    fun inflateAsync(
        context: Context,
        @LayoutRes layoutId: Int,
        parent: ViewGroup? = null,
        callback: (View) -> Unit
    ) {
        val pooled = viewPool.obtain(layoutId, context, parent)
        if (pooled != null) {
            PoolStats.recordHit(layoutId)
            InflateTracker.recordInflate(layoutId, 0)
            callback(pooled)
            return
        }

        PoolStats.recordMiss(layoutId)
        val contextRef = WeakReference(context)
        val parentRef = parent?.let(::WeakReference)

        // 已知必须主线程：直接在主线程 inflate，避免后台崩溃
        if (viewPool.isMainThreadOnly(layoutId)) {
            val run = Runnable {
                val inflateContext = contextRef.get() ?: return@Runnable
                val inflateParent = parentRef?.get()
                val view = InflateTracker.track(layoutId) {
                    LayoutInflater.from(inflateContext).inflate(layoutId, inflateParent, false)
                }
                callback(view)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
            return
        }

        asyncExecutor.execute {
            val inflateContext = contextRef.get() ?: return@execute
            try {
                val inflateParent = parentRef?.get()
                val view = InflateTracker.track(layoutId) {
                    LayoutInflater.from(inflateContext).cloneInContext(inflateContext)
                        .inflate(layoutId, inflateParent, false)
                }
                inflateParent?.post { callback(view) } ?: callback(view)
            } catch (e: Throwable) {
                // 后台 inflate 失败，标记并降级到主线程重试
                viewPool.markAsMainThreadOnly(layoutId)
                mainHandler.post {
                    val mainContext = contextRef.get() ?: return@post
                    val mainParent = parentRef?.get()
                    val view = InflateTracker.track(layoutId) {
                        LayoutInflater.from(mainContext).inflate(layoutId, mainParent, false)
                    }
                    callback(view)
                }
            }
        }
    }

    fun recycle(@LayoutRes layoutId: Int, view: View) {
        viewPool.recycle(layoutId, view)
    }

    internal fun obtainFromPool(
        @LayoutRes layoutId: Int,
        context: Context,
        parent: ViewGroup?
    ): View? = viewPool.obtain(layoutId, context, parent)

    fun warmUp(context: Context, layouts: List<ViewPool.WarmUpEntry>) {
        viewPool.warmUpOnIdle(context, layouts)
    }

    fun warmUp(context: Context, @LayoutRes layoutId: Int, count: Int = 2) {
        viewPool.warmUp(context, layoutId, count)
    }

    fun setMaxPoolSize(size: Int) {
        viewPool.setMaxPoolSize(size)
    }

    fun setMaxPoolSize(@LayoutRes layoutId: Int, size: Int) {
        viewPool.setMaxPoolSize(layoutId, size)
    }

    /**
     * 根据 InflateTracker 统计数据自动调整各布局池大小。
     * 建议在应用运行一段时间后（如用户使用 5 分钟后）调用一次。
     */
    fun autoTune(topN: Int = 20, minSize: Int = 2, maxSize: Int = 12) {
        viewPool.autoTune(topN, minSize, maxSize)
    }

    /**
     * 显式标记某个布局只能在主线程 inflate。
     *
     * 适用于已知包含以下组件的布局：
     * - androidx.compose.ui.platform.ComposeView (内部依赖 LiveData)
     * - WebView / SurfaceView / TextureView
     * - 自定义 View 在构造或 attach 时访问 LiveData / Lifecycle
     *
     * 标记后，warmUp/inflateAsync 都会走主线程，避免后台 inflate 崩溃。
     * 即使不显式标记，FastInflater 在后台 inflate 失败时也会自动检测并标记。
     */
    fun markAsMainThreadOnly(@LayoutRes layoutId: Int) {
        viewPool.markAsMainThreadOnly(layoutId)
    }

    fun isMainThreadOnly(@LayoutRes layoutId: Int): Boolean {
        return viewPool.isMainThreadOnly(layoutId)
    }

    /**
     * 控制某个布局是否允许进入 FastInflater 池。
     *
     * 如果布局里包含生命周期敏感组件，例如自定义 View 在构造函数、onAttachedToWindow
     * 或业务 bind 阶段注册 EventBus、宿主 Lifecycle observer、Activity callback 等，
     * 且没有可靠的解绑/重置策略，应关闭池化。
     *
     * 关闭后该 layout 仍会通过正常 LayoutInflater inflate，但不会命中池、不会被 recycle
     * 保存，也不会被 warmUp 预创建。
     */
    fun setPoolingEnabled(@LayoutRes layoutId: Int, enabled: Boolean) {
        viewPool.setPoolingEnabled(layoutId, enabled)
    }

    fun isPoolingEnabled(@LayoutRes layoutId: Int): Boolean {
        return viewPool.isPoolingEnabled(layoutId)
    }

    /**
     * 监听 warmUp 失败/降级事件。用于诊断哪些布局触发了主线程降级。
     */
    fun setWarmUpListener(listener: ViewPool.WarmUpListener?) {
        viewPool.setWarmUpListener(listener)
    }

    /**
     * 启用 factory 隔离。开启后，pool 会区分不同 LayoutInflater.factory2 的 View，
     * 避免不同 Activity/Theme 间串池。
     *
     * 默认关闭。绝大多数项目全程使用同一个 AppCompat Factory2，不需要开启。
     * 开启后每次 obtain/recycle 都需要访问 LayoutInflater.factory2，有少量额外开销。
     *
     * 切换隔离模式时会清空池，因为旧 key 已失效。
     */
    fun setFactoryIsolation(enabled: Boolean) {
        viewPool.setFactoryIsolation(enabled)
    }

    /**
     * 开启/关闭 host（Activity）隔离。默认关闭。
     *
     * 开启后不同 Activity 的 View 不会串池，避免 context/theme 不一致导致的 UI 异常。
     * 关闭后所有 context 共享同一个桶，适合全局 theme 一致的项目（绝大多数情况）。
     *
     * 开启后建议用 Activity context 做 warmUp，以确保预热的 View 进入对应 Activity 的桶。
     * 切换时会清空池。
     */
    fun setHostIsolation(enabled: Boolean) {
        viewPool.setHostIsolation(enabled)
    }

    fun isHostIsolationEnabled(): Boolean {
        return viewPool.isHostIsolationEnabled()
    }

    /**
     * 一键开关诊断埋点（[InflateTracker] + [PoolStats]）。
     *
     * 默认开启——耗时追踪和命中率监控是这个库的核心诊断价值。
     * 调优完成后可以关闭，彻底消除热路径上的 `nanoTime` / 原子自增 / HashMap 查询开销。
     *
     * 也可以单独控制：[InflateTracker.enabled] / [PoolStats.enabled]。
     */
    fun setMetricsEnabled(enabled: Boolean) {
        InflateTracker.enabled = enabled
        PoolStats.enabled = enabled
    }

    companion object {
        @Volatile
        private var instance: FastInflater? = null

        fun init(context: Context): FastInflater {
            return instance ?: synchronized(this) {
                instance ?: FastInflater(context).also { instance = it }
            }
        }

        fun get(): FastInflater {
            return instance ?: throw IllegalStateException(
                "FastInflater not initialized. Call FastInflater.init(context) first."
            )
        }
    }
}
