/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.voice.VoiceCommit
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTrackTest {

    private class Start(val field: VoiceCommit.Field, val identical: Boolean = false, val restarting: Boolean = false)

    private fun field(id: Int, pkg: String = "app", type: Int = 1, hint: String? = null) = VoiceCommit.Field(pkg, id, type, hintText = hint)

    /** Each start's answer from one FieldTrack. */
    private fun answers(vararg starts: Start): List<Boolean> = FieldTrack().let { t -> starts.map { t.started(it.field, it.identical, it.restarting) } }

    @Test
    @Uat("PAN-36")
    fun aFieldIsTheSameByItsEditorInfoOrItsViewAppAndTraitsOrARestartAlike() = assertRows(
        "the first field is a new one; the same view started again (restartInput's EditorInfo, or back from another app) the same" to {
            assertEquals(listOf(false, true), answers(Start(field(7)), Start(field(7))))
        },
        "another view, app or trait is another field" to {
            assertEquals(
                listOf(false, false, false, false, false),
                answers(Start(field(7)), Start(field(8)), Start(field(8, pkg = "other")), Start(field(8, pkg = "other", type = 0x21)), Start(field(8, pkg = "other", type = 0x21, hint = "mail"))),
            )
        },
        "back to a field after another is a new start" to {
            assertEquals(listOf(false, false, false), answers(Start(field(7)), Start(field(8)), Start(field(7))))
        },
        "an unset id alike tells nothing, the same EditorInfo does" to {
            assertEquals(listOf(false, false, true), answers(Start(field(0)), Start(field(0)), Start(field(0), identical = true)))
        },
        "a WebView's restartInput alike is the same, with another hint or type not" to {
            assertEquals(
                listOf(false, false, true, false, false),
                answers(Start(field(-1)), Start(field(-1)), Start(field(-1), restarting = true), Start(field(-1, hint = "mail"), restarting = true), Start(field(-1, type = 2), restarting = true)),
            )
        },
    )
}
