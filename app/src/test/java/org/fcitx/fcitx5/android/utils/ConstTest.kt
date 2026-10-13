/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** The help opened main's manual, so a release's users read of options their build has not got. */
class ConstTest {

    @Test
    fun aReleaseOpensTheManualAtItsTag() {
        assertEquals("$REPO/blob/v0.2.0/docs/README.md", manualUrl(REPO, "0.2.0", "text"))
    }

    @Test
    fun aBuildBetweenReleasesOpensMainsManual() {
        assertEquals("$REPO/blob/HEAD/docs/README.md", manualUrl(REPO, "0.1.0-5-gabc1234", "text"))
        assertEquals("$REPO/blob/HEAD/docs/README.md", manualUrl(REPO, "0.1.0-untagged", "text"))
    }

    @Test
    fun theVoiceBuildsReleaseOpensTheManualAtItsTagToo() {
        assertEquals("$REPO/blob/v0.2.0/docs/README.md", manualUrl(REPO, "0.2.0-voice", "voice"))
        assertEquals("$REPO/blob/HEAD/docs/README.md", manualUrl(REPO, "0.1.0-5-gabc1234-voice", "voice"))
    }

    private companion object {
        const val REPO = "https://example.invalid/repo"
    }
}
