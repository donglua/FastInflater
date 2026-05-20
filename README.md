# FastInflater

高性能 Android 布局加载框架，目标是从根本上解决 `LayoutInflater` 的性能瓶颈。

## 为什么需要 FastInflater

在 Android 开发中，`LayoutInflater` 是将 XML 布局文件转换为 View 树的核心组件，但它也是应用启动和页面跳转时导致主线程卡顿（掉帧）的常见元凶。

`LayoutInflater` 的性能瓶颈主要集中在以下四个方面：

### 1. 反射创建对象的开销（核心瓶颈）

这是 `LayoutInflater` 最耗时的环节。当解析到 XML 中的一个节点（例如 `<TextView>` 或 `<com.example.MyCustomView>`）时，系统需要将这个字符串名字实例化为真正的对象。

- **字符串转换：** 系统会通过 `Class.forName(name).getConstructor(...).newInstance(...)` 的反射机制来创建 View。
- **性能损耗：** 反射本身的性能开销远大于直接 `new` 一个对象。虽然 Android 内部对构造函数做了一定缓存（通过 HashMap 缓存 Constructor），但在一个稍微复杂的页面中，包含几十甚至上百个 View 时，这些反射的累加耗时仍然非常可观。

### 2. IO 操作与 XML 解析

虽然 Android 在打包 APK 时会将明文的 XML 布局预编译为二进制 XML（Binary XML），以此提高解析速度并减小体积，但加载过程仍有不可忽视的瓶颈：

- **IO 读取：** 系统依然需要通过底层文件系统，将二进制 XML 数据从 APK 中读取到内存中，这本质上属于 IO 操作，在低端机型上耗时明显。
- **树形遍历：** `LayoutInflater` 会调用 `XmlResourceParser`（基于 `XmlPullParser`）逐个节点进行深度优先遍历。布局嵌套层级越深，递归解析和回溯的计算量就越大。

### 3. 属性解析与主题合并 (TypedArray)

单纯创建出 View 还不算完，每个 View 标签内都附带了大量的属性（如 `layout_width`、`textColor` 等）。

- **跨层调用：** 系统需要读取这些属性，并结合当前的 Theme、Style 进行综合计算，生成 `TypedArray` 供 View 读取。
- **Native 通信：** 这个过程大量依赖 `AssetManager`，涉及到频繁的 Java 层与 Native 层（C++）跨层通信和资源查找，计算量非常密集。

### 4. 内存分配与 GC 抖动 (Memory Churn)

- **对象爆炸：** 一次 inflate 操作不仅仅会创建 View 实例本身，还会同时创建大量的 `LayoutParams`、`TypedArray`、以及解析过程中产生的各类中间临时小对象。
- **触发 GC：** 在极短的时间内申请大量内存，极易触发垃圾回收（GC）。如果此时在主线程发生 GC 停顿，就会导致明显的掉帧卡顿。

> 一句话总结：LayoutInflater 慢在"一边做 IO 读文件，一边用低效的反射造对象，还要频繁跨层解析无数的属性，最后引发一波 GC"。

## FastInflater 的解决策略

| 瓶颈 | FastInflater 对策 |
|---|---|
| 反射 + XML 解析 | Phase 2 编译期 codegen，直接 `new View()` + `setXx()`，跳过反射和 XML |
| IO 读取 | Phase 3 native mmap 二进制属性表，一次映射零拷贝 |
| TypedArray 跨层 | codegen 阶段预解析静态属性，运行时只处理主题动态值 |
| GC 抖动 | Phase 1 View 池化复用，避免重复创建和销毁 |

## Roadmap

- **Phase 1（当前）** — 运行时 View 池 + IdleHandler 预热 + 多线程异步 inflate + 热点布局采集
- **Phase 2** — KSP 编译期代码生成，覆盖 Top-N 热点布局
- **Phase 3** — Native 属性表（mmap 二进制 blob）+ JNI 共享资源缓存

Roadmap:
- **Phase 1 (this repo)** — runtime pool + tracker. Zero build changes.
- **Phase 2** — KSP codegen for the top-N hottest layouts.
- **Phase 3** — Native attr table (mmap'd binary blob) for the long tail, with JNI shared resource cache.

## Modules

- `lib/` — the FastInflater library.
- `demo/` — sample app with a benchmark screen (system `LayoutInflater` vs `FastInflater`) and a hot-layout report.

## Quick start

```kotlin
// Application
FastInflater.init(this)
FastInflater.get().warmUp(listOf(
    ViewPool.WarmUpEntry(R.layout.item_feed, count = 4),
))

// Anywhere
val view = FastInflater.get().inflate(R.layout.item_feed, parent)

// Recycle when the view is no longer needed (e.g. RecyclerView onViewRecycled)
FastInflater.get().recycle(R.layout.item_feed, view)

// Async path (non-blocking, falls through to the pool first)
FastInflater.get().inflateAsync(R.layout.item_feed, parent) { view ->
    parent.addView(view)
}
```

## Hot-layout tracking (debug builds)

```kotlin
InflateTracker.setReporter { stats ->
    stats.entries
        .sortedByDescending { it.value.totalMs.get() }
        .take(50)
        .forEach { (id, stat) ->
            Log.d("Inflate", "id=$id count=${stat.count.get()} avg=${stat.avgMs}ms")
        }
}

// Trigger anywhere — typically on background or screen-leave
InflateTracker.report()
```

Once you have a few days of real-world data, feed the top-N list into Phase 2 codegen.

## Build

Open in Android Studio. The Gradle wrapper jar is generated on first sync (gradle 8.5, AGP 8.2, Kotlin 1.9.22, JDK 11, minSdk 24).
