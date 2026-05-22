package com.github.donglua.fastinflater

/**
 * 自定义 View 可实现的池化状态回调。
 *
 * [ViewCleaner] 会在 View 被回收进池前递归调用 [onRecycleForPool]，用于清理
 * 默认清理器无法理解的业务状态，例如展开/折叠标记、临时 Drawable、自定义属性缓存等。
 *
 * 这个接口只适合清理 View 自己持有的可恢复状态。涉及 EventBus、Lifecycle observer、
 * Activity callback 等外部注册关系时，仍应使用 [ViewRecyclePolicy] 或直接关闭该 layout 池化。
 */
interface PoolableView {
    fun onRecycleForPool()
}
