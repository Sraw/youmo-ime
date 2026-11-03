/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// TalkBack reads a card's strokes by these names, a shape's its index's (CharCardView): one for each, in
// StrokeShape's order, in each language. Read from the resource files, as UserWordsClearTextTest does
class StrokeNamesTest {

    private fun names(values: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$values/strings.xml"))
        val arrays = doc.getElementsByTagName("string-array")
        val array = (0 until arrays.length).map { arrays.item(it) as Element }.single { it.getAttribute("name") == "char_card_stroke_names" }
        val items = array.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent }
    }

    @Test
    fun aNameForEachShapeInItsOrder() {
        // zh-CN's as stroke-order.tsv's head names each code (横折钩 HZG), the five kinds' 折 after them
        val head = File("../lexicon/stroke-order.tsv").readLines().takeWhile { it.startsWith("#") }
        val named = head.flatMap { Regex("""([A-Z]+) (\p{IsHan}+) """).findAll(it).map { m -> m.groupValues[1] to m.groupValues[2] }.toList() }.toMap()
        assertEquals(StrokeShape.entries.map { named[it.code] ?: "折" }, names("values-zh-rCN"))
        assertEquals(
            listOf(
                "橫", "提", "豎", "豎鉤", "撇", "點", "捺", "橫折", "橫撇", "橫鉤", "橫折鉤", "橫折提", "橫折彎", "橫折折", "橫斜鉤", "橫折彎鉤",
                "橫撇彎鉤", "橫折折撇", "橫折折折鉤", "橫折折折", "豎折", "豎彎", "豎提", "豎折撇", "豎折折", "豎彎鉤", "豎折折鉤", "撇折", "撇點",
                "彎鉤", "斜鉤", "臥鉤", "折",
            ),
            names("values-zh-rTW"),
        )
        assertEquals(
            listOf(
                "horizontal", "rising", "vertical", "vertical hook", "left-falling", "dot", "right-falling", "horizontal turn",
                "horizontal left-falling", "horizontal hook", "horizontal turn hook", "horizontal turn rising", "horizontal turn bend",
                "horizontal turn turn", "horizontal slanting hook", "horizontal turn bend hook", "horizontal left-falling bend hook",
                "horizontal turn turn left-falling", "horizontal turn turn turn hook", "horizontal turn turn turn", "vertical turn",
                "vertical bend", "vertical rising", "vertical turn left-falling", "vertical turn turn", "vertical bend hook",
                "vertical turn turn hook", "left-falling turn", "left-falling dot", "bent hook", "slanting hook", "lying hook", "turning",
            ),
            names("values"),
        )
    }
}
