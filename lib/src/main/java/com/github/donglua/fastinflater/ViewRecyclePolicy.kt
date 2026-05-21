package com.github.donglua.fastinflater

import android.view.View

/**
 * 自定义 View 回收/复用策略。
 *
 * 对于有复杂内部状态的自定义布局，默认的 [ViewCleaner] 无法保证完全清理干净。
 * 通过注册 Policy，调用方可以精确控制哪些状态需要重置。
 */
interface ViewRecyclePolicy {

    /**
     * View 被回收进池时调用。用于清理业务状态（图片、文本、listener 等）。
     * 默认实现调用 [ViewCleaner.clean]。
     */
    fun onRecycle(view: View) {
        ViewCleaner.clean(view)
    }

    /**
     * View 从池中取出、即将被复用时调用。用于重置到可用初始状态。
     */
    fun onObtain(view: View) {}

    /**
     * 返回 false 表示该 View 状态不可恢复，应丢弃而非回收。
     * 例如 View 已经 detach 或内部状态损坏时。
     */
    fun canRecycle(view: View): Boolean = true
}
