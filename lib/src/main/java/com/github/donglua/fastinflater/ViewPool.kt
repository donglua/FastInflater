package com.github.donglua.fastinflater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

class ViewPool {

    /** 监听 warmUp 过程的事件（主要用于诊断后台 inflate 失败 → 主线程降级）。 */
    interface WarmUpListener {
        /** 后台 inflate 抛异常，已记录该布局并自动 fallback 到主线程。 */
        fun onBackgroundInflateFailed(@LayoutRes layoutId: Int, error: Throwable) {}
        /** 布局被标记为只能主线程 inflate（含手动标记和自动检测两种情况）。 */
        fun onMarkedAsMainThreadOnly(@LayoutRes layoutId: Int) {}
    }

    private val pool = ConcurrentHashMap<PoolKey, ConcurrentLinkedDeque<View>>()
    private val policies = ConcurrentHashMap<Int, ViewRecyclePolicy>()
    private val perLayoutMaxSize = ConcurrentHashMap<Int, Int>()
    /** 已知必须在主线程 inflate 的布局，warmUp 会直接走主线程 IdleHandler。 */
    private val mainThreadOnly = ConcurrentHashMap.newKeySet<Int>()
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var warmUpListener: WarmUpListener? = null

    private var defaultMaxPoolSize = 4

    fun setMaxPoolSize(size: Int) {
        defaultMaxPoolSize = size
    }

    fun setMaxPoolSize(@LayoutRes layoutId: Int, size: Int) {
        perLayoutMaxSize[layoutId] = size
    }

    private fun maxSizeFor(@LayoutRes layoutId: Int): Int {
        return perLayoutMaxSize[layoutId] ?: defaultMaxPoolSize
    }

    fun registerPolicy(@LayoutRes layoutId: Int, policy: ViewRecyclePolicy) {
        policies[layoutId] = policy
    }

    fun obtain(@LayoutRes layoutId: Int, context: Context, parent: ViewGroup? = null): View? {
        val key = PoolKey(layoutId, fingerprint(context))
        return pool[key]?.poll()?.also { view ->
            val policy = policies[layoutId]
            if (policy != null) {
                policy.onObtain(view)
            } else {
                ViewCleaner.clean(view)
            }
        }
    }

    fun recycle(@LayoutRes layoutId: Int, view: View) {
        val policy = policies[layoutId]
        if (policy != null && !policy.canRecycle(view)) {
            return
        }
        val key = PoolKey(layoutId, fingerprint(view.context))
        val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
        if (deque.size < maxSizeFor(layoutId)) {
            policy?.onRecycle(view) ?: ViewCleaner.clean(view)
            deque.offer(view)
        }
    }

    /**
     * 设置 warmUp 监听器，可以观察后台 inflate 失败/降级事件。
     */
    fun setWarmUpListener(listener: WarmUpListener?) {
        warmUpListener = listener
    }

    /**
     * 显式标记某个布局只能在主线程 inflate。
     *
     * 适用于已知包含以下组件的布局：
     * - androidx.compose.ui.platform.ComposeView (内部依赖 LiveData)
     * - WebView / SurfaceView / TextureView
     * - 自定义 View 中在构造或 attach 流程访问 LiveData / Lifecycle
     *
     * 标记后，warmUp 会直接走主线程 IdleHandler，不会尝试后台 inflate。
     */
    fun markAsMainThreadOnly(@LayoutRes layoutId: Int) {
        if (mainThreadOnly.add(layoutId)) {
            warmUpListener?.onMarkedAsMainThreadOnly(layoutId)
        }
    }

    fun isMainThreadOnly(@LayoutRes layoutId: Int): Boolean = layoutId in mainThreadOnly

    fun warmUp(context: Context, @LayoutRes layoutId: Int, count: Int = 1) {
        // 已知必须主线程：直接走主线程 IdleHandler
        if (layoutId in mainThreadOnly) {
            warmUpOnMainIdle(context, layoutId, count)
            return
        }
        executor.execute {
            val inflater = LayoutInflater.from(context).cloneInContext(context)
            val key = PoolKey(layoutId, fingerprint(context))
            repeat(count) { i ->
                try {
                    val view = inflater.inflate(layoutId, null, false)
                    val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
                    if (deque.size < maxSizeFor(layoutId)) {
                        deque.offer(view)
                    }
                } catch (e: Throwable) {
                    // 后台 inflate 失败，多半是组件依赖主线程（ComposeView/LiveData/WebView 等）
                    // 标记后续 warmUp 走主线程，并把剩余预热数量降级到主线程
                    val newlyMarked = mainThreadOnly.add(layoutId)
                    warmUpListener?.onBackgroundInflateFailed(layoutId, e)
                    if (newlyMarked) {
                        warmUpListener?.onMarkedAsMainThreadOnly(layoutId)
                    }
                    val remaining = count - i
                    if (remaining > 0) {
                        warmUpOnMainIdle(context, layoutId, remaining)
                    }
                    return@execute
                }
            }
        }
    }

    /**
     * 在主线程 IdleHandler 中分批预热，避免抢占用户交互帧。
     * 每次 idle 只 inflate 1 个，避免长时间占用主线程。
     */
    private fun warmUpOnMainIdle(context: Context, @LayoutRes layoutId: Int, count: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { warmUpOnMainIdle(context, layoutId, count) }
            return
        }
        val key = PoolKey(layoutId, fingerprint(context))
        Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            private var remaining = count

            override fun queueIdle(): Boolean {
                if (remaining <= 0) return false
                try {
                    val view = LayoutInflater.from(context).inflate(layoutId, null, false)
                    val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
                    if (deque.size < maxSizeFor(layoutId)) {
                        deque.offer(view)
                    }
                } catch (_: Throwable) {
                    // 主线程也失败，说明布局本身有问题，放弃
                    return false
                }
                remaining--
                return remaining > 0
            }
        })
    }

    /**
     * 根据 InflateTracker 的统计数据自动调整各布局的池大小。
     * 高频布局分配更大的池，低频布局保持默认。
     *
     * @param topN 取 inflate 次数最多的前 N 个布局进行调整
     * @param minSize 最小池大小
     * @param maxSize 最大池大小
     */
    fun autoTune(topN: Int = 20, minSize: Int = 2, maxSize: Int = 12) {
        val top = InflateTracker.topN(topN)
        if (top.isEmpty()) return

        val maxCount = top.first().second.count.get()
        if (maxCount <= 0) return

        top.forEach { (layoutId, stat) ->
            val count = stat.count.get()
            // 按使用频率线性映射到 [minSize, maxSize]
            val suggested = (count.toFloat() / maxCount * (maxSize - minSize) + minSize)
                .toInt()
                .coerceIn(minSize, maxSize)
            perLayoutMaxSize[layoutId] = suggested
        }
    }

    fun warmUpOnIdle(context: Context, layouts: List<WarmUpEntry>) {
        android.os.Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            private var index = 0

            override fun queueIdle(): Boolean {
                if (index >= layouts.size) return false
                val entry = layouts[index++]
                warmUp(context, entry.layoutId, entry.count)
                return index < layouts.size
            }
        })
    }

    fun poolSize(@LayoutRes layoutId: Int, context: Context): Int {
        val key = PoolKey(layoutId, fingerprint(context))
        return pool[key]?.size ?: 0
    }

    fun clear() {
        pool.clear()
    }

    fun trimToSize(keep: Int) {
        pool.forEach { (_, deque) ->
            while (deque.size > keep) {
                deque.poll()
            }
        }
    }

    private fun fingerprint(context: Context): String {
        val inflater = LayoutInflater.from(context)
        val factory2 = inflater.factory2
        val factory = inflater.factory
        return when {
            factory2 != null -> factory2.javaClass.name
            factory != null -> factory.javaClass.name
            else -> ""
        }
    }

    private data class PoolKey(
        @param:LayoutRes val layoutId: Int,
        val factoryFingerprint: String
    )

    data class WarmUpEntry(
        @param:LayoutRes val layoutId: Int,
        val count: Int = 2
    )
}
