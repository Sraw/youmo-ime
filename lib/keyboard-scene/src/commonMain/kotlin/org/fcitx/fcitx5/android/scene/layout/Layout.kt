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
) : Cell

/** Room taking no touch (the old GapKey). */
class Gap(override val id: String?, val width: Width, override val condition: Condition?, override val line: Int) : Cell

/** A native widget's place (the handwriting pad, the nine keys' side column): laid out, not drawn. */
class Slot(
    override val id: String?,
    val kind: String,
    val width: Width,
    val rows: Int,
    val face: Face,
    override val condition: Condition?,
    override val line: Int,
) : Cell

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

/** A long press's popup as written (`<popup>`); the app fills a preset's from PopupPreset. */
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
        val WITH_LETTER = setOf("case", "root", "sp-hint", "alt", "punct")
        val WITH_NUMBER = setOf("zhuyin-group", "nine-letters")
        val PLAIN = setOf("ime-name", "return", "table-extra", "comma", "period")

        /** Icon bindings (`icon=`, `badge=`). */
        val ICONS = setOf("shift-icon", "return-icon", "hold-badge")
    }
}
