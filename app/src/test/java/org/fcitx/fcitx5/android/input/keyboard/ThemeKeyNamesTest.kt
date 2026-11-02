/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeMakerTestdata
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.theme.KeyName
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
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
        "handwriting" to listOf(HandwritingKeyboard.Layout.flatten()),
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
    fun aBoardsKeysCarryTheirNamesToTheViews() {
        val keyboard = NumberKeyboard(context, ThemePreset.PixelDark)
        assertEquals(KeyNames.NUMBER, keyboard.themeBoard)
        val names = keyboard.allViews.filterIsInstance<KeyView>().mapNotNull { it.themeKey }.toList()
        assertTrue(names.toString(), "backspace" in names && "1" in names)
    }

    private fun label(a: KeyDef.Appearance) = (a as? KeyDef.Appearance.Text)?.displayText ?: ""

    // what a theme draws it as, near enough for a page to start from (the render's is the real one)
    private fun kind(key: KeyDef) = when {
        key.name == "space" -> "space"
        key.name == "enter" || key.name == "confirm" -> "enter"
        key.appearance.variant == KeyDef.Appearance.Variant.Normal -> "letter"
        else -> "function"
    }

    /**
     * The theme maker's first list of keys (web/theme-maker/test/testdata/key-layout.json): each
     * board's rows, each key's name, label, width share and kind, and the format's names. A stand-in
     * till the measured layouts (ThemeMakerLayoutTest) replace it; written with -PupdateThemeMaker.
     */
    @Test
    fun theKeysAsAListForTheThemeMaker() {
        val rows = mapOf(
            "qwerty" to TextKeyboard.Layout,
            "nine" to T9Keyboard.layout(NineKeyLetters.STANDARD),
            "k14" to GroupedKeyboard.layout(NineKeyLetters.G14) { emptyList() },
            "k18" to GroupedKeyboard.layout(NineKeyLetters.G18) { emptyList() },
            "number" to NumberKeyboard.Layout,
            "handwriting" to HandwritingKeyboard.Layout,
            "symbols" to listOf(PickerLayout.panelKeys(context, symbolPicker().switchKey)),
            "emoji" to listOf(PickerLayout.listKeys(emojiPicker().switchKey, PickerWindow.SearchKey)),
        )
        val out = buildJsonObject {
            put("roles", JsonArray(KeyNames.ROLES.map(::JsonPrimitive)))
            put("positions", buildJsonObject { KeyNames.POSITIONS.forEach { (b, keys) -> put(b, JsonArray(keys.map(::JsonPrimitive))) } })
            put("boards", buildJsonObject {
                rows.forEach { (board, layout) ->
                    put(board, JsonArray(layout.map { row ->
                        JsonArray(row.map { key ->
                            buildJsonObject {
                                put("name", key.name)
                                put("label", label(key.appearance))
                                put("width", key.appearance.percentWidth)
                                put("kind", kind(key))
                            }
                        })
                    }))
                }
            })
        }
        ThemeMakerTestdata.check("key-layout.json", Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), out) + "\n")
    }
}
