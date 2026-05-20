package com.aspect.fastinflater.compiler

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LayoutXmlParser {

    private val supportedTags = setOf(
        "View", "TextView", "ImageView", "Button", "EditText", "ImageButton",
        "CheckBox", "RadioButton", "Switch", "ProgressBar", "Space",
        "LinearLayout", "FrameLayout", "RelativeLayout",
        "androidx.appcompat.widget.AppCompatTextView",
        "androidx.appcompat.widget.AppCompatImageView",
        "androidx.appcompat.widget.AppCompatButton",
        "androidx.appcompat.widget.AppCompatEditText",
        "androidx.constraintlayout.widget.ConstraintLayout",
        "androidx.recyclerview.widget.RecyclerView",
    )

    private val unsupportedTags = setOf(
        "include", "merge", "fragment", "ViewStub", "view", "layout"
    )

    fun parse(file: File): LayoutNode? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(file)
            val root = doc.documentElement ?: return null
            if (root.tagName == "layout") return null
            buildNode(root)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNode(element: Element): LayoutNode {
        val tag = element.tagName
        val attrs = mutableMapOf<String, String>()
        for (i in 0 until element.attributes.length) {
            val attr = element.attributes.item(i)
            attrs[attr.nodeName] = attr.nodeValue
        }
        val children = mutableListOf<LayoutNode>()
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val c = childNodes.item(i)
            if (c.nodeType == Node.ELEMENT_NODE) {
                children.add(buildNode(c as Element))
            }
        }
        val supported = tag !in unsupportedTags &&
            (tag in supportedTags || tag.contains('.'))
        return LayoutNode(tag, attrs, children, supported)
    }
}

data class LayoutNode(
    val tag: String,
    val attrs: Map<String, String>,
    val children: List<LayoutNode>,
    val supported: Boolean,
) {
    val isFullySupported: Boolean
        get() = supported && children.all { it.isFullySupported }
}
