package com.aspect.fastinflater.demo

import android.app.Application
import android.util.Log
import com.aspect.fastinflater.FastInflater
import com.aspect.fastinflater.InflateTracker

class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        FastInflater.init(this)
        FastInflater.get().setMaxPoolSize(8)

        InflateTracker.setReporter { stats ->
            stats.entries
                .sortedByDescending { it.value.totalMs.get() }
                .take(20)
                .forEach { (id, stat) ->
                    Log.d(
                        "InflateTracker",
                        "layout=${resources.getResourceEntryName(id)} " +
                            "count=${stat.count.get()} " +
                            "total=${stat.totalMs.get()}ms " +
                            "avg=${stat.avgMs}ms " +
                            "max=${stat.maxMs.get()}ms"
                    )
                }
        }
    }
}
