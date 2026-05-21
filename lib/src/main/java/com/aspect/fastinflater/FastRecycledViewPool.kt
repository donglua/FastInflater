package com.aspect.fastinflater

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/**
 * 对接 RecyclerView 的 ViewPool，自动利用 FastInflater 的池化能力。
 *
 * 使用方式：
 * ```
 * recyclerView.setRecycledViewPool(FastRecycledViewPool(context))
 * ```
 *
 * 配合 Adapter 使用时，需要在 onCreateViewHolder 中通过 [createViewHolder] 创建 ViewHolder，
 * 以便自动标记 layoutId 用于回收：
 * ```
 * override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
 *     val view = FastInflater.get().inflate(parent, viewType)
 *     view.setTag(R.id.fast_inflater_layout_id, viewType)
 *     return MyViewHolder(view)
 * }
 * ```
 *
 * 或者使用更简洁的方式——让 viewType 直接等于 layoutId：
 * ```
 * override fun getItemViewType(position: Int) = R.layout.item_feed
 * ```
 */
class FastRecycledViewPool(
    private val context: Context
) : RecyclerView.RecycledViewPool() {

    /**
     * ViewHolder 被回收时，将 View 放回 FastInflater 的池。
     */
    override fun putRecycledView(scrap: RecyclerView.ViewHolder) {
        val layoutId = extractLayoutId(scrap)
        if (layoutId != null) {
            FastInflater.get().recycle(layoutId, scrap.itemView)
        }
        super.putRecycledView(scrap)
    }

    private fun extractLayoutId(holder: RecyclerView.ViewHolder): Int? {
        // 优先从 tag 中获取（用户显式标记）
        val tagged = holder.itemView.getTag(R.id.fast_inflater_layout_id)
        if (tagged is Int) return tagged

        // fallback: 如果 viewType 是合法的 layout resource id，直接使用
        val viewType = holder.itemViewType
        return if (viewType > 0) viewType else null
    }

    companion object {
        /**
         * 便捷方法：为 RecyclerView 设置 FastRecycledViewPool 并预热指定布局。
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
