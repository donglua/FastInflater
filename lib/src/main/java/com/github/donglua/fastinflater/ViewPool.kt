package com.github.donglua.fastinflater

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TableLayout
import android.widget.TableRow
import androidx.annotation.LayoutRes
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ViewPool {

    /** 监听 warmUp 过程的事件（主要用于诊断后台 inflate 失败 → 主线程降级）。 */
    interface WarmUpListener {
        /** 后台 inflate 抛异常，已记录该布局并自动 fallback 到主线程。 */
        fun onBackgroundInflateFailed(@LayoutRes layoutId: Int, error: Throwable) {}
        /** 布局被标记为只能主线程 inflate（含手动标记和自动检测两种情况）。 */
        fun onMarkedAsMainThreadOnly(@LayoutRes layoutId: Int) {}
    }

    /**
     * Pool key 用 Long 表示，避免每次 obtain/recycle 分配对象。
     * - 高 32 位：layoutId
     * - 低 32 位：隔离 hash（hostHash xor factoryHash，两者都关时为 0）
     */
    private val pool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<View>>()
    private val policies = ConcurrentHashMap<Int, ViewRecyclePolicy>()
    private val perLayoutMaxSize = ConcurrentHashMap<Int, Int>()
    private val warmingCounts = ConcurrentHashMap<Long, AtomicInteger>()
    private val layoutParamsCompatibility = ConcurrentHashMap<LayoutParamsCompatibilityKey, Boolean>()
    private val poolGeneration = AtomicInteger(0)
    /** 已知必须在主线程 inflate 的布局，warmUp 会直接走主线程 IdleHandler。 */
    private val mainThreadOnly = ConcurrentHashMap.newKeySet<Int>()
    /** 已知必须在主线程创建的 View 类，避免相同自定义 View 在其他布局中反复后台试错。 */
    private val mainThreadOnlyViewClasses = ConcurrentHashMap.newKeySet<String>()
    /** 已知不应复用的布局，例如包含宿主 Lifecycle/EventBus 绑定的自定义 View。 */
    private val poolingDisabled = ConcurrentHashMap.newKeySet<Int>()
    private val executor = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var warmUpListener: WarmUpListener? = null

    @Volatile
    private var factoryIsolation = false

    /**
     * 开启/关闭 host（Activity）隔离。默认关闭。
     *
     * 开启后 pool key 包含 Activity identityHashCode，
     * 不同 Activity 的 View 不会串池，避免 context/theme 不一致导致的 UI 异常。
     *
     * 注意：开启后 Application context 预热的 View 进入独立桶（hostHash=0），
     * 不会被 Activity context 的 obtain 命中。请改用 Activity context 做 warmUp。
     *
     * 关闭时所有 context 共享同一个桶，适合全局 theme 一致的项目（绝大多数情况）。
     */
    @Volatile
    private var hostIsolation = false

    private var defaultMaxPoolSize = 4

    fun setMaxPoolSize(size: Int) {
        defaultMaxPoolSize = size
    }

    fun setMaxPoolSize(@LayoutRes layoutId: Int, size: Int) {
        perLayoutMaxSize[layoutId] = size
    }

    private fun maxSizeFor(@LayoutRes layoutId: Int): Int {
        return perLayoutMaxSize[layoutId] ?: defaultMaxPoolSize
    }

    fun registerPolicy(@LayoutRes layoutId: Int, policy: ViewRecyclePolicy) {
        policies[layoutId] = policy
    }

    /**
     * 启用 factory 隔离。开启后 pool key 会同时考虑 LayoutInflater.factory2 的类型，
     * 避免不同 Activity/Theme（持有不同 Factory2）下的 View 串池。
     *
     * 默认关闭。绝大多数项目全程使用同一个 AppCompat Factory2，不需要开启。
     * 开启后每次 obtain/recycle 都需要访问 LayoutInflater.factory2，有少量额外开销。
     *
     * 切换隔离模式时会清空池，因为旧 key 已失效。
     */
    fun setFactoryIsolation(enabled: Boolean) {
        if (factoryIsolation == enabled) return
        factoryIsolation = enabled
        clear()
    }

    fun isFactoryIsolationEnabled(): Boolean = factoryIsolation

    /**
     * 开启/关闭 host（Activity）隔离。默认关闭。
     *
     * 开启后不同 Activity 的 View 不会串池，Application context 预热的 View 进入共享桶。
     * 关闭后所有 context 共享同一个桶，适合全局 theme 一致的项目（绝大多数情况）。
     *
     * 切换时会清空池。
     */
    fun setHostIsolation(enabled: Boolean) {
        if (hostIsolation == enabled) return
        hostIsolation = enabled
        clear()
    }

    fun isHostIsolationEnabled(): Boolean = hostIsolation

    /**
     * 清除指定 Activity 关联的所有池条目。
     * 在 Activity.onDestroy 时调用，避免池中 View 持有已销毁 Activity 的 context。
     *
     * - hostIsolation 关闭：退化为 [clear]（无法按 host 区分）。
     * - hostIsolation + factoryIsolation 同时开启：低 32 位是 hostHash xor factoryHash，
     *   无法仅凭 activityHash 精确匹配，退化为 [clear] 避免泄漏。
     * - 仅 hostIsolation 开启：精确删除低 32 位等于 activityHash 的条目。
     */
    fun clearForHost(activity: Activity) {
        if (!hostIsolation || factoryIsolation) {
            clear()
            return
        }
        poolGeneration.incrementAndGet()
        val activityHash = System.identityHashCode(activity)
        val mask = 0xFFFFFFFFL
        pool.keys.removeIf { key ->
            (key and mask).toInt() == activityHash
        }
        warmingCounts.keys.removeIf { key ->
            (key and mask).toInt() == activityHash
        }
    }

    /**
     * 控制某个布局是否允许进入 FastInflater 池。
     *
     * 对于在构造、attach 或 bind 阶段注册全局 EventBus、宿主 Lifecycle observer、
     * Activity callback 等生命周期敏感组件的布局，应关闭池化。关闭后：
     * - [obtain] 永远不会返回池中旧 View
     * - [recycle] 会直接丢弃传入 View
     * - [warmUp] 不会预创建该布局
     * - 已经缓存的同 layout View 会被移除
     */
    fun setPoolingEnabled(@LayoutRes layoutId: Int, enabled: Boolean) {
        if (enabled) {
            poolingDisabled.remove(layoutId)
        } else if (poolingDisabled.add(layoutId)) {
            clearLayout(layoutId)
        }
    }

    fun isPoolingEnabled(@LayoutRes layoutId: Int): Boolean {
        return !isPoolingDisabled(layoutId)
    }

    /**
     * 从池中取一个 View。热路径：poll + 父容器 LayoutParams 兼容性检查 +
     * 可选的 policy.onObtain() 钩子。
     *
     * 池中的 View 在 [recycle] 时已经被清理过（默认 [ViewCleaner.clean] 或 [ViewRecyclePolicy.onRecycle]），
     * View 处于 detached 状态、不会被外部修改，所以这里不再重复清理整棵 View 树。
     *
     * 并发安全：用有界尝试（snapshot size）替代无界 while 循环，避免并发 offer 导致的活锁。
     * 不兼容的 View 暂存本地列表，最后统一放回队尾，保留给后续兼容的 parent 使用。
     */
    fun obtain(@LayoutRes layoutId: Int, context: Context, parent: ViewGroup? = null): View? {
        if (isPoolingDisabled(layoutId)) return null
        val key = keyFor(layoutId, context)
        return pollCompatible(key, layoutId, context, parent)
    }

    /**
     * 从指定 deque 中 poll 出第一个与 parent 兼容的 View。
     * 最多尝试 deque 当前 size 次（快照），不兼容的 View 暂存后统一放回队尾。
     */
    private fun pollCompatible(
        key: Long,
        @LayoutRes layoutId: Int,
        context: Context,
        parent: ViewGroup?
    ): View? {
        val deque = pool[key] ?: return null
        val maxAttempts = deque.size
        if (maxAttempts == 0) return null

        val rejected = ArrayList<View>(4)
        var found: View? = null

        for (i in 0 until maxAttempts) {
            val view = deque.poll() ?: break
            if (ensureAttachableToParent(layoutId, context, parent, view)) {
                found = view
                break
            }
            rejected.add(view)
        }

        // 不兼容的 View 放回队尾，保留给后续兼容的 parent 使用
        for (view in rejected) {
            deque.offerLast(view)
        }

        if (found != null) {
            policies[layoutId]?.onObtain(found)
        }
        return found
    }

    /**
     * 把 View 放回池中。冷路径：清理 View 状态（默认或自定义），然后入队。
     *
     * 清理在这里做，使 [obtain] 命中时无需再处理 View 树。
     */
    fun recycle(@LayoutRes layoutId: Int, view: View) {
        if (isPoolingDisabled(layoutId)) return
        val key = keyFor(layoutId, view.context)
        val deque = pool.getOrPut(key) { ConcurrentLinkedDeque() }
        if (deque.size < maxSizeFor(layoutId)) {
            prepareForPool(layoutId, view)?.let(deque::offer)
        }
    }

    /**
     * 设置 warmUp 监听器，可以观察后台 inflate 失败/降级事件。
     */
    fun setWarmUpListener(listener: WarmUpListener?) {
        warmUpListener = listener
    }

    /**
     * 显式标记某个布局只能在主线程 inflate。
     *
     * 适用于已知包含以下组件的布局：
     * - androidx.compose.ui.platform.ComposeView (内部依赖 LiveData)
     * - WebView / SurfaceView / TextureView
     * - 自定义 View 中在构造或 attach 流程访问 LiveData / Lifecycle
     *
     * 标记后，warmUp 会直接走主线程 IdleHandler，不会尝试后台 inflate。
     */
    fun markAsMainThreadOnly(@LayoutRes layoutId: Int) {
        if (mainThreadOnly.add(layoutId)) {
            warmUpListener?.onMarkedAsMainThreadOnly(layoutId)
        }
    }

    fun isMainThreadOnly(@LayoutRes layoutId: Int): Boolean = layoutId in mainThreadOnly

    fun warmUp(context: Context, @LayoutRes layoutId: Int, count: Int = 1) {
        if (isPoolingDisabled(layoutId)) return
        val key = keyFor(layoutId, context)
        val warmCount = reserveWarmUp(key, layoutId, count)
        if (warmCount <= 0) return
        val generation = poolGeneration.get()

        // 已知必须主线程：直接走主线程 IdleHandler
        if (layoutId in mainThreadOnly) {
            warmUpOnMainIdle(context, layoutId, key, warmCount, generation)
            return
        }
        val contextRef = WeakReference(context)
        executor.execute {
            val warmContext = contextRef.get()
            if (warmContext == null) {
                finishWarmUp(key, warmCount)
                return@execute
            }
            if (layoutId in mainThreadOnly || containsMainThreadOnlyTag(warmContext, layoutId)) {
                if (mainThreadOnly.add(layoutId)) {
                    warmUpListener?.onMarkedAsMainThreadOnly(layoutId)
                }
                warmUpOnMainIdle(contextRef, layoutId, key, warmCount, generation)
                return@execute
            }
            val inflater = LayoutInflater.from(warmContext).cloneInContext(warmContext)
            repeat(warmCount) { i ->
                if (isStaleGeneration(generation)) {
                    finishWarmUp(key, warmCount - i)
                    return@execute
                }
                var transferredToMain = false
                try {
                    val view = inflater.inflate(layoutId, null, false)
                    val deque = dequeFor(key)
                    if (!isStaleGeneration(generation) && canAcceptWarmView(key, layoutId) &&
                        canEnterPool(layoutId, view)
                    ) {
                        deque.offer(view)
                    }
                } catch (e: Throwable) {
                    // 后台 inflate 失败，多半是组件依赖主线程（ComposeView/LiveData/WebView 等）
                    // 标记后续 warmUp 走主线程，并把剩余预热数量降级到主线程
                    if (WarmUpFallbackClassifier.isMainThreadDependencyFailure(e)) {
                        WarmUpFallbackClassifier.extractInflatingClassName(e)
                            ?.let(mainThreadOnlyViewClasses::add)
                    }
                    val newlyMarked = mainThreadOnly.add(layoutId)
                    warmUpListener?.onBackgroundInflateFailed(layoutId, e)
                    if (newlyMarked) {
                        warmUpListener?.onMarkedAsMainThreadOnly(layoutId)
                    }
                    val remaining = warmCount - i
                    if (remaining > 0) {
                        warmUpOnMainIdle(contextRef, layoutId, key, remaining, generation)
                        transferredToMain = true
                    }
                    return@execute
                } finally {
                    if (!transferredToMain) {
                        finishWarmUp(key, 1)
                    }
                }
            }
        }
    }

    /**
     * 在主线程 IdleHandler 中分批预热，避免抢占用户交互帧。
     * 每次 idle 只 inflate 1 个，避免长时间占用主线程。
     */
    private fun warmUpOnMainIdle(
        context: Context,
        @LayoutRes layoutId: Int,
        key: Long,
        count: Int,
        generation: Int
    ) {
        warmUpOnMainIdle(WeakReference(context), layoutId, key, count, generation)
    }

    private fun warmUpOnMainIdle(
        contextRef: WeakReference<Context>,
        @LayoutRes layoutId: Int,
        key: Long,
        count: Int,
        generation: Int
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { warmUpOnMainIdle(contextRef, layoutId, key, count, generation) }
            return
        }
        Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            private var remaining = count

            override fun queueIdle(): Boolean {
                if (remaining <= 0) return false
                if (isStaleGeneration(generation)) {
                    finishWarmUp(key, remaining)
                    return false
                }
                val warmContext = contextRef.get()
                if (warmContext == null) {
                    finishWarmUp(key, remaining)
                    return false
                }
                try {
                    val view = LayoutInflater.from(warmContext).inflate(layoutId, null, false)
                    val deque = dequeFor(key)
                    if (!isStaleGeneration(generation) && canAcceptWarmView(key, layoutId) &&
                        canEnterPool(layoutId, view)
                    ) {
                        deque.offer(view)
                    }
                } catch (_: Throwable) {
                    // 主线程也失败，说明布局本身有问题，放弃
                    finishWarmUp(key, remaining)
                    return false
                }
                remaining--
                finishWarmUp(key, 1)
                return remaining > 0
            }
        })
    }

    /**
     * 根据 InflateTracker 的统计数据自动调整各布局的池大小。
     * 高频布局分配更大的池，低频布局保持默认。
     *
     * @param topN 取 inflate 次数最多的前 N 个布局进行调整
     * @param minSize 最小池大小
     * @param maxSize 最大池大小
     */
    fun autoTune(topN: Int = 20, minSize: Int = 2, maxSize: Int = 12) {
        val top = InflateTracker.topN(topN)
        if (top.isEmpty()) return

        val maxCount = top.first().second.count.get()
        if (maxCount <= 0) return

        top.forEach { (layoutId, stat) ->
            val count = stat.count.get()
            // 按使用频率线性映射到 [minSize, maxSize]
            val suggested = (count.toFloat() / maxCount * (maxSize - minSize) + minSize)
                .toInt()
                .coerceIn(minSize, maxSize)
            perLayoutMaxSize[layoutId] = suggested
        }
    }

    fun warmUpOnIdle(context: Context, layouts: List<WarmUpEntry>) {
        val contextRef = WeakReference(context)
        Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            private var index = 0

            override fun queueIdle(): Boolean {
                if (index >= layouts.size) return false
                val warmContext = contextRef.get() ?: return false
                val entry = layouts[index++]
                warmUp(warmContext, entry.layoutId, entry.count)
                return index < layouts.size
            }
        })
    }

    fun poolSize(@LayoutRes layoutId: Int, context: Context): Int {
        if (isPoolingDisabled(layoutId)) return 0
        val key = keyFor(layoutId, context)
        return pool[key]?.size ?: 0
    }

    fun clear() {
        poolGeneration.incrementAndGet()
        pool.clear()
        warmingCounts.clear()
    }

    fun trimToSize(keep: Int) {
        poolGeneration.incrementAndGet()
        warmingCounts.clear()
        pool.forEach { (_, deque) ->
            while (deque.size > keep) {
                deque.poll()
            }
        }
    }

    private fun clearLayout(@LayoutRes layoutId: Int) {
        if (!hostIsolation && !factoryIsolation) {
            // 无隔离模式：key = layoutId << 32，直接移除
            val key = layoutId.toLong() shl 32
            pool.remove(key)
            warmingCounts.remove(key)
            return
        }
        // 有隔离模式：高 32 位匹配 layoutId 的所有 key 都要移除
        pool.keys.removeIf { key ->
            (key ushr 32).toInt() == layoutId
        }
        warmingCounts.keys.removeIf { key ->
            (key ushr 32).toInt() == layoutId
        }
    }

    private fun containsMainThreadOnlyTag(context: Context, @LayoutRes layoutId: Int): Boolean {
        return runCatching {
            val parser = context.resources.getLayout(layoutId)
            try {
                var eventType = parser.eventType
                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        val className = if (tagName == "view") {
                            parser.getAttributeValue(null, "class")
                        } else {
                            tagName
                        }
                        if (className != null && isMainThreadOnlyTag(className)) {
                            return true
                        }
                    }
                    eventType = parser.next()
                }
                false
            } finally {
                parser.close()
            }
        }.getOrDefault(false)
    }

    private fun isMainThreadOnlyTag(tagName: String): Boolean {
        if (WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass(tagName)) return true
        if (tagName !in mainThreadOnlyViewClasses) return false
        return true
    }

    private fun isPoolingDisabled(@LayoutRes layoutId: Int): Boolean {
        return poolingDisabled.isNotEmpty() && layoutId in poolingDisabled
    }

    /**
     * 计算 pool key。
     * - 高 32 位始终为 layoutId
     * - 低 32 位根据隔离模式组合 hostHash 和 factoryHash
     *   - hostIsolation=true: 包含 Activity identityHashCode（Application context 为 0）
     *   - factoryIsolation=true: 包含 LayoutInflater.factory2 的 class hashCode
     *   - 两者都关: 低 32 位为 0，等价于旧行为
     */
    private fun keyFor(@LayoutRes layoutId: Int, context: Context): Long {
        val high = layoutId.toLong() shl 32
        if (!hostIsolation && !factoryIsolation) {
            return high
        }
        var low = 0
        if (hostIsolation) {
            low = hostHash(context)
        }
        if (factoryIsolation) {
            low = low xor factoryHash(context)
        }
        return high or (low.toLong() and 0xFFFFFFFFL)
    }

    /**
     * 返回 context 所属 Activity 的 identityHashCode，用于 host 隔离。
     * 非 Activity context（Application / Service）返回 0，进入共享桶。
     */
    private fun hostHash(context: Context): Int {
        val activity = unwrapActivity(context) ?: return 0
        return System.identityHashCode(activity)
    }

    /**
     * 沿 ContextWrapper 链向上查找 Activity。
     */
    private fun unwrapActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is Activity) return ctx
            ctx = if (ctx is ContextWrapper) ctx.baseContext else null
        }
        return null
    }

    private fun factoryHash(context: Context): Int {
        val inflater = LayoutInflater.from(context)
        val factory: Any? = inflater.factory2 ?: inflater.factory
        return factory?.javaClass?.hashCode() ?: 0
    }

    private fun reserveWarmUp(key: Long, @LayoutRes layoutId: Int, requested: Int): Int {
        if (requested <= 0) return 0

        val maxSize = maxSizeFor(layoutId)
        if (maxSize <= 0) return 0

        val warming = warmingCounts.getOrPut(key) { AtomicInteger(0) }
        while (true) {
            val currentWarming = warming.get()
            val currentPoolSize = pool[key]?.size ?: 0
            val capacity = maxSize - currentPoolSize - currentWarming
            if (capacity <= 0) return 0

            val reserved = requested.coerceAtMost(capacity)
            if (warming.compareAndSet(currentWarming, currentWarming + reserved)) {
                return reserved
            }
        }
    }

    private fun finishWarmUp(key: Long, count: Int) {
        if (count <= 0) return

        val warming = warmingCounts[key] ?: return
        val remaining = warming.addAndGet(-count)
        if (remaining <= 0) {
            warmingCounts.remove(key, warming)
        }
    }

    private fun canAcceptWarmView(key: Long, @LayoutRes layoutId: Int): Boolean {
        return !isPoolingDisabled(layoutId) && (pool[key]?.size ?: 0) < maxSizeFor(layoutId)
    }

    private fun prepareForPool(@LayoutRes layoutId: Int, view: View): View? {
        if (!canEnterPool(layoutId, view)) {
            return null
        }
        policies[layoutId]?.onRecycle(view) ?: ViewCleaner.clean(view)
        return view
    }

    private fun canEnterPool(@LayoutRes layoutId: Int, view: View): Boolean {
        // 仍 attached 的 view 不能入池：下一次 obtain + parent.addView 会抛 IllegalStateException
        // 如果业务确认安全（已经 detach 或者 parent 即将丢弃），可以注册 ViewRecyclePolicy 覆盖默认行为
        if (view.parent != null) {
            val policy = policies[layoutId] ?: return false
            return policy.canRecycle(view)
        }
        return policies[layoutId]?.canRecycle(view) ?: true
    }

    private fun ensureAttachableToParent(
        @LayoutRes layoutId: Int,
        context: Context,
        parent: ViewGroup?,
        view: View
    ): Boolean {
        parent ?: return true
        val params = view.layoutParams
        if (params == null || !canAttachToParent(parent, view)) {
            view.layoutParams = generateRootLayoutParams(context, layoutId, parent) ?: return params == null
        }
        return canAttachToParent(parent, view)
    }

    private fun generateRootLayoutParams(
        context: Context,
        @LayoutRes layoutId: Int,
        parent: ViewGroup
    ): ViewGroup.LayoutParams? {
        val parser = runCatching { context.resources.getLayout(layoutId) }.getOrNull() ?: return null
        try {
            var dataDepth = -1
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val tagName = parser.name
                    if (tagName == "data") {
                        dataDepth = parser.depth
                    } else if (tagName != "layout" && dataDepth < 0) {
                        return runCatching {
                            parent.generateLayoutParams(Xml.asAttributeSet(parser))
                        }.getOrNull()
                    }
                } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG &&
                    dataDepth == parser.depth && parser.name == "data"
                ) {
                    dataDepth = -1
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }
        return null
    }

    private fun canAttachToParent(parent: ViewGroup?, view: View): Boolean {
        val params = view.layoutParams ?: return true
        parent ?: return true
        val key = LayoutParamsCompatibilityKey(parent.javaClass, params.javaClass)
        return layoutParamsCompatibility.getOrPut(key) {
            acceptsLayoutParams(parent, params)
        }
    }

    private fun acceptsLayoutParams(
        parent: ViewGroup,
        params: ViewGroup.LayoutParams
    ): Boolean {
        return when (parent) {
            is TableLayout -> params is TableLayout.LayoutParams
            is TableRow -> params is TableRow.LayoutParams
            is LinearLayout -> params is LinearLayout.LayoutParams
            is FrameLayout -> params is FrameLayout.LayoutParams
            is RelativeLayout -> params is RelativeLayout.LayoutParams
            is GridLayout -> params is GridLayout.LayoutParams
            else -> expectedParamsClass(parent.javaClass)?.isAssignableFrom(params.javaClass) == true
        }
    }

    private fun expectedParamsClass(
        parentClass: Class<out ViewGroup>
    ): Class<out ViewGroup.LayoutParams>? {
        var current: Class<*>? = parentClass
        while (current != null && ViewGroup::class.java.isAssignableFrom(current)) {
            val paramsClass = runCatching {
                Class.forName(
                    "${current.name}\$LayoutParams",
                    false,
                    current.classLoader
                ).asSubclass(ViewGroup.LayoutParams::class.java)
            }.getOrNull()
            if (paramsClass != null) {
                return paramsClass
            }
            current = current.superclass
        }
        return null
    }

    private fun dequeFor(key: Long): ConcurrentLinkedDeque<View> {
        return pool.getOrPut(key) { ConcurrentLinkedDeque() }
    }

    private fun isStaleGeneration(generation: Int): Boolean {
        return poolGeneration.get() != generation
    }

    data class WarmUpEntry(
        @param:LayoutRes val layoutId: Int,
        val count: Int = 2
    )

    private data class LayoutParamsCompatibilityKey(
        val parentClass: Class<out ViewGroup>,
        val layoutParamsClass: Class<out ViewGroup.LayoutParams>
    )
}
