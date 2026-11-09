/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// a drawable the page would draw otherwise than the app is refused, not written wrong
class VectorIconJsonTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun json(body: String): String {
        val f = dir.newFile()
        f.writeText(
            """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
              |  android:viewportWidth="24" android:viewportHeight="24">$body</vector>
            """.trimMargin(),
        )
        return VectorIconJson.of("icon", f)
    }

    private fun refused(body: String): String = assertThrows(IllegalStateException::class.java) { json(body) }.message.orEmpty()

    @Test
    fun aFilledAndAStrokedPathAreWritten() {
        assertEquals(
            """{"vw":24.0,"vh":24.0,"paths":[{"d":"M0 0h24v24z","rule":"evenodd"},{"d":"M0 0L24 24","stroke":2.0}]}""",
            json(
                """<path android:fillColor="#fff" android:fillType="evenOdd" android:pathData="M0 0h24v24z"/>
                  |<path android:strokeColor="#fff" android:strokeWidth="2" android:strokeLineCap="butt" android:pathData="M0 0L24 24"/>
                """.trimMargin(),
            ),
        )
    }

    @Test
    fun whatThePageWouldDrawOtherwiseIsRefused() {
        assertTrue(refused("""<clip-path android:pathData="M0 0h12v12z"/>""").contains("clip-path"))
        assertTrue(refused("""<path android:pathData="M0 0h24v24z"/>""").contains("no colour"))
        assertTrue(refused("""<path android:fillColor="#fff" android:strokeColor="#000" android:pathData="M0 0h24v24z"/>""").contains("both"))
        assertTrue(refused("""<path android:strokeColor="#000" android:strokeLineCap="round" android:pathData="M0 0L24 24"/>""").contains("strokeLineCap"))
        assertTrue(refused("""<path android:strokeColor="#000" android:strokeLineJoin="round" android:pathData="M0 0L24 24"/>""").contains("strokeLineJoin"))
        assertTrue(refused("""<path android:strokeColor="#000" android:strokeMiterLimit="10" android:pathData="M0 0L24 24"/>""").contains("strokeMiterLimit"))
        assertTrue(refused("""<group><path android:strokeColor="#000" android:strokeAlpha="0.5" android:pathData="M0 0L24 24"/></group>""").contains("strokeAlpha"))
    }
}
