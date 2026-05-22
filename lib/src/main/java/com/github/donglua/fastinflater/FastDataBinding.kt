package com.github.donglua.fastinflater

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding

object FastDataBinding {

    inline fun <reified T : ViewDataBinding> inflate(
        context: Context,
        @LayoutRes layoutId: Int,
        parent: ViewGroup? = null,
        attachToRoot: Boolean = false
    ): T {
        val pooled = obtainPooled(layoutId, context, parent)
        if (pooled != null) {
            val binding = DataBindingUtil.bind<T>(pooled)
            if (binding != null) {
                PoolStats.recordHit(layoutId)
                InflateTracker.recordInflate(layoutId, 0)
                if (attachToRoot && parent != null) {
                    parent.addView(pooled)
                }
                return binding
            }
            // bind 失败（binding tag 丢失），fallback 到正常 inflate
        }

        PoolStats.recordMiss(layoutId)

        val inflater = LayoutInflater.from(context)
        return InflateTracker.track(layoutId) {
            DataBindingUtil.inflate(inflater, layoutId, parent, attachToRoot)
        }
    }

    fun <T : ViewDataBinding> inflate(
        context: Context,
        @LayoutRes layoutId: Int,
        bindingClass: Class<T>,
        parent: ViewGroup? = null,
        attachToRoot: Boolean = false
    ): T {
        val fast = FastInflater.get()
        val pooled = fast.obtainFromPool(layoutId, context, parent)
        if (pooled != null) {
            val binding = DataBindingUtil.bind<T>(pooled)
            if (binding != null) {
                PoolStats.recordHit(layoutId)
                InflateTracker.recordInflate(layoutId, 0)
                if (attachToRoot && parent != null) {
                    parent.addView(pooled)
                }
                return binding
            }
        }

        PoolStats.recordMiss(layoutId)

        val inflater = LayoutInflater.from(context)
        return InflateTracker.track(layoutId) {
            DataBindingUtil.inflate(inflater, layoutId, parent, attachToRoot)
        }
    }

    @PublishedApi
    internal fun obtainPooled(
        @LayoutRes layoutId: Int,
        context: Context,
        parent: ViewGroup?
    ) = FastInflater.get().obtainFromPool(layoutId, context, parent)
}
