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
 * An on-screen keyboard an input method is typed on (I1): pinyin's, 双拼's and English's own
 * choice, set on its page or from ⋯'s 键盘. A hardware keyboard types the full one's whatever is
 * chosen (KeyboardPair). The app stores its own enums' names for these (PinyinKeyboard,
 * ShuangpinKeyboard, EnglishKeyboard in AppPrefs): those never renamed, this one free to be.
 * [CUSTOM]: a layout of the user's own (061-custom-layout §5), which one stored beside it.
 */
enum class ImKeyboard { FULL, KEYS18, KEYS14, NINE_KEYS, ZHUYIN_NINE, ZHUYIN_FULL, CUSTOM }

/**
 * A layout of the user's own as the keyboard options see it (061-custom-layout §5.2): its [id] and [name]; its [family]
 * (`letters`, `grouped`, `nine`; a number pad's or the symbols' none of an input method's) and the app's board it is held
 * to and drawn as where it cannot be ([basedOn]: its `based-on`, or its family's where that is of another family,
 * LayoutCheck.baseId); a grouped one's [groups] (each key's letters, in order: SceneGroups.of); whether it is [drawn] (its file
 * held to what it must be as it is read: CustomLayoutSource; else its base is drawn in its place, §4.4); whether it has the
 * keys a table types beside the letters ([tableExtras], qwerty's `table-extra=` keys); its file's [stamp], which changes
 * with it (a keyboard made anew for a layout replaced); a number board's [pads], the fields it is for (its `pads=`, `full`
 * where it says none: 061-custom-layout §7, L5; empty for any other family).
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

/**
 * Which keyboards each input method offers, and which layout the screen shows for it: the input
 * method is the language or the scheme, the keyboard its setting (I1 §2). The app reads the
 * choices from its preferences and passes them in; this decides.
 */
object ImKeyboards {

    /** What the screen shows: each a layout of its own, the keys sending their own codes ([layoutOf]). */
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

    /**
     * Each input method's keyboard as the user chose it: [ImKeyboard.FULL] for one never chosen; the tables' one choice,
     * [table] (061-custom-layout Q6: the full keyboard or a layout of the user's); the layout of the user's each one chose
     * where it chose [ImKeyboard.CUSTOM] ([layouts], by [slot]), and those of them read ([customs], by id: one not there
     * does not read, its base unknown, the full keyboard drawn in its place).
     */
    data class Chosen(
        val pinyin: ImKeyboard = ImKeyboard.FULL,
        val shuangpin: ImKeyboard = ImKeyboard.FULL,
        val english: ImKeyboard = ImKeyboard.FULL,
        val table: ImKeyboard = ImKeyboard.FULL,
        val layouts: Map<String, String> = emptyMap(),
        val customs: Map<String, CustomChoice> = emptyMap(),
    ) {
        /** [im]'s, [ImKeyboard.FULL] for one with no choice. */
        fun of(im: String): ImKeyboard = when (im) {
            InputMethodCatalog.PINYIN -> pinyin
            InputMethodCatalog.SHUANGPIN -> shuangpin
            InputMethodCatalog.ENGLISH -> english
            else -> if (InputMethodCatalog.hasTableKeys(im)) table else ImKeyboard.FULL
        }

        /** The id of the layout of the user's [im] chose, where it chose one ([ImKeyboard.CUSTOM]). */
        fun layoutOf(im: String): String? = layouts[slot(im)]?.takeIf { of(im) == ImKeyboard.CUSTOM }

        /** That layout as read; null where [im] chose none, or it does not read. */
        fun customOf(im: String): CustomChoice? = layoutOf(im)?.let(customs::get)
    }

    /** What an input method's choice of a layout of the user's is stored under: its own name, the tables' one [TABLE]. */
    fun slot(im: String): String = if (InputMethodCatalog.hasTableKeys(im)) TABLE else im

    /** The tables' one choice of keyboard ([slot]). */
    const val TABLE = "table"

    /** The input methods that choose a keyboard, by [slot]: what may use a layout of the user's. */
    val SLOTS = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH, TABLE)

    private val ALL = listOf(ImKeyboard.FULL, ImKeyboard.KEYS18, ImKeyboard.KEYS14, ImKeyboard.NINE_KEYS)

    /**
     * The keyboards [im] offers, in the order offered; none for one with no choice (a table).
     * 双拼's 14 keys not under a [scheme] that types `;` (微软: ing), which no key of theirs has (I1 Q5).
     */
    fun choices(im: String, scheme: ShuangpinScheme): List<ImKeyboard> = when (im) {
        // pinyin typed in zhuyin too (design 051 Q1 A): its words, two keyboards of its own
        InputMethodCatalog.PINYIN -> ALL + ImKeyboard.ZHUYIN_NINE + ImKeyboard.ZHUYIN_FULL
        InputMethodCatalog.SHUANGPIN -> if (has14(scheme)) ALL else ALL - ImKeyboard.KEYS14
        InputMethodCatalog.ENGLISH -> listOf(ImKeyboard.FULL, ImKeyboard.NINE_KEYS)
        else -> emptyList()
    }

    /** Whether 双拼's [scheme] can be typed on the 14 keys: every key of it is a letter. */
    fun has14(scheme: ShuangpinScheme): Boolean = !scheme.types(';')

    /**
     * Whether [im] may be typed on [layout], a layout of the user's, under [scheme] (061-custom-layout §5.1, Q5, Q6): one
     * that is drawn; of `letters`, every input method that chooses (a table, where it has the keys the tables type beside
     * the letters); of `nine`, pinyin, 双拼, English; of `grouped`, pinyin whatever its groups, 双拼 where they are the 14
     * keys' (not under a scheme that types `;`, [has14]) or the 18 keys', in any order.
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

    /** Of [all], the layouts of the user's [im] may be typed on under [scheme] ([usable]), in their order. */
    fun customs(im: String, scheme: ShuangpinScheme, all: List<CustomChoice>): List<CustomChoice> = all.filter { usable(im, it, scheme) }

    /**
     * Whether [im] has a choice of keyboards ⋯ and its page offer: one of [choices], or (a table) a layout of the user's it
     * may be typed on ([customs]: its keyboard otherwise none to choose).
     */
    fun offers(im: String, scheme: ShuangpinScheme, all: List<CustomChoice>): Boolean =
        choices(im, scheme).isNotEmpty() || customs(im, scheme, all).isNotEmpty()

    /**
     * The built-in keyboard a grouped layout of the user's is ([CustomChoice.groups] those of the 14 or 18 keys, in any order:
     * the keyboard the engine reads them as, SceneGroups.letters), null for one of its own groups.
     */
    fun groupedAs(layout: CustomChoice): ImKeyboard? {
        if (layout.family != GROUPED) return null
        return when (SceneGroups.letters(layout.groups)) {
            NineKeyLetters.G14 -> ImKeyboard.KEYS14
            NineKeyLetters.G18 -> ImKeyboard.KEYS18
            else -> null
        }
    }

    /**
     * The built-in keyboard [layout], a layout of the user's (null: one that does not read), is made from (§4.4): what is drawn
     * in its place where it cannot be (not read, refused, one [im] cannot type), or once it is deleted; the full keyboard
     * for none.
     */
    fun baseOf(layout: CustomChoice?): ImKeyboard = when (layout?.basedOn) {
        "k18" -> ImKeyboard.KEYS18
        "k14" -> ImKeyboard.KEYS14
        "nine" -> ImKeyboard.NINE_KEYS
        else -> ImKeyboard.FULL
    }

    /**
     * The keyboard [im] is typed on, [chosen] its choice: one it does not offer the nearest it does,
     * the 14 keys under 微软 the 18 keys (grouped keys as well, the letters two or one a key). A layout
     * of the user's ([custom], null where it does not read) where [im] may be typed on it ([usable]), else its base
     * where [im] has that ([baseOf]), else the full keyboard (§4.4: the preference left as it is).
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

    /**
     * The choice [im]'s [chosen] is to be stored as once [scheme] no longer offers it ([effective]:
     * 双拼's 14 keys the 18 keys under 微软), null while it does: set on a scheme's change, so
     * the keyboard changes with it and stays so for a scheme that has the 14 keys again. A layout of the
     * user's ([custom]) that does not read or is not drawn is left chosen (§4.4: its base drawn meanwhile).
     */
    fun fallback(im: String, chosen: ImKeyboard, scheme: ShuangpinScheme, custom: CustomChoice? = null): ImKeyboard? {
        if (chosen == ImKeyboard.CUSTOM && custom?.drawn != true) return null
        return effective(im, chosen, scheme, custom).takeIf { it != chosen && choices(im, scheme).isNotEmpty() }
    }

    /**
     * What each input method that chose layout [id] of the user's ([chosen]) is to choose once it is deleted (§4.4), by
     * [slot]: its base where the input method has it, else the full keyboard. [layout] the layout as read before
     * (null: it did not read, the full keyboard).
     */
    fun afterDelete(chosen: Chosen, id: String, layout: CustomChoice?, scheme: ShuangpinScheme): Map<String, ImKeyboard> =
        usedBy(chosen, id).associateWith { effective(imOf(it), baseOf(layout), scheme) }

    /** An input method of [slot]: the tables' any table (they choose alike). */
    fun imOf(slot: String): String = if (slot == TABLE) InputMethodCatalog.TABLES.keys.first() else slot

    /** The [SLOTS] whose keyboard is layout [id] of the user's ([chosen]): 用在：… under its card (§4.3). */
    fun usedBy(chosen: Chosen, id: String): List<String> = SLOTS.filter { chosen.layoutOf(imOf(it)) == id }

    /**
     * The layouts of the user's an input method draws ([shown]: chosen, and drawn under [scheme]), each once in [SLOTS]'
     * order: those 按键字符和快捷键 has a tab of (061-custom-layout §9; one chosen and not drawn has none).
     */
    fun inUse(chosen: Chosen, scheme: ShuangpinScheme): List<CustomChoice> =
        SLOTS.mapNotNull { shown(imOf(it), chosen, scheme).custom }.distinctBy { it.id }

    /** The [SLOTS] that may be typed on [layout] under [scheme] ([usable]): 用在… of its card, the question after its import (§4.2 step 5). */
    fun usableBy(layout: CustomChoice, scheme: ShuangpinScheme): List<String> = SLOTS.filter { usable(imOf(it), layout, scheme) }

    /**
     * What [slot] is to choose as 用在… of [layout] is set (§4.3): it, ticked ([on]); else, where it was its keyboard, its
     * base as once it is deleted ([afterDelete]); null to leave as it is.
     */
    fun ticked(chosen: Chosen, slot: String, layout: CustomChoice, on: Boolean, scheme: ShuangpinScheme): ImKeyboard? = when {
        on -> ImKeyboard.CUSTOM
        chosen.layoutOf(imOf(slot)) == layout.id -> effective(imOf(slot), baseOf(layout), scheme)
        else -> null
    }

    /**
     * What [im]'s keys were typed on: the layout shown, for 双拼 its [scheme], which reads the same letters otherwise, and
     * a grouped layout of the user's of its own [groups] (another grouping's keys other codes); null for the 14 or 18
     * keys' groups wherever its keys are, which type the built-in keyboard's codes ([layout] its; review L4 N4).
     */
    data class TypedWith(val layout: Layout, val scheme: ShuangpinScheme?, val groups: List<String>? = null)

    /**
     * What [im] is typed with now ([layoutOf], 双拼's scheme too): what was typed is typed again on
     * the same alone (清除's 恢复, HostCore.retype), another's keys meaning other keys or syllables.
     */
    fun typedWith(im: String, chosen: Chosen, scheme: ShuangpinScheme): TypedWith {
        val shown = shown(im, chosen, scheme)
        return TypedWith(shown.layout, scheme.takeIf { im == InputMethodCatalog.SHUANGPIN }, shown.custom?.takeIf { it.family == GROUPED && groupedAs(it) == null }?.groups)
    }

    /** What the screen shows for an input method: [layout] (the built-in one's it is typed as), and the layout of the user's drawn in its place ([custom]), if any. */
    data class Shown(val layout: Layout, val custom: CustomChoice? = null)

    /**
     * What input method [im] shows ([layoutOf]), with the layout of the user's drawn where it chose one and may be typed on
     * it: of `letters` the full keyboard's (a password's too: a letter a key), of `nine` the nine keys' (in a password,
     * English's the full keyboard, the built-in one), of `grouped` the 14 or 18 keys' it has the groups of, or pinyin's
     * keyboard of its own groups (the 14 keys', which takes a layout's own: GroupedKeyboard).
     */
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

    /**
     * The layout input method [im] shows, each one's keyboard as [chosen] has it, 双拼's under
     * [scheme]; in a [password] field English's the full keyboard whatever it chose, a letter a key.
     * A layout of the user's the built-in one's it is typed as ([shown]).
     */
    fun layoutOf(im: String, chosen: Chosen, scheme: ShuangpinScheme, password: Boolean = false): Layout {
        val custom = chosen.customOf(im)
        if (chosen.of(im) == ImKeyboard.CUSTOM && custom != null && effective(im, ImKeyboard.CUSTOM, scheme, custom) == ImKeyboard.CUSTOM) {
            return shown(im, chosen, scheme, password).layout
        }
        return layoutOf(im, effective(im, chosen.of(im), scheme, custom), password)
    }

    // [keyboard], one [im] is typed on ([effective]), as the layout shown
    private fun layoutOf(im: String, keyboard: ImKeyboard, password: Boolean): Layout {
        return when (im) {
            InputMethodCatalog.PINYIN -> when (keyboard) {
                ImKeyboard.FULL -> Layout.TEXT
                ImKeyboard.KEYS18 -> Layout.PINYIN18
                ImKeyboard.KEYS14 -> Layout.PINYIN14
                ImKeyboard.NINE_KEYS -> Layout.PINYIN_NINE
                ImKeyboard.ZHUYIN_NINE -> Layout.ZHUYIN_NINE
                ImKeyboard.ZHUYIN_FULL -> Layout.ZHUYIN_FULL
                // a layout of the user's: shown's own
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

    /**
     * Whether English on the nine keys spells words from the keys ([predictive], 预测) rather than
     * a letter at a time (连按): only where English [suggests] words at all (its setting on, the
     * field neither a password, nor an address, nor one asking for no suggestions), as the engine
     * hands the keys to no word there.
     */
    fun spellsWords(predictive: Boolean, suggests: Boolean, password: Boolean, address: Boolean, noSuggestions: Boolean): Boolean =
        predictive && suggests && !password && !address && !noSuggestions

    private const val LETTERS = "letters"
    private const val NINE = "nine"
    private const val GROUPED = "grouped"

    // the input methods typed on a letters or a nine-key layout of the user's (a table aside: usable)
    private val TYPED = setOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.ENGLISH)
}
