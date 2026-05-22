package com.github.donglua.fastinflater

internal object WarmUpFallbackClassifier {
    private val knownMainThreadOnlyViewClasses = setOf(
        "android.webkit.WebView",
        "android.view.SurfaceView",
        "android.view.TextureView",
        "androidx.compose.ui.platform.ComposeView"
    )

    private val knownMainThreadOnlySimpleNames = knownMainThreadOnlyViewClasses
        .mapTo(HashSet()) { it.substringAfterLast('.') }

    fun isKnownMainThreadOnlyViewClass(className: String): Boolean {
        return className in knownMainThreadOnlyViewClasses ||
            className.substringAfterLast('.') in knownMainThreadOnlySimpleNames
    }

    fun isLikelyCustomViewClass(className: String): Boolean {
        return '.' in className && !className.startsWith("android.")
    }

    fun isMainThreadDependencyFailure(error: Throwable): Boolean {
        return error.causeSequence().any { cause ->
            val message = cause.message ?: return@any false
            message.contains("Can't create handler inside thread") ||
                message.contains("has not called Looper.prepare()") ||
                message.contains("must be called on the main thread", ignoreCase = true) ||
                message.contains("should be called from the main thread", ignoreCase = true) ||
                message.contains("not on the main thread", ignoreCase = true)
        }
    }

    fun extractInflatingClassName(error: Throwable): String? {
        return error.causeSequence()
            .mapNotNull { cause -> cause.message?.let(::extractInflatingClassName) }
            .firstOrNull()
    }

    private fun extractInflatingClassName(message: String): String? {
        val marker = "Error inflating class "
        val start = message.indexOf(marker)
        if (start < 0) return null

        return message.substring(start + marker.length)
            .lineSequence()
            .first()
            .substringBefore(':')
            .trim()
            .takeIf { it.isNotEmpty() && it != "<unknown>" }
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> {
        return generateSequence(this) { it.cause }
    }
}
