package com.github.donglua.fastinflater

import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView 场景的推荐入口。
 *
 * FastInflater 只加速 ViewHolder 创建侧：预热 item View，并在
 * Adapter.onCreateViewHolder 中通过 FastInflater.get().inflate(parent, viewType)
 * 优先取到预热 View。ViewHolder 创建后的滑动复用仍交给 RecyclerView 自己。
 */
object FastInflaterRecycler {

    fun install(
        recyclerView: RecyclerView,
        warmUpLayouts: List<ViewPool.WarmUpEntry> = emptyList()
    ) {
        recyclerView.setRecycledViewPool(FastRecycledViewPool(recyclerView.context))
        if (warmUpLayouts.isNotEmpty()) {
            FastInflater.get().warmUp(recyclerView.context, warmUpLayouts)
        }
    }

    fun preload(
        recyclerView: RecyclerView,
        @LayoutRes layoutId: Int,
        count: Int
    ) {
        FastInflater.get().warmUp(recyclerView.context, layoutId, count)
    }

    fun preload(
        recyclerView: RecyclerView,
        warmUpLayouts: List<ViewPool.WarmUpEntry>
    ) {
        if (warmUpLayouts.isNotEmpty()) {
            FastInflater.get().warmUp(recyclerView.context, warmUpLayouts)
        }
    }
}
