/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// the file kept from the last run: what it wrote read back; an empty, torn or other-version file none kept (the
// keys held back as on a first install), never punctuation off (S7a round 4)
@RunWith(RobolectricTestRunner::class)
class PunctuationStartKeysTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file get() = File(ctx.createDeviceProtectedStorageContext().filesDir, "punctuation-start-keys")

    // where 0.6's betas kept it: the credential store
    private val old get() = File(ctx.filesDir, "punctuation-start-keys")

    @After
    fun forget() {
        PunctuationStartKeys.forget(ctx)
        old.delete()
    }

    private fun readBack(): Map<String, String>? {
        PunctuationStartKeys.loadNow(ctx)
        return PunctuationStartKeys.known
    }

    @Test
    fun writtenReadsBack() {
        val keys = mapOf("," to "、", "." to "。")
        PunctuationStartKeys.write(ctx, keys)
        assertEquals(keys, readBack())
        PunctuationStartKeys.write(ctx, emptyMap())
        assertEquals(emptyMap<String, String>(), readBack())
    }

    @Test
    fun anEmptyTornOrOtherFileIsNoneKept() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val whole = file.readText()
        assertRows(
            listOf(
                "empty" to "",
                "torn short of its last line" to whole.substring(0, whole.lastIndexOf(',')),
                "another version's" to whole.replaceFirst("v1", "v9"),
                "no header" to "，\n",
                "a line without its tab" to "v1\t2\n,\t、\n.",
                "a line's value empty" to "v1\t2\n,\t、\n.\t",
            ),
            { (why, bad) -> "$why <$bad>" },
        ) { (_, bad) ->
            file.writeText(bad)
            assertNull(readBack())
        }
    }

    @Test
    fun theCopyKeptWhereBetasKeptItMovesOnceToTheDeviceProtectedStore() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val text = file.readText()
        PunctuationStartKeys.forget(ctx)
        if (old.absolutePath == file.absolutePath) return
        old.writeText(text)
        // the user unlocked, no device-protected copy: moved over and read, the old one gone
        assertEquals(mapOf("," to "、", "." to "。"), readBack())
        assertEquals(text, file.readText())
        assertEquals(false, old.exists())
        // a device-protected copy there: an old one left is not read over it
        old.writeText("v1\t1\n,\t，\n")
        assertEquals(mapOf("," to "、", "." to "。"), readBack())
    }
}
