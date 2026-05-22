package com.github.donglua.fastinflater

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PoolStatsTest {

    @Before
    fun setUp() {
        PoolStats.reset()
        PoolStats.enabled = true
    }

    // --- 1. recordHit increments global and per-layout hits ---

    @Test
    fun `recordHit increments global hits`() {
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.totalHits).isEqualTo(1)
    }

    @Test
    fun `recordHit increments per-layout hits`() {
        PoolStats.recordHit(LAYOUT_A)

        val stat = PoolStats.statFor(LAYOUT_A)
        assertThat(stat).isNotNull()
        assertThat(stat!!.hits.get()).isEqualTo(1)
    }

    @Test
    fun `multiple recordHit calls accumulate`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.totalHits).isEqualTo(3)
        assertThat(PoolStats.statFor(LAYOUT_A)!!.hits.get()).isEqualTo(3)
    }

    // --- 2. recordMiss increments global and per-layout misses ---

    @Test
    fun `recordMiss increments global misses`() {
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.totalMisses).isEqualTo(1)
    }

    @Test
    fun `recordMiss increments per-layout misses`() {
        PoolStats.recordMiss(LAYOUT_A)

        val stat = PoolStats.statFor(LAYOUT_A)
        assertThat(stat).isNotNull()
        assertThat(stat!!.misses.get()).isEqualTo(1)
    }

    @Test
    fun `multiple recordMiss calls accumulate`() {
        PoolStats.recordMiss(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.totalMisses).isEqualTo(2)
        assertThat(PoolStats.statFor(LAYOUT_A)!!.misses.get()).isEqualTo(2)
    }

    // --- 3. hitRate computes correctly ---

    @Test
    fun `hitRate is 0_75 for 3 hits and 1 miss`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(0.75)
    }

    @Test
    fun `hitRate is 1_0 when all hits`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(1.0)
    }

    @Test
    fun `hitRate is 0_0 when all misses`() {
        PoolStats.recordMiss(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun `hitRate is 0_5 for equal hits and misses`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(0.5)
    }

    // --- 4. hitRate is 0.0 when no records ---

    @Test
    fun `hitRate is 0_0 when no records exist`() {
        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(0.0)
    }

    // --- 5. hitRateFor returns per-layout rate ---

    @Test
    fun `hitRateFor returns correct rate for specific layout`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.hitRateFor(LAYOUT_A)).isWithin(TOLERANCE).of(2.0 / 3.0)
    }

    @Test
    fun `hitRateFor returns layout-specific rate independent of other layouts`() {
        // Layout A: 1 hit, 0 misses -> 1.0
        PoolStats.recordHit(LAYOUT_A)
        // Layout B: 0 hits, 1 miss -> 0.0
        PoolStats.recordMiss(LAYOUT_B)

        assertThat(PoolStats.hitRateFor(LAYOUT_A)).isWithin(TOLERANCE).of(1.0)
        assertThat(PoolStats.hitRateFor(LAYOUT_B)).isWithin(TOLERANCE).of(0.0)
    }

    // --- 6. hitRateFor returns 0.0 for unknown layout ---

    @Test
    fun `hitRateFor returns 0_0 for unknown layout`() {
        assertThat(PoolStats.hitRateFor(LAYOUT_UNKNOWN)).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun `hitRateFor returns 0_0 for unknown layout when other layouts have data`() {
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.hitRateFor(LAYOUT_UNKNOWN)).isWithin(TOLERANCE).of(0.0)
    }

    // --- 7. enabled=false skips recording ---

    @Test
    fun `disabled stats skips recordHit`() {
        PoolStats.enabled = false
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.totalHits).isEqualTo(0)
        assertThat(PoolStats.statFor(LAYOUT_A)).isNull()
    }

    @Test
    fun `disabled stats skips recordMiss`() {
        PoolStats.enabled = false
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.totalMisses).isEqualTo(0)
        assertThat(PoolStats.statFor(LAYOUT_A)).isNull()
    }

    @Test
    fun `re-enabling stats resumes recording`() {
        PoolStats.enabled = false
        PoolStats.recordHit(LAYOUT_A)

        PoolStats.enabled = true
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.totalHits).isEqualTo(1)
    }

    @Test
    fun `disabled stats does not create per-layout entry`() {
        PoolStats.enabled = false
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_B)

        assertThat(PoolStats.snapshot()).isEmpty()
    }

    // --- 8. reset clears global and per-layout stats ---

    @Test
    fun `reset clears global hits and misses`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        PoolStats.reset()

        assertThat(PoolStats.totalHits).isEqualTo(0)
        assertThat(PoolStats.totalMisses).isEqualTo(0)
    }

    @Test
    fun `reset clears per-layout data`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_B)

        PoolStats.reset()

        assertThat(PoolStats.statFor(LAYOUT_A)).isNull()
        assertThat(PoolStats.statFor(LAYOUT_B)).isNull()
        assertThat(PoolStats.snapshot()).isEmpty()
    }

    @Test
    fun `reset restores hitRate to 0`() {
        PoolStats.recordHit(LAYOUT_A)

        PoolStats.reset()

        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(0.0)
    }

    // --- 9. snapshot returns a copy ---

    @Test
    fun `snapshot returns a defensive copy`() {
        PoolStats.recordHit(LAYOUT_A)

        val snapshot = PoolStats.snapshot()
        PoolStats.recordHit(LAYOUT_B)

        // The snapshot should not contain LAYOUT_B since it was taken before that record
        assertThat(snapshot).containsKey(LAYOUT_A)
        assertThat(snapshot).doesNotContainKey(LAYOUT_B)
    }

    @Test
    fun `snapshot is not empty when data exists`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_B)

        val snapshot = PoolStats.snapshot()

        assertThat(snapshot).hasSize(2)
        assertThat(snapshot).containsKey(LAYOUT_A)
        assertThat(snapshot).containsKey(LAYOUT_B)
    }

    @Test
    fun `snapshot is empty when no data`() {
        assertThat(PoolStats.snapshot()).isEmpty()
    }

    @Test
    fun `mutating snapshot does not affect PoolStats`() {
        PoolStats.recordHit(LAYOUT_A)

        val snapshot = PoolStats.snapshot() as MutableMap
        snapshot.clear()

        // Original data should still exist
        assertThat(PoolStats.statFor(LAYOUT_A)).isNotNull()
        assertThat(PoolStats.snapshot()).hasSize(1)
    }

    // --- 10. statFor returns null for unknown layout ---

    @Test
    fun `statFor returns null for unknown layout`() {
        assertThat(PoolStats.statFor(LAYOUT_UNKNOWN)).isNull()
    }

    @Test
    fun `statFor returns null for unknown layout when other layouts exist`() {
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.statFor(LAYOUT_UNKNOWN)).isNull()
    }

    @Test
    fun `statFor returns non-null for recorded layout`() {
        PoolStats.recordHit(LAYOUT_A)

        assertThat(PoolStats.statFor(LAYOUT_A)).isNotNull()
    }

    // --- 11. totalHits and totalMisses accessors ---

    @Test
    fun `totalHits starts at 0`() {
        assertThat(PoolStats.totalHits).isEqualTo(0)
    }

    @Test
    fun `totalMisses starts at 0`() {
        assertThat(PoolStats.totalMisses).isEqualTo(0)
    }

    @Test
    fun `totalHits reflects hits across all layouts`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_B)
        PoolStats.recordHit(LAYOUT_C)

        assertThat(PoolStats.totalHits).isEqualTo(3)
    }

    @Test
    fun `totalMisses reflects misses across all layouts`() {
        PoolStats.recordMiss(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_B)

        assertThat(PoolStats.totalMisses).isEqualTo(2)
    }

    @Test
    fun `totalHits does not include misses and vice versa`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_A)

        assertThat(PoolStats.totalHits).isEqualTo(1)
        assertThat(PoolStats.totalMisses).isEqualTo(1)
    }

    // --- 12. Multiple layouts tracked independently ---

    @Test
    fun `multiple layouts have independent hit counts`() {
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_B)

        assertThat(PoolStats.statFor(LAYOUT_A)!!.hits.get()).isEqualTo(2)
        assertThat(PoolStats.statFor(LAYOUT_B)!!.hits.get()).isEqualTo(1)
    }

    @Test
    fun `multiple layouts have independent miss counts`() {
        PoolStats.recordMiss(LAYOUT_A)
        PoolStats.recordMiss(LAYOUT_B)
        PoolStats.recordMiss(LAYOUT_B)
        PoolStats.recordMiss(LAYOUT_B)

        assertThat(PoolStats.statFor(LAYOUT_A)!!.misses.get()).isEqualTo(1)
        assertThat(PoolStats.statFor(LAYOUT_B)!!.misses.get()).isEqualTo(3)
    }

    @Test
    fun `multiple layouts have independent hit rates`() {
        // Layout A: 2 hits, 0 misses -> 1.0
        PoolStats.recordHit(LAYOUT_A)
        PoolStats.recordHit(LAYOUT_A)

        // Layout B: 1 hit, 1 miss -> 0.5
        PoolStats.recordHit(LAYOUT_B)
        PoolStats.recordMiss(LAYOUT_B)

        // Layout C: 0 hits, 3 misses -> 0.0
        PoolStats.recordMiss(LAYOUT_C)
        PoolStats.recordMiss(LAYOUT_C)
        PoolStats.recordMiss(LAYOUT_C)

        assertThat(PoolStats.hitRateFor(LAYOUT_A)).isWithin(TOLERANCE).of(1.0)
        assertThat(PoolStats.hitRateFor(LAYOUT_B)).isWithin(TOLERANCE).of(0.5)
        assertThat(PoolStats.hitRateFor(LAYOUT_C)).isWithin(TOLERANCE).of(0.0)

        // Global: 3 hits, 4 misses -> 3/7
        assertThat(PoolStats.hitRate).isWithin(TOLERANCE).of(3.0 / 7.0)
    }

    @Test
    fun `recording on one layout does not affect another`() {
        PoolStats.recordHit(LAYOUT_A)

        val statB = PoolStats.statFor(LAYOUT_B)
        assertThat(statB).isNull()
    }

    // --- 13. Concurrent hits/misses from multiple threads ---

    @Test
    fun `concurrent hits are all counted`() {
        val threadCount = 10
        val opsPerThread = 1000
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        PoolStats.recordHit(LAYOUT_A)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val expected = (threadCount * opsPerThread).toLong()
        assertThat(PoolStats.totalHits).isEqualTo(expected)
        assertThat(PoolStats.statFor(LAYOUT_A)!!.hits.get()).isEqualTo(expected)
    }

    @Test
    fun `concurrent misses are all counted`() {
        val threadCount = 10
        val opsPerThread = 1000
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    repeat(opsPerThread) {
                        PoolStats.recordMiss(LAYOUT_A)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val expected = (threadCount * opsPerThread).toLong()
        assertThat(PoolStats.totalMisses).isEqualTo(expected)
        assertThat(PoolStats.statFor(LAYOUT_A)!!.misses.get()).isEqualTo(expected)
    }

    @Test
    fun `concurrent mixed hits and misses across multiple layouts`() {
        val threadCount = 8
        val opsPerThread = 500
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val layouts = intArrayOf(LAYOUT_A, LAYOUT_B, LAYOUT_C)

        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    val layout = layouts[threadIndex % layouts.size]
                    repeat(opsPerThread) {
                        if (it % 2 == 0) {
                            PoolStats.recordHit(layout)
                        } else {
                            PoolStats.recordMiss(layout)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val expectedTotal = (threadCount * opsPerThread).toLong()
        assertThat(PoolStats.totalHits + PoolStats.totalMisses).isEqualTo(expectedTotal)
    }

    // --- Stat class unit tests ---

    @Test
    fun `Stat hitRate is 0 when empty`() {
        val stat = PoolStats.Stat()

        assertThat(stat.hitRate).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun `Stat hitRate computes correctly`() {
        val stat = PoolStats.Stat()
        stat.hits.set(3)
        stat.misses.set(1)

        assertThat(stat.hitRate).isWithin(TOLERANCE).of(0.75)
    }

    companion object {
        private const val LAYOUT_A = 0x7f0a0001
        private const val LAYOUT_B = 0x7f0a0002
        private const val LAYOUT_C = 0x7f0a0003
        private const val LAYOUT_UNKNOWN = 0x7f0affff
        private const val TOLERANCE = 1e-9
    }
}
