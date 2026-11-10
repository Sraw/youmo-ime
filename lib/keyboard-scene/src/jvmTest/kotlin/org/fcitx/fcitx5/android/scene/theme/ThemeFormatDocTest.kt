/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Icons
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 060-render-p3 T10: docs/theme-format.md's property table is [Values.PROPERTIES] and its reserved table
 * [Values.RESERVED], row for row, so the authors' reference and the engine cannot drift apart. A stage that
 * makes a reserved property work (A2–A4's motion) moves its row from the one table to the other.
 * On the JVM alone: the file is read by path, which the build hands in.
 *
 * contract: the authors' reference (docs/theme-format.md) and the engine's tables agree row for row
 */
class ThemeFormatDocTest {

    private val doc = File(System.getProperty("scene.themeDoc") ?: error("no scene.themeDoc: run the tests through Gradle")).readText()

    // the first cells of the tables in the section headed [heading] (`| `name` | …`)
    private fun rows(heading: String): List<String> {
        val section = doc.split("\n## ").firstOrNull { it.startsWith(heading + "\n") }
        assertTrue(section != null, "docs/theme-format.md has no section ## $heading")
        return ROW.findAll(section).map { it.groupValues[1] }.toList()
    }

    private fun check(heading: String, engine: Collection<String>, where: String) {
        val documented = rows(heading)
        val missing = engine - documented.toSet()
        val extra = documented.toSet() - engine.toSet()
        val twice = documented.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(
            missing.isEmpty() && extra.isEmpty() && twice.isEmpty(),
            buildString {
                append("docs/theme-format.md ## $heading and $where differ:")
                missing.forEach { append("\n  missing a row for `$it` (in $where): add it to the table") }
                extra.forEach { append("\n  a row for `$it`, which $where does not have: remove or move it") }
                twice.forEach { append("\n  `$it` has two rows") }
            },
        )
    }

    @Test
    fun propertyTableIsValuesProperties() = check("属性", Values.PROPERTIES, "Values.PROPERTIES")

    @Test
    fun reservedTableIsValuesReserved() = check("保留名单", Values.RESERVED.keys, "Values.RESERVED")

    // 060-theme-sounds-icons: the reserved elements' line (另外的保留写法：元素 `<a>`、`<b>`…) names ThemeReader.RESERVED_ELEMENTS,
    // and is not there where it has none
    @Test
    fun reservedElementsLineIsThemeReaders() {
        val section = doc.split("\n## ").first { it.startsWith("保留名单\n") }
        val line = section.lines().firstOrNull { it.startsWith(RESERVED_LINE) }
        if (ThemeReader.RESERVED_ELEMENTS.isEmpty()) {
            assertTrue(line == null, "docs/theme-format.md ## 保留名单 lists reserved elements, ThemeReader.RESERVED_ELEMENTS has none: remove \"$line\"")
        } else {
            val documented = ELEMENT.findAll(line ?: "").map { it.groupValues[1] }.toSet()
            assertEquals(ThemeReader.RESERVED_ELEMENTS, documented, "docs/theme-format.md ## 保留名单's $RESERVED_LINE")
        }
    }

    @Test
    fun rowsAreRead() {
        // the parse itself: a table it no longer finds would pass an empty engine list, never ours
        assertEquals("fill", rows("属性").first())
        assertEquals("height", rows("保留名单").first())
    }

    @Test
    fun systemColorsAreSystemNames() {
        // the table under 系统颜色 (up to the next heading), its `group-N` rows expanded by the line N 取: …
        val section = doc.substringAfter("#### 系统颜色\n").substringBefore("\n#")
        val suffixes = section.lines().first { it.startsWith("N 取") }.let { line -> NUMBER.findAll(line).map { it.groupValues[1] }.toList() }
        val documented = SYSTEM_ROW.findAll(section).map { it.groupValues[1] }
            .flatMap { if (it.endsWith("-N")) suffixes.map { n -> it.removeSuffix("N") + n } else listOf(it) }.toSet()
        val missing = ColorExpr.SYSTEM_NAMES - documented
        val extra = documented - ColorExpr.SYSTEM_NAMES
        assertTrue(
            missing.isEmpty() && extra.isEmpty(),
            "docs/theme-format.md 系统颜色 and ColorExpr.SYSTEM_NAMES differ: missing $missing, not in the code $extra",
        )
    }

    // the built-in icon names' table under 替换自带图标 (its rows `| 用在 | `a`、`b` |` up to the first line not a row)
    // is Icons.ALL, so an icon added to the code cannot leave the authors' list behind
    @Test
    fun builtInIconTableIsIcons() {
        val table = doc.substringAfter("自带图标的名字：\n").lines().map { it.trim() }.dropWhile { it.isEmpty() }.takeWhile { it.startsWith("|") }
        val documented = table.flatMap { row -> NAME.findAll(row).map { it.groupValues[1] } }.toSet()
        assertTrue(table.size > 2, "docs/theme-format.md has no table after 自带图标的名字：")
        assertEquals(Icons.ALL, documented, "docs/theme-format.md 自带图标的名字 and Icons.ALL")
    }

    private companion object {
        val SYSTEM_ROW = Regex("(?m)^\\| `([a-z0-9-]+(?:-N)?)` \\|")
        val NUMBER = Regex("`([0-9]+)`")
        const val RESERVED_LINE = "另外的保留写法"
        val ELEMENT = Regex("`<([a-z-]+)>`")
        val NAME = Regex("`([a-z][a-z-]*)`")

        val ROW = Regex("(?m)^\\| `([a-z][a-z-]*)` \\|")
    }
}
