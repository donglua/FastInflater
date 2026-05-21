package com.aspect.fastinflater

import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object InflateTracker {

    private val stats = ConcurrentHashMap<Int, LayoutStat>()
    private var reporter: ((Map<Int, LayoutStat>) -> Unit)? = null

    fun setReporter(block: (Map<Int, LayoutStat>) -> Unit) {
        reporter = block
    }

    fun recordInflate(@LayoutRes layoutId: Int, durationNs: Long) {
        val stat = stats.getOrPut(layoutId) { LayoutStat() }
        stat.count.incrementAndGet()
        stat.totalNs.addAndGet(durationNs)
        var cur = stat.maxNs.get()
        while (durationNs > cur) {
            if (stat.maxNs.compareAndSet(cur, durationNs)) break
            cur = stat.maxNs.get()
        }
    }

    fun snapshot(): Map<Int, LayoutStat> = HashMap(stats)

    fun topN(n: Int): List<Pair<Int, LayoutStat>> {
        return stats.entries
            .sortedByDescending { it.value.totalNs.get() }
            .take(n)
            .map { it.key to it.value }
    }

    fun report() {
        reporter?.invoke(snapshot())
    }

    fun reset() {
        stats.clear()
    }

    inline fun <T> track(@LayoutRes layoutId: Int, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        recordInflate(layoutId, System.nanoTime() - start)
        return result
    }

    class LayoutStat {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
        val maxNs = AtomicLong(0)

        /** 兼容旧 API：总耗时（毫秒） */
        val totalMs: AtomicLong get() = AtomicLong(totalNs.get() / 1_000_000)
        val maxMs: AtomicLong get() = AtomicLong(maxNs.get() / 1_000_000)

        val avgMs: Long get() = if (count.get() > 0) totalNs.get() / 1_000_000 / count.get() else 0
        val avgUs: Long get() = if (count.get() > 0) totalNs.get() / 1_000 / count.get() else 0
    }
}
