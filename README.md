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
    implementation("com.github.donglua:FastInflater:v0.5.0")
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
- **自定义 View 自清理** — 自定义 View 可实现 `PoolableView`，回收时清理内部脏状态
- **生命周期敏感布局保护** — 可按 layout 关闭池化，避免 EventBus/Lifecycle 监听脱离宿主
- **生命周期与内存压力响应** — Activity 销毁、Configuration 变化、trimMemory 时自动清池

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

// 包含宿主 Lifecycle/EventBus 注册且无法可靠解绑的布局，关闭池化
FastInflater.get().setPoolingEnabled(R.layout.item_lifecycle_sensitive, false)
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

默认 `ViewCleaner` 只清理 Android 基础 View 状态，例如文本、图片、listener、alpha、translation、scale、scroll 和动画。业务自定义状态不会被自动识别，例如：

- 自定义 View 内部的展开/折叠变量、选中缓存、加载状态
- 运行时替换的特殊背景、前景、Drawable callback
- bind 阶段写入但下一次 bind 不一定覆盖的自定义属性

这类 layout 必须补充清理逻辑，否则复用时可能出现上一条数据的头像、背景、展开状态挂到下一条数据上的 UI 错乱。

单个自定义 View 可以实现 `PoolableView`：

```kotlin
class ExpandableUserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), PoolableView {

    private var expanded = false

    override fun onRecycleForPool() {
        expanded = false
        background = null
    }
}
```

布局级状态或多个子 View 的组合状态，使用 `ViewRecyclePolicy`：

```kotlin
FastInflater.get().registerPolicy(R.layout.item_feed, object : ViewRecyclePolicy {
    override fun onRecycle(view: View) {
        ViewCleaner.clean(view)
        view.findViewById<ImageView>(R.id.avatar).setImageDrawable(null)
        view.findViewById<TextView>(R.id.title).text = null
    }
    override fun canRecycle(view: View): Boolean {
        return !view.isAttachedToWindow
    }
})
```

### 生命周期敏感布局

`markAsMainThreadOnly()` 只解决后台 inflate 问题，不解决“复用后的生命周期归属”问题。

如果 XML 中存在自定义 View，在构造函数、`onAttachedToWindow()` 或业务 bind 阶段注册了 EventBus、宿主 `LifecycleObserver`、Activity callback、广播监听等外部资源，进入池后这些注册关系可能继续存活，导致后台持续收事件、引用旧 Activity，甚至在旧宿主销毁后崩溃。

FastInflater 会在 Activity 销毁时清空全局 View 池，避免池中 View 跨宿主生命周期长期持有旧 Activity context。这个保护不能替代业务解绑：同一个 Activity 生命周期内的复用仍需要布局自身清理干净。

处理规则：

- 能可靠解绑：注册 `ViewRecyclePolicy`，在 `onRecycle()` 中注销 EventBus/Lifecycle/callback，并在 `onObtain()` 中恢复可复用初始状态。
- 不能可靠解绑：关闭该 layout 的池化。

```kotlin
// 直接禁用池化：不会命中池、不会保存 recycle 的 View、不会 warmUp 预创建
FastInflater.get().setPoolingEnabled(R.layout.item_lifecycle_sensitive, false)

// 或者提供完整解绑策略
FastInflater.get().registerPolicy(R.layout.item_lifecycle_sensitive, object : ViewRecyclePolicy {
    override fun onRecycle(view: View) {
        val lifecycleView = view.findViewById<MyLifecycleView>(R.id.lifecycle_view)
        lifecycleView.unbindLifecycle()
        lifecycleView.unregisterEventBus()
        ViewCleaner.clean(view)
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

关闭后 `PoolStats.recordHit()` / `InflateTracker.recordInflate()` 等记录入口仍可能被调用，但会通过 `enabled` 快速返回；不会执行耗时计时、原子自增或统计 Map 写入。`inflate` 只保留池查询/回退 inflate 所需的最小逻辑。

### 主线程依赖的布局

部分 View 不能后台 inflate（ComposeView、WebView、含 LiveData/Handler/GestureDetector 的自定义 View）。FastInflater 会优先识别已知主线程组件，并直接降级到主线程 IdleHandler；未知自定义 View 仍会先尝试后台 inflate，如果触发主线程依赖异常，会记录该布局和 View 类，后续 warmUp 不再反复后台试错。也可以预先标记：

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
