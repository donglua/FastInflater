package com.github.donglua.fastinflater

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
}
