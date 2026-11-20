/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Gap
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.layout.Width

class ExpandedRow(
    val height: Double,
    val indent: Double,
    val endIndent: Double,
    val split: RowSplit,
    val items: List<Item>,
    val line: Int,
)

sealed interface Item {
    val width: Width
    val rows: Int get() = 1
}

class ExpandedKey(
    val key: Key,
    val attrs: KeyAttrs,
    val label: String?,
    val alt: String?,
    val icon: String?,
    val badge: String?,
    val a11y: String,
    /** Read by snapshots, exports and tests; the app reads [alt] and [popup]. */
    val altRaw: String? = null,
) : Item {
    override val width: Width get() = attrs.width ?: Width.Units(1.0)
    override val rows: Int get() = attrs.rows ?: 1
    val kind: String get() = attrs.kind ?: "letter"
}

class ExpandedGap(val gap: Gap) : Item {
    override val width: Width get() = gap.width
}

class ExpandedSlot(val slot: Slot) : Item {
    override val width: Width get() = slot.width
    override val rows: Int get() = slot.rows
}

/** A file may write an id twice where each has a `when=` (the number pad's `-`); two keys shown of one id are a problem. */
object Expand {

    /** A board split in halves has both sides: LayoutReader refuses `split-side` there. */
    fun rows(layout: Layout, fragments: Map<String, Fragment>, env: Env, splitSide: String? = null): Parsed<List<ExpandedRow>> {
        val p = Problems()
        val all = env.facts
        val facts = Condition.Facts { name, values ->
            if (name == "split-side") splitSide != null && (values == null || splitSide in values) else all.has(name, values)
        }
        val out = ArrayList<ExpandedRow>()
        for (part in layout.parts) {
            when (part) {
                is Row -> if (part.condition?.holds(facts) != false) row(part, layout.defaults, env, facts, p)?.let(out::add)
                is Include -> if (part.condition?.holds(facts) != false) {
                    val fragment = fragments[part.src]
                    if (fragment == null) {
                        p.add(part.line, "<include src=\"${part.src}\">: no such fragment")
                    } else {
                        val defaults = fragment.defaults.over(layout.defaults)
                        fragment.rows.filter { it.condition?.holds(facts) != false }.mapNotNullTo(out) { row(it, defaults, env, facts, p) }
                    }
                }
            }
        }
        if (out.isEmpty() && p.isEmpty()) p.add(0, "no row shows here")
        val seen = HashSet<String>()
        out.flatMap { it.items }.filterIsInstance<ExpandedKey>().forEach { k ->
            if (!seen.add(k.key.id)) p.add(k.key.line, "two keys \"${k.key.id}\" shown here")
        }
        return p.result { out }
    }

    private fun row(row: Row, fileDefaults: KeyAttrs, env: Env, facts: Condition.Facts, p: Problems): ExpandedRow? {
        val defaults = row.defaults.over(fileDefaults)
        val items = row.cells.filter { it.condition?.holds(facts) != false }.map { cell ->
            when (cell) {
                is Key -> {
                    val attrs = cell.attrs.over(defaults)
                    key(cell, cell.variants.firstOrNull { it.condition.holds(facts) }?.attrs?.over(attrs) ?: attrs, env)
                }
                is Gap -> ExpandedGap(cell)
                is Slot -> ExpandedSlot(cell)
            }
        }
        if (items.isEmpty()) {
            p.add(row.line, "a row with nothing shown here")
            return null
        }
        return ExpandedRow(row.height, row.indent, row.endIndent, row.split, items, row.line)
    }

    private fun key(key: Key, attrs: KeyAttrs, env: Env): ExpandedKey {
        val label = attrs.label?.let { text(it, env) }
        val alt = reading(attrs, env)
        return ExpandedKey(
            key, attrs, label, alt,
            attrs.icon?.let { icon(it, env) },
            attrs.badge?.let { icon(it, env) },
            attrs.a11y?.let { text(it, env) } ?: spoken(attrs, key.letters != null, heard(attrs, label, env), alt, env.texts),
            attrs.alt?.let(::written),
        )
    }

    // a named key's corner is the user's long press's first, not a theme's `alt=` they changed; a key of no name its layout's
    private fun reading(attrs: KeyAttrs, env: Env): String? {
        val alts = env.texts.alts
        val name = attrs.shortcut
        if (alts != null && name != null) {
            return alts[name]?.takeIf { it.isNotEmpty() }
        }
        return attrs.alt?.let { text(it, env) }?.takeIf { it.isNotEmpty() }
    }

    // as following Shift shows it: a capital not read as one only because it is drawn so
    private fun heard(attrs: KeyAttrs, label: String?, env: Env): String? =
        if (env.texts.letterCase == LetterCase.Shift) label
        else attrs.label?.let { text(it, env.copy(texts = env.texts.copy(letterCase = LetterCase.Shift))) }

    /** As theme 3.0's keys said it: TalkBack reads the description, so a group's letters are spaced ("Q W"), not read as a word. */
    fun spoken(attrs: KeyAttrs, group: Boolean, label: String?, alt: String?, texts: Texts): String {
        if (attrs.role == SPACE) return if (label.isNullOrEmpty()) A11Y_SPACE else texts.a11ySpace.replace(ARG1, label)
        val said = label?.let { if (group) it.uppercase().toList().joinToString(" ") else it }.orEmpty()
        return when {
            alt == null -> said
            said.isEmpty() -> alt
            // one pass: a literal "%2$s" in the label must not be taken for the alt's place
            else -> ARGS.replace(texts.a11yGrouped) { if (it.value == ARG1) said else alt }
        }
    }

    private const val SPACE = "space"
    private const val ARG1 = "%1\$s"

    private const val A11Y_SPACE = "a11y_key_space"
    private val ARGS = Regex("%[12]\\$" + "s")

    private fun written(t: Text): String = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> t.toString()
    }

    fun text(t: Text, env: Env): String = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> binding(t.name, t.arg.orEmpty(), env)
    }

    private fun binding(name: String, arg: String, env: Env): String {
        val texts = env.texts
        return when (name) {
            "case" -> cased(arg, env)
            // so a screen reader's text, read under 跟随 Shift, is the same whatever 拼音键字母大小写 draws
            "root" -> texts.other["root:$arg"]?.takeIf { it.isNotEmpty() } ?: cased(arg, env)
            "group" -> LetterCaseRule.shown(arg, LetterCaseRule.upper(texts.letterCase, env.shift, shiftless = true))
            // no built-in layout binds it any more; a theme's own written before still reads
            "alt" -> texts.alts?.get("full/$arg").orEmpty()
            "punct" -> arg.map { c -> c.toString().let { texts.punctuation[it] ?: it } }.joinToString("")
            "comma" -> texts.comma
            "period" -> texts.period
            "ime-name" -> texts.imeName
            "return" -> texts.returnLabel
            "confirm" -> texts.confirm
            "back" -> texts.back
            "nine-letters" -> nineLetters(texts.nineLetters[arg].orEmpty(), env)
            else -> texts.other[if (arg.isEmpty()) name else "$name:$arg"].orEmpty()
        }
    }

    private fun cased(letter: String, env: Env): String =
        LetterCaseRule.shown(letter, LetterCaseRule.upper(env.texts.letterCase, env.shift, keepUppercase = env.texts.keepUppercase))

    private fun nineLetters(letters: String, env: Env): String =
        LetterCaseRule.shown(letters, LetterCaseRule.upper(env.texts.letterCase, env.shift, shiftless = env.input.ime != "english"))

    fun icon(t: Text, env: Env): String? = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> when (t.name) {
            "shift-icon" -> when (env.shift) {
                Shift.None -> "shift"
                Shift.Once -> "shift-once"
                Shift.Lock -> "shift-locked"
            }
            "return-icon" -> env.texts.returnIcon
            "hold-badge" -> "voice".takeIf { env.input.holdVoice }
            "lock-icon" -> if (env.states["lock"].orEmpty().contains(KeyState.Locked)) "lock" else "lock-open"
            else -> null
        }
    }
}
