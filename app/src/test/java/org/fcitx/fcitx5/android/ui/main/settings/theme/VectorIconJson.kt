/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A vector drawable of the app's as the theme maker's page draws it (`youmo-icons`, `youmo-engine-icons`):
 * its viewport and paths, `{vw, vh, paths: [{d, rule?, stroke?, alpha?, transform?}]}`, each filled in the
 * tint unless stroked, a group's transform written as SVG's.
 */
object VectorIconJson {
    fun of(name: String): String {
        val file = File("src/main/res/drawable/$name.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/drawable/$name.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val root = doc.documentElement
        fun attr(e: Element, a: String) = e.getAttributeNS(NS, a).takeIf { it.isNotEmpty() }
        val paths = buildJsonArray {
            fun visit(e: Element, transform: String?) {
                val children = e.childNodes
                for (i in 0 until children.length) {
                    val c = children.item(i) as? Element ?: continue
                    when (c.tagName) {
                        "group" -> visit(c, listOfNotNull(transform, groupTransform(c, ::attr)).joinToString(" ").ifEmpty { null })
                        "path" -> add(
                            buildJsonObject {
                                put("d", attr(c, "pathData") ?: "")
                                attr(c, "fillType")?.let { put("rule", if (it == "evenOdd") "evenodd" else "nonzero") }
                                if (attr(c, "strokeColor") != null && attr(c, "fillColor") == null) {
                                    put("stroke", attr(c, "strokeWidth")?.toDouble() ?: 1.0)
                                }
                                attr(c, "fillAlpha")?.let { put("alpha", it.toDouble()) }
                                transform?.let { put("transform", it) }
                            },
                        )
                    }
                }
            }
            visit(root, null)
        }
        return buildJsonObject {
            put("vw", attr(root, "viewportWidth")!!.toDouble())
            put("vh", attr(root, "viewportHeight")!!.toDouble())
            put("paths", paths)
        }.toString()
    }

    // a group's transform as SVG's: about its pivot, scaled and rotated, then translated
    private fun groupTransform(e: Element, attr: (Element, String) -> String?): String? {
        val px = attr(e, "pivotX")?.toDouble() ?: 0.0
        val py = attr(e, "pivotY")?.toDouble() ?: 0.0
        val tx = attr(e, "translateX")?.toDouble() ?: 0.0
        val ty = attr(e, "translateY")?.toDouble() ?: 0.0
        val sx = attr(e, "scaleX")?.toDouble() ?: 1.0
        val sy = attr(e, "scaleY")?.toDouble() ?: 1.0
        val r = attr(e, "rotation")?.toDouble() ?: 0.0
        if (listOf(tx, ty, sx - 1, sy - 1, r).all { it == 0.0 }) return null
        return "translate(${tx + px} ${ty + py}) rotate($r) scale($sx $sy) translate(${-px} ${-py})"
    }

    private const val NS = "http://schemas.android.com/apk/res/android"
}
