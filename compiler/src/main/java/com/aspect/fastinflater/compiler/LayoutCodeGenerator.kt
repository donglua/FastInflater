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
) {

    private val androidContext = ClassName("android.content", "Context")
    private val androidViewGroup = ClassName("android.view", "ViewGroup")
    private val androidView = ClassName("android.view", "View")

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

    private fun buildRootCode(root: LayoutNode): CodeBlock {
        val cb = CodeBlock.builder()
        cb.add("val root = ")
        emitNode(cb, root, "ctx")
        cb.add("\n")
        cb.addStatement("if (attachToRoot && parent != null) parent.addView(root)")
        cb.addStatement("return root")
        return cb.build()
    }

    private fun emitNode(cb: CodeBlock.Builder, node: LayoutNode, ctxVar: String) {
        val viewClass = resolveClassName(node.tag)
        cb.add("%T(%L).apply {\n", viewClass, ctxVar)
        emitAttrs(cb, node)
        node.children.forEach { child ->
            cb.add("addView(")
            emitNode(cb, child, ctxVar)
            cb.add(")\n")
        }
        cb.add("}")
    }

    private fun emitAttrs(cb: CodeBlock.Builder, node: LayoutNode) {
        val w = node.attrs["android:layout_width"]
        val h = node.attrs["android:layout_height"]
        if (w != null || h != null) {
            cb.addStatement(
                "layoutParams = %T(%L, %L)",
                ClassName("android.view", "ViewGroup", "LayoutParams"),
                dimenToCode(w ?: "wrap_content"),
                dimenToCode(h ?: "wrap_content"),
            )
        }
        node.attrs["android:id"]?.let { id ->
            val name = id.removePrefix("@+id/").removePrefix("@id/")
            cb.addStatement("id = %T.id.%L", ClassName("R", ""), name)
        }
        node.attrs["android:text"]?.let { text ->
            if (!text.startsWith("@")) {
                cb.addStatement("(this as? %T)?.text = %S", ClassName("android.widget", "TextView"), text)
            }
        }
    }

    private fun resolveClassName(tag: String): ClassName {
        if (tag.contains('.')) {
            val pkg = tag.substringBeforeLast('.')
            val name = tag.substringAfterLast('.')
            return ClassName(pkg, name)
        }
        return ClassName("android.widget", tag)
    }

    private fun dimenToCode(value: String): String = when (value) {
        "match_parent", "fill_parent" -> "android.view.ViewGroup.LayoutParams.MATCH_PARENT"
        "wrap_content" -> "android.view.ViewGroup.LayoutParams.WRAP_CONTENT"
        else -> "android.view.ViewGroup.LayoutParams.WRAP_CONTENT"
    }
}
