/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMarksTest {

    // contract: the golden's header is the model's tables (polish_golden.py): its `after` labels' characters are
    // TAGGED, no more and no fewer, so a retrained tagger writing a new mark fails here until it is added to
    // TAGGED (voice-9). That is all this holds: which of the rules' sets the new mark belongs in is still for
    // whoever adds it to decide, set by set
    @Test
    fun taggedIsWhatTheTaggerWrites() {
        val afters = javaClass.getResourceAsStream("/voice/polish-golden.tsv")!!.bufferedReader().useLines { lines ->
            lines.first { it.startsWith("afters\t") }.split('\t').drop(1)
        }
        assertEquals(VoiceMarks.TAGGED.toSet(), afters.joinToString("").toSet())
    }

    @Test
    fun wideAndAsciiPairUp() = assertEquals(VoiceMarks.WIDE.length, VoiceMarks.ASCII.length)
}
