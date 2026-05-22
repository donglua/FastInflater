package com.github.donglua.fastinflater

import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object InflateTracker {

    /**
     * 全局开关。默认开启——耗时追踪是这个库的核心诊断价值。
     * 调优完成后可关闭，彻底消除热路径上的 nanoTime / HashMap / CAS 开销。
     *
     * 关闭后 [track] 仅调用 block()，不计时；[recordInflate] 直接 return。
     */
    @Volatile
    var enabled: Boolean = true

    private val stats = ConcurrentHashMap<Int, LayoutStat>()
    private var reporter: ((Map<Int, LayoutStat>) -> Unit)? = null

    fun setReporter(block: (Map<Int, LayoutStat>) -> Unit) {
        reporter = block
    }

    fun recordInflate(@LayoutRes layoutId: Int, durationNs: Long) {
        if (!enabled) return
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
        if (!enabled) return block()
        val start = System.nanoTime()
        val result = block()
        recordInflate(layoutId, System.nanoTime() - start)
        return result
    }

    class LayoutStat {
        val count = AtomicInteger(0)
        val totalNs = AtomicLong(0)
        val maxNs = AtomicLong(0)

        val totalMs: Long get() = totalNs.get() / 1_000_000
        val maxMs: Long get() = maxNs.get() / 1_000_000
        val avgMs: Long get() = if (count.get() > 0) totalNs.get() / 1_000_000 / count.get() else 0
        val avgUs: Long get() = if (count.get() > 0) totalNs.get() / 1_000 / count.get() else 0
    }
}
