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

/** A row as the environment has it: what shows, each key's text read (060-render-v2 §6 step 2). */
class ExpandedRow(
    val height: Double,
    val indent: Double,
    val endIndent: Double,
    val split: RowSplit,
    val items: List<Item>,
    val line: Int,
)

/** What a row holds once expanded: [rows] tall (one, or the rows below it too: rules C §2, §8). */
sealed interface Item {
    val width: Width
    val rows: Int get() = 1
}

/** A key shown, its attributes laid over its row's and file's defaults (and the variant that holds), its bindings read. */
class ExpandedKey(
    val key: Key,
    val attrs: KeyAttrs,
    val label: String?,
    val alt: String?,
    val icon: String?,
    val badge: String?,
    val a11y: String,
    /** [alt] as the layout wrote it, a binding unread (`{punct:~}`): what the app offers in a long press. */
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

/**
 * Step 2 of §6: includes put in, `when=` weighed, a key's first `<variant>` that holds laid over it,
 * bindings read. [fragments] are the files includes name, by id; one not there is a problem, as is a
 * row with nothing shown, and two keys shown of one id (a file may write an id twice where each has
 * a `when=`: the number pad's `-`, at one place or another as the pad is).
 */
object Expand {

    /**
     * [splitSide]: the side a board split goes to (`left`, `right`), which `split-side` asks; null
     * where it is not split to a side (a board split in halves has both: LayoutReader refuses it there).
     */
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
        val alt = attrs.alt?.let { text(it, env) }?.takeIf { it.isNotEmpty() }
        return ExpandedKey(
            key, attrs, label, alt,
            attrs.icon?.let { icon(it, env) },
            attrs.badge?.let { icon(it, env) },
            attrs.a11y?.let { text(it, env) } ?: spoken(attrs, key.letters != null, label, alt, env.texts),
            attrs.alt?.let(::written),
        )
    }

    /**
     * What a screen reader says of a key the layout gives no a11y, as theme 3.0's keys said it: the
     * space with the input method's name ([Texts.a11ySpace], BaseKeyboard's a11y_key_space_with_ime:
     * TalkBack reads the description, not the name drawn); a group key's letters one by one in
     * capitals (a key with letters=), a `;` kept (GroupedKeyboard's "Q W", "L ;": TalkBack read the face "QW" as a word),
     * any other label as it is (Rime, Fn, ABC); then the alt as shown ([Texts.a11yGrouped]'s
     * a11y_key_grouped: "Q W, 1", "ABC, 2"; in Chinese "Q W，1", "ABC，2", on every keyboard that passes it).
     */
    fun spoken(attrs: KeyAttrs, group: Boolean, label: String?, alt: String?, texts: Texts): String {
        if (attrs.role == SPACE && !label.isNullOrEmpty()) return texts.a11ySpace.replace(ARG1, label)
        val said = label?.let { if (group) it.uppercase().toList().joinToString(" ") else it }.orEmpty()
        return when {
            alt == null -> said
            said.isEmpty() -> alt
            else -> texts.a11yGrouped.replace(ARG1, said).replace(ARG2, alt)
        }
    }

    private const val SPACE = "space"
    private const val ARG1 = "%1\$s"
    private const val ARG2 = "%2\$s"

    private fun written(t: Text): String = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> t.toString()
    }

    /** [t] as [env] reads it: a literal as written, a binding's value (empty where the environment has none). */
    fun text(t: Text, env: Env): String = when (t) {
        is Text.Literal -> t.text
        is Text.Binding -> binding(t.name, t.arg.orEmpty(), env)
    }

    private fun binding(name: String, arg: String, env: Env): String {
        val texts = env.texts
        return when (name) {
            "case" -> if (env.shift != Shift.None || texts.keepUppercase) arg.uppercase() else arg
            "alt" -> texts.alts?.get(arg).orEmpty()
            "punct" -> texts.punctuation[arg] ?: arg
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

    // rules C §2: pinyin's and 双拼's in capitals; English's as Shift has them, small without
    private fun nineLetters(letters: String, env: Env): String =
        if (env.input.ime == "english" && env.shift == Shift.None) letters.lowercase() else letters.uppercase()

    /** An icon's name: as written, or a binding's (null: none drawn). */
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
            // the symbols panel's lock (PickerLayout): open, or shut while the panel stays
            "lock-icon" -> if (env.states["lock"].orEmpty().contains(KeyState.Locked)) "lock" else "lock-open"
            else -> null
        }
    }
}
