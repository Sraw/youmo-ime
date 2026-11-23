/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.keys.ScrollLists.Shown
import org.fcitx.fcitx5.android.scene.keys.ScrollLists.Typed
import org.fcitx.fcitx5.android.scene.skin.assertRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollListsTest {
    private fun texts(items: List<ScrollItem>?) = items?.map { (it as ScrollItem.Text).text }

    // contract: each category's built-in list, which 恢复默认, the store's 「is it the default」 and a backup lean on. 0.6.2's
    // (T9Keyboard, AddressKeys, NumberPad, which this module cannot see), the number pad's with % : ( ) # added
    @Test
    fun eachCategorysDefaultIs062sOrAsChosen() {
        assertEquals(listOf("nine", "nine-english", "number", "phone", "datetime", "url", "email"), ScrollDefaults.IDS)
        assertEquals(listOf(",", ".", "?", "!", "…", "、", ":", "~", "@"), texts(ScrollDefaults.of("nine")))
        assertEquals(listOf(",", ".", "?", "!", "'", "-", ":", ";", "@"), texts(ScrollDefaults.of("nine-english")))
        assertEquals(listOf("+", "-", "*", "/", "%", ",", ":", "(", ")", "#"), texts(ScrollDefaults.of("number")))
        assertEquals(listOf("+", "*", "#", "-", "(", ")", ","), texts(ScrollDefaults.of("phone")))
        assertEquals(listOf("-", "/", ":"), texts(ScrollDefaults.of("datetime")))
        assertEquals(listOf("/", ".", ".com", ":", "-", "_", "?", "=", "&", "#", ".cn", ".net", ".org"), texts(ScrollDefaults.of("url")))
        assertEquals(listOf("@", ".", ".com", "-", "_", "+", ".cn", ".net", ".org"), texts(ScrollDefaults.of("email")))
        assertNull(ScrollDefaults.of("symbols"))
    }

    private fun shown(kind: String = "scroll", list: String? = "number", field: String? = "text", fieldPad: String? = null, pad: String? = null, ime: String? = "pinyin", composing: Boolean = false) =
        ScrollLists.shown(kind, list, field, fieldPad, pad, ime, composing)

    @Test
    fun theFieldDecidesOverTheLayoutThenTheLayoutsList() {
        assertEquals(Shown.Syllables, shown(kind = "nine-side", list = null, composing = true))
        assertEquals(Shown.Syllables, shown(kind = "nine-side", list = null, ime = "shuangpin", composing = true))
        // English's nine keys type no syllables
        assertEquals(Shown.Category("nine-english"), shown(kind = "nine-side", list = null, ime = "english", composing = true))
        // a scroll slot never shows syllables
        assertEquals(Shown.Category("nine"), shown(list = "nine", composing = true))
        assertEquals(Shown.Category("url"), shown(field = "url"))
        assertEquals(Shown.Category("email"), shown(kind = "nine-side", list = null, field = "email", composing = false))
        // a phone field's field is `number`; a 26-key board's list there too
        assertEquals(Shown.Category("phone"), shown(list = "nine", field = "number", fieldPad = "phone"))
        assertEquals(Shown.Category("datetime"), shown(field = "number", fieldPad = "datetime"))
        // the cards know the pad alone
        assertEquals(Shown.Category("phone"), shown(field = null, pad = "phone"))
        assertEquals(Shown.Category("number"), shown(pad = "full"))
        assertEquals(Shown.Category("nine"), shown(kind = "nine-side", list = null))
        assertEquals(Shown.Category("nine-english"), shown(list = "nine-english"))
    }

    private val marks = mapOf("," to "，", "." to "。", "?" to "？", "#" to "＃")
    private fun text(t: String, asIs: Boolean = false) = ScrollItem.Text(t, asIs)

    private class Tap(val why: String, val category: String, val item: ScrollItem, val ime: String, val typed: Typed)

    private val nines = listOf("nine", "nine-english")
    private val pads = listOf("number", "phone", "datetime")
    private val imes = listOf("english", "pinyin")

    // a category × the input method, each cell; 0.6.2's own pairs typed as there (T9Keyboard.pick, the number pad's keys)
    private val taps: List<Tap> = nines.flatMap { nine ->
        listOf(
            Tap("English types a single character as a key of its own, so its space follows", nine, text(","), "english", Typed.English(",")),
            Tap("English commits a longer text", nine, text(".com"), "english", Typed.Commit(".com")),
            Tap("marked, an ASCII mark held back as punctuation", nine, text(","), "pinyin", Typed.Commit("，", punctuation = true)),
            Tap("marked, not ASCII: no punctuation", nine, text("…"), "pinyin", Typed.Commit("…", punctuation = false)),
            Tap("shuangpin as pinyin", nine, text("@"), "shuangpin", Typed.Commit("@", punctuation = true)),
            Tap("不转中文标点", nine, text("#", asIs = true), "pinyin", Typed.Commit("#")),
        )
    } + pads.flatMap { pad ->
        imes.flatMap { ime ->
            mapOf("+" to "KP_Add", "-" to "KP_Subtract", "*" to "KP_Multiply", "/" to "KP_Divide", "," to "KP_Separator", "." to "KP_Decimal", "=" to "KP_Equal")
                .map { (t, sym) -> Tap("the pad's keypad key", pad, text(t), ime, Typed.Keypad(sym)) } +
                // the 0.6.2 number board's # ( ) : were commit:
                listOf("#", "(", ")", ":", "%").map { t -> Tap("a pad's other mark committed", pad, text(t), ime, Typed.Commit(t)) }
        }
    } + listOf("url", "email").flatMap { address ->
        imes.flatMap { ime -> listOf("/", ".com", "@", "#").map { t -> Tap("an address's as it is", address, text(t), ime, Typed.Commit(t)) } }
    } + ScrollDefaults.IDS.flatMap { id -> imes.map { ime -> Tap("a command on any list", id, ScrollItem.Command("copy"), ime, Typed.Command("copy")) } }

    @Test
    fun whatATapTypesByCategoryAndInputMethod() = assertRows(taps, { "${it.category} ${it.ime} ${it.item}: ${it.why}" }) {
        assertEquals(it.typed, ScrollLists.typed(it.category, it.item, it.ime, marks))
    }

    @Test
    fun anItemShowsAsItTypes() {
        assertEquals("，", ScrollLists.label("nine", text(","), "pinyin", marks))
        assertEquals("，", ScrollLists.label("nine-english", text(","), "pinyin", marks), "English's list on pinyin is marked too")
        assertEquals(",", ScrollLists.label("nine", text(","), "english", marks))
        assertEquals("#", ScrollLists.label("nine", text("#", asIs = true), "pinyin", marks))
        assertEquals(",", ScrollLists.label("number", text(","), "pinyin", marks))
        assertEquals(null, ScrollLists.label("number", ScrollItem.Command("copy"), "pinyin", marks), "a command is its icon")
    }
}
