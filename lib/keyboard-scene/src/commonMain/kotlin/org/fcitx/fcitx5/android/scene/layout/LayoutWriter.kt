/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.xml.Xml
import kotlin.math.floor

/**
 * A [Layout] as layout-1's text (what the layout maker saves and the app exports), canonical: one
 * layout is always the same text, and [LayoutReader] reads it back as the same layout.
 *
 * - `<meta>`, `<defaults>`, then the rows and includes in order; two spaces an indent, an element a line.
 * - An attribute only where it is not what the reader takes when it is not written (`columns="10"`, `height="1"`,
 *   `on-split="side"`, a gap one unit wide), each in the order the reader reads them; a function key's name without its
 *   `fn/` (as written by hand).
 * - A `<gap>`'s `look=` and `class=` are not written: 0.6.0's, read and drawn no more (§1.1).
 * - Comments, and where a file put its attributes on lines of their own, are not kept.
 */
object LayoutWriter {

    fun write(layout: Layout): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        val root = listOf(
            "xmlns" to LayoutReader.NAMESPACE, "id" to layout.id, "name" to layout.name, "family" to layout.family,
            "based-on" to layout.basedOn,
            "columns" to layout.columns.takeIf { it != DEFAULT_COLUMNS }?.let(::number),
            "on-split" to when (layout.onSplit) {
                OnSplit.Split -> "split"
                OnSplit.None -> "none"
                OnSplit.Side -> null
            },
            "preview" to "off".takeIf { !layout.preview },
            "popups" to "off".takeIf { !layout.popups },
            "pads" to layout.pads?.joinToString(" "),
        )
        open("", "keyboard", root)
        layout.meta?.let { m ->
            element(
                "  ", "meta",
                listOf("author" to m.author, "version" to m.version, "license" to m.license, "min-app" to m.minApp, "description" to m.description),
            )
        }
        defaults("  ", layout.defaults)
        for (part in layout.parts) {
            when (part) {
                is Include -> element("  ", "include", listOf("src" to part.src, "when" to part.condition?.toString()))
                is Row -> row(part)
            }
        }
        appendLine("</keyboard>")
    }

    private const val DEFAULT_COLUMNS = 10.0

    private fun StringBuilder.row(row: Row) {
        val attrs = listOf(
            "id" to row.id,
            "indent" to row.indent.takeIf { it != 0.0 }?.let(::number),
            "end-indent" to row.endIndent.takeIf { it != 0.0 }?.let(::number),
            "height" to row.height.takeIf { it != 1.0 }?.let(::number),
            "when" to row.condition?.toString(),
            "split" to "halves".takeIf { row.split == RowSplit.Halves },
        )
        open("  ", "row", attrs)
        defaults("    ", row.defaults)
        for (cell in row.cells) {
            when (cell) {
                is Key -> key(cell)
                is Gap -> element(
                    "    ", "gap",
                    listOf("id" to cell.id, "width" to cell.width.takeIf { it != Width.Units(1.0) }?.let(::width), "when" to cell.condition?.toString()),
                )
                is Slot -> slot(cell)
            }
        }
        appendLine("  </row>")
    }

    private fun StringBuilder.defaults(indent: String, d: KeyAttrs) {
        val attrs = look(d) + listOf("width" to d.width?.let(::width))
        if (attrs.any { it.second != null }) element(indent, "defaults", attrs)
    }

    private fun StringBuilder.slot(cell: Slot) {
        val scroll = cell.kind == LayoutReader.SCROLL
        element(
            "    ", "slot",
            listOf(
                "id" to cell.id, "kind" to cell.kind, "list" to cell.list,
                "direction" to cell.direction.takeIf { it != Direction.Vertical && scroll }?.xml,
                "show" to cell.show.takeIf { it != Slot.DEFAULT_SHOW && scroll }?.toString(),
                "width" to cell.width.takeIf { it != Width.Units(1.0) }?.let(::width),
                "rows" to cell.rows.takeIf { it != 1 }?.toString(),
                "face" to cell.face.takeIf { it != if (scroll) LayoutReader.SCROLL_FACE else Face.Default }?.xml,
                "look" to cell.look.takeIf { !scroll || it != LayoutReader.SCROLL_LOOK },
                "class" to cell.classes.takeIf { it.isNotEmpty() }?.joinToString(" "),
                "when" to cell.condition?.toString(),
            ),
        )
    }

    private fun StringBuilder.key(k: Key) {
        val attrs = listOf("id" to k.id, "when" to k.condition?.toString()) + look(k.attrs) + listOf(
            "width" to k.attrs.width?.let(::width),
            "rows" to k.attrs.rows?.toString(),
            "letters" to k.letters?.toString(),
            "lean" to k.lean?.let { if (it) "on" else "off" },
        )
        if (k.popup == null && k.menu.isEmpty() && k.variants.isEmpty()) {
            element("    ", "key", attrs)
            return
        }
        open("    ", "key", attrs)
        for (v in k.variants) element("      ", "variant", listOf("when" to v.condition.toString()) + look(v.attrs))
        k.popup?.let { p ->
            val pa = listOf("from" to p.from.takeIf { it != "preset" }, "preset" to p.preset) + p.options.map { it.key to it.value }
            if (p.items.isEmpty()) {
                element("      ", "popup", pa)
            } else {
                open("      ", "popup", pa)
                p.items.forEach { (text, send) -> element("        ", "item", listOf("text" to text, "send" to send)) }
                appendLine("      </popup>")
            }
        }
        if (k.menu.isNotEmpty()) {
            appendLine("      <menu>")
            k.menu.forEach { element("        ", "item", listOf("label" to it.label, "icon" to it.icon, "do" to it.action)) }
            appendLine("      </menu>")
        }
        appendLine("    </key>")
    }

    // the look and actions a key, a variant and <defaults> share, in the reader's order (LayoutReader.look)
    private fun look(a: KeyAttrs): List<Pair<String, String?>> = listOf(
        "label" to a.label?.let(::text),
        "alt" to a.alt?.let(::text),
        "icon" to a.icon?.let(::text),
        "kind" to a.kind,
        "role" to a.role,
        "class" to a.classes?.joinToString(" "),
        "text-size" to a.textSize?.let(::number),
        "compact-text-scale" to a.compactTextScale?.let(::number),
        "text-style" to a.bold?.let { if (it) "bold" else "normal" },
        "face" to a.face?.xml,
        "margin" to a.margin?.toString(),
        "a11y" to a.a11y?.let(::text),
        "sound" to a.sound,
        "badge" to a.badge?.let(::text),
    ) + ACTIONS.map { it to a.actions[it] } + listOf(
        "swipe-axis" to a.actions["swipe-axis"],
        "repeat" to a.actions["repeat"],
        "preview" to a.actions["preview"],
        // a function key's bare (as layouts write it), unless its name holds a / (fn/a/b): bare, it would read as a/b
        "shortcut" to a.shortcut?.let { KeyId(it).let { id -> if (id.isFunction && '/' !in id.key) id.key else it } },
        "mark" to a.mark?.toString(),
        "split" to "after".takeIf { a.splitAfter == true },
    )

    private val ACTIONS = listOf("send", "long-press", "swipe-up", "swipe-down", "double-tap")

    private fun text(t: Text): String = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> t.toString()
    }

    private fun width(w: Width): String = when (w) {
        Width.Fill -> "fill"
        is Width.Units -> number(w.units)
    }

    // a whole number without its .0, else as Kotlin writes it (alike on the JVM and JS for a layout's widths)
    // as a person writes it: never in an exponent (the JVM's 1.0E-4, JS's 1e-7), which the reader does not take
    internal fun number(v: Double): String {
        if (v == floor(v)) return v.toLong().toString()
        val s = v.toString()
        val e = s.indexOfFirst { it == 'E' || it == 'e' }
        if (e < 0) return s
        val mantissa = s.substring(0, e).removePrefix("-")
        val digits = mantissa.replace(".", "").trimEnd('0')
        val sign = if (v < 0) "-" else ""
        val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it } + s.substring(e + 1).toInt()
        return sign + when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
    }

    private fun StringBuilder.open(indent: String, name: String, attrs: List<Pair<String, String?>>) {
        appendLine("$indent<$name${attributes(attrs)}>")
    }

    private fun StringBuilder.element(indent: String, name: String, attrs: List<Pair<String, String?>>) {
        appendLine("$indent<$name${attributes(attrs)}/>")
    }

    private fun attributes(attrs: List<Pair<String, String?>>): String =
        attrs.mapNotNull { (k, v) -> v?.let { " $k=\"${escape(it)}\"" } }.joinToString("")

    private fun escape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\t' -> append("&#9;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                // what XML has no character for (a control pasted into a name): left out, as the reader drops it
                else -> if (Xml.allows(c)) append(c)
            }
        }
    }
}
