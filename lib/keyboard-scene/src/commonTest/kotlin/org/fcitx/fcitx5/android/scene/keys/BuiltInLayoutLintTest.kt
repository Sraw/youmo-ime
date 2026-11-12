/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.listNames
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in layouts (src/commonMain/layouts) leave every key's long press, swipe and corner to its name (060-keys-unify
 * §6.5): one rule for every key on every keyboard, KeyDefaults' and the user's. A theme's own layout may write them (its
 * keys' defaults, C3): this reads the app's alone.
 */
class BuiltInLayoutLintTest {

    // a key as a layout has it: its attributes over its row's and its file's defaults, its variants' each over those
    private class Written(val file: String, val key: Key, val attrs: KeyAttrs) {
        val all: List<KeyAttrs> get() = listOf(attrs) + key.variants.map { it.attrs.over(attrs) }
        val name: String? get() = all.firstNotNullOfOrNull { it.shortcut }
        val where: String get() = "$file ${key.id} (line ${key.line})"

        // a function key by its name (fn/comma types, yet is one); a key of none by its kind
        val function: Boolean get() = name?.let { KeyId.of(it).isFunction } ?: (attrs.kind != null && attrs.kind != "letter")
        val address: Boolean get() = name == null && attrs.role in ADDRESS
    }

    private val keys: List<Written> by lazy {
        val dir = "$TEST_DATA/../../commonMain/layouts"
        listNames(dir).filter { it.endsWith(".xml") }.flatMap { file ->
            val text = testData(file)
            val (defaults, rows) = if ("<fragment" in text) {
                LayoutReader.fragment(text).ok().let { it.defaults to it.rows }
            } else {
                LayoutReader.layout(text).ok().let { l -> l.defaults to l.parts.filterIsInstance<Row>() }
            }
            rows.flatMap { row -> row.cells.filterIsInstance<Key>().map { k -> Written(file, k, k.attrs.over(row.defaults.over(defaults))) } }
        }
    }

    @Test
    fun everyBuiltInLayoutIsRead() {
        // the boards and the fragment their bottom rows share: none missed by a reader that found nothing
        assertTrue(keys.map { it.file }.toSet().containsAll(listOf("qwerty.xml", "nine.xml", "k14.xml", "k18.xml", "grouped-bottom.xml", "zhuyin.xml")))
    }

    @Test
    fun aCharacterKeyWritesNoSwipeNorCorner() {
        // its first is its long press's (KeyDefaults, the user's key_lists): the swipe types it, the corner draws it
        val wrong = keys.filter { !it.function }.flatMap { k -> k.all.flatMap { a -> listOfNotNull("swipe-up".takeIf { it in a.actions }, "alt".takeIf { a.alt != null }) }.map { "${k.where}: $it" } }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun aFunctionKeyWritesNoLongPressNorSwipe() {
        // its list, slots and action long press by its name alone, the same on every keyboard (§5: KeyDefaults.function,
        // FUNCTION_ACTIONS, SlotDefaults); an address key's own (a field's commit key, out of the keys' model)
        val wrong = keys.filter { it.function }.flatMap { k ->
            val popup = "<popup>".takeIf { k.key.popup != null && !k.address }
            listOfNotNull(popup) + k.all.flatMap { a -> listOf("long-press", "swipe-up").filter { it in a.actions } }.map { "${k.where}: $it" }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun onlyAnAddressKeyDrawsNoMark() {
        val wrong = keys.filter { k -> k.all.any { it.mark == false } && !k.address && !markOffKept(k) }.map { it.where }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun everyKeyOfTheModelHasAName() {
        val wrong = keys.filter { it.name == null && !unnamedKept(it) }.map { it.where }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun everyNameIsOneKeyIdKnows() {
        val wrong = keys.flatMap { k -> k.all.mapNotNull { it.shortcut }.map { k to KeyId.of(it) } }
            .filter { (_, id) -> id.form !in FORMS || id.key.isEmpty() }
            .map { (k, id) -> "${k.where}: $id" }
        assertEquals(emptyList(), wrong)
    }

    private companion object {
        val ADDRESS = setOf("slash", "at", "dot", "domain")

        // full, 九键, 14 and 18 键, 注音九键 and 大千, the number pad's digits, the function keys
        val FORMS = setOf("full", "t9", "g14", "g18", "zy9", "zyfull", "num", KeyId.FUNCTION)

        // the keys out of the keys' model, with the reason each is (060-keys-unify §5): a panel's own way about (back,
        // lock, search, the emoji and emoticon tabs, the candidates' pages), 手写's 注 (its long press a menu), a table's
        // 拼 lookup (one keyboard's, a table's own), the bar's number row (on the bar, no long press nor slots), the number
        // pad's operators and marks (no list, §5), 表情搜索's letters (they spell a query, popups="off");
        // qwerty's ; and * (双拼's ing and a table's extra code keys, shown for one scheme or table): not named in 060-keys-unify,
        // a list and slots of their own wait on the editor's pages showing them
        fun unnamedKept(k: Written): Boolean = k.address ||
            k.file == "number-row.xml" || k.file == "emoji-search.xml" ||
            k.attrs.role in setOf("back", "lock", "reading", "lookup", "lookup-on") ||
            k.key.id in setOf("search", "emoji", "emoticon", "page-up", "page-down") ||
            (k.file == "number.xml" && k.key.id.none { it.isLetterOrDigit() }) ||
            (k.file == "qwerty.xml" && k.attrs.role in setOf("semicolon", "star"))

        // qwerty's ; and * (above), and 注音's keys: a zhuyin face has no room for a corner (rules C, its layouts' defaults)
        fun markOffKept(k: Written): Boolean =
            (k.file == "qwerty.xml" && k.attrs.role in setOf("semicolon", "star")) || k.file in setOf("zhuyin.xml", "zhuyin-nine.xml")
    }
}
