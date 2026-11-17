/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
// YoumoEngine is at the module's top (the page's names), so its test is too

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.LayoutWriter
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A user's layout as the layout maker's page reads it (YoumoEngine.readOwnLayout): a grouped one based on the 26 keys (the
 * page's merge makes one) is drawn with its own based-on's styles and shortcuts, the 26 keys', as the app draws it and as
 * LayoutCheck tells of it; the 18 keys are only what it is held to (R26 F8117).
 */
class YoumoEngineLayoutTest {
    @Test
    fun aGroupedLayoutOnTheTwentySixKeysIsDrawnAsItsOwnBasedOns() {
        val xml = testData("k18.xml").replaceFirst("id=\"k18\"", "id=\"my-k18\" based-on=\"qwerty\"")
        val read = YoumoEngine.readOwnLayout(xml)
        assertEquals(true, read.ok, JSON.stringify(read.problems))
        val layout = read.layout as YoumoLayout
        assertEquals("grouped", layout.family)
        assertEquals("qwerty", layout.variantOf, "the board whose styles (Env.base) and shortcuts it draws with")
        // its key shape its own family's, the 18 keys' (R26 F8437): the 11th group's corner (, not the 14 keys' ~
        assertEquals("k18", layout.shapeOf)
        assertEquals("(", YoumoEngine.defaultMarks(layout, null).shortcuts["g18/h:up"].symbol as String?)
    }

    // a layout written back (the layout maker's 从示例开始) as it was read, not with the app's keys' long presses and swipes
    // KeyData laid on it to draw it (R26 F8297): its q's swipe of its own is drawn as the app's q's (held), written as its
    // own. (qwerty.xml as it is draws as it writes: the app's key data is its own.)
    @Test
    fun aLayoutIsWrittenAsItWasRead() {
        val xml = testData("qwerty.xml").replaceFirst("id=\"qwerty\"", "id=\"my-qwerty\" based-on=\"qwerty\"")
            .replaceFirst("send=\"q\" shortcut=\"full/q\"", "send=\"q\" shortcut=\"full/q\" swipe-down=\"x\"")
        val read = YoumoEngine.readOwnLayout(xml)
        assertEquals(true, read.ok, JSON.stringify(read.problems))
        val layout = read.layout as YoumoLayout
        assertNotEquals(LayoutWriter.write(layout.layout), YoumoEngine.writeLayout(layout), "what is drawn has the app's data laid on it")
        assertEquals(LayoutWriter.write((LayoutReader.layout(xml) as Parsed.Ok).value), YoumoEngine.writeLayout(layout))
        val board = YoumoEngine.readLayout(testData("qwerty.xml"), null, "qwerty").layout as YoumoLayout
        assertEquals(LayoutWriter.write((LayoutReader.layout(testData("qwerty.xml")) as Parsed.Ok).value), YoumoEngine.writeLayout(board))
        // the same of a theme pack's board (readLayout's, R26 F8488): its q's swipe of its own drawn as the app's q's, not
        // taken, and written as its own
        val packs = testData("qwerty.xml").replaceFirst("send=\"q\" shortcut=\"full/q\"", "send=\"q\" shortcut=\"full/q\" swipe-down=\"x\"")
        val pack = YoumoEngine.readLayout(packs, null, "qwerty")
        assertEquals(true, pack.ok, JSON.stringify(pack.problems))
        val packLayout = pack.layout as YoumoLayout
        assertNotEquals(LayoutWriter.write(packLayout.layout), YoumoEngine.writeLayout(packLayout), "what is drawn has the app's data laid on it")
        assertEquals(LayoutWriter.write((LayoutReader.layout(packs) as Parsed.Ok).value), YoumoEngine.writeLayout(packLayout))
    }
}
