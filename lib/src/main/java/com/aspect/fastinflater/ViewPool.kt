package com.aspect.fastinflater

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

class ViewPool {

    private val pool = ConcurrentHashMap<PoolKey, ConcurrentLinkedDeque<View>>()
    private val policies = ConcurrentHashMap<Int, ViewRecyclePolicy>()
    private val perLayoutMaxSize = ConcurrentHashMap<Int, Int>()
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )

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

    fun warmUp(context: Context, @LayoutRes layoutId: Int, count: Int = 1) {
        executor.execute {
            val inflater = LayoutInflater.from(context).cloneInContext(context)
            val key = PoolKey(layoutId, fingerprint(context))
            repeat(count) {
                try {
                    val view = inflater.inflate(layoutId, null, false)
                    val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
                    if (deque.size < maxSizeFor(layoutId)) {
                        deque.offer(view)
                    }
                } catch (_: Exception) {
                }
            }
        }
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
