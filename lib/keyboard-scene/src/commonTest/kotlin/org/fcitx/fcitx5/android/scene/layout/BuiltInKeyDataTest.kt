/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.listNames
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// BuiltInKeyData is the app's layouts' held data, written as source so neither the app (on the main thread) nor the
// page reads every layout to look a key up: made again of the layouts here, it must be the
// file; -Psnapshots.record=true writes it anew
class BuiltInKeyDataTest {
    private val layoutsDir = "$TEST_DATA/../../commonMain/layouts"
    private val sourceFile = "$TEST_DATA/../../commonMain/kotlin/org/fcitx/fcitx5/android/scene/layout/BuiltInKeyData.kt"

    private val texts: Map<String, String> by lazy {
        listNames(layoutsDir).filter { it.endsWith(".xml") }.associate { it.removeSuffix(".xml") to readText("$layoutsDir/$it")!! }
    }

    private val fragments: Map<String, Fragment> by lazy {
        texts.mapNotNull { (id, t) -> (LayoutReader.fragment(t) as? Parsed.Ok)?.value?.let { id to it } }.toMap()
    }

    /** The app's boards, by their files' ids, each with its fragments. */
    private val boards: Map<String, Pair<Layout, Map<String, Fragment>>> by lazy {
        texts.filterKeys { it !in fragments }.mapValues { (id, t) ->
            val l = (LayoutReader.layout(t) as? Parsed.Ok)?.value ?: fail("$id does not read")
            l to l.parts.filterIsInstance<Include>().associate { it.src to fragments.getValue(it.src) }
        }
    }

    @Test
    fun theTableIsTheAppsLayouts() {
        val made = source(boards.mapValues { (_, b) -> KeyData.board(b.first, b.second) }.entries.sortedBy { it.key }.associate { it.key to it.value })
        if (recording()) writeText(sourceFile, made)
        assertEquals(made, readText(sourceFile), "BuiltInKeyData.kt is not the layouts': -Psnapshots.record=true")
    }

    @Test
    fun theTableHasEveryBoardAndTheFragmentsNone() {
        assertEquals(boards.keys, BuiltInKeyData.BOARDS.keys)
        assertEquals(PackLayouts.FRAGMENTS, fragments.keys)
        // a board the user picks among their own layouts not stood for: a pack's file of it is one of those
        for (id in boards.keys) assertEquals(id !in KeyData.LISTED, PackLayouts.replaces(id), id)
        for (id in fragments.keys) assertTrue(PackLayouts.replaces(id), id)
        assertTrue(!PackLayouts.replaces("mine"))
    }

    // a name is one key's wherever it is (KeyData looks one up on any board): each name's data the same on every board
    // that has it; a group key's place is its keyboard's (g14/ and g18/ never share a board)
    @Test
    fun eachNameIsTheSameOnEveryBoard() {
        val seen = HashMap<String, Pair<String, KeyData.Held>>()
        val differ = ArrayList<String>()
        for ((board, b) in BuiltInKeyData.BOARDS.entries.sortedBy { it.key }) for ((name, held) in b.named) {
            val first = seen.getOrPut(name) { board to held }
            if (first.second != held) differ += "$name: ${first.first} ${first.second}, $board $held"
        }
        assertEquals(emptyList(), differ)
    }

    // the names KeyData looks up are the names the keys have: the first column of ime-core's
    // keys-golden.tsv, every key of every board as KeyResolver resolves it
    @Test
    fun theNamesAreTheGoldenKeys() {
        // ime-core's, reached across modules from this one's test data: the path in every message, should either move
        val path = "$TEST_DATA/../../../../ime-core/src/test/resources/keys-golden.tsv"
        val golden = (readText(path) ?: error("no keys-golden.tsv at $path"))
            .lines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore('\t') }.toSet()
        val named = BuiltInKeyData.BOARDS.values.flatMap { it.named.keys }.toSet()
        assertEquals(emptyList(), (golden - named).sorted(), "in $path, no board's")
        assertEquals(emptyList(), (named - golden).sorted(), "a board's, not in $path")
    }

    // the generated file: a function of each board, names sorted, data written only where there is some (one function
    // a board, as a JVM method holds at most 64 KB of code: one map of all of them would near it as boards are added)
    private fun source(boards: Map<String, KeyData.Board>): String = buildString {
        appendLine(
            """
            |/*
            | * SPDX-License-Identifier: LGPL-2.1-or-later
            | * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
            | */
            |package org.fcitx.fcitx5.android.scene.layout
            |
            |import org.fcitx.fcitx5.android.scene.layout.KeyData.Board
            |import org.fcitx.fcitx5.android.scene.layout.KeyData.Held
            |
            |/**
            | * What the app's keys do held or swiped, by board and name ([KeyData.board] of each of src/commonMain/layouts): what
            | * KeyData puts in place of a theme's. Written by BuiltInKeyDataTest (-Psnapshots.record=true), which holds it to the
            | * layouts; not edited by hand.
            | */
            |@Suppress("LongMethod", "LargeClass")
            |internal object BuiltInKeyData {
            |    private val NONE = Held()
            |
            |    val BOARDS: Map<String, Board> by lazy {
            |        mapOf(
            """.trimMargin(),
        )
        for (id in boards.keys) appendLine("            ${q(id)} to ${fn(id)}(),")
        appendLine("        )")
        appendLine("    }")
        for ((id, b) in boards) {
            appendLine()
            appendLine("    private fun ${fn(id)}() = Board(")
            appendLine("        popups = ${b.popups},")
            appendLine("        named = ${map(b.named, 8)},")
            if (b.unnamed.isNotEmpty()) appendLine("        unnamed = ${map(b.unnamed, 8)},")
            if (b.places.isNotEmpty()) {
                appendLine("        places = mapOf(" + b.places.entries.sortedBy { it.value }.joinToString(", ") { "${q(it.key)} to ${it.value}" } + "),")
            }
            appendLine("    )")
        }
        appendLine("}")
    }

    // `emoji-search` -> emojiSearch
    private fun fn(id: String) = id.split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun map(m: Map<String, KeyData.Held>, indent: Int): String {
        if (m.isEmpty()) return "emptyMap()"
        val pad = " ".repeat(indent + 4)
        return m.entries.sortedBy { it.key }.joinToString(",\n", "mapOf(\n", ",\n" + " ".repeat(indent) + ")") { (k, v) -> "$pad${q(k)} to ${held(v)}" }
    }

    private fun held(h: KeyData.Held): String {
        if (h == KeyData.Held()) return "NONE"
        val args = listOfNotNull(
            h.alt?.let { "alt = ${text(it)}" },
            h.actions.takeIf { it.isNotEmpty() }?.let { a -> "actions = mapOf(" + a.entries.sortedBy { it.key }.joinToString(", ") { "${q(it.key)} to ${q(it.value)}" } + ")" },
            h.popup?.let { p ->
                val items = if (p.items.isEmpty()) "emptyList()" else p.items.joinToString(", ", "listOf(", ")") { "${q(it.first)} to ${it.second?.let(::q) ?: "null"}" }
                val options = if (p.options.isEmpty()) "emptyMap()" else p.options.entries.sortedBy { it.key }.joinToString(", ", "mapOf(", ")") { "${q(it.key)} to ${q(it.value)}" }
                "popup = Popup(${q(p.from)}, ${p.preset?.let(::q) ?: "null"}, $items, $options)"
            },
            h.menu.takeIf { it.isNotEmpty() }?.let { m ->
                "menu = listOf(" + m.joinToString(", ") { "MenuItem(${q(it.label)}, ${it.icon?.let(::q) ?: "null"}, ${q(it.action)})" } + ")"
            },
        )
        return "Held(${args.joinToString(", ")})"
    }

    private fun text(t: Text): String = when (t) {
        is Text.Literal -> "Text.Literal(${q(t.text)})"
        is Text.Binding -> "Text.Binding(${q(t.name)}, ${t.arg?.let(::q) ?: "null"})"
    }

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' || c == '\\' || c == '$' -> append('\\').append(c)
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }
}
