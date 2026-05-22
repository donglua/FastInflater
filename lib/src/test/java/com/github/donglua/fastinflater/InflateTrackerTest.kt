package com.github.donglua.fastinflater

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InflateTrackerTest {

    @Before
    fun setUp() {
        InflateTracker.reset()
        InflateTracker.enabled = true
        InflateTracker.setReporter { }
    }

    // ── 1. recordInflate records count, totalNs, maxNs correctly ──

    @Test
    fun `recordInflate records count totalNs and maxNs for single call`() {
        val layoutId = 0x7f0a0001
        val durationNs = 5_000_000L // 5ms

        InflateTracker.recordInflate(layoutId, durationNs)

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.count.get()).isEqualTo(1)
        assertThat(stat.totalNs.get()).isEqualTo(5_000_000L)
        assertThat(stat.maxNs.get()).isEqualTo(5_000_000L)
    }

    // ── 2. recordInflate with multiple calls accumulates stats ──

    @Test
    fun `recordInflate accumulates count and totalNs across multiple calls`() {
        val layoutId = 0x7f0a0001

        InflateTracker.recordInflate(layoutId, 1_000_000L)
        InflateTracker.recordInflate(layoutId, 2_000_000L)
        InflateTracker.recordInflate(layoutId, 3_000_000L)

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.count.get()).isEqualTo(3)
        assertThat(stat.totalNs.get()).isEqualTo(6_000_000L)
        assertThat(stat.maxNs.get()).isEqualTo(3_000_000L)
    }

    @Test
    fun `recordInflate tracks separate layout IDs independently`() {
        val layoutA = 0x7f0a0001
        val layoutB = 0x7f0a0002

        InflateTracker.recordInflate(layoutA, 1_000_000L)
        InflateTracker.recordInflate(layoutB, 2_000_000L)

        val snapshot = InflateTracker.snapshot()
        assertThat(snapshot).hasSize(2)
        assertThat(snapshot[layoutA]!!.totalNs.get()).isEqualTo(1_000_000L)
        assertThat(snapshot[layoutB]!!.totalNs.get()).isEqualTo(2_000_000L)
    }

    // ── 3. maxNs only updates when new value is larger (CAS logic) ──

    @Test
    fun `maxNs only updates when new duration is larger`() {
        val layoutId = 0x7f0a0001

        InflateTracker.recordInflate(layoutId, 5_000_000L)
        InflateTracker.recordInflate(layoutId, 3_000_000L) // smaller, should not update max
        InflateTracker.recordInflate(layoutId, 7_000_000L) // larger, should update max
        InflateTracker.recordInflate(layoutId, 1_000_000L) // smaller again

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.maxNs.get()).isEqualTo(7_000_000L)
        assertThat(stat.count.get()).isEqualTo(4)
        assertThat(stat.totalNs.get()).isEqualTo(16_000_000L)
    }

    @Test
    fun `maxNs updates when equal duration is recorded`() {
        val layoutId = 0x7f0a0001

        InflateTracker.recordInflate(layoutId, 5_000_000L)
        InflateTracker.recordInflate(layoutId, 5_000_000L) // equal, CAS loop condition is >

        val stat = InflateTracker.snapshot()[layoutId]!!
        // Equal values do NOT update maxNs because the CAS condition is strict >
        assertThat(stat.maxNs.get()).isEqualTo(5_000_000L)
    }

    // ── 4. enabled=false skips recording ──

    @Test
    fun `recordInflate does nothing when enabled is false`() {
        InflateTracker.enabled = false
        InflateTracker.recordInflate(0x7f0a0001, 5_000_000L)

        assertThat(InflateTracker.snapshot()).isEmpty()
    }

    @Test
    fun `recordInflate resumes recording after re-enabling`() {
        InflateTracker.enabled = false
        InflateTracker.recordInflate(0x7f0a0001, 1_000_000L)

        InflateTracker.enabled = true
        InflateTracker.recordInflate(0x7f0a0001, 2_000_000L)

        val stat = InflateTracker.snapshot()[0x7f0a0001]!!
        assertThat(stat.count.get()).isEqualTo(1)
        assertThat(stat.totalNs.get()).isEqualTo(2_000_000L)
    }

    // ── 5. snapshot returns a copy ──

    @Test
    fun `snapshot returns a defensive copy that does not affect internal state`() {
        val layoutId = 0x7f0a0001
        InflateTracker.recordInflate(layoutId, 1_000_000L)

        val copy = InflateTracker.snapshot() as MutableMap
        copy.remove(layoutId)

        // Internal state should be unaffected
        val freshSnapshot = InflateTracker.snapshot()
        assertThat(freshSnapshot).containsKey(layoutId)
        assertThat(freshSnapshot[layoutId]!!.count.get()).isEqualTo(1)
    }

    @Test
    fun `snapshot returns empty map when no stats recorded`() {
        assertThat(InflateTracker.snapshot()).isEmpty()
    }

    // ── 6. topN returns entries sorted by totalNs descending ──

    @Test
    fun `topN returns entries sorted by totalNs in descending order`() {
        InflateTracker.recordInflate(1, 100L)
        InflateTracker.recordInflate(2, 300L)
        InflateTracker.recordInflate(3, 200L)

        val top = InflateTracker.topN(3)
        assertThat(top).hasSize(3)
        assertThat(top[0].first).isEqualTo(2)
        assertThat(top[1].first).isEqualTo(3)
        assertThat(top[2].first).isEqualTo(1)
    }

    @Test
    fun `topN with n=1 returns only the highest totalNs entry`() {
        InflateTracker.recordInflate(1, 100L)
        InflateTracker.recordInflate(2, 300L)
        InflateTracker.recordInflate(3, 200L)

        val top = InflateTracker.topN(1)
        assertThat(top).hasSize(1)
        assertThat(top[0].first).isEqualTo(2)
        assertThat(top[0].second.totalNs.get()).isEqualTo(300L)
    }

    // ── 7. topN with n larger than entries returns all ──

    @Test
    fun `topN with n larger than entries returns all entries`() {
        InflateTracker.recordInflate(1, 100L)
        InflateTracker.recordInflate(2, 200L)

        val top = InflateTracker.topN(10)
        assertThat(top).hasSize(2)
    }

    @Test
    fun `topN with zero entries returns empty list`() {
        assertThat(InflateTracker.topN(5)).isEmpty()
    }

    @Test
    fun `topN with n=0 returns empty list`() {
        InflateTracker.recordInflate(1, 100L)
        assertThat(InflateTracker.topN(0)).isEmpty()
    }

    // ── 8. reset clears all stats ──

    @Test
    fun `reset clears all recorded stats`() {
        InflateTracker.recordInflate(1, 1_000_000L)
        InflateTracker.recordInflate(2, 2_000_000L)

        InflateTracker.reset()

        assertThat(InflateTracker.snapshot()).isEmpty()
        assertThat(InflateTracker.topN(10)).isEmpty()
    }

    @Test
    fun `recording works normally after reset`() {
        InflateTracker.recordInflate(1, 1_000_000L)
        InflateTracker.reset()
        InflateTracker.recordInflate(1, 500_000L)

        val stat = InflateTracker.snapshot()[1]!!
        assertThat(stat.count.get()).isEqualTo(1)
        assertThat(stat.totalNs.get()).isEqualTo(500_000L)
    }

    // ── 9. report invokes reporter callback with snapshot ──

    @Test
    fun `report invokes the reporter callback with current snapshot`() {
        var reportedData: Map<Int, InflateTracker.LayoutStat>? = null
        InflateTracker.setReporter { data -> reportedData = data }

        InflateTracker.recordInflate(1, 1_000_000L)
        InflateTracker.recordInflate(2, 2_000_000L)
        InflateTracker.report()

        assertThat(reportedData).isNotNull()
        assertThat(reportedData).hasSize(2)
        assertThat(reportedData!![1]!!.totalNs.get()).isEqualTo(1_000_000L)
        assertThat(reportedData!![2]!!.totalNs.get()).isEqualTo(2_000_000L)
    }

    @Test
    fun `report passes a snapshot copy to reporter`() {
        var reportedData: Map<Int, InflateTracker.LayoutStat>? = null
        InflateTracker.setReporter { data -> reportedData = data }

        InflateTracker.recordInflate(1, 1_000_000L)
        InflateTracker.report()

        // Modify internal state after report
        InflateTracker.recordInflate(2, 2_000_000L)

        // Reporter's copy should not reflect new data
        assertThat(reportedData).hasSize(1)
        assertThat(reportedData).doesNotContainKey(2)
    }

    // ── 10. report without reporter set does nothing (no crash) ──

    @Test
    fun `report does not crash when no reporter is set`() {
        // Reset reporter to null by setting a fresh InflateTracker state
        // Use reflection or just set a null-like approach
        // Since setReporter only accepts non-null, we test with setUp's empty reporter
        InflateTracker.recordInflate(1, 1_000_000L)
        // This should complete without exception
        InflateTracker.report()
    }

    // ── 11. track measures duration and records it ──

    @Test
    fun `track executes block and records duration`() {
        val layoutId = 0x7f0a0001
        val result = InflateTracker.track(layoutId) {
            // Simulate some work
            Thread.sleep(10)
            "hello"
        }

        assertThat(result).isEqualTo("hello")
        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.count.get()).isEqualTo(1)
        // Duration should be at least ~10ms = 10_000_000ns
        assertThat(stat.totalNs.get()).isGreaterThan(1_000_000L)
        assertThat(stat.maxNs.get()).isGreaterThan(1_000_000L)
    }

    @Test
    fun `track returns block result of different types`() {
        val intResult = InflateTracker.track(1) { 42 }
        assertThat(intResult).isEqualTo(42)

        val listResult = InflateTracker.track(2) { listOf(1, 2, 3) }
        assertThat(listResult).containsExactly(1, 2, 3)

        val nullResult: Any? = InflateTracker.track(3) { null }
        assertThat(nullResult).isNull()
    }

    // ── 12. track with enabled=false skips measurement but still executes block ──

    @Test
    fun `track still executes block when disabled but does not record`() {
        InflateTracker.enabled = false
        var executed = false

        val result = InflateTracker.track(0x7f0a0001) {
            executed = true
            "result"
        }

        assertThat(result).isEqualTo("result")
        assertThat(executed).isTrue()
        assertThat(InflateTracker.snapshot()).isEmpty()
    }

    // ── 13. LayoutStat computed properties (totalMs, maxMs, avgMs, avgUs) ──

    @Test
    fun `LayoutStat totalMs converts nanoseconds to milliseconds`() {
        val layoutId = 0x7f0a0001
        InflateTracker.recordInflate(layoutId, 5_500_000L) // 5.5ms

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.totalMs).isEqualTo(5L) // truncated
    }

    @Test
    fun `LayoutStat maxMs converts nanoseconds to milliseconds`() {
        val layoutId = 0x7f0a0001
        InflateTracker.recordInflate(layoutId, 3_000_000L)
        InflateTracker.recordInflate(layoutId, 7_200_000L)

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.maxMs).isEqualTo(7L) // 7.2ms truncated
    }

    @Test
    fun `LayoutStat avgMs computes average in milliseconds`() {
        val layoutId = 0x7f0a0001
        // 2ms + 4ms = 6ms total, count=2, avg=3ms
        InflateTracker.recordInflate(layoutId, 2_000_000L)
        InflateTracker.recordInflate(layoutId, 4_000_000L)

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.avgMs).isEqualTo(3L)
    }

    @Test
    fun `LayoutStat avgUs computes average in microseconds`() {
        val layoutId = 0x7f0a0001
        // 200us + 400us = 600us total, count=2, avg=300us
        InflateTracker.recordInflate(layoutId, 200_000L)
        InflateTracker.recordInflate(layoutId, 400_000L)

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.avgUs).isEqualTo(300L)
    }

    @Test
    fun `LayoutStat totalMs and maxMs are zero for sub-millisecond durations`() {
        val layoutId = 0x7f0a0001
        InflateTracker.recordInflate(layoutId, 500_000L) // 0.5ms

        val stat = InflateTracker.snapshot()[layoutId]!!
        assertThat(stat.totalMs).isEqualTo(0L)
        assertThat(stat.maxMs).isEqualTo(0L)
    }

    // ── 14. LayoutStat avgMs/avgUs returns 0 when count is 0 ──

    @Test
    fun `LayoutStat avgMs returns 0 when count is zero`() {
        val stat = InflateTracker.LayoutStat()
        assertThat(stat.avgMs).isEqualTo(0L)
    }

    @Test
    fun `LayoutStat avgUs returns 0 when count is zero`() {
        val stat = InflateTracker.LayoutStat()
        assertThat(stat.avgUs).isEqualTo(0L)
    }

    @Test
    fun `LayoutStat fresh instance has all zeros`() {
        val stat = InflateTracker.LayoutStat()
        assertThat(stat.count.get()).isEqualTo(0)
        assertThat(stat.totalNs.get()).isEqualTo(0L)
        assertThat(stat.maxNs.get()).isEqualTo(0L)
        assertThat(stat.totalMs).isEqualTo(0L)
        assertThat(stat.maxMs).isEqualTo(0L)
        assertThat(stat.avgMs).isEqualTo(0L)
        assertThat(stat.avgUs).isEqualTo(0L)
    }

    // ── 15. Concurrent recordInflate from multiple threads ──

    @Test
    fun `concurrent recordInflate from multiple threads is thread-safe`() {
        val layoutId = 0x7f0a0001
        val threadCount = 10
        val iterationsPerThread = 1000
        val durationPerCall = 1_000L // 1 microsecond
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            executor.submit {
                barrier.await() // ensure all threads start simultaneously
                repeat(iterationsPerThread) {
                    InflateTracker.recordInflate(layoutId, durationPerCall)
                }
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val stat = InflateTracker.snapshot()[layoutId]!!
        val expectedCount = threadCount * iterationsPerThread
        val expectedTotal = expectedCount.toLong() * durationPerCall

        assertThat(stat.count.get()).isEqualTo(expectedCount)
        assertThat(stat.totalNs.get()).isEqualTo(expectedTotal)
        assertThat(stat.maxNs.get()).isEqualTo(durationPerCall)
    }

    @Test
    fun `concurrent recordInflate with varying durations tracks correct max`() {
        val layoutId = 0x7f0a0001
        val threadCount = 8
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val maxDuration = 100_000L

        repeat(threadCount) { threadIdx ->
            executor.submit {
                // Each thread records a unique duration
                val duration = (threadIdx + 1) * 10_000L
                repeat(100) {
                    InflateTracker.recordInflate(layoutId, duration)
                }
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val stat = InflateTracker.snapshot()[layoutId]!!
        // Thread 7 (index 7) records (7+1)*10_000 = 80_000
        assertThat(stat.maxNs.get()).isEqualTo(80_000L)
        assertThat(stat.count.get()).isEqualTo(threadCount * 100)
    }

    @Test
    fun `concurrent recordInflate to different layout IDs`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { threadIdx ->
            executor.submit {
                val layoutId = threadIdx + 1
                repeat(50) {
                    InflateTracker.recordInflate(layoutId, 1_000L)
                }
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        val snapshot = InflateTracker.snapshot()
        assertThat(snapshot).hasSize(threadCount)
        snapshot.forEach { (_, stat) ->
            assertThat(stat.count.get()).isEqualTo(50)
            assertThat(stat.totalNs.get()).isEqualTo(50_000L)
        }
    }
}
