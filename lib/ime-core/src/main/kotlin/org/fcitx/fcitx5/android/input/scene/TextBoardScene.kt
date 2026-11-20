/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyMarkText
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults
import java.util.Calendar

/** What the 26 keys show, as qwerty.xml's `when=` asks; the keyboard decides each as it always has. */
data class TextBoardState(
    /** `ing` on `;` (semicolonKey). */
    val semicolonScheme: Boolean = false,
    /** 确定 in Return's place while a code is typed. */
    val table: Boolean = false,
    /** TableKeyboard.lookUpKey: its 拼 key in Shift's place. */
    val tableLookUp: Boolean = false,
    /** TableKeyboard.extraKey: `;` or `*`. */
    val tableExtra: Char? = null,
    val lookingUp: Boolean = false,
    /** Return commits it (ReturnKeyAppearance.composing). */
    val composing: Boolean = false,
    val address: AddressKeys = AddressKeys.NONE,
    val langKey: Boolean = true,
    val holdVoice: Boolean = false,
    /** TableKeyboard.labelled: `{root:x}` on them. */
    val roots: Boolean = false,
    /** ShiftSeparator.on: 分词 in Shift's place. */
    val separating: Boolean = false,
) {
    fun input(): Input = Input(
        ime = when {
            semicolonScheme -> "shuangpin"
            table -> "table"
            else -> "pinyin"
        },
        schemeTypes = if (semicolonScheme) setOf("semicolon") else emptySet(),
        table = if (table) "table" else null,
        roots = roots,
        tableLookUp = table && tableLookUp,
        tableExtra = when (tableExtra) {
            ';' -> "semicolon"
            '*' -> "star"
            else -> null
        },
        field = when (address) {
            AddressKeys.URL -> "url"
            AddressKeys.EMAIL -> "email"
            AddressKeys.NONE -> "text"
        },
        voice = holdVoice,
        holdVoice = holdVoice,
        langKey = langKey,
        // asked only of a table and pinyin/双拼: elsewhere (English's word) each key typed would make a new scene the same as before
        composing = composing && table || separating,
        lookingUp = lookingUp,
    )
}

/** `{root:x}`: what a table shows over a letter ([TableKeyboard.label]); corners are the resolver's. */
object SceneLetters {
    private val LETTERS = 'a'..'z'

    /** "" for a letter with none, for which the engine's `{root:x}` falls back on `{case:x}`. */
    fun roots(table: TableKeyboard?): Map<String, String> =
        if (table?.labelled != true) emptyMap() else LETTERS.associate { c -> "root:$c" to table.label(c).orEmpty() }
}

/** Each key's two swipe slots, 上滑 and 下滑, where each draws. */
object SceneMarks {

    /** A table's roots take [charShortcuts]' room; a long press's first by the key's name (none: nothing drawn). */
    @Suppress("LongParameterList")
    fun settings(
        slots: SwipeSlots,
        ways: KeySwipe.Ways,
        upAt: KeyMarks.At,
        downAt: KeyMarks.At,
        charShortcuts: Boolean,
        names: Collection<String>,
        firsts: Map<String, String?> = emptyMap(),
        now: () -> Calendar = Calendar::getInstance,
    ): MarkSettings {
        val marks = HashMap<String, MarkContent>()
        val user = HashSet<String>()
        // one time for every mark of the board, read at the first with variables, so two keys' `$minute` never differ
        val at by lazy(now)
        // the names as a layout reads them, one a key (`full/q`, `fn/space`): a slot stored by the same
        for (name in names) {
            for (up in listOf(true, false)) {
                if (slots.isSet(name, up)) user += "$name:${if (up) "up" else "down"}"
                marks["$name:${if (up) "up" else "down"}"] = when (val slot = slots.resolve(name, up)) {
                    SwipeSlot.First -> firsts[name]?.let(::symbol) ?: MarkContent.Empty
                    else -> content(slot.shortcut) { at } ?: MarkContent.Empty
                }
            }
        }
        return MarkSettings(
            upAt = upAt,
            downAt = downAt,
            charShortcuts = charShortcuts,
            upOn = ways.up,
            downOn = ways.down,
            firstUp = slots.orientation == SwipeOrientation.FirstUp,
            shortcuts = marks,
            user = user,
        )
    }

    /** Its variants' too, and the included [fragments]'. */
    fun names(layout: Layout, fragments: Collection<Fragment> = emptyList()): Set<String> =
        (layout.parts.filterIsInstance<Row>() + fragments.flatMap { it.rows })
            .flatMap { it.cells }.filterIsInstance<Key>()
            .flatMap { k -> listOf(k.attrs.shortcut) + k.variants.map { it.attrs.shortcut } }
            .filterNotNull().toSet()

    /** As a swipe's text is (KeyMarkText): two characters as seen and 「…」; typed and said whole. */
    fun symbol(first: String): MarkContent = MarkContent.Symbol(KeyMarkText.of(first))

    /** [now] asked of a text of variables alone: marks are made at each state, some 30-70 slots. */
    fun content(s: KeyShortcut, now: () -> Calendar): MarkContent? = when (s) {
        is KeyShortcut.Command -> MarkContent.Icon(icon(s.command))
        // at most two characters as seen, the rest an ellipsis: the bubble shows it whole
        is KeyShortcut.Text -> (if (s.variables) s.typed(now()) else s.text).takeIf { it.isNotEmpty() }?.let { MarkContent.Text(KeyMarkText.of(it), KeyMarkText.shorter(it)) }
        KeyShortcut.None -> null
    }

    /** SlotDefaults.icon's names; the app draws each name's drawable. */
    fun icon(command: KeyCommand): String = SlotDefaults.icon(command.key)
}
