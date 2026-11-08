/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.KeyName
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Row
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * contract: the 3.0 theme format's key names, shared with the web page; a rename breaks theme files.
 * Every key a theme can style by itself has the name the theme format gives it ([KeyNames]); the
 * web theme maker's list of keys is that format's, so a key named here but not there (or twice on a
 * board) is a key a theme cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeKeyNamesTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun boards(): Map<String, List<List<KeyDef>>> = mapOf(
        "qwerty" to listOf(TextKeyboard.Layout.flatten()),
        "nine" to listOf(
            T9Keyboard.layout(NineKeyLetters.STANDARD).flatten(),
            T9Keyboard.layout(NineKeyLetters.STANDARD, english = true).flatten(),
            T9Keyboard.layout(NineKeyLetters.STANDARD, shuangpin = true).flatten(),
        ),
        "k14" to listOf(GroupedKeyboard.layout(NineKeyLetters.G14) { emptyList() }.flatten()),
        "k18" to listOf(GroupedKeyboard.layout(NineKeyLetters.G18) { emptyList() }.flatten()),
        "number" to NumberPad.entries.map { NumberKeyboard.layout(it).flatten() },
        "handwriting" to listOf(HandwritingKeyboard.layout(context).flatten()),
        "zhuyin-nine" to listOf(ZhuyinKeyboard.nineLayout().flatten()),
        "zhuyin" to listOf(ZhuyinKeyboard.fullLayout().flatten(), ZhuyinKeyboard.fullLayout(compact = true).flatten()),
        "symbols" to listOf(PickerLayout.panelKeys(context, symbolPicker().switchKey)),
        "emoji" to listOf(
            PickerLayout.listKeys(emojiPicker().switchKey, PickerWindow.SearchKey),
            PickerLayout.listKeys(emoticonPicker().switchKey, null),
        ),
    )

    // a key with no name of its own: drawn as its kind on every board
    private fun unnamed(key: KeyDef) = key.appearance.let {
        it is KeyDef.Appearance.Text && it.displayText.isEmpty()
    }

    @Test
    fun everyKeyHasItsBoardsName() {
        for ((board, layouts) in boards()) for (keys in layouts) for (key in keys) {
            if (key.name == null) {
                assertTrue("$board: ${key.appearance} has no name", unnamed(key))
                continue
            }
            assertTrue("$board/${key.name} is not a name of the format", KeyNames.isKnown(KeyName(board, key.name!!)))
        }
    }

    @Test
    fun noTwoKeysOfABoardShareAName() {
        for ((board, layouts) in boards()) for (keys in layouts) {
            val names = keys.mapNotNull { it.name }
            assertEquals(board, names.distinct(), names)
        }
    }

    @Test
    fun everyPlaceOfABoardIsAKey() {
        // the format's places are the keys there are: none named that no keyboard has
        for ((board, layouts) in boards()) {
            val names = layouts.flatten().mapNotNull { it.name }.toSet()
            val places = KeyNames.POSITIONS.getValue(board).toSet()
            assertEquals(board, places, places intersect names)
        }
    }

    @Test
    fun aBoardsKeysCarryTheirNames() {
        val keyboard = NumberKeyboard(context, ThemePreset.PixelDark).laidOut()
        assertEquals(KeyNames.NUMBER, keyboard.themeBoard)
        val names = keyboard.shownKeys().mapNotNull { it.id }
        assertTrue(names.toString(), "backspace" in names && "1" in names)
    }

    // a keyboard drawn by the scene (060-render-p2 S4) styled by the same names as its views were: its
    // layout's id the board, its keys' ids the keys a theme's override selects (k14/r1c1, qwerty/q, zhuyin/,, zhuyin-nine/r4c4)
    @Test
    fun aSceneLayoutsKeysAreItsBoardsNames() {
        val drawn = mapOf("qwerty" to "qwerty", "k14" to "k14", "k18" to "k18", "zhuyin" to "zhuyin", "zhuyin-nine" to "zhuyin-nine", "number" to "number")
        for ((id, board) in drawn) {
            val layout = SceneLayouts.of(context, id) ?: error("no layout $id")
            assertEquals(board, layout.layout.id)
            val rows = layout.layout.parts.flatMap { p -> if (p is Row) listOf(p) else layout.fragments.getValue((p as Include).src).rows }
            val ids = rows.flatMap { r -> r.cells.filterIsInstance<Key>().mapNotNull { it.id } }.toSet()
            ids.forEach { assertTrue("$board/$it is not a name of the format", KeyNames.isKnown(KeyName(board, it))) }
            val views = boards().getValue(board).flatten().mapNotNull { it.name }.toSet()
            assertEquals(board, views, ids intersect views)
        }
    }
}
