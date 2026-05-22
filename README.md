# FastInflater

[![CI](https://github.com/donglua/FastInflater/actions/workflows/ci.yml/badge.svg)](https://github.com/donglua/FastInflater/actions/workflows/ci.yml)

Android XML 布局性能诊断与优化工具。通过 inflate 耗时追踪定位热点布局，通过 View 池化复用降低重复 inflate 开销。

**适用场景：** 仍在维护 XML 布局的 Android 项目，尤其是 RecyclerView 密集列表、Tab 切换、Dialog 反复弹出等高频 inflate 场景。如果你的项目已全面迁移到 Jetpack Compose，则不需要这个库。

## 引入

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.donglua:FastInflater:0.3.0")
}
```

## 它能做什么

**诊断：** 告诉你哪个布局慢、慢多少、被 inflate 了多少次、池命中率是多少。这些信息在 Android 现有工具链里没有现成替代品。

**优化：** 对高频重复 inflate 的布局，池命中时耗时从几十 ms 降到 0。不是所有场景都有用，但有用的场景收益是 100%。

## 核心特性

- **纳秒级耗时追踪** — 精确记录每次 inflate 耗时，按布局拆分，识别热点
- **池命中率监控** — 全局和 per-layout 的 hit/miss 统计，用数据指导调优
- **View 池化复用** — 池命中时 inflate 耗时降至 0，避免重复创建和 GC
- **IdleHandler 预热** — 利用主线程空闲时间预创建 View，不抢占用户交互帧
- **异步 inflate** — 后台线程 inflate，池优先；含 ComposeView 等主线程依赖的布局自动降级
- **自适应池大小** — 根据运行时统计数据自动调整各布局池容量
- **RecyclerView 集成** — 预热加速 ViewHolder 首次创建
- **DataBinding 支持** — `FastDataBinding` 兼容池化复用
- **自定义回收策略** — `ViewRecyclePolicy` 接口，精确控制 View 状态清理
- **内存压力响应** — 监听 trimMemory 分级清理；Configuration 变化自动清池

## Quick Start

### 初始化 + 预热

```kotlin
// Application.onCreate
FastInflater.init(this)

// 预热高频布局（IdleHandler，不阻塞主线程）
FastInflater.get().warmUp(this, listOf(
    ViewPool.WarmUpEntry(R.layout.item_feed, count = 4),
    ViewPool.WarmUpEntry(R.layout.item_comment, count = 3),
))

// 包含 ComposeView/WebView 的布局，标记为主线程预热
FastInflater.get().markAsMainThreadOnly(R.layout.fragment_compose)
```

### inflate + 回收

```kotlin
// 池命中时零耗时
val view = FastInflater.get().inflate(context, R.layout.item_feed, parent)

// View 不再使用时回收进池
FastInflater.get().recycle(R.layout.item_feed, view)

// 异步 inflate（池优先，未命中时后台 inflate，主线程回调）
FastInflater.get().inflateAsync(context, R.layout.item_feed, parent) { view ->
    parent.addView(view)
}
```

### RecyclerView 集成

```kotlin
// 预热 + 安装创建侧加速
FastInflaterRecycler.install(recyclerView, warmUpLayouts = listOf(
    ViewPool.WarmUpEntry(R.layout.item_feed, 4)
))

// 也可以只预热，不替换 RecyclerView.RecycledViewPool
FastInflaterRecycler.preload(recyclerView, R.layout.item_feed, count = 4)

// Adapter 中让 viewType == layoutId
override fun getItemViewType(position: Int) = R.layout.item_feed

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
    val view = FastInflater.get().inflate(parent, viewType)
    return MyViewHolder(view)
}
```

FastInflater 只在 `onCreateViewHolder` 创建侧加速，不参与 RecyclerView 的回收流程，不会出现双池冲突。

### DataBinding

```kotlin
val binding = FastDataBinding.inflate<ItemFeedBinding>(
    context, R.layout.item_feed, parent
)
```

### 自定义回收策略

```kotlin
FastInflater.get().registerPolicy(R.layout.item_feed, object : ViewRecyclePolicy {
    override fun onRecycle(view: View) {
        view.findViewById<ImageView>(R.id.avatar).setImageDrawable(null)
        view.findViewById<TextView>(R.id.title).text = null
    }
    override fun canRecycle(view: View): Boolean {
        return !view.isAttachedToWindow
    }
})
```

## 监控与调优

### 热点布局追踪

```kotlin
InflateTracker.setReporter { stats ->
    stats.entries
        .sortedByDescending { it.value.totalNs.get() }
        .take(20)
        .forEach { (id, stat) ->
            val name = resources.getResourceEntryName(id)
            Log.d("Inflate", "$name count=${stat.count.get()} avg=${stat.avgMs}ms")
        }
}

InflateTracker.report()
```

### 池命中率

```kotlin
Log.d("Pool", "hit rate: ${(PoolStats.hitRate * 100).toInt()}%")
Log.d("Pool", "item_feed: ${(PoolStats.hitRateFor(R.layout.item_feed) * 100).toInt()}%")
```

**调优参考：**
- 命中率 < 50%：预热数量不足或池太小
- 命中率 > 90%：池化充分发挥作用
- 命中率 100% 且池经常满：可以适当减小池大小节省内存

### 自适应池大小

```kotlin
// 运行 3~5 分钟后，根据实际数据自动调优
FastInflater.get().autoTune()

// 或手动设置
FastInflater.get().setMaxPoolSize(R.layout.item_feed, 8)
```

### 关闭埋点（可选）

诊断埋点（`InflateTracker` + `PoolStats`）默认开启——这是这个库的核心诊断价值。调优完成、上线后如果想彻底消除热路径上的 `nanoTime` / 原子自增 / HashMap 查询开销，可以一键关闭：

```kotlin
// 一键关闭两个埋点
FastInflater.get().setMetricsEnabled(false)

// 或单独控制
InflateTracker.enabled = false
PoolStats.enabled = false
```

关闭后不会执行耗时计时、原子自增或统计 Map 写入；`inflate` 只保留池查询/回退 inflate 所需的最小逻辑。

### 主线程依赖的布局

部分 View 不能后台 inflate（ComposeView、WebView、含 LiveData 的自定义 View）。FastInflater 会自动检测并降级到主线程 IdleHandler，也可以预先标记：

```kotlin
FastInflater.get().markAsMainThreadOnly(R.layout.fragment_compose_view)

// 监听降级事件
FastInflater.get().setWarmUpListener(object : ViewPool.WarmUpListener {
    override fun onBackgroundInflateFailed(layoutId: Int, error: Throwable) {
        Log.w("FastInflater", "fallback: ${resources.getResourceEntryName(layoutId)}")
    }
})
```

## Modules

- `lib/` — FastInflater 核心库
- `demo/` — 示例应用，包含 benchmark 和热点布局报告

## License

Apache 2.0
