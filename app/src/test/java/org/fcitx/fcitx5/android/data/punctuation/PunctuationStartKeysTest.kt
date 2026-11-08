/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import androidx.test.core.app.ApplicationProvider
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
    private val file get() = File(ctx.filesDir, "punctuation-start-keys")

    @After
    fun forget() = PunctuationStartKeys.forget(ctx)

    private fun readBack(): Map<String, String>? {
        PunctuationStartKeys.loadNow(ctx)
        return PunctuationStartKeys.known
    }

    @Test
    fun writtenReadsBack() {
        val keys = mapOf("," to "、", "." to "。")
        PunctuationStartKeys.write(ctx, keys)
        PunctuationStartKeys.forget(ctx).also { PunctuationStartKeys.write(ctx, keys) }
        assertEquals(keys, readBack())
        PunctuationStartKeys.write(ctx, emptyMap())
        assertEquals(emptyMap<String, String>(), readBack())
    }

    @Test
    fun anEmptyTornOrOtherFileIsNoneKept() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val whole = file.readText()
        for (bad in listOf("", whole.substring(0, whole.lastIndexOf(',')), whole.replaceFirst("v1", "v9"), "，\n", "v1\t2\n,\t、\n.", "v1\t2\n,\t、\n.\t")) {
            file.writeText(bad)
            assertNull("<$bad>", readBack())
        }
    }
}
