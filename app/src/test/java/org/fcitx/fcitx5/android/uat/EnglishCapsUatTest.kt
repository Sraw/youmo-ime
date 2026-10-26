/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/08-english.md's capitals: what the keyboard sends as the field asks (AutoCaps, read from the
 * editor's getCursorCapsMode). What English does with the keys is EnglishUatTest's (:lib:ime-eval);
 * here the text it would commit is put in the field as the engine's commit would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class EnglishCapsUatTest {

    private var h: KeyboardHarness? = null

    @After
    fun close() {
        h?.close()
    }

    private fun english(inputType: Int) = KeyboardHarness(im = KeyboardHarness.ENGLISH).also { h = it }.start(inputType).also {
        it.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
    }

    // the letter key [c] whichever case it shows
    private fun KeyboardHarness.letter(c: Char): TextKeyView = KeyboardHarness.all(inputView).filterIsInstance<TextKeyView>()
        .first { shown(it) && it.mainText.text.toString().equals(c.toString(), ignoreCase = true) }

    // the characters of the keys sent down, in order
    private fun KeyboardHarness.typed(): String = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }.joinToString("") { call ->
        Regex("(?:char|key)=([^,]+),").find(call)?.groupValues?.get(1)
            ?: Regex("sym=(\\d+)").find(call)?.groupValues?.get(1)?.toInt()?.toChar()?.toString()
            ?: error("a key not read: $call")
    }

    @Test
    @Uat("EN-09")
    fun aSentencesFirstLetterIsACapital() {
        val h = english(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        h.tap(h.letter('h'))
        h.tap(h.letter('i'))
        assertEquals("Hi", h.typed())
        // English's commit of "hi", space and . (EnglishUatTest EN-06): the field reads "Hi. "
        h.field.setText("Hi. ")
        h.field.setSelection(4)
        h.idle()
        h.tap(h.letter('o'))
        h.tap(h.letter('k'))
        assertEquals("HiOk", h.typed())
    }

    @Test
    @Uat("EN-10")
    fun anAddressFieldGetsNoCapitals() {
        val h = english(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        "abc".forEach { h.tap(h.letter(it)) }
        assertEquals("abc", h.typed())
    }
}
