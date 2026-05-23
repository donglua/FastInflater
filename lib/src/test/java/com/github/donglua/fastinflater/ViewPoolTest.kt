package com.github.donglua.fastinflater

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewPoolTest {

    private lateinit var context: Context
    private lateinit var pool: ViewPool

    private companion object {
        private const val LAYOUT_ID = 0x7f0a0001
        private const val LAYOUT_ID_2 = 0x7f0a0002
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pool = ViewPool()
    }

    // ---------------------------------------------------------------
    // 1. obtain returns null from empty pool
    // ---------------------------------------------------------------
    @Test
    fun `obtain returns null from empty pool`() {
        val result = pool.obtain(LAYOUT_ID, context)
        assertThat(result).isNull()
    }

    // ---------------------------------------------------------------
    // 2. recycle then obtain returns the same view
    // ---------------------------------------------------------------
    @Test
    fun `recycle then obtain returns the same view`() {
        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        val obtained = pool.obtain(LAYOUT_ID, context)
        assertThat(obtained).isSameInstanceAs(view)
    }

    @Test
    fun `obtain applies parent layout params when warm view has none`() {
        val layoutId = context.resources.getIdentifier(
            "fast_inflater_match_parent_item",
            "layout",
            "com.github.donglua.fastinflater.test"
        )
        val view = View(context)
        pool.recycle(layoutId, view)

        val parent = FrameLayout(context)
        val obtained = pool.obtain(layoutId, context, parent)

        assertThat(obtained).isSameInstanceAs(view)
        val params = obtained!!.layoutParams
        assertThat(params).isInstanceOf(FrameLayout.LayoutParams::class.java)
        assertThat(params.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(params.height).isEqualTo((68 * context.resources.displayMetrics.density).toInt())
    }

    // ---------------------------------------------------------------
    // 3. pool respects max size (default 4)
    // ---------------------------------------------------------------
    @Test
    fun `pool respects default max size of 4`() {
        val views = (1..5).map { View(context) }
        views.forEach { pool.recycle(LAYOUT_ID, it) }

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(4)
    }

    @Test
    fun `5th recycled view is dropped when default max size is 4`() {
        val views = (1..5).map { View(context) }
        views.forEach { pool.recycle(LAYOUT_ID, it) }

        // Obtain all 4 and verify the 5th view is not among them
        val obtained = (1..4).mapNotNull { pool.obtain(LAYOUT_ID, context) }
        assertThat(obtained).hasSize(4)

        // Pool should be empty now
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
    }

    // ---------------------------------------------------------------
    // 4. setMaxPoolSize per-layout overrides default
    // ---------------------------------------------------------------
    @Test
    fun `setMaxPoolSize per-layout overrides default`() {
        pool.setMaxPoolSize(LAYOUT_ID, 2)

        val views = (1..4).map { View(context) }
        views.forEach { pool.recycle(LAYOUT_ID, it) }

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
    }

    @Test
    fun `setMaxPoolSize per-layout does not affect other layouts`() {
        pool.setMaxPoolSize(LAYOUT_ID, 2)

        // LAYOUT_ID_2 should still use default max of 4
        val views = (1..4).map { View(context) }
        views.forEach { pool.recycle(LAYOUT_ID_2, it) }

        assertThat(pool.poolSize(LAYOUT_ID_2, context)).isEqualTo(4)
    }

    // ---------------------------------------------------------------
    // 5. setPoolingEnabled(false) makes obtain return null
    // ---------------------------------------------------------------
    @Test
    fun `setPoolingEnabled false makes obtain return null even with recycled views`() {
        val view = View(context)
        pool.recycle(LAYOUT_ID, view)
        pool.setPoolingEnabled(LAYOUT_ID, false)

        val obtained = pool.obtain(LAYOUT_ID, context)
        assertThat(obtained).isNull()
    }

    // ---------------------------------------------------------------
    // 6. setPoolingEnabled(false) makes recycle silently discard
    // ---------------------------------------------------------------
    @Test
    fun `setPoolingEnabled false makes recycle silently discard`() {
        pool.setPoolingEnabled(LAYOUT_ID, false)

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        // Re-enable and check nothing was stored
        pool.setPoolingEnabled(LAYOUT_ID, true)
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
    }

    // ---------------------------------------------------------------
    // 7. setPoolingEnabled(false) returns poolSize 0
    // ---------------------------------------------------------------
    @Test
    fun `setPoolingEnabled false returns poolSize 0`() {
        val view = View(context)
        pool.recycle(LAYOUT_ID, view)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        pool.setPoolingEnabled(LAYOUT_ID, false)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
    }

    // ---------------------------------------------------------------
    // 8. setPoolingEnabled(true) re-enables pooling
    // ---------------------------------------------------------------
    @Test
    fun `setPoolingEnabled true re-enables pooling after disable`() {
        pool.setPoolingEnabled(LAYOUT_ID, false)
        assertThat(pool.isPoolingEnabled(LAYOUT_ID)).isFalse()

        pool.setPoolingEnabled(LAYOUT_ID, true)
        assertThat(pool.isPoolingEnabled(LAYOUT_ID)).isTrue()

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)
        assertThat(pool.obtain(LAYOUT_ID, context)).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // 9. registerPolicy — canRecycle returning false prevents recycling
    // ---------------------------------------------------------------
    @Test
    fun `registerPolicy canRecycle false prevents recycling`() {
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun canRecycle(view: View): Boolean = false
        })

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
    }

    // ---------------------------------------------------------------
    // 10. registerPolicy — onRecycle is called instead of default ViewCleaner
    // ---------------------------------------------------------------
    @Test
    fun `registerPolicy onRecycle is called on recycle`() {
        var onRecycleCalled = false
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun onRecycle(view: View) {
                onRecycleCalled = true
                // Intentionally not calling ViewCleaner.clean to verify custom policy is used
            }
        })

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        assertThat(onRecycleCalled).isTrue()
    }

    @Test
    fun `registerPolicy onRecycle receives the recycled view`() {
        var recycledView: View? = null
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun onRecycle(view: View) {
                recycledView = view
            }
        })

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        assertThat(recycledView).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // 11. registerPolicy — onObtain is called when obtaining from pool
    // ---------------------------------------------------------------
    @Test
    fun `registerPolicy onObtain is called when obtaining from pool`() {
        var onObtainCalled = false
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun onObtain(view: View) {
                onObtainCalled = true
            }
        })

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        // onObtain should not be called during recycle
        assertThat(onObtainCalled).isFalse()

        pool.obtain(LAYOUT_ID, context)
        assertThat(onObtainCalled).isTrue()
    }

    @Test
    fun `registerPolicy onObtain is not called when pool is empty`() {
        var onObtainCalled = false
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun onObtain(view: View) {
                onObtainCalled = true
            }
        })

        pool.obtain(LAYOUT_ID, context)
        assertThat(onObtainCalled).isFalse()
    }

    // ---------------------------------------------------------------
    // 12. clear empties the pool
    // ---------------------------------------------------------------
    @Test
    fun `clear empties the pool`() {
        pool.recycle(LAYOUT_ID, View(context))
        pool.recycle(LAYOUT_ID, View(context))
        pool.recycle(LAYOUT_ID_2, View(context))

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
        assertThat(pool.poolSize(LAYOUT_ID_2, context)).isEqualTo(1)

        pool.clear()

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
        assertThat(pool.poolSize(LAYOUT_ID_2, context)).isEqualTo(0)
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
        assertThat(pool.obtain(LAYOUT_ID_2, context)).isNull()
    }

    // ---------------------------------------------------------------
    // 13. trimToSize keeps only specified number
    // ---------------------------------------------------------------
    @Test
    fun `trimToSize keeps only specified number of views`() {
        repeat(4) { pool.recycle(LAYOUT_ID, View(context)) }
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(4)

        pool.trimToSize(2)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
    }

    @Test
    fun `trimToSize with 0 empties the pool`() {
        repeat(3) { pool.recycle(LAYOUT_ID, View(context)) }
        pool.trimToSize(0)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
    }

    @Test
    fun `trimToSize does nothing when pool is smaller than keep`() {
        repeat(2) { pool.recycle(LAYOUT_ID, View(context)) }
        pool.trimToSize(5)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
    }

    // ---------------------------------------------------------------
    // 14. poolSize returns correct count
    // ---------------------------------------------------------------
    @Test
    fun `poolSize returns correct count`() {
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)

        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)

        pool.obtain(LAYOUT_ID, context)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
    }

    @Test
    fun `poolSize returns 0 for unknown layout`() {
        assertThat(pool.poolSize(0x7f0a9999, context)).isEqualTo(0)
    }

    // ---------------------------------------------------------------
    // 15. markAsMainThreadOnly marks correctly
    // ---------------------------------------------------------------
    @Test
    fun `markAsMainThreadOnly marks layout correctly`() {
        assertThat(pool.isMainThreadOnly(LAYOUT_ID)).isFalse()

        pool.markAsMainThreadOnly(LAYOUT_ID)
        assertThat(pool.isMainThreadOnly(LAYOUT_ID)).isTrue()
    }

    @Test
    fun `markAsMainThreadOnly does not affect other layouts`() {
        pool.markAsMainThreadOnly(LAYOUT_ID)

        assertThat(pool.isMainThreadOnly(LAYOUT_ID)).isTrue()
        assertThat(pool.isMainThreadOnly(LAYOUT_ID_2)).isFalse()
    }

    // ---------------------------------------------------------------
    // 16. setFactoryIsolation clears pool when toggled
    // ---------------------------------------------------------------
    @Test
    fun `setFactoryIsolation clears pool when toggled on`() {
        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        pool.setFactoryIsolation(true)

        // Pool was cleared; since factory isolation changes the key, old entries are gone
        // poolSize with factory isolation uses a different key, so it should be 0
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
    }

    @Test
    fun `setFactoryIsolation clears pool when toggled off`() {
        pool.setFactoryIsolation(true)
        pool.recycle(LAYOUT_ID, View(context))

        pool.setFactoryIsolation(false)
        assertThat(pool.obtain(LAYOUT_ID, context)).isNull()
    }

    @Test
    fun `setFactoryIsolation same value does not clear pool`() {
        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        // Setting to same value (false -> false) should not clear
        pool.setFactoryIsolation(false)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
    }

    @Test
    fun `isFactoryIsolationEnabled reflects current state`() {
        assertThat(pool.isFactoryIsolationEnabled()).isFalse()

        pool.setFactoryIsolation(true)
        assertThat(pool.isFactoryIsolationEnabled()).isTrue()

        pool.setFactoryIsolation(false)
        assertThat(pool.isFactoryIsolationEnabled()).isFalse()
    }

    // ---------------------------------------------------------------
    // 17. LayoutParams — View with LinearLayout.LayoutParams not obtained for FrameLayout parent
    // ---------------------------------------------------------------
    @Test
    fun `view with LinearLayout LayoutParams not obtained for FrameLayout parent`() {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pool.recycle(LAYOUT_ID, view)

        val frameParent = FrameLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, frameParent)
        assertThat(obtained).isNull()
    }

    // ---------------------------------------------------------------
    // 18. LayoutParams — View with matching LayoutParams is obtained successfully
    // ---------------------------------------------------------------
    @Test
    fun `view with matching LayoutParams is obtained for correct parent`() {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pool.recycle(LAYOUT_ID, view)

        val linearParent = LinearLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, linearParent)
        assertThat(obtained).isSameInstanceAs(view)
    }

    @Test
    fun `view with FrameLayout LayoutParams is obtained for FrameLayout parent`() {
        val view = View(context)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pool.recycle(LAYOUT_ID, view)

        val frameParent = FrameLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, frameParent)
        assertThat(obtained).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // 19. LayoutParams — View with null LayoutParams is always compatible
    // ---------------------------------------------------------------
    @Test
    fun `view with null LayoutParams is compatible with any parent`() {
        val view = View(context)
        // View created with no LayoutParams → layoutParams is null
        assertThat(view.layoutParams).isNull()

        pool.recycle(LAYOUT_ID, view)

        val frameParent = FrameLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, frameParent)
        assertThat(obtained).isSameInstanceAs(view)
    }

    @Test
    fun `view with null LayoutParams is compatible with LinearLayout parent`() {
        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        val linearParent = LinearLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, linearParent)
        assertThat(obtained).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // 20. obtain with null parent always succeeds (no LayoutParams check)
    // ---------------------------------------------------------------
    @Test
    fun `obtain with null parent always succeeds regardless of LayoutParams`() {
        val view = View(context)
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pool.recycle(LAYOUT_ID, view)

        // With null parent, LayoutParams check is skipped
        val obtained = pool.obtain(LAYOUT_ID, context, null)
        assertThat(obtained).isSameInstanceAs(view)
    }

    @Test
    fun `obtain with default parent parameter (null) always succeeds`() {
        val view = View(context)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pool.recycle(LAYOUT_ID, view)

        // Using default parameter (parent = null)
        val obtained = pool.obtain(LAYOUT_ID, context)
        assertThat(obtained).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // Additional edge case tests
    // ---------------------------------------------------------------

    @Test
    fun `setMaxPoolSize global affects all layouts`() {
        pool.setMaxPoolSize(2)

        repeat(4) { pool.recycle(LAYOUT_ID, View(context)) }
        repeat(4) { pool.recycle(LAYOUT_ID_2, View(context)) }

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
        assertThat(pool.poolSize(LAYOUT_ID_2, context)).isEqualTo(2)
    }

    @Test
    fun `different layout IDs have independent pools`() {
        val view1 = View(context)
        val view2 = View(context)

        pool.recycle(LAYOUT_ID, view1)
        pool.recycle(LAYOUT_ID_2, view2)

        assertThat(pool.obtain(LAYOUT_ID, context)).isSameInstanceAs(view1)
        assertThat(pool.obtain(LAYOUT_ID_2, context)).isSameInstanceAs(view2)
    }

    @Test
    fun `setWarmUpListener sets listener without error`() {
        // Just verify no exception; listener behavior tested via warmUp which requires real layout resources
        pool.setWarmUpListener(object : ViewPool.WarmUpListener {
            override fun onBackgroundInflateFailed(layoutId: Int, error: Throwable) {}
            override fun onMarkedAsMainThreadOnly(layoutId: Int) {}
        })
        pool.setWarmUpListener(null)
    }

    @Test
    fun `isPoolingEnabled returns true by default`() {
        assertThat(pool.isPoolingEnabled(LAYOUT_ID)).isTrue()
    }

    @Test
    fun `recycle different view types into same layout ID`() {
        val textView = TextView(context)
        val view = View(context)

        pool.recycle(LAYOUT_ID, textView)
        pool.recycle(LAYOUT_ID, view)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
    }

    @Test
    fun `policy canRecycle selectively filters views`() {
        pool.registerPolicy(LAYOUT_ID, object : ViewRecyclePolicy {
            override fun canRecycle(view: View): Boolean = view is TextView
        })

        pool.recycle(LAYOUT_ID, View(context))         // should be rejected
        pool.recycle(LAYOUT_ID, TextView(context))      // should be accepted
        pool.recycle(LAYOUT_ID, View(context))           // should be rejected

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
        assertThat(pool.obtain(LAYOUT_ID, context)).isInstanceOf(TextView::class.java)
    }

    @Test
    fun `clear does not affect pooling enabled state`() {
        pool.setPoolingEnabled(LAYOUT_ID, false)
        pool.clear()

        assertThat(pool.isPoolingEnabled(LAYOUT_ID)).isFalse()
    }

    @Test
    fun `clear does not affect mainThreadOnly state`() {
        pool.markAsMainThreadOnly(LAYOUT_ID)
        pool.clear()

        assertThat(pool.isMainThreadOnly(LAYOUT_ID)).isTrue()
    }

    @Test
    fun `setPoolingEnabled false clears existing cached views for that layout`() {
        pool.recycle(LAYOUT_ID, View(context))
        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)

        pool.setPoolingEnabled(LAYOUT_ID, false)

        // Re-enable and confirm the views were cleared
        pool.setPoolingEnabled(LAYOUT_ID, true)
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
    }

    @Test
    fun `markAsMainThreadOnly notifies warmUp listener`() {
        var notifiedLayoutId: Int? = null
        pool.setWarmUpListener(object : ViewPool.WarmUpListener {
            override fun onMarkedAsMainThreadOnly(layoutId: Int) {
                notifiedLayoutId = layoutId
            }
        })

        pool.markAsMainThreadOnly(LAYOUT_ID)
        assertThat(notifiedLayoutId).isEqualTo(LAYOUT_ID)
    }

    @Test
    fun `markAsMainThreadOnly duplicate call does not re-notify listener`() {
        var callCount = 0
        pool.setWarmUpListener(object : ViewPool.WarmUpListener {
            override fun onMarkedAsMainThreadOnly(layoutId: Int) {
                callCount++
            }
        })

        pool.markAsMainThreadOnly(LAYOUT_ID)
        pool.markAsMainThreadOnly(LAYOUT_ID)
        assertThat(callCount).isEqualTo(1)
    }

    // ---------------------------------------------------------------
    // P0: recycle rejects view that is still attached to a parent
    // ---------------------------------------------------------------
    @Test
    fun `recycle rejects view that is still attached to a parent`() {
        val parent = LinearLayout(context)
        val view = View(context)
        parent.addView(view)

        pool.recycle(LAYOUT_ID, view)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
    }

    @Test
    fun `recycle accepts view after it is removed from parent`() {
        val parent = LinearLayout(context)
        val view = View(context)
        parent.addView(view)
        parent.removeView(view)

        pool.recycle(LAYOUT_ID, view)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
        assertThat(pool.obtain(LAYOUT_ID, context)).isSameInstanceAs(view)
    }

    // ---------------------------------------------------------------
    // P0: obtain bounded attempts — incompatible views are preserved
    // ---------------------------------------------------------------
    @Test
    fun `obtain skips incompatible views and returns first compatible one`() {
        // View with FrameLayout params — incompatible with LinearLayout parent
        val incompatible = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // View with LinearLayout params — compatible
        val compatible = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        pool.recycle(LAYOUT_ID, incompatible)
        pool.recycle(LAYOUT_ID, compatible)

        val linearParent = LinearLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, linearParent)

        assertThat(obtained).isSameInstanceAs(compatible)
        // incompatible view should still be in the pool
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
    }

    @Test
    fun `obtain returns null when all views are incompatible and preserves them`() {
        val view1 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val view2 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        pool.recycle(LAYOUT_ID, view1)
        pool.recycle(LAYOUT_ID, view2)

        val linearParent = LinearLayout(context)
        val obtained = pool.obtain(LAYOUT_ID, context, linearParent)

        assertThat(obtained).isNull()
        // Both views should still be in the pool for a future compatible parent
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(2)
    }

    // ---------------------------------------------------------------
    // Host isolation (opt-in)
    // ---------------------------------------------------------------
    @Test
    fun `host isolation off — views shared across all contexts`() {
        assertThat(pool.isHostIsolationEnabled()).isFalse()

        val view = View(context)
        pool.recycle(LAYOUT_ID, view)

        // Same context obtains it
        assertThat(pool.obtain(LAYOUT_ID, context)).isSameInstanceAs(view)
    }

    @Test
    fun `setHostIsolation clears pool when toggled`() {
        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        pool.setHostIsolation(true)

        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(0)
    }

    @Test
    fun `setHostIsolation same value does not clear pool`() {
        pool.recycle(LAYOUT_ID, View(context))
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)

        pool.setHostIsolation(false) // same as default
        assertThat(pool.poolSize(LAYOUT_ID, context)).isEqualTo(1)
    }

    // ---------------------------------------------------------------
    // Host isolation — core semantics
    // ---------------------------------------------------------------

    @Test
    fun `host isolation — app context view not obtained by activity context`() {
        pool.setHostIsolation(true)

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()

        val view = View(appContext)
        pool.recycle(LAYOUT_ID, view)

        // App context 桶有 view
        assertThat(pool.poolSize(LAYOUT_ID, appContext)).isEqualTo(1)
        // Activity context 桶为空，obtain 不到
        assertThat(pool.obtain(LAYOUT_ID, activity)).isNull()
        // App context 自己能 obtain 回来
        assertThat(pool.obtain(LAYOUT_ID, appContext)).isSameInstanceAs(view)
    }

    @Test
    fun `host isolation — two activities do not share views`() {
        pool.setHostIsolation(true)

        val activityA = Robolectric.buildActivity(Activity::class.java).create().get()
        val activityB = Robolectric.buildActivity(Activity::class.java).create().get()

        val viewA = View(activityA)
        pool.recycle(LAYOUT_ID, viewA)

        // Activity B 拿不到 Activity A 的 view
        assertThat(pool.obtain(LAYOUT_ID, activityB)).isNull()
        // Activity A 自己能拿到
        assertThat(pool.obtain(LAYOUT_ID, activityA)).isSameInstanceAs(viewA)
    }

    @Test
    fun `host isolation — clearForHost only clears target activity bucket`() {
        pool.setHostIsolation(true)

        val activityA = Robolectric.buildActivity(Activity::class.java).create().get()
        val activityB = Robolectric.buildActivity(Activity::class.java).create().get()

        pool.recycle(LAYOUT_ID, View(activityA))
        pool.recycle(LAYOUT_ID, View(activityB))

        assertThat(pool.poolSize(LAYOUT_ID, activityA)).isEqualTo(1)
        assertThat(pool.poolSize(LAYOUT_ID, activityB)).isEqualTo(1)

        pool.clearForHost(activityA)

        // A 的桶被清空
        assertThat(pool.poolSize(LAYOUT_ID, activityA)).isEqualTo(0)
        assertThat(pool.obtain(LAYOUT_ID, activityA)).isNull()
        // B 的桶不受影响
        assertThat(pool.poolSize(LAYOUT_ID, activityB)).isEqualTo(1)
        assertThat(pool.obtain(LAYOUT_ID, activityB)).isNotNull()
    }
}
