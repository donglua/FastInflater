package com.aspect.fastinflater

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
            InflateTracker.recordInflate(layoutId, 0)
            val binding = DataBindingUtil.bind<T>(pooled)
            if (binding != null) {
                if (attachToRoot && parent != null) {
                    parent.addView(pooled)
                }
                return binding
            }
        }

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
            InflateTracker.recordInflate(layoutId, 0)
            val binding = DataBindingUtil.bind<T>(pooled)
            if (binding != null) {
                if (attachToRoot && parent != null) {
                    parent.addView(pooled)
                }
                return binding
            }
        }

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
