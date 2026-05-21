package com.github.donglua.fastinflater.demo

import android.app.Application
import android.util.Log
import com.github.donglua.fastinflater.FastInflater
import com.github.donglua.fastinflater.InflateTracker
import com.github.donglua.fastinflater.demo.generated.FastInflaterGenerated

class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        FastInflater.init(this)
        FastInflater.get().setMaxPoolSize(8)

        // 注册 codegen 生成的布局（Phase 2）
        FastInflaterGenerated.registerAll(FastInflater.get().registry())

        InflateTracker.setReporter { stats ->
            stats.entries
                .sortedByDescending { it.value.totalNs.get() }
                .take(20)
                .forEach { (id, stat) ->
                    Log.d(
                        "InflateTracker",
                        "layout=${resources.getResourceEntryName(id)} " +
                            "count=${stat.count.get()} " +
                            "total=${stat.totalNs.get() / 1_000_000}ms " +
                            "avg=${stat.avgMs}ms " +
                            "max=${stat.maxNs.get() / 1_000_000}ms"
                    )
                }
        }
    }
}
