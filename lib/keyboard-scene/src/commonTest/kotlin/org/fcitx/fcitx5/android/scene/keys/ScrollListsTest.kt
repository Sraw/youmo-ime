/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.keys.ScrollLists.Shown
import org.fcitx.fcitx5.android.scene.keys.ScrollLists.Typed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollListsTest {
    private fun texts(items: List<ScrollItem>?) = items?.map { (it as ScrollItem.Text).text }

    // 0.6.2's lists, written out as c8e399386 had them (T9Keyboard, AddressKeys, NumberPad), which this module cannot see
    @Test
    fun eachCategorysDefaultIsWhat062Had() {
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

    // a category × the input method, each cell; 0.6.2's own pairs typed as there (T9Keyboard.pick, the number pad's keys)
    @Test
    fun whatATapTypesByCategoryAndInputMethod() {
        for (nine in listOf("nine", "nine-english")) {
            // English types a single character as a key of its own, so its space follows
            assertEquals(Typed.English(","), ScrollLists.typed(nine, text(","), "english", marks), nine)
            assertEquals(Typed.Commit(".com"), ScrollLists.typed(nine, text(".com"), "english", marks), nine)
            // T9Keyboard.kt:148: marked, an ASCII mark held back as punctuation
            assertEquals(Typed.Commit("，", punctuation = true), ScrollLists.typed(nine, text(","), "pinyin", marks), nine)
            assertEquals(Typed.Commit("…", punctuation = false), ScrollLists.typed(nine, text("…"), "pinyin", marks), nine)
            assertEquals(Typed.Commit("@", punctuation = true), ScrollLists.typed(nine, text("@"), "shuangpin", marks), nine)
            assertEquals(Typed.Commit("#"), ScrollLists.typed(nine, text("#", asIs = true), "pinyin", marks), "$nine: 不转中文标点")
        }
        for (pad in listOf("number", "phone", "datetime")) {
            for (ime in listOf("english", "pinyin")) {
                mapOf("+" to "KP_Add", "-" to "KP_Subtract", "*" to "KP_Multiply", "/" to "KP_Divide", "," to "KP_Separator", "." to "KP_Decimal", "=" to "KP_Equal")
                    .forEach { (t, sym) -> assertEquals(Typed.Keypad(sym), ScrollLists.typed(pad, text(t), ime, marks), "$pad $ime $t") }
                // number.xml of c8e399386: # ( ) : were commit:
                for (t in listOf("#", "(", ")", ":", "%")) assertEquals(Typed.Commit(t), ScrollLists.typed(pad, text(t), ime, marks), "$pad $ime $t")
            }
        }
        for (address in listOf("url", "email")) for (ime in listOf("english", "pinyin")) {
            for (t in listOf("/", ".com", "@", "#")) assertEquals(Typed.Commit(t), ScrollLists.typed(address, text(t), ime, marks), "$address $ime $t")
        }
        for (id in ScrollDefaults.IDS) for (ime in listOf("english", "pinyin")) {
            assertEquals(Typed.Command("copy"), ScrollLists.typed(id, ScrollItem.Command("copy"), ime, marks), "$id $ime")
        }
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
