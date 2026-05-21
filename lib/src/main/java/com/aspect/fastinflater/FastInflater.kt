package com.aspect.fastinflater

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.util.concurrent.Executors

class FastInflater private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val viewPool = ViewPool()
    private val registry = GeneratedLayoutRegistry()
    // 单线程避免 LayoutInflater 并发访问 Resources 的线程安全问题（Android 8.x 及以下）
    private val asyncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FastInflater-async").apply { isDaemon = true }
    }

    fun registry(): GeneratedLayoutRegistry = registry

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
            InflateTracker.recordInflate(layoutId, 0)
            if (attachToRoot && parent != null) {
                parent.addView(pooled)
            }
            return pooled
        }

        val creator = registry.resolve(context, layoutId)
        if (creator != null) {
            return InflateTracker.track(layoutId) {
                creator.create(context, parent, attachToRoot)
            }
        }

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
            InflateTracker.recordInflate(layoutId, 0)
            callback(pooled)
            return
        }

        val creator = registry.resolve(context, layoutId)
        asyncExecutor.execute {
            val view = InflateTracker.track(layoutId) {
                if (creator != null) {
                    creator.create(context, parent, false)
                } else {
                    LayoutInflater.from(context).cloneInContext(context)
                        .inflate(layoutId, parent, false)
                }
            }
            parent?.post { callback(view) } ?: callback(view)
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
