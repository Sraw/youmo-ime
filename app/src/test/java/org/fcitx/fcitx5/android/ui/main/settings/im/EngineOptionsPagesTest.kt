/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Choice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Flags
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings.Toggle
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Kotlin host's pages ([EngineOptions.page]) have every option the app's pages read, at the
 * RawConfig path they read it: the pages stay as they are when the host changes.
 * contract: pages generated from fcitx's option table, which mutation cannot see.
 */
class EngineOptionsPagesTest {

    private fun paths(items: List<InputMethodSettings.Item>): List<String> = items.flat().flatMap {
        when (it) {
            is Toggle -> listOf(it.key)
            is Choice -> listOf(it.key)
            is Flags -> it.flags.map { (key, _) -> "${it.path}/$key" }
            else -> emptyList()
        }
    }

    @Test
    fun `each page's options are on the host's page for its input method`() {
        val options = EngineOptions()
        val withSettings = appInputMethods.mapNotNull { im -> InputMethodSettings.of(im)?.let { im to paths(it) } }
            .filter { (_, paths) -> paths.isNotEmpty() }
        for ((im, paths) in withSettings) {
            // an input method with settings but no host page (a lost branch) must fail, not be skipped
            val page = options.page(im)
            assertNotNull("$im has no page", page)
            for (path in paths) assertNotNull("$im $path", page!!.find(path))
        }
    }

    @Test
    fun `the shared page and the hardware keyboard's read pinyin's`() {
        val page = EngineOptions().page("engine-pinyin")!!
        for (path in paths(InputMethodSettings.SHARED + InputMethodSettings.HARDWARE_KEYBOARD)) assertNotNull(path, page.find(path))
    }
}
