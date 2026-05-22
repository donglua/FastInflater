package com.github.donglua.fastinflater

import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 池命中率统计。用于评估预热策略是否有效、池大小是否合理。
 *
 * 使用方式：
 * ```
 * // 查看全局命中率
 * val rate = PoolStats.hitRate  // 0.0 ~ 1.0
 *
 * // 查看某个布局的命中率
 * val feedRate = PoolStats.hitRateFor(R.layout.item_feed)
 *
 * // 导出所有数据
 * PoolStats.snapshot().forEach { (layoutId, stat) ->
 *     Log.d("Pool", "layout=$layoutId hits=${stat.hits} misses=${stat.misses} rate=${stat.hitRate}")
 * }
 * ```
 */
object PoolStats {

    /**
     * 全局开关。默认开启——命中率数据是这个库的核心诊断价值。
     * 调优完成、上线后可以关掉以彻底消除热路径上的原子自增和 HashMap 查询。
     */
    @Volatile
    var enabled: Boolean = true

    private val global = Stat()
    private val perLayout = ConcurrentHashMap<Int, Stat>()

    @PublishedApi
    internal fun recordHit(@LayoutRes layoutId: Int) {
        if (!enabled) return
        global.hits.incrementAndGet()
        perLayout.getOrPut(layoutId) { Stat() }.hits.incrementAndGet()
    }

    @PublishedApi
    internal fun recordMiss(@LayoutRes layoutId: Int) {
        if (!enabled) return
        global.misses.incrementAndGet()
        perLayout.getOrPut(layoutId) { Stat() }.misses.incrementAndGet()
    }

    val hitRate: Double get() = global.hitRate

    val totalHits: Long get() = global.hits.get()
    val totalMisses: Long get() = global.misses.get()

    fun hitRateFor(@LayoutRes layoutId: Int): Double {
        return perLayout[layoutId]?.hitRate ?: 0.0
    }

    fun statFor(@LayoutRes layoutId: Int): Stat? = perLayout[layoutId]

    fun snapshot(): Map<Int, Stat> = HashMap(perLayout)

    fun reset() {
        global.hits.set(0)
        global.misses.set(0)
        perLayout.clear()
    }

    class Stat {
        val hits = AtomicLong(0)
        val misses = AtomicLong(0)

        val hitRate: Double
            get() {
                val total = hits.get() + misses.get()
                return if (total > 0) hits.get().toDouble() / total else 0.0
            }
    }
}
