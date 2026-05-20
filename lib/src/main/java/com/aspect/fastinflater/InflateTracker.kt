package com.aspect.fastinflater

import android.os.SystemClock
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

    fun recordInflate(@LayoutRes layoutId: Int, durationMs: Long) {
        val stat = stats.getOrPut(layoutId) { LayoutStat() }
        stat.count.incrementAndGet()
        stat.totalMs.addAndGet(durationMs)
        if (durationMs > stat.maxMs.get()) {
            stat.maxMs.set(durationMs)
        }
    }

    fun snapshot(): Map<Int, LayoutStat> = HashMap(stats)

    fun topN(n: Int): List<Pair<Int, LayoutStat>> {
        return stats.entries
            .sortedByDescending { it.value.totalMs.get() }
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
        val start = SystemClock.elapsedRealtime()
        val result = block()
        recordInflate(layoutId, SystemClock.elapsedRealtime() - start)
        return result
    }

    class LayoutStat {
        val count = AtomicInteger(0)
        val totalMs = AtomicLong(0)
        val maxMs = AtomicLong(0)

        val avgMs: Long get() = if (count.get() > 0) totalMs.get() / count.get() else 0
    }
}
