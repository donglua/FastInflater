# FastInflater

高性能 Android 布局加载框架，通过 View 池化复用 + 智能预热 + 异步 inflate，从根本上解决 `LayoutInflater` 的性能瓶颈。

**适用场景：** 仍在维护大量 XML 布局的 Android 项目（尤其是 RecyclerView 密集列表场景）。如果你的项目已全面迁移到 Jetpack Compose，则不需要这个库。

## 为什么需要 FastInflater

`LayoutInflater` 是将 XML 布局转换为 View 树的核心组件，也是应用启动和页面跳转时主线程卡顿的常见元凶。

瓶颈集中在四个方面：

1. **反射创建对象** — `Class.forName().getConstructor().newInstance()` 的开销远大于直接 `new`，复杂页面几十上百个 View 的反射累加非常可观。
2. **IO + XML 解析** — 从 APK 读取二进制 XML 并逐节点深度优先遍历，低端机型上耗时明显。
3. **TypedArray 跨层解析** — 属性需结合 Theme/Style 计算，大量 Java-Native 跨层通信。
4. **GC 抖动** — 短时间内创建大量 LayoutParams、TypedArray 等临时对象，触发 GC 停顿。

## FastInflater 的解决策略

| 瓶颈 | 对策 |
|---|---|
| 反射 + XML 解析 | Phase 2 编译期 codegen，直接 `new View()` + `setXx()` |
| IO 读取 | Phase 3 native mmap 二进制属性表，零拷贝 |
| TypedArray 跨层 | codegen 预解析静态属性，运行时只处理动态值 |
| GC 抖动 | Phase 1 View 池化复用 + 自适应池大小 |

## 核心特性

- **View 池化复用** — 池命中时 inflate 耗时降至 0，避免重复创建和 GC
- **IdleHandler 预热** — 利用主线程空闲时间预创建 View，不抢占用户交互帧
- **异步 inflate** — 单线程后台 inflate，线程安全，池优先
- **自适应池大小** — 根据运行时统计数据自动调整各布局池容量
- **池命中率监控** — 全局和 per-layout 的 hit/miss 统计，指导调优
- **纳秒级耗时追踪** — 精确记录每次 inflate 耗时，识别热点布局
- **RecyclerView 无感集成** — 一行代码对接，无需修改 Adapter
- **DataBinding 支持** — `FastDataBinding` 兼容池化复用
- **自定义回收策略** — `ViewRecyclePolicy` 接口，精确控制 View 状态清理
- **内存压力响应** — 监听 trimMemory，分级清理池；Configuration 变化时自动清池

## Roadmap

- **Phase 1（当前）** — 运行时 View 池 + 智能预热 + 异步 inflate + 热点追踪 + 自适应调优
- **Phase 2** — Gradle Plugin 编译期代码生成，覆盖 Top-N 热点布局
- **Phase 3** — Native 属性表（mmap 二进制 blob）+ JNI 共享资源缓存

## Modules

- `lib/` — FastInflater 核心库
- `compiler/` — Gradle Plugin，编译期 codegen（Phase 2，实验性）
- `demo/` — 示例应用，包含 benchmark 和热点布局报告

## Quick Start

### 基础用法

```kotlin
// Application.onCreate
FastInflater.init(this)

// 预热高频布局（IdleHandler，不阻塞主线程）
FastInflater.get().warmUp(this, listOf(
    ViewPool.WarmUpEntry(R.layout.item_feed, count = 4),
    ViewPool.WarmUpEntry(R.layout.item_comment, count = 3),
))

// inflate（池命中时零耗时）
val view = FastInflater.get().inflate(context, R.layout.item_feed, parent)

// 回收（View 不再使用时）
FastInflater.get().recycle(R.layout.item_feed, view)

// 异步 inflate（池优先，未命中时后台线程 inflate）
FastInflater.get().inflateAsync(context, R.layout.item_feed, parent) { view ->
    parent.addView(view)
}
```

### RecyclerView 集成

最简单的方式——一行代码：

```kotlin
FastRecycledViewPool.install(recyclerView, warmUpLayouts = listOf(
    ViewPool.WarmUpEntry(R.layout.item_feed, 4)
))
```

让 `getItemViewType()` 返回 layoutId（这是常见做法），回收时自动进入 FastInflater 的池：

```kotlin
override fun getItemViewType(position: Int) = R.layout.item_feed

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
    val view = FastInflater.get().inflate(parent, viewType)
    return MyViewHolder(view)
}
```

### DataBinding

```kotlin
val binding = FastDataBinding.inflate<ItemFeedBinding>(
    context, R.layout.item_feed, parent
)
```

### 自定义回收策略

对于有复杂内部状态的布局，提供自定义清理逻辑：

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

### 自适应池大小

运行一段时间后，根据实际数据自动调优：

```kotlin
// 建议在 app 运行 3~5 分钟后调用
FastInflater.get().autoTune()
```

也可以手动设置单个布局的池大小：

```kotlin
FastInflater.get().setMaxPoolSize(R.layout.item_feed, 8)
FastInflater.get().setMaxPoolSize(R.layout.activity_detail, 2)
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
            Log.d("Inflate", "$name count=${stat.count.get()} avg=${stat.avgMs}ms avgUs=${stat.avgUs}us")
        }
}

InflateTracker.report()
```

### 池命中率

```kotlin
// 全局命中率
Log.d("Pool", "hit rate: ${(PoolStats.hitRate * 100).toInt()}%")

// 单个布局
Log.d("Pool", "item_feed hit rate: ${(PoolStats.hitRateFor(R.layout.item_feed) * 100).toInt()}%")

// 详细数据
PoolStats.snapshot().forEach { (layoutId, stat) ->
    Log.d("Pool", "layout=$layoutId hits=${stat.hits.get()} misses=${stat.misses.get()}")
}
```

**调优参考：**
- 命中率 < 50%：预热数量不足或池太小，增加 warmUp count 或调大 maxPoolSize
- 命中率 > 90%：池化充分发挥作用
- 命中率 100% 且池经常满：可以适当减小池大小，节省内存

