package com.aspect.fastinflater

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/**
 * 对接 RecyclerView 的 ViewPool，利用 FastInflater 的预热池加速 ViewHolder 创建。
 *
 * 设计原则：
 * - RecyclerView 自己的回收机制不变（putRecycledView 完全交给 super）
 * - FastInflater 的池只在"创建侧"发挥作用：当 RecyclerView 池为空、
 *   需要新建 ViewHolder 时，优先从 FastInflater 预热池取 View
 *
 * 使用方式：
 * ```
 * // 设置 pool
 * FastRecycledViewPool.install(recyclerView, warmUpLayouts = listOf(
 *     ViewPool.WarmUpEntry(R.layout.item_feed, 4)
 * ))
 *
 * // Adapter 中，让 viewType == layoutId
 * override fun getItemViewType(position: Int) = R.layout.item_feed
 *
 * override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
 *     // 优先从 FastInflater 池取，未命中时正常 inflate
 *     val view = FastInflater.get().inflate(parent, viewType)
 *     return MyViewHolder(view)
 * }
 * ```
 *
 * 注意：此类不会把 View 同时放入两个池，避免复用冲突。
 */
class FastRecycledViewPool(
    private val context: Context
) : RecyclerView.RecycledViewPool() {

    // 回收完全交给 RecyclerView 自己的池，不做任何额外操作
    // FastInflater 的池只用于预热和首次创建加速

    companion object {
        /**
         * 便捷方法：为 RecyclerView 设置 FastRecycledViewPool 并预热指定布局。
         *
         * 预热的 View 会进入 FastInflater 的池，当 Adapter.onCreateViewHolder
         * 调用 FastInflater.get().inflate() 时会优先命中这些预热的 View。
         */
        fun install(
            recyclerView: RecyclerView,
            warmUpLayouts: List<ViewPool.WarmUpEntry> = emptyList()
        ) {
            val pool = FastRecycledViewPool(recyclerView.context)
            recyclerView.setRecycledViewPool(pool)

            if (warmUpLayouts.isNotEmpty()) {
                FastInflater.get().warmUp(recyclerView.context, warmUpLayouts)
            }
        }
    }
}
