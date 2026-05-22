package com.github.donglua.fastinflater

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import android.widget.EditText
import android.widget.CompoundButton

object ViewCleaner {

    fun clean(view: View) {
        cleanSingle(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clean(view.getChildAt(i))
            }
        }
    }

    private fun cleanSingle(view: View) {
        if (view is PoolableView) {
            view.onRecycleForPool()
        }

        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.setOnTouchListener(null)
        view.setOnKeyListener(null)
        view.setOnFocusChangeListener(null)
        // 不清除 tag：DataBinding 依赖 view tag 存储 binding 信息
        // 如果清除会导致 DataBindingUtil.bind() 返回 null
        // view.tag = null
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotation = 0f
        view.alpha = 1f
        view.scrollX = 0
        view.scrollY = 0
        view.clearAnimation()
        view.clearFocus()

        if (view is TextView) {
            view.text = null
            view.setOnEditorActionListener(null)
        }
        if (view is ImageView) {
            view.setImageDrawable(null)
        }
        if (view is EditText) {
            view.text = null
        }
        if (view is CompoundButton) {
            view.setOnCheckedChangeListener(null)
        }
    }
}
