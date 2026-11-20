/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.scene.SceneGroups

/**
 * An on-screen keyboard an input method is typed on. The app stores its own enums' names (PinyinKeyboard and
 * kin in AppPrefs), so this one may be renamed. [CUSTOM]: a user's own layout, stored beside it.
 */
enum class ImKeyboard { FULL, KEYS18, KEYS14, NINE_KEYS, ZHUYIN_NINE, ZHUYIN_FULL, CUSTOM }

/**
 * A user's layout as the keyboard options see it: [basedOn] is the board it is drawn as where it cannot be;
 * [drawn] false means its base is drawn instead; [stamp] changes with its file; [pads] only for a number board.
 */
data class CustomChoice(
    val id: String,
    val name: String,
    val family: String,
    val basedOn: String?,
    val groups: List<String> = emptyList(),
    val drawn: Boolean = true,
    val tableExtras: Boolean = false,
    val stamp: String = "",
    val pads: List<String> = emptyList(),
)

/** Which keyboards each input method offers and which layout the screen shows for it; the app passes the prefs in. */
object ImKeyboards {

    enum class Layout {
        TEXT,
        PINYIN14,
        PINYIN18,
        PINYIN_NINE,
        SHUANGPIN18,
        SHUANGPIN14,
        SHUANGPIN_NINE,
        ZHUYIN_NINE,
        ZHUYIN_FULL,
        ENGLISH_NINE,
    }

    /** [ImKeyboard.FULL] where never chosen; the tables share one choice [table]; a custom id missing from [customs] does not read. */
    data class Chosen(
        val pinyin: ImKeyboard = ImKeyboard.FULL,
        val shuangpin: ImKeyboard = ImKeyboard.FULL,
        val english: ImKeyboard = ImKeyboard.FULL,
        val table: ImKeyboard = ImKeyboard.FULL,
        val layouts: Map<String, String> = emptyMap(),
        val customs: Map<String, CustomChoice> = emptyMap(),
    ) {
        fun of(im: String): ImKeyboard = when (im) {
            InputMethodCatalog.PINYIN -> pinyin
            InputMethodCatalog.SHUANGPIN -> shuangpin
            InputMethodCatalog.ENGLISH -> english
            else -> if (InputMethodCatalog.hasTableKeys(im)) table else ImKeyboard.FULL
        }

        fun layoutOf(im: String): String? = layouts[slot(im)]?.takeIf { of(im) == ImKeyboard.CUSTOM }

        fun customOf(im: String): CustomChoice? = layoutOf(im)?.let(customs::get)
    }

    fun slot(im: String): String = if (InputMethodCatalog.hasTableKeys(im)) TABLE else im

    const val TABLE = "table"

    val SLOTS = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH, TABLE)

    private val ALL = listOf(ImKeyboard.FULL, ImKeyboard.KEYS18, ImKeyboard.KEYS14, ImKeyboard.NINE_KEYS)

    /** None for a table. 双拼's 14 keys not under a [scheme] that types `;` (微软: ing), which no key of theirs has. */
    fun choices(im: String, scheme: ShuangpinScheme): List<ImKeyboard> = when (im) {
        // pinyin typed in zhuyin too: two keyboards of its own
        InputMethodCatalog.PINYIN -> ALL + ImKeyboard.ZHUYIN_NINE + ImKeyboard.ZHUYIN_FULL
        InputMethodCatalog.SHUANGPIN -> if (has14(scheme)) ALL else ALL - ImKeyboard.KEYS14
        InputMethodCatalog.ENGLISH -> listOf(ImKeyboard.FULL, ImKeyboard.NINE_KEYS)
        else -> emptyList()
    }

    fun has14(scheme: ShuangpinScheme): Boolean = !scheme.types(';')

    /**
     * Whether [im] may be typed on a user's [layout]: `letters` by every input method that chooses (a table if it
     * has the table's extra keys); `nine` by pinyin, 双拼, English; `grouped` by pinyin, and 双拼 if its groups are the 14 or 18 keys'.
     */
    fun usable(im: String, layout: CustomChoice, scheme: ShuangpinScheme): Boolean {
        if (!layout.drawn) return false
        val table = InputMethodCatalog.hasTableKeys(im)
        return when (layout.family) {
            LETTERS -> im in TYPED || (table && layout.tableExtras)
            NINE -> im in TYPED
            GROUPED -> when (im) {
                InputMethodCatalog.PINYIN -> true
                InputMethodCatalog.SHUANGPIN -> when (groupedAs(layout)) {
                    ImKeyboard.KEYS14 -> has14(scheme)
                    ImKeyboard.KEYS18 -> true
                    else -> false
                }
                else -> false
            }
            else -> false
        }
    }

    fun customs(im: String, scheme: ShuangpinScheme, all: List<CustomChoice>): List<CustomChoice> = all.filter { usable(im, it, scheme) }

    /** A table offers a choice only through a user's layout it may be typed on. */
    fun offers(im: String, scheme: ShuangpinScheme, all: List<CustomChoice>): Boolean =
        choices(im, scheme).isNotEmpty() || customs(im, scheme, all).isNotEmpty()

    /** The built-in keyboard the engine reads a grouped layout as, null for groups of its own. */
    fun groupedAs(layout: CustomChoice): ImKeyboard? {
        if (layout.family != GROUPED) return null
        return when (SceneGroups.letters(layout.groups)) {
            NineKeyLetters.G14 -> ImKeyboard.KEYS14
            NineKeyLetters.G18 -> ImKeyboard.KEYS18
            else -> null
        }
    }

    /** What is drawn in a layout's place where it cannot be, or once deleted; full for none. */
    fun baseOf(layout: CustomChoice?): ImKeyboard = when (layout?.basedOn) {
        "k18" -> ImKeyboard.KEYS18
        "k14" -> ImKeyboard.KEYS14
        "nine" -> ImKeyboard.NINE_KEYS
        else -> ImKeyboard.FULL
    }

    /**
     * The keyboard [im] is typed on: an unoffered choice falls to the nearest (the 14 keys under 微软 to the 18);
     * a user's layout if [usable], else its base where [im] has it, else full (the preference left as it is).
     */
    fun effective(im: String, chosen: ImKeyboard, scheme: ShuangpinScheme, custom: CustomChoice? = null): ImKeyboard {
        if (chosen == ImKeyboard.CUSTOM) {
            return if (custom != null && usable(im, custom, scheme)) ImKeyboard.CUSTOM else effective(im, baseOf(custom), scheme)
        }
        val offered = choices(im, scheme)
        return when {
            chosen in offered -> chosen
            chosen == ImKeyboard.KEYS14 && ImKeyboard.KEYS18 in offered -> ImKeyboard.KEYS18
            else -> ImKeyboard.FULL
        }
    }

    /** What to store once [scheme] no longer offers [chosen] (so it stays after switching back), null while it does. */
    fun fallback(im: String, chosen: ImKeyboard, scheme: ShuangpinScheme, custom: CustomChoice? = null): ImKeyboard? {
        if (chosen == ImKeyboard.CUSTOM && custom?.drawn != true) return null
        return effective(im, chosen, scheme, custom).takeIf { it != chosen && choices(im, scheme).isNotEmpty() }
    }

    /** Each slot that chose layout [id], once it is deleted: its base where the input method has it, else full. */
    fun afterDelete(chosen: Chosen, id: String, layout: CustomChoice?, scheme: ShuangpinScheme): Map<String, ImKeyboard> =
        usedBy(chosen, id).associateWith { effective(imOf(it), baseOf(layout), scheme) }

    /** The tables choose alike: any one stands for them. */
    fun imOf(slot: String): String = if (slot == TABLE) InputMethodCatalog.TABLES.keys.first() else slot

    /** 用在：… under its card. */
    fun usedBy(chosen: Chosen, id: String): List<String> = SLOTS.filter { chosen.layoutOf(imOf(it)) == id }

    /** Each once in [SLOTS]' order: those 按键字符和快捷键 has a tab for. */
    fun inUse(chosen: Chosen, scheme: ShuangpinScheme): List<CustomChoice> =
        SLOTS.mapNotNull { shown(imOf(it), chosen, scheme).custom }.distinctBy { it.id }

    /** 用在… of its card, and the question after its import. */
    fun usableBy(layout: CustomChoice, scheme: ShuangpinScheme): List<String> = SLOTS.filter { usable(imOf(it), layout, scheme) }

    /** [on]: it; else, where it was its keyboard, as [afterDelete]; null to leave as it is. */
    fun ticked(chosen: Chosen, slot: String, layout: CustomChoice, on: Boolean, scheme: ShuangpinScheme): ImKeyboard? = when {
        on -> ImKeyboard.CUSTOM
        chosen.layoutOf(imOf(slot)) == layout.id -> effective(imOf(slot), baseOf(layout), scheme)
        else -> null
    }

    /** What [im]'s keys were typed on, for retyping: [groups] only for a grouped layout of its own groups (others type the built-in codes). */
    data class TypedWith(val layout: Layout, val scheme: ShuangpinScheme?, val groups: List<String>? = null)

    /** What was typed is typed again on the same alone (清除's 恢复): another's keys mean other keys or syllables. */
    fun typedWith(im: String, chosen: Chosen, scheme: ShuangpinScheme): TypedWith {
        val shown = shown(im, chosen, scheme)
        return TypedWith(shown.layout, scheme.takeIf { im == InputMethodCatalog.SHUANGPIN }, shown.custom?.takeIf { it.family == GROUPED && groupedAs(it) == null }?.groups)
    }

    /** [layout] is the built-in one it is typed as; [custom] the user's drawn in its place. */
    data class Shown(val layout: Layout, val custom: CustomChoice? = null)

    /** A user's layout drawn where [im] chose one and may type on it; English's nine keys fall to full in a password. */
    fun shown(im: String, chosen: Chosen, scheme: ShuangpinScheme, password: Boolean = false): Shown {
        val custom = chosen.customOf(im)
        if (effective(im, chosen.of(im), scheme, custom) != ImKeyboard.CUSTOM || custom == null) return Shown(layoutOf(im, chosen, scheme, password))
        val keyboard = when (custom.family) {
            LETTERS -> ImKeyboard.FULL
            NINE -> ImKeyboard.NINE_KEYS
            else -> groupedAs(custom) ?: ImKeyboard.KEYS14
        }
        val layout = layoutOf(im, keyboard, password)
        // English's nine keys in a password: the full keyboard, the app's
        return Shown(layout, custom.takeIf { layout != Layout.TEXT || custom.family == LETTERS })
    }

    /** In a [password] field English's the full keyboard whatever it chose. */
    fun layoutOf(im: String, chosen: Chosen, scheme: ShuangpinScheme, password: Boolean = false): Layout {
        val custom = chosen.customOf(im)
        if (chosen.of(im) == ImKeyboard.CUSTOM && custom != null && effective(im, ImKeyboard.CUSTOM, scheme, custom) == ImKeyboard.CUSTOM) {
            return shown(im, chosen, scheme, password).layout
        }
        return layoutOf(im, effective(im, chosen.of(im), scheme, custom), password)
    }

    private fun layoutOf(im: String, keyboard: ImKeyboard, password: Boolean): Layout {
        return when (im) {
            InputMethodCatalog.PINYIN -> when (keyboard) {
                ImKeyboard.FULL -> Layout.TEXT
                ImKeyboard.KEYS18 -> Layout.PINYIN18
                ImKeyboard.KEYS14 -> Layout.PINYIN14
                ImKeyboard.NINE_KEYS -> Layout.PINYIN_NINE
                ImKeyboard.ZHUYIN_NINE -> Layout.ZHUYIN_NINE
                ImKeyboard.ZHUYIN_FULL -> Layout.ZHUYIN_FULL
                ImKeyboard.CUSTOM -> Layout.TEXT
            }
            InputMethodCatalog.SHUANGPIN -> when (keyboard) {
                ImKeyboard.FULL -> Layout.TEXT
                ImKeyboard.KEYS18 -> Layout.SHUANGPIN18
                ImKeyboard.KEYS14 -> Layout.SHUANGPIN14
                ImKeyboard.NINE_KEYS -> Layout.SHUANGPIN_NINE
                // not offered: effective has made them FULL; a layout of the user's: shown's own
                ImKeyboard.ZHUYIN_NINE, ImKeyboard.ZHUYIN_FULL, ImKeyboard.CUSTOM -> Layout.TEXT
            }
            InputMethodCatalog.ENGLISH -> if (keyboard == ImKeyboard.NINE_KEYS && !password) Layout.ENGLISH_NINE else Layout.TEXT
            else -> Layout.TEXT
        }
    }

    /** Only where English suggests at all (setting on, not a password, an address or no-suggestions field). */
    fun spellsWords(predictive: Boolean, suggests: Boolean, password: Boolean, address: Boolean, noSuggestions: Boolean): Boolean =
        predictive && suggests && !password && !address && !noSuggestions

    private const val LETTERS = "letters"
    private const val NINE = "nine"
    private const val GROUPED = "grouped"

    // a table aside: usable
    private val TYPED = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
}
