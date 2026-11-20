/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

/** A keyboard as layout-1.xsd writes it, before any condition is weighed or binding read. */
class Layout(
    val id: String,
    val name: String,
    val family: String,
    val columns: Double,
    val onSplit: OnSplit,
    val defaults: KeyAttrs,
    val parts: List<Part>,
    val preview: Boolean = true,
    val popups: Boolean = true,
    /** The app board a custom layout was made from: what its required keys are held to and what is drawn once it is gone. */
    val basedOn: String? = null,
    val meta: LayoutMeta? = null,
    val pads: List<String>? = null,
) {
    /** The one place a layout is remade from another (KeyData), so a new member needs carrying over here alone. */
    fun with(defaults: KeyAttrs, parts: List<Part>, popups: Boolean): Layout =
        Layout(id, name, family, columns, onSplit, defaults, parts, preview, popups, basedOn, meta, pads)
}

data class LayoutMeta(val author: String?, val version: String?, val license: String?, val minApp: String?, val description: String?)

sealed interface Part

class Include(val src: String, val condition: Condition?, val line: Int) : Part

class Fragment(val id: String, val defaults: KeyAttrs, val rows: List<Row>)

enum class OnSplit { Split, Side, None }

enum class RowSplit { Halves, Scale }

class Row(
    val id: String?,
    val indent: Double,
    val endIndent: Double,
    val height: Double,
    val condition: Condition?,
    val split: RowSplit,
    val defaults: KeyAttrs,
    val cells: List<Cell>,
    val line: Int,
) : Part {
    fun withCells(cells: List<Cell>): Row = Row(id, indent, endIndent, height, condition, split, defaults, cells, line)
}

sealed interface Width {
    data class Units(val units: Double) : Width
    data object Fill : Width
}

sealed interface Cell {
    val id: String?
    val condition: Condition?
    val line: Int
}

class Key(
    override val id: String,
    override val condition: Condition?,
    val attrs: KeyAttrs,
    val popup: Popup?,
    val menu: List<MenuItem>,
    override val line: Int,
    val variants: List<Variant> = emptyList(),
    /** The key's own, no variant's: the engine reads a layout's codes by its groups. */
    val letters: Letters? = null,
    val lean: Boolean? = null,
    /** Set by [KeyData] to the app key of its name's place, so a key merged or moved before it shifts no corner. */
    val place: Int? = null,
) : Cell {
    val leans: Boolean get() = letters != null && letters.group.length >= 2 && lean != false

    fun named(shortcut: String): Key = Key(id, condition, attrs.copy(shortcut = shortcut), popup, menu, line, variants, letters, lean, place)
}

data class Letters(val rows: List<String>) {
    val group: String get() = rows.joinToString("")

    override fun toString() = rows.joinToString(" ")

    companion object {
        fun of(text: String) = Letters(text.split(' '))
    }
}

class Gap(
    override val id: String?,
    val width: Width,
    override val condition: Condition?,
    override val line: Int,
) : Cell

class Slot(
    override val id: String?,
    val kind: String,
    val width: Width,
    val rows: Int,
    val face: Face,
    val look: String?,
    val classes: List<String>,
    override val condition: Condition?,
    override val line: Int,
) : Cell

class Variant(val condition: Condition, val attrs: KeyAttrs, val line: Int)

enum class Face(val xml: String) { Default("default"), Keep("keep"), None("none"), Special("special") }

/** Actions are carried to the scene as written: the app runs them. */
data class KeyAttrs(
    val width: Width? = null,
    val rows: Int? = null,
    val label: Text? = null,
    val alt: Text? = null,
    val icon: Text? = null,
    val kind: String? = null,
    val role: String? = null,
    val classes: List<String>? = null,
    val textSize: Double? = null,
    val compactTextScale: Double? = null,
    val bold: Boolean? = null,
    val face: Face? = null,
    val margin: Boolean? = null,
    val a11y: Text? = null,
    val sound: String? = null,
    val badge: Text? = null,
    val actions: Map<String, String> = emptyMap(),
    val shortcut: String? = null,
    val mark: Boolean? = null,
    val splitAfter: Boolean? = null,
) {
    fun over(under: KeyAttrs): KeyAttrs = KeyAttrs(
        width ?: under.width, rows ?: under.rows, label ?: under.label, alt ?: under.alt, icon ?: under.icon,
        kind ?: under.kind, role ?: under.role, classes ?: under.classes, textSize ?: under.textSize,
        compactTextScale ?: under.compactTextScale, bold ?: under.bold, face ?: under.face, margin ?: under.margin,
        a11y ?: under.a11y, sound ?: under.sound, badge ?: under.badge, under.actions + actions,
        shortcut ?: under.shortcut, mark ?: under.mark, splitAfter ?: under.splitAfter,
    )
}

data class Popup(val from: String, val preset: String?, val items: List<Pair<String, String?>>, val options: Map<String, String>)

data class MenuItem(val label: String, val icon: String?, val action: String)

sealed interface Text {
    data class Literal(val text: String) : Text
    data class Binding(val name: String, val arg: String?) : Text {
        override fun toString(): String = if (arg == null) "{$name}" else "{$name:$arg}"
    }

    companion object {
        val WITH_LETTER = setOf("case", "root", "sp-hint", "alt")
        val WITH_NUMBER = setOf("zhuyin-group", "nine-letters")

        const val GROUP = "group"

        /** Up to four in `label=` alone: older readers took one anywhere, so a pack for them writes no run. */
        const val PUNCT = "punct"
        val PLAIN = setOf("ime-name", "return", "confirm", "table-extra", "comma", "period", "back")

        val ICONS = setOf("shift-icon", "return-icon", "hold-badge", "lock-icon")
    }
}
