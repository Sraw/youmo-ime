/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * This app's own fields (the settings' search box) on the Kotlin host: the service marks them
 * Sensitive (CapabilityFlags.forField), [EngineReplay.Field.NO_LEARNING] here. The app side
 * (OwnFieldUatTest) can only see what the service tells; what the engine makes of the field is here.
 */
class OwnFieldEngineUatTest {

    @Test
    @Uat("PR-16")
    fun whatWasDeletedInTheAppsOwnFieldIsNotTheTextBefore() {
        // zai alone, and after 我 in another app's field: the text before does reorder its page (在 first
        // both times), or the check below would say nothing. This app's own field, Sensitive, keeps none
        val alone = with(HostReplay()) {
            type("zai")
            candidates.take(PAGE)
        }
        val afterWo = with(HostReplay()) {
            type("wo")
            pick("我")
            type("zai")
            candidates.take(PAGE)
        }
        assertNotEquals("zai after 我 as alone ($alone): the text before is not used, the check below is vacuous", alone, afterWo)
        // 我 deleted is not the context (F4277): each row its own replay. Only the TEXT row tests the
        // deletion as such; the Sensitive field sends no context at all
        assertRows(
            listOf(
                EngineReplay.Field.NO_LEARNING to "this app's own field: Sensitive, no context sent",
                EngineReplay.Field.TEXT to "another app's field: the deleted 我 is not the text before (F4176)",
            ),
            { it.second },
        ) { (field, _) ->
            with(HostReplay(field = field)) {
                type("wo")
                pick("我")
                backspace()
                expectText("")
                type("zai")
                assertEquals(alone, candidates.take(PAGE))
            }
        }
    }

    private companion object {
        // the bar's first page, about
        const val PAGE = 6
    }
}
