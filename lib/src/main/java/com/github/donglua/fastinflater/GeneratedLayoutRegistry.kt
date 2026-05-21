package com.github.donglua.fastinflater

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import java.util.concurrent.ConcurrentHashMap

class GeneratedLayoutRegistry {

    fun interface Creator {
        fun create(ctx: Context, parent: ViewGroup?, attachToRoot: Boolean): View
    }

    private val byName = ConcurrentHashMap<String, Creator>()
    private val byId = ConcurrentHashMap<Int, Creator>()

    fun register(layoutName: String, creator: Creator) {
        byName[layoutName] = creator
    }

    internal fun resolve(context: Context, @LayoutRes layoutId: Int): Creator? {
        byId[layoutId]?.let { return it }
        val name = try {
            context.resources.getResourceEntryName(layoutId)
        } catch (_: Exception) {
            return null
        }
        val creator = byName[name] ?: return null
        byId[layoutId] = creator
        return creator
    }

    fun size(): Int = byName.size
}
