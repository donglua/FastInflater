package com.aspect.fastinflater.compiler

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import java.io.File

class LayoutCodeGenerator(
    private val packageName: String,
    private val outputDir: File,
    private val rPackage: String = packageName.removeSuffix(".generated"),
) {

    private val androidContext = ClassName("android.content", "Context")
    private val androidViewGroup = ClassName("android.view", "ViewGroup")
    private val androidView = ClassName("android.view", "View")
    private val typedValue = ClassName("android.util", "TypedValue")
    private val rClass = ClassName(rPackage, "R")

    fun generate(layoutName: String, className: String, root: LayoutNode) {
        val createFn = FunSpec.builder("create")
            .addParameter("ctx", androidContext)
            .addParameter(
                ParameterSpec.builder("parent", androidViewGroup.copy(nullable = true))
                    .defaultValue("null").build()
            )
            .addParameter(
                ParameterSpec.builder("attachToRoot", Boolean::class)
                    .defaultValue("false").build()
            )
            .returns(androidView)
            .addCode(buildRootCode(root))
            .build()

        val type = TypeSpec.objectBuilder(className)
            .addFunction(createFn)
            .build()

        FileSpec.builder(packageName, className)
            .addType(type)
            .build()
            .writeTo(outputDir)
    }

    fun generateRegistry(entries: List<GenerateLayoutsTask.RegistryEntry>) {
        val registerAll = FunSpec.builder("registerAll")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("registry", ClassName("com.aspect.fastinflater", "GeneratedLayoutRegistry"))
            .apply {
                entries.forEach { entry ->
                    addStatement(
                        "registry.register(%S) { ctx, parent, attach -> %T.create(ctx, parent, attach) }",
                        entry.layoutName,
                        ClassName(packageName, entry.className.substringAfterLast('.'))
                    )
                }
            }
            .build()

        val obj = TypeSpec.objectBuilder("FastInflaterGenerated")
            .addFunction(registerAll)
            .build()

        FileSpec.builder(packageName, "FastInflaterGenerated")
            .addType(obj)
            .build()
            .writeTo(outputDir)
    }

    // --- Code generation ---

    private fun buildRootCode(root: LayoutNode): CodeBlock {
        val cb = CodeBlock.builder()
        cb.addStatement("val dm = ctx.resources.displayMetrics")
        cb.add("val root = ")
        emitNode(cb, root, parentTag = null)
        cb.add("\n")
        cb.addStatement("if (attachToRoot && parent != null) parent.addView(root)")
        cb.addStatement("return root")
        return cb.build()
    }

    private fun emitNode(cb: CodeBlock.Builder, node: LayoutNode, parentTag: String?) {
        val viewClass = resolveClassName(node.tag)
        cb.add("%T(ctx).apply {\n", viewClass)
        emitLayoutParams(cb, node, parentTag)
        emitViewAttrs(cb, node)
        node.children.forEach { child ->
            cb.add("addView(")
            emitNode(cb, child, parentTag = node.tag)
            cb.add(")\n")
        }
        cb.add("}")
    }

    // --- LayoutParams ---

    private fun emitLayoutParams(cb: CodeBlock.Builder, node: LayoutNode, parentTag: String?) {
        val w = node.attrs["android:layout_width"]
        val h = node.attrs["android:layout_height"]
        if (w == null && h == null) return

        val wCode = dimenToCode(w ?: "wrap_content")
        val hCode = dimenToCode(h ?: "wrap_content")

        val weight = node.attrs["android:layout_weight"]
        val hasMargin = node.attrs.keys.any { it.startsWith("android:layout_margin") }
        val isLinearParent = parentTag != null && isLinearLayout(parentTag)

        if (isLinearParent && (weight != null || hasMargin)) {
            val lpClass = ClassName("android.widget", "LinearLayout", "LayoutParams")
            if (weight != null) {
                cb.addStatement("layoutParams = %T(%L, %L, %Lf).apply {", lpClass, wCode, hCode, weight.toFloatOrNull() ?: 0f)
            } else {
                cb.addStatement("layoutParams = %T(%L, %L).apply {", lpClass, wCode, hCode)
            }
            emitMargins(cb, node)
            cb.addStatement("}")
        } else if (hasMargin) {
            val lpClass = ClassName("android.view", "ViewGroup", "MarginLayoutParams")
            cb.addStatement("layoutParams = %T(%L, %L).apply {", lpClass, wCode, hCode)
            emitMargins(cb, node)
            cb.addStatement("}")
        } else {
            val lpClass = ClassName("android.view", "ViewGroup", "LayoutParams")
            cb.addStatement("layoutParams = %T(%L, %L)", lpClass, wCode, hCode)
        }
    }

    private fun emitMargins(cb: CodeBlock.Builder, node: LayoutNode) {
        val all = node.attrs["android:layout_margin"]
        if (all != null) {
            val px = dpToPxCode(all)
            cb.addStatement("setMargins(%L, %L, %L, %L)", px, px, px, px)
            return
        }
        val top = node.attrs["android:layout_marginTop"]
        val bottom = node.attrs["android:layout_marginBottom"]
        val start = node.attrs["android:layout_marginStart"] ?: node.attrs["android:layout_marginLeft"]
        val end = node.attrs["android:layout_marginEnd"] ?: node.attrs["android:layout_marginRight"]

        if (start != null) cb.addStatement("marginStart = %L", dpToPxCode(start))
        if (end != null) cb.addStatement("marginEnd = %L", dpToPxCode(end))
        if (top != null) cb.addStatement("topMargin = %L", dpToPxCode(top))
        if (bottom != null) cb.addStatement("bottomMargin = %L", dpToPxCode(bottom))
    }

    // --- View attributes ---

    private fun emitViewAttrs(cb: CodeBlock.Builder, node: LayoutNode) {
        // id
        node.attrs["android:id"]?.let { id ->
            val name = id.removePrefix("@+id/").removePrefix("@id/")
            cb.addStatement("id = %T.id.%L", rClass, name)
        }

        // padding
        emitPadding(cb, node)

        // orientation (LinearLayout)
        node.attrs["android:orientation"]?.let { ori ->
            if (isLinearLayout(node.tag)) {
                val value = if (ori == "horizontal") "HORIZONTAL" else "VERTICAL"
                cb.addStatement("orientation = %T.%L",
                    ClassName("android.widget", "LinearLayout"), value)
            }
        }

        // gravity
        node.attrs["android:gravity"]?.let { gravity ->
            cb.addStatement("gravity = %L", gravityToCode(gravity))
        }

        // text
        node.attrs["android:text"]?.let { text ->
            if (!text.startsWith("@")) {
                cb.addStatement("(this as? %T)?.text = %S",
                    ClassName("android.widget", "TextView"), text)
            }
        }

        // textSize
        node.attrs["android:textSize"]?.let { size ->
            val sp = parseSpValue(size)
            if (sp != null) {
                cb.addStatement("(this as? %T)?.setTextSize(%T.COMPLEX_UNIT_SP, %Lf)",
                    ClassName("android.widget", "TextView"), typedValue, sp)
            }
        }

        // textStyle
        node.attrs["android:textStyle"]?.let { style ->
            val typeface = typefaceToCode(style)
            if (typeface != null) {
                cb.addStatement("(this as? %T)?.setTypeface(null, %L)",
                    ClassName("android.widget", "TextView"), typeface)
            }
        }

        // src (ImageView)
        node.attrs["android:src"]?.let { src ->
            emitDrawableSrc(cb, src)
        }

        // contentDescription
        node.attrs["android:contentDescription"]?.let { desc ->
            if (!desc.startsWith("@")) {
                cb.addStatement("contentDescription = %S", desc)
            }
        }
    }

    private fun emitPadding(cb: CodeBlock.Builder, node: LayoutNode) {
        val all = node.attrs["android:padding"]
        if (all != null) {
            val px = dpToPxCode(all)
            cb.addStatement("setPadding(%L, %L, %L, %L)", px, px, px, px)
            return
        }
        val l = node.attrs["android:paddingStart"] ?: node.attrs["android:paddingLeft"]
        val r = node.attrs["android:paddingEnd"] ?: node.attrs["android:paddingRight"]
        val t = node.attrs["android:paddingTop"]
        val b = node.attrs["android:paddingBottom"]
        if (l != null || r != null || t != null || b != null) {
            cb.addStatement("setPadding(%L, %L, %L, %L)",
                l?.let { dpToPxCode(it) } ?: "paddingLeft",
                t?.let { dpToPxCode(it) } ?: "paddingTop",
                r?.let { dpToPxCode(it) } ?: "paddingRight",
                b?.let { dpToPxCode(it) } ?: "paddingBottom")
        }
    }

    private fun emitDrawableSrc(cb: CodeBlock.Builder, src: String) {
        if (src.startsWith("@android:drawable/")) {
            val name = src.removePrefix("@android:drawable/")
            cb.addStatement("(this as? %T)?.setImageResource(android.R.drawable.%L)",
                ClassName("android.widget", "ImageView"), name)
        } else if (src.startsWith("@drawable/")) {
            val name = src.removePrefix("@drawable/")
            cb.addStatement("(this as? %T)?.setImageResource(%T.drawable.%L)",
                ClassName("android.widget", "ImageView"), rClass, name)
        }
    }

    // --- Utilities ---

    private fun resolveClassName(tag: String): ClassName {
        if (tag.contains('.')) {
            val pkg = tag.substringBeforeLast('.')
            val name = tag.substringAfterLast('.')
            return ClassName(pkg, name)
        }
        return when (tag) {
            "LinearLayout" -> ClassName("android.widget", "LinearLayout")
            "FrameLayout" -> ClassName("android.widget", "FrameLayout")
            "RelativeLayout" -> ClassName("android.widget", "RelativeLayout")
            else -> ClassName("android.widget", tag)
        }
    }

    private fun isLinearLayout(tag: String): Boolean {
        return tag == "LinearLayout" || tag == "android.widget.LinearLayout"
    }

    private fun dimenToCode(value: String): String = when (value) {
        "match_parent", "fill_parent" -> "android.view.ViewGroup.LayoutParams.MATCH_PARENT"
        "wrap_content" -> "android.view.ViewGroup.LayoutParams.WRAP_CONTENT"
        "0dp", "0dip" -> "0"
        else -> {
            val dp = parseDpValue(value)
            if (dp != null) {
                dpToPxCode(value)
            } else {
                "android.view.ViewGroup.LayoutParams.WRAP_CONTENT"
            }
        }
    }

    private fun dpToPxCode(value: String): String {
        val dp = parseDpValue(value) ?: return "0"
        return "android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, ${dp}f, dm).toInt()"
    }

    private fun parseDpValue(value: String): Float? {
        val cleaned = value.removeSuffix("dp").removeSuffix("dip").trim()
        return cleaned.toFloatOrNull()
    }

    private fun parseSpValue(value: String): Float? {
        val cleaned = value.removeSuffix("sp").trim()
        return cleaned.toFloatOrNull()
    }

    private fun gravityToCode(gravity: String): String {
        val parts = gravity.split("|")
        return parts.joinToString(" or ") { part ->
            when (part.trim()) {
                "center" -> "android.view.Gravity.CENTER"
                "center_horizontal" -> "android.view.Gravity.CENTER_HORIZONTAL"
                "center_vertical" -> "android.view.Gravity.CENTER_VERTICAL"
                "top" -> "android.view.Gravity.TOP"
                "bottom" -> "android.view.Gravity.BOTTOM"
                "start", "left" -> "android.view.Gravity.START"
                "end", "right" -> "android.view.Gravity.END"
                "fill" -> "android.view.Gravity.FILL"
                "fill_horizontal" -> "android.view.Gravity.FILL_HORIZONTAL"
                "fill_vertical" -> "android.view.Gravity.FILL_VERTICAL"
                "clip_horizontal" -> "android.view.Gravity.CLIP_HORIZONTAL"
                "clip_vertical" -> "android.view.Gravity.CLIP_VERTICAL"
                else -> "android.view.Gravity.NO_GRAVITY"
            }
        }
    }

    private fun typefaceToCode(style: String): String? {
        val parts = style.split("|")
        val flags = parts.mapNotNull { part ->
            when (part.trim()) {
                "bold" -> "android.graphics.Typeface.BOLD"
                "italic" -> "android.graphics.Typeface.ITALIC"
                "bold_italic" -> "android.graphics.Typeface.BOLD_ITALIC"
                "normal" -> "android.graphics.Typeface.NORMAL"
                else -> null
            }
        }
        return if (flags.isNotEmpty()) flags.joinToString(" or ") else null
    }
}
