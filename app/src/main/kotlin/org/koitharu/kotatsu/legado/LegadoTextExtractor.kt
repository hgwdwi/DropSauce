package org.koitharu.kotatsu.legado

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Converts an inert DOM into plain text without relying on HTML serialization or pretty-printing. */
internal object LegadoTextExtractor {
    private val blocks = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "section", "article", "hr", "pre")
    private val sourceWhitespace = Regex("[\\r\\n\\t ]+")

    fun extract(root: Element): String {
        val out = StringBuilder()
        fun trimAsciiEnd() {
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        }
        fun boundary(force: Boolean = false) {
            trimAsciiEnd()
            if (force || (out.isNotEmpty() && out.last() != '\n')) out.append('\n')
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is Element -> when (node.normalName()) {
                        "br" -> boundary(force = true)
                        in blocks -> boundary()
                    }
                    is TextNode -> {
                        val text = sourceWhitespace.replace(node.wholeText, " ")
                        if (out.isEmpty() || out.last() == '\n' || out.last() == ' ') {
                            out.append(text.trimStart(' '))
                        } else out.append(text)
                    }
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blocks) boundary()
            }
        }, root)
        return out.toString().trim(' ', '\n', '\r', '\t')
    }
}
