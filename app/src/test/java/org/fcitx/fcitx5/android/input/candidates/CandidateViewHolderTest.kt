/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A tapped candidate is picked by what it showed on the tap, not by what its holder is bound to
 * when the pick runs: a list refined in between shows another word there. Under the app, whose
 * preferences CandidateItemUi's gesture view reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CandidateViewHolderTest {

    @Test
    fun aTapPicksWhatTheHolderShowedThenNotWhatItIsBoundToWhenThePickRuns() {
        val holder = CandidateViewHolder(CandidateItemUi(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark))
        holder.update(1, CandidateWord("", "你", ""))
        val pick = holder.pick()
        holder.update(1, CandidateWord("", "拟", ""))
        val fake = FakeFcitxAPI()
        runBlocking { pick(this, fake) }
        assertEquals(listOf("select(1, 你)"), fake.calls)
    }
}
