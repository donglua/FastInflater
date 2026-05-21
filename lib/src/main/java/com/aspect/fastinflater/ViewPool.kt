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
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )

    private var maxPoolSize = 4

    fun setMaxPoolSize(size: Int) {
        maxPoolSize = size
    }

    fun obtain(@LayoutRes layoutId: Int, context: Context, parent: ViewGroup? = null): View? {
        val key = PoolKey(layoutId, fingerprint(context))
        return pool[key]?.poll()?.also { view ->
            ViewCleaner.clean(view)
        }
    }

    fun recycle(@LayoutRes layoutId: Int, view: View) {
        val key = PoolKey(layoutId, fingerprint(view.context))
        val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
        if (deque.size < maxPoolSize) {
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
                    if (deque.size < maxPoolSize) {
                        deque.offer(view)
                    }
                } catch (_: Exception) {
                }
            }
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
