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
        for (values in listOf("values", "values-zh-rCN", "values-zh-rTW")) assertEquals(values, StrokeShape.entries.size, names(values).size)
        val zh = names("values-zh-rCN")
        val some = listOf(StrokeShape.H, StrokeShape.SG, StrokeShape.HZG, StrokeShape.HZWG, StrokeShape.SZWG, StrokeShape.BXG, StrokeShape.ZHE)
        assertEquals(listOf("横", "竖钩", "横折钩", "横折弯钩", "竖折折钩", "卧钩", "折"), some.map { zh[it.ordinal] })
        assertEquals(listOf("橫", "豎鉤", "橫折鉤", "橫折彎鉤", "豎折折鉤", "臥鉤", "折"), some.map { names("values-zh-rTW")[it.ordinal] })
        assertEquals("vertical hook", names("values")[StrokeShape.SG.ordinal])
    }
}
