package com.github.donglua.fastinflater

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WarmUpFallbackClassifierTest {

    // ── isKnownMainThreadOnlyViewClass: full class names ──

    @Test
    fun `isKnownMainThreadOnlyViewClass returns true for WebView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("android.webkit.WebView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns true for SurfaceView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("android.view.SurfaceView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns true for TextureView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("android.view.TextureView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns true for ComposeView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("androidx.compose.ui.platform.ComposeView"))
            .isTrue()
    }

    // ── isKnownMainThreadOnlyViewClass: simple names ──

    @Test
    fun `isKnownMainThreadOnlyViewClass matches simple name WebView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("WebView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass matches simple name ComposeView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("ComposeView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass matches simple name SurfaceView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("SurfaceView"))
            .isTrue()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass matches simple name TextureView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("TextureView"))
            .isTrue()
    }

    // ── isKnownMainThreadOnlyViewClass: non-matching ──

    @Test
    fun `isKnownMainThreadOnlyViewClass returns false for TextView`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("android.widget.TextView"))
            .isFalse()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns false for custom view`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("com.example.MyView"))
            .isFalse()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns false for empty string`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass(""))
            .isFalse()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass returns false for partial match`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("android.webkit.WebViewClient"))
            .isFalse()
    }

    @Test
    fun `isKnownMainThreadOnlyViewClass matches simple name from different package`() {
        assertThat(WarmUpFallbackClassifier.isKnownMainThreadOnlyViewClass("com.custom.WebView"))
            .isTrue()
    }

    // ── isMainThreadDependencyFailure: matching messages ──

    @Test
    fun `isMainThreadDependencyFailure matches cant create handler inside thread`() {
        val error = RuntimeException("Can't create handler inside thread that has not called Looper.prepare()")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches has not called Looper prepare`() {
        val error = RuntimeException("Thread has not called Looper.prepare()")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches must be called on the main thread case insensitive`() {
        val error = RuntimeException("This method Must Be Called On The Main Thread")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches must be called on the main thread lowercase`() {
        val error = RuntimeException("must be called on the main thread")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches should be called from the main thread`() {
        val error = RuntimeException("This should be called from the main thread")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches should be called from the main thread case insensitive`() {
        val error = RuntimeException("Should Be Called From The Main Thread")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches not on the main thread`() {
        val error = RuntimeException("View operation not on the main thread")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure matches not on the main thread case insensitive`() {
        val error = RuntimeException("NOT ON THE MAIN THREAD")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isTrue()
    }

    // ── isMainThreadDependencyFailure: non-matching ──

    @Test
    fun `isMainThreadDependencyFailure returns false for unrelated exception`() {
        val error = RuntimeException("NullPointerException at com.example.Foo.bar")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isFalse()
    }

    @Test
    fun `isMainThreadDependencyFailure returns false for null message`() {
        val error = RuntimeException(null as String?)
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isFalse()
    }

    @Test
    fun `isMainThreadDependencyFailure returns false for empty message`() {
        val error = RuntimeException("")
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(error)).isFalse()
    }

    // ── isMainThreadDependencyFailure: nested causes ──

    @Test
    fun `isMainThreadDependencyFailure checks nested causes`() {
        val root = RuntimeException("Can't create handler inside thread")
        val wrapper = RuntimeException("Inflation failed", root)
        val outerWrapper = RuntimeException("View creation error", wrapper)
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(outerWrapper)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure returns false when no cause matches`() {
        val root = IllegalArgumentException("Invalid argument")
        val wrapper = RuntimeException("Something went wrong", root)
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(wrapper)).isFalse()
    }

    @Test
    fun `isMainThreadDependencyFailure finds match in deeply nested cause`() {
        val deepCause = RuntimeException("has not called Looper.prepare()")
        val mid2 = IllegalStateException("init failed", deepCause)
        val mid1 = RuntimeException("wrap", mid2)
        val top = RuntimeException("outer", mid1)
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(top)).isTrue()
    }

    @Test
    fun `isMainThreadDependencyFailure returns false when causes all have null messages`() {
        val root = RuntimeException(null as String?)
        val wrapper = RuntimeException(null as String?, root)
        assertThat(WarmUpFallbackClassifier.isMainThreadDependencyFailure(wrapper)).isFalse()
    }

    // ── extractInflatingClassName ──

    @Test
    fun `extractInflatingClassName extracts class name`() {
        val error = RuntimeException("Error inflating class com.example.MyView")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error))
            .isEqualTo("com.example.MyView")
    }

    @Test
    fun `extractInflatingClassName extracts class name with trailing colon and reason`() {
        val error = RuntimeException("Error inflating class com.example.MyView: some reason")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error))
            .isEqualTo("com.example.MyView")
    }

    @Test
    fun `extractInflatingClassName returns null when no marker found`() {
        val error = RuntimeException("Something completely different happened")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error)).isNull()
    }

    @Test
    fun `extractInflatingClassName returns null for unknown class`() {
        val error = RuntimeException("Error inflating class <unknown>")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error)).isNull()
    }

    @Test
    fun `extractInflatingClassName searches through exception cause chain`() {
        val rootCause = RuntimeException("Error inflating class com.example.DeepView")
        val wrapper = RuntimeException("Outer error", rootCause)
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(wrapper))
            .isEqualTo("com.example.DeepView")
    }

    @Test
    fun `extractInflatingClassName returns first match from cause chain`() {
        val rootCause = RuntimeException("Error inflating class com.example.InnerView")
        val wrapper = RuntimeException("Error inflating class com.example.OuterView", rootCause)
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(wrapper))
            .isEqualTo("com.example.OuterView")
    }

    @Test
    fun `extractInflatingClassName returns null for empty class name`() {
        val error = RuntimeException("Error inflating class ")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error)).isNull()
    }

    @Test
    fun `extractInflatingClassName returns null for null message`() {
        val error = RuntimeException(null as String?)
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error)).isNull()
    }

    @Test
    fun `extractInflatingClassName handles class name with newline`() {
        val error = RuntimeException("Error inflating class com.example.MyView\nAdditional info here")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error))
            .isEqualTo("com.example.MyView")
    }

    @Test
    fun `extractInflatingClassName handles class name with whitespace`() {
        val error = RuntimeException("Error inflating class   com.example.MyView  ")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error))
            .isEqualTo("com.example.MyView")
    }

    @Test
    fun `extractInflatingClassName handles message with prefix before marker`() {
        val error = RuntimeException("Binary XML file line #12: Error inflating class com.example.MyView")
        assertThat(WarmUpFallbackClassifier.extractInflatingClassName(error))
            .isEqualTo("com.example.MyView")
    }
}
