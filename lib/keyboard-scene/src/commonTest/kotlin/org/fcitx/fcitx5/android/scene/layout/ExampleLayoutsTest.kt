/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.FakeMeasurer
import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Forms
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.listNames
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layout maker's examples: web/theme-maker/examples/layouts/, each a file the app
 * imports as it is. Each checks clean, not even a warning, on both targets; each drawn as the app draws a layout of the
 * user's (KeyData's Own reading, its marks the defaults of its names), upright, turned, compact and split without a
 * warning, block held as snapshots in example-layouts/ (-Psnapshots.record=true on the JVM writes them; both targets
 * compare): the other forms' drawing is the built-in boards' snapshots'.
 */
class ExampleLayoutsTest {
    private val ids = listOf("k17", "azerty", "big-number")

    private fun example(id: String) = testData("../../../../../web/theme-maker/examples/layouts/$id.layout")

    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    @Test
    fun eachChecksCleanWhereItSaysItIsUsed() {
        val used = mapOf(
            "k17" to (listOf("pinyin") to emptyList()),
            "azerty" to (listOf("pinyin", "shuangpin", "english", "table") to emptyList()),
            "big-number" to (emptyList<String>() to listOf("full", "digits", "decimal")),
        )
        for (id in ids) {
            val r = LayoutCheck.check(example(id), sources, "0.6.1", Fixtures.materialLight)
            assertEquals(emptyList(), r.errors, id)
            assertEquals(emptyList(), r.warnings, id)
            assertEquals(used.getValue(id), r.ims to r.pads, id)
            assertEquals("0.6.1", r.minApp, id)
        }
    }

    // the forms §15 names, by the name its snapshot carries: number's in its three pads
    private val forms = linkedMapOf("block" to Forms.block, "landscape" to Forms.landscape, "compact" to Forms.compact, "split" to Forms.split)

    // each drawn, by its snapshot's name, block's text (the other forms' null)
    private fun shots(): List<Pair<String, String?>> = ids.flatMap { id ->
        val read = LayoutReader.layout(example(id)).ok()
        // the app's fragments (grouped-bottom): what an include of a layout of the user's reads
        val held = KeyData.of(read.id, read, Forms.fragments, KeyData.Mode.Own)
        val variant = DefaultMarks.variantOf(held.layout)
        val pads = if (read.family == "number") read.pads.orEmpty().map { it to it } else listOf("" to null)
        forms.flatMap { (form, env) ->
            pads.map { (padName, pad) ->
                val told = env.copy(input = env.input.copy(pad = pad))
                val marks = DefaultMarks.shortcuts(held.layout, held.fragments, variant, told.texts.punctuation, told.marks.firstUp)
                val alts = marks.entries.mapNotNull { (k, v) -> (v as? MarkContent.Symbol)?.takeIf { k.endsWith(":up") }?.let { k.removeSuffix(":up") to it.text } }.toMap()
                val drawn = told.copy(marks = told.marks.copy(shortcuts = marks), texts = told.texts.copy(alts = alts))
                val scene = SceneBuilder(Fixtures.materialLight, drawn, FakeMeasurer).build(held.layout, held.fragments).ok()
                assertTrue(scene.warnings.isEmpty(), "$id $form $padName: ${scene.warnings}")
                listOf(id, form, padName).filter { it.isNotEmpty() }.joinToString("-") to if (form == "block") SceneText.of(scene) else null
            }
        }
    }

    @Test
    fun eachDrawnInEveryFormAsHeld() {
        val shots = shots().mapNotNull { (name, text) -> text?.let { name to it } }
        val failed = shots.mapNotNull { (name, text) ->
            val path = "$TEST_DATA/example-layouts/$name.txt"
            val committed = readText(path)
            when {
                recording() -> null.also { writeText(path, text) }
                committed == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                committed != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
        if (!recording()) assertEquals(shots.map { "${it.first}.txt" }.sorted(), listNames("$TEST_DATA/example-layouts"), "a snapshot no shot writes")
    }
}
