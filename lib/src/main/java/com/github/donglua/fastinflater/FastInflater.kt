package com.github.donglua.fastinflater

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
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

        // 已知必须主线程：直接在主线程 inflate，避免后台崩溃
        if (viewPool.isMainThreadOnly(layoutId)) {
            val run = Runnable {
                val view = InflateTracker.track(layoutId) {
                    LayoutInflater.from(context).inflate(layoutId, parent, false)
                }
                callback(view)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
            return
        }

        asyncExecutor.execute {
            try {
                val view = InflateTracker.track(layoutId) {
                    LayoutInflater.from(context).cloneInContext(context)
                        .inflate(layoutId, parent, false)
                }
                parent?.post { callback(view) } ?: callback(view)
            } catch (e: Throwable) {
                // 后台 inflate 失败，标记并降级到主线程重试
                viewPool.markAsMainThreadOnly(layoutId)
                mainHandler.post {
                    val view = InflateTracker.track(layoutId) {
                        LayoutInflater.from(context).inflate(layoutId, parent, false)
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
