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

/** This app's own fields on the Kotlin host: the service marks them Sensitive, [EngineReplay.Field.NO_LEARNING] here. */
class OwnFieldEngineUatTest {

    @Test
    @Uat("PR-16")
    fun whatWasDeletedInTheAppsOwnFieldIsNotTheTextBefore() {
        // the text before reorders zai's page elsewhere, or the check below would say nothing; the Sensitive field keeps none
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
        // 我 deleted is not the context: each row its own replay
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
