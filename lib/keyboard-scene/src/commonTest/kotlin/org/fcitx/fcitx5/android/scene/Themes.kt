/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import kotlin.test.assertEquals

/**
 * Whether [actual] is the theme [expected] is, as far as drawing it goes: every field alike but where
 * its styles are (their lines, their places counted with the styles that set nothing, which a file may
 * have of a later stage's properties alone and the writer leaves out) and the system colours it was
 * read with (the writer writes what they made).
 */
internal fun assertSameTheme(expected: Theme, actual: Theme, what: String = expected.name) {
    fun fields(t: Theme) = listOf(
        "name" to t.name, "dark" to t.dark, "version" to t.version, "variants" to t.variants, "colors" to t.colors,
        "baseColors" to t.baseColors, "darkColors" to t.darkColors, "fonts" to t.fonts, "icons" to t.icons, "meta" to t.meta,
        "soundFiles" to t.soundFiles,
        "picture" to t.picture?.let { listOf(it.src, it.brightness, it.blurDp, it.cuts) },
    )
    fields(expected).zip(fields(actual)).forEach { (e, a) -> assertEquals(e.second, a.second, "$what: ${e.first}") }
    fun styles(t: Theme) = t.styles.filter { it.values.isNotEmpty() }.map { it.selectors to it.values }
    val e = styles(expected)
    val a = styles(actual)
    e.zip(a).forEachIndexed { i, (x, y) -> assertEquals(x, y, "$what: style $i") }
    assertEquals(e.size, a.size, "$what: styles")
}

/** [theme] written and read back. */
internal fun rewritten(theme: Theme, maxStyles: Int = ThemeReader.MAX_STYLES): Theme =
    ThemeReader.theme(ThemeWriter.write(theme), maxStyles = maxStyles).ok()
