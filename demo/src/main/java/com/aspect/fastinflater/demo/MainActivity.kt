package com.aspect.fastinflater.demo

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aspect.fastinflater.FastInflater
import com.aspect.fastinflater.InflateTracker
import com.aspect.fastinflater.ViewPool

class MainActivity : AppCompatActivity() {

    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Warm up with Activity context — preserves AppCompat Factory2
        FastInflater.get().warmUp(
            this,
            listOf(
                ViewPool.WarmUpEntry(R.layout.item_feed, 4),
            )
        )

        resultView = findViewById(R.id.result)

        findViewById<Button>(R.id.btn_system).setOnClickListener {
            runBenchmark(useFastInflater = false)
        }
        findViewById<Button>(R.id.btn_fast).setOnClickListener {
            runBenchmark(useFastInflater = true)
        }
        findViewById<Button>(R.id.btn_report).setOnClickListener {
            InflateTracker.report()
            resultView.text = buildString {
                append("Top inflate hot layouts:\n")
                InflateTracker.topN(10).forEach { (id, stat) ->
                    val name = resources.getResourceEntryName(id)
                    append(name).append(" ")
                    append("count=").append(stat.count.get()).append(" ")
                    append("total=").append(stat.totalMs.get()).append("ms ")
                    append("avg=").append(stat.avgMs).append("ms\n")
                }
            }
        }
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            InflateTracker.reset()
            resultView.text = "tracker reset"
        }
    }

    private fun runBenchmark(useFastInflater: Boolean) {
        val iterations = 200
        val fast = FastInflater.get()

        if (useFastInflater) {
            repeat(iterations) {
                val v = fast.inflate(this, R.layout.item_feed)
                fast.recycle(R.layout.item_feed, v)
            }
        }

        InflateTracker.reset()

        val start = SystemClock.elapsedRealtimeNanos()
        if (useFastInflater) {
            repeat(iterations) {
                val v = fast.inflate(this, R.layout.item_feed)
                fast.recycle(R.layout.item_feed, v)
            }
        } else {
            repeat(iterations) {
                LayoutInflater.from(this).inflate(R.layout.item_feed, null, false)
            }
        }
        val durationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

        val label = if (useFastInflater) "FastInflater (pool hit)" else "LayoutInflater"
        resultView.text = buildString {
            append(label).append("\n")
            append("iterations=").append(iterations).append("\n")
            append("total=").append("%.2f".format(durationMs)).append("ms\n")
            append("avg=").append("%.3f".format(durationMs / iterations)).append("ms/inflate")
        }
    }
}
