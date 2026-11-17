/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// a layout of the user's styled as the board it is based on (061-custom-layout §10): `<board>/<key>`, `<board>/*` and
// `@keyboard=<board>` select both its own id and its based-on
class BasedOnStylesTest {
    private fun s(text: String) = Selector.parse(text)!!

    private val mine = Selector.Target("key", "my-qwerty", "q", null, setOf("letter"), emptySet(), base = "qwerty")

    @Test
    fun aBoardsNameSelectsItsOwnIdAndItsBase() {
        for (sel in listOf("qwerty/q", "qwerty/*", "my-qwerty/q", "my-qwerty/*", "key")) assertTrue(s(sel).selects(mine), sel)
        for (sel in listOf("nine/q", "qwerty/w", "k18/*")) assertFalse(s(sel).selects(mine), sel)
        // none based on: its own id alone
        assertFalse(s("qwerty/q").selects(mine.copy(base = null)))
        // the order of the styles as before: a name's selector weighs what it weighed
        assertEquals(s("qwerty/q").specificity, s("my-qwerty/q").specificity)
    }

    @Test
    fun theEnvironmentIsInTheBasesKeyboardSceneToo() {
        val env = Fixtures.portrait.copy(board = "my-qwerty", base = "qwerty")
        assertTrue("keyboard=qwerty" in env.scenes && "keyboard=my-qwerty" in env.scenes)
        assertFalse("keyboard=qwerty" in env.copy(base = null).scenes)
    }

    @Test
    fun aSceneOfTheUsersLayoutTakesTheBasesStyles() {
        val layout = LayoutReader.layout(Fixtures.qwertySource.replaceFirst("id=\"qwerty\"", "id=\"my-qwerty\" based-on=\"qwerty\"")).ok()
        val theme = ThemeReader.theme(
            testData("material-light.xml").replace(
                "</theme>",
                "<style select=\"qwerty/q\" fill=\"#ff0000\"/>\n<style select=\"@keyboard=qwerty my-qwerty/w\" fill=\"#00ff00\"/>\n</theme>",
            ),
        ).ok()
        val named = theme.styles[theme.styles.size - 2]
        val scened = theme.styles.last()
        fun fill(base: String?, key: String) =
            SceneBuilder(theme, Fixtures.portrait.copy(board = layout.id, base = base), FakeMeasurer).sources(layout, emptyMap(), key, null, emptySet())["fill"]
        assertEquals(named, fill("qwerty", "q"))
        assertEquals(scened, fill("qwerty", "w"))
        assertNotEquals(named, fill(null, "q"))
        assertNotEquals(scened, fill(null, "w"))
    }

    // L8 (§10): the layout maker's 17 keys example, based on k18: a theme's style for an 18 keys key by its id is the
    // 17 keys' key of that id's too; HJK, a key of its own, takes what is written for k17/hjk, not k18's H or JK
    @Test
    fun aThemesEighteenKeysStylesAreTheSeventeenKeysOfTheSameId() {
        val layout = LayoutReader.layout(testData("../../../../../web/theme-maker/examples/layouts/k17.layout")).ok()
        assertEquals("k18", layout.basedOn)
        val fragments = mapOf("grouped-bottom" to LayoutReader.fragment(testData("grouped-bottom.xml")).ok())
        val theme = ThemeReader.theme(
            testData("material-light.xml").replace(
                "</theme>",
                // k18/r2c4 (H) last: were it HJK's, it would be the one taken
                "<style select=\"k18/r1c1\" fill=\"#ff0000\"/>\n<style select=\"k17/hjk\" fill=\"#0000ff\"/>\n" +
                    "<style select=\"k18/r2c4\" fill=\"#00ff00\"/>\n</theme>",
            ),
        ).ok()
        val (q, hjk, h) = theme.styles.takeLast(3)
        fun fill(base: String?, key: String) =
            SceneBuilder(theme, Fixtures.portrait.copy(board = layout.id, base = base), FakeMeasurer).sources(layout, fragments, key, null, emptySet())["fill"]
        assertEquals(q, fill("k18", "r1c1"))
        assertEquals(hjk, fill("k18", "hjk"))
        assertNotEquals(h, fill("k18", "hjk"))
        // the app's 18 keys' style not the user's where the scene is not based on it
        assertNotEquals(q, fill(null, "r1c1"))
    }
}
