package com.github.donglua.fastinflater

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewCleanerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── Helper: PoolableView implementation ──────────────────────────────

    private class TestPoolableView(context: Context) : View(context), PoolableView {
        var recycled = false
        override fun onRecycleForPool() {
            recycled = true
        }
    }

    // ── 1. Alpha ─────────────────────────────────────────────────────────

    @Test
    fun `clean resets alpha to 1f`() {
        val view = View(context).apply { alpha = 0.3f }

        ViewCleaner.clean(view)

        assertThat(view.alpha).isEqualTo(1f)
    }

    // ── 2. TranslationX / Y / Z ──────────────────────────────────────────

    @Test
    fun `clean resets translationX to 0f`() {
        val view = View(context).apply { translationX = 42f }

        ViewCleaner.clean(view)

        assertThat(view.translationX).isEqualTo(0f)
    }

    @Test
    fun `clean resets translationY to 0f`() {
        val view = View(context).apply { translationY = -15f }

        ViewCleaner.clean(view)

        assertThat(view.translationY).isEqualTo(0f)
    }

    @Test
    fun `clean resets translationZ to 0f`() {
        val view = View(context).apply { translationZ = 8f }

        ViewCleaner.clean(view)

        assertThat(view.translationZ).isEqualTo(0f)
    }

    // ── 3. ScaleX / Y ───────────────────────────────────────────────────

    @Test
    fun `clean resets scaleX to 1f`() {
        val view = View(context).apply { scaleX = 2.5f }

        ViewCleaner.clean(view)

        assertThat(view.scaleX).isEqualTo(1f)
    }

    @Test
    fun `clean resets scaleY to 1f`() {
        val view = View(context).apply { scaleY = 0.5f }

        ViewCleaner.clean(view)

        assertThat(view.scaleY).isEqualTo(1f)
    }

    // ── 4. Rotation ──────────────────────────────────────────────────────

    @Test
    fun `clean resets rotation to 0f`() {
        val view = View(context).apply { rotation = 180f }

        ViewCleaner.clean(view)

        assertThat(view.rotation).isEqualTo(0f)
    }

    // ── 5. ScrollX / Y ──────────────────────────────────────────────────

    @Test
    fun `clean resets scrollX to 0`() {
        val view = View(context).apply { scrollX = 100 }

        ViewCleaner.clean(view)

        assertThat(view.scrollX).isEqualTo(0)
    }

    @Test
    fun `clean resets scrollY to 0`() {
        val view = View(context).apply { scrollY = 200 }

        ViewCleaner.clean(view)

        assertThat(view.scrollY).isEqualTo(0)
    }

    // ── 6. TextView text cleared ─────────────────────────────────────────

    @Test
    fun `clean sets TextView text to null`() {
        val textView = TextView(context).apply { text = "Hello World" }

        ViewCleaner.clean(textView)

        assertThat(textView.text.toString()).isEmpty()
    }

    // ── 7. ImageView drawable cleared ────────────────────────────────────

    @Test
    fun `clean sets ImageView drawable to null`() {
        val imageView = ImageView(context).apply {
            setImageDrawable(ColorDrawable(0xFFFF0000.toInt()))
        }
        assertThat(imageView.drawable).isNotNull()

        ViewCleaner.clean(imageView)

        assertThat(imageView.drawable).isNull()
    }

    // ── 8. Recursive cleaning of ViewGroup children ──────────────────────

    @Test
    fun `clean recursively cleans children of ViewGroup`() {
        val parent = LinearLayout(context)
        val childView = View(context).apply {
            alpha = 0.5f
            translationX = 10f
        }
        val childTextView = TextView(context).apply {
            text = "child text"
            scaleX = 3f
        }
        parent.addView(childView)
        parent.addView(childTextView)

        // Also modify the parent itself
        parent.alpha = 0.2f

        ViewCleaner.clean(parent)

        // Parent should be cleaned
        assertThat(parent.alpha).isEqualTo(1f)

        // Child View should be cleaned
        assertThat(childView.alpha).isEqualTo(1f)
        assertThat(childView.translationX).isEqualTo(0f)

        // Child TextView should be cleaned
        assertThat(childTextView.text.toString()).isEmpty()
        assertThat(childTextView.scaleX).isEqualTo(1f)
    }

    // ── 9. PoolableView callback invoked ─────────────────────────────────

    @Test
    fun `clean calls onRecycleForPool for PoolableView implementations`() {
        val poolableView = TestPoolableView(context)
        assertThat(poolableView.recycled).isFalse()

        ViewCleaner.clean(poolableView)

        assertThat(poolableView.recycled).isTrue()
    }

    // ── 10. Plain View does not crash ────────────────────────────────────

    @Test
    fun `clean handles a plain View without crashing`() {
        val view = View(context)

        // Should not throw any exception
        ViewCleaner.clean(view)

        // Verify default values are maintained
        assertThat(view.alpha).isEqualTo(1f)
        assertThat(view.translationX).isEqualTo(0f)
        assertThat(view.translationY).isEqualTo(0f)
        assertThat(view.translationZ).isEqualTo(0f)
        assertThat(view.scaleX).isEqualTo(1f)
        assertThat(view.scaleY).isEqualTo(1f)
        assertThat(view.rotation).isEqualTo(0f)
        assertThat(view.scrollX).isEqualTo(0)
        assertThat(view.scrollY).isEqualTo(0)
    }

    // ── 11. Nested ViewGroups cleaned recursively ────────────────────────

    @Test
    fun `clean on a ViewGroup with nested ViewGroups cleans all levels`() {
        val root = FrameLayout(context)
        val level1 = LinearLayout(context).apply { alpha = 0.1f }
        val level2 = FrameLayout(context).apply { translationX = 99f }
        val deepChild = TextView(context).apply {
            text = "deep"
            rotation = 45f
        }

        level2.addView(deepChild)
        level1.addView(level2)
        root.addView(level1)

        ViewCleaner.clean(root)

        assertThat(level1.alpha).isEqualTo(1f)
        assertThat(level2.translationX).isEqualTo(0f)
        assertThat(deepChild.text.toString()).isEmpty()
        assertThat(deepChild.rotation).isEqualTo(0f)
    }

    // ── Additional edge-case tests ───────────────────────────────────────

    @Test
    fun `clean resets all transform properties at once`() {
        val view = View(context).apply {
            alpha = 0.1f
            translationX = 5f
            translationY = 10f
            translationZ = 15f
            scaleX = 2f
            scaleY = 3f
            rotation = 90f
            scrollX = 50
            scrollY = 60
        }

        ViewCleaner.clean(view)

        assertThat(view.alpha).isEqualTo(1f)
        assertThat(view.translationX).isEqualTo(0f)
        assertThat(view.translationY).isEqualTo(0f)
        assertThat(view.translationZ).isEqualTo(0f)
        assertThat(view.scaleX).isEqualTo(1f)
        assertThat(view.scaleY).isEqualTo(1f)
        assertThat(view.rotation).isEqualTo(0f)
        assertThat(view.scrollX).isEqualTo(0)
        assertThat(view.scrollY).isEqualTo(0)
    }

    @Test
    fun `clean on empty ViewGroup does not crash`() {
        val emptyGroup = LinearLayout(context).apply { alpha = 0.5f }

        ViewCleaner.clean(emptyGroup)

        assertThat(emptyGroup.alpha).isEqualTo(1f)
    }

    @Test
    fun `clean clears EditText text`() {
        val editText = EditText(context).apply { setText("user input") }
        assertThat(editText.text.toString()).isEqualTo("user input")

        ViewCleaner.clean(editText)

        assertThat(editText.text.toString()).isEmpty()
    }

    @Test
    fun `clean clears CompoundButton checked change listener`() {
        val checkBox = CheckBox(context)
        var listenerCalled = false
        checkBox.setOnCheckedChangeListener { _, _ -> listenerCalled = true }

        ViewCleaner.clean(checkBox)

        // After cleaning, toggling the check state should not trigger the old listener
        checkBox.isChecked = !checkBox.isChecked
        assertThat(listenerCalled).isFalse()
    }

    @Test
    fun `clean on ViewGroup also resets parent transform properties`() {
        val parent = LinearLayout(context).apply {
            translationX = 20f
            translationY = 30f
            scaleX = 0.8f
            rotation = 270f
        }
        parent.addView(View(context))

        ViewCleaner.clean(parent)

        assertThat(parent.translationX).isEqualTo(0f)
        assertThat(parent.translationY).isEqualTo(0f)
        assertThat(parent.scaleX).isEqualTo(1f)
        assertThat(parent.rotation).isEqualTo(0f)
    }

    // ── Visibility / Enabled / Selected / Activated / ContentDescription ──

    @Test
    fun `clean resets visibility to VISIBLE`() {
        val view = View(context).apply { visibility = View.GONE }

        ViewCleaner.clean(view)

        assertThat(view.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `clean resets isEnabled to true`() {
        val view = View(context).apply { isEnabled = false }

        ViewCleaner.clean(view)

        assertThat(view.isEnabled).isTrue()
    }

    @Test
    fun `clean resets isSelected to false`() {
        val view = View(context).apply { isSelected = true }

        ViewCleaner.clean(view)

        assertThat(view.isSelected).isFalse()
    }

    @Test
    fun `clean resets isActivated to false`() {
        val view = View(context).apply { isActivated = true }

        ViewCleaner.clean(view)

        assertThat(view.isActivated).isFalse()
    }

    @Test
    fun `clean clears contentDescription`() {
        val view = View(context).apply { contentDescription = "test desc" }

        ViewCleaner.clean(view)

        assertThat(view.contentDescription).isNull()
    }

    @Test
    fun `clean resets CompoundButton isChecked to false`() {
        val checkBox = CheckBox(context).apply { isChecked = true }

        ViewCleaner.clean(checkBox)

        assertThat(checkBox.isChecked).isFalse()
    }
}
