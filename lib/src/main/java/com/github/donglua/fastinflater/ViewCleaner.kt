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

        // ⚠️ 以下属性会被归一化到"可见/可用"默认值。
        // 如果布局 XML 中某些子 View 的静态默认值不是 VISIBLE / enabled / 无 contentDescription，
        // 复用后需要在 bind 阶段或 ViewRecyclePolicy.onObtain() 中恢复。
        view.visibility = View.VISIBLE
        view.isEnabled = true
        view.isSelected = false
        view.isActivated = false
        view.contentDescription = null

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
            view.isChecked = false
        }
    }
}
