/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.host.HostEvent
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

    // the harness open, closed after each test (the tests name theirs h)
    private var open: KeyboardHarness? = null

    @After
    fun close() {
        open?.close()
    }

    private fun english(inputType: Int) = KeyboardHarness(im = KeyboardHarness.ENGLISH).also { open = it }.start(inputType).also {
        it.emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
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

    // a fake engine that keeps the glides offered: whether each came with Shift on
    private val fake = FakeHostApi().apply { inputMethodEntryCached = KeyboardHarness.ENGLISH }
    private val glides = ArrayList<Glide>()
    private val keepingGlides = object : HostApi by fake {
        override suspend fun offerGlide(glide: Glide): Boolean {
            glides += glide
            return true
        }
    }

    private fun KeyboardHarness.glide(from: Char, to: Char) {
        val end = box(letter(to))
        drag(letter(from), end.exactCenterX(), end.exactCenterY(), steps = 12)
    }

    // the field's text as the engine's commits leave it (EnglishUatTest EN-21), the cursor at its end
    private fun KeyboardHarness.typedIn(text: String) {
        field.setText(text)
        field.setSelection(text.length)
        idle()
    }

    @Test
    @Uat("EN-21")
    fun aGlideTakesShiftWhereTheFieldAsksACapitalOrItIsPressed() {
        AppPrefs.getInstance().keyboard.glideTyping.setValue(true)
        try {
            val h = KeyboardHarness(im = KeyboardHarness.ENGLISH, host = keepingGlides).also { open = it }
                .start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            check(fake.events.tryEmit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH)))
            h.idle()
            // a sentence's start: Shift on by itself
            h.glide('h', 'o')
            h.typedIn("Hello ")
            // mid-sentence: off
            h.glide('l', 'n')
            h.typedIn("Hello London. ")
            h.glide('i', 'm')
            h.typedIn("So ")
            h.glide('i', 'm')
            assertEquals(listOf(true, false, true, false), glides.map { it.shift })
            h.close()
            glides.clear()
            // nothing capitalised where the field asks none; Shift tapped, the glide takes it
            val q = KeyboardHarness(im = KeyboardHarness.ENGLISH, host = keepingGlides).also { open = it }
                .start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            check(fake.events.tryEmit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH)))
            q.idle()
            q.typedIn("ok ")
            q.glide('i', 'm')
            q.typedIn("")
            q.tap(q.described("Shift"))
            q.glide('h', 'o')
            assertEquals(listOf(false, true), glides.map { it.shift })
        } finally {
            AppPrefs.getInstance().keyboard.glideTyping.setValue(false)
        }
    }

    @Test
    @Uat("EN-10")
    fun anAddressFieldGetsNoCapitals() {
        val h = english(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        "abc".forEach { h.tap(h.letter(it)) }
        assertEquals("abc", h.typed())
    }
}
