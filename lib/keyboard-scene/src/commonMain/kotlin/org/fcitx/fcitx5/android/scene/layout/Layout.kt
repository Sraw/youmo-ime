/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

/**
 * A keyboard as layout-1.xsd writes it (`https://youmo-ime/layout/1`): what is on it and how it is
 * arranged, before any condition is weighed or binding read ([org.fcitx.fcitx5.android.scene.engine]).
 */
class Layout(
    val id: String,
    val name: String,
    val family: String,
    val columns: Double,
    val onSplit: OnSplit,
    val defaults: KeyAttrs,
    val parts: List<Part>,
    /** Its keys pop up a preview as they are pressed, and their long press's keyboard or menu (the number pad's do not: rules C §7). */
    val preview: Boolean = true,
    val popups: Boolean = true,
)

/** A row of a keyboard, or the rows of a fragment it includes. */
sealed interface Part

/** `<include src>`: [src]'s rows here, while [condition] holds. */
class Include(val src: String, val condition: Condition?, val line: Int) : Part

/** A file of rows other keyboards include (`<fragment>`, a shared bottom row). */
class Fragment(val id: String, val defaults: KeyAttrs, val rows: List<Row>)

enum class OnSplit { Split, Side, None }

/**
 * How a row splits in two on a keyboard that does ([OnSplit.Split], rules A §8). [Halves]: its keys
 * but those [KeyAttrs.split] after, five each side, the middle one on both where there are nine;
 * the room between them filling. [Scale]: every key as wide in split letters as it was in letters.
 */
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
) : Part

/** A width in key units (1 = a letter); [Fill] the row's room left, shared among its fills. */
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
    /** The first that holds is laid over [attrs]. */
    val variants: List<Variant> = emptyList(),
    /**
     * The letters the key types as one group (`letters="qw"`, the 14 and 18 keys'): a tap sends the
     * group's code, a long press its letters (the app's, 060-render-v2 §15). The key's own, no
     * variant's: a layout's groups are what the engine reads its codes by.
     */
    val letters: Letters? = null,
    /** `lean=` as written: `off`, its tap sends the group's code alone, not where it fell (null: not written). */
    val lean: Boolean? = null,
) : Cell {
    /**
     * Whether a tap on it leans to the letter it fell nearest (060-render-v2 §15, 按落点加权): a group
     * of two letters or more whose `lean=` is not off.
     */
    val leans: Boolean get() = letters != null && letters.group.length >= 2 && lean != false
}

/**
 * A group key's letters as they sit on it (layout-1's `letters=`, 060-render-v2 §15): a row each, a
 * space between rows (`"pq rs"`, 2 × 2), each row's letters sharing its width, the rows its height.
 * Where a tap leans and, P3, where its letters are drawn by default.
 */
data class Letters(val rows: List<String>) {
    /** The group's letters, row by row: what the engine reads it as. */
    val group: String get() = rows.joinToString("")

    /** As written. */
    override fun toString() = rows.joinToString(" ")

    companion object {
        fun of(text: String) = Letters(text.split(' '))
    }
}

/**
 * Room taking no touch (the old GapKey); with a [look], a key's face of that kind drawn there all the
 * same, a key that does nothing (the number pad's blank places: rules C §7, the old BlankKey).
 */
class Gap(
    override val id: String?,
    val width: Width,
    val look: String?,
    val classes: List<String>,
    override val condition: Condition?,
    override val line: Int,
) : Cell

/**
 * A native widget's place (the handwriting pad, the nine keys' side column), [rows] tall: laid out,
 * the widget drawn by the app; with a [look], a key's face of that kind under it (the column a
 * function key's, the pad a letter's: rules C §2, §8), never pressed.
 */
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

/** A key's look and actions where [condition] holds (`<variant>`): the nine keys' 1 while typing (rules C §2). */
class Variant(val condition: Condition, val attrs: KeyAttrs, val line: Int)

enum class Face(val xml: String) { Default("default"), Keep("keep"), None("none"), Special("special") }

/**
 * A key's look and actions, each null where not written; [over] lays one set on another (a key's on
 * its row's defaults, on its file's). Actions are carried to the scene as written: the app runs them.
 */
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
    /** These, where written, over [under]. */
    fun over(under: KeyAttrs): KeyAttrs = KeyAttrs(
        width ?: under.width, rows ?: under.rows, label ?: under.label, alt ?: under.alt, icon ?: under.icon,
        kind ?: under.kind, role ?: under.role, classes ?: under.classes, textSize ?: under.textSize,
        compactTextScale ?: under.compactTextScale, bold ?: under.bold, face ?: under.face, margin ?: under.margin,
        a11y ?: under.a11y, sound ?: under.sound, badge ?: under.badge, under.actions + actions,
        shortcut ?: under.shortcut, mark ?: under.mark, splitAfter ?: under.splitAfter,
    )
}

/**
 * A long press's popup as written (`<popup>`): [from] `preset` (PopupPreset's, [preset] or the key's
 * own), `list` ([items]), `none`, or made of the key itself by the app, `nine` (a nine key's letters,
 * its digit and t9_symbols) or `group` (a group's letters, its corner and their 26 keys' symbols).
 */
data class Popup(val from: String, val preset: String?, val items: List<Pair<String, String?>>, val options: Map<String, String>)

data class MenuItem(val label: String, val icon: String?, val action: String)

/**
 * Text on a key: as written, or one binding read from the environment as the scene is built
 * (layout-1.xsd's list: `{case:q}`, `{ime-name}`, `{shift-icon}`…).
 */
sealed interface Text {
    data class Literal(val text: String) : Text
    data class Binding(val name: String, val arg: String?) : Text {
        override fun toString(): String = if (arg == null) "{$name}" else "{$name:$arg}"
    }

    companion object {
        /** Bindings with an argument, and the characters it may be. */
        val WITH_LETTER = setOf("case", "root", "sp-hint", "alt")
        val WITH_NUMBER = setOf("zhuyin-group", "nine-letters")

        /** `{group:qw}`: a 14/18 键 key's one or two letters (`;` among them), cased as 拼音键字母大小写 says. */
        const val GROUP = "group"

        /**
         * `{punct:x}`: a character but a space or a brace, as punctuation maps it (`(`, `@`, `:` among the 18 keys' corners);
         * up to four, each mapped, in `label=` alone (九键 1's `,.?!`): `alt=` and `a11y=` take one (LayoutReader), and the
         * 0.6.0 betas' readers took one anywhere, so a pack for them writes no run.
         */
        const val PUNCT = "punct"
        val PLAIN = setOf("ime-name", "return", "confirm", "table-extra", "comma", "period", "back")

        /** Icon bindings (`icon=`, `badge=`). */
        val ICONS = setOf("shift-icon", "return-icon", "hold-badge", "lock-icon")
    }
}
