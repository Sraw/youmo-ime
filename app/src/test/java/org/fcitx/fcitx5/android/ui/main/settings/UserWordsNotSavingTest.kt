/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/** 用户词库 says at the top that learning is not being saved while the engine's overview says so, and nothing otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class UserWordsNotSavingTest {

    @Before
    fun phone() = phoneStorage()

    private fun shownWith(saving: Boolean): Boolean = SettingsHarness().use { st ->
        st.api.learned = {
            if (it == LearningOp.Overview) LearningResult.Overview(0, 0, 0, 0, 0, habits = true, saving = saving) else LearningResult.Done()
        }
        st.open(SettingsRoute.UserWords)
        // the answer taken, so the line's absence is not just from before it
        val asked = "learning(${LearningOp.Overview})"
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (asked !in st.api.calls) {
            check(System.nanoTime() < until) { "no overview asked: ${st.api.calls}" }
            Thread.sleep(POLL_MS)
            st.idle()
        }
        st.idle()
        val line = st.s(R.string.learning_not_saving)
        st.preferences().any { it.summary?.toString() == line }
    }

    @Test
    @Uat("WD-46")
    fun thePageSaysLearningIsNotSavedOnlyWhileItIsNot() {
        assertEquals(true, shownWith(saving = false))
        assertEquals(false, shownWith(saving = true))
    }

    private companion object {
        const val WAIT_S = 5L
        const val POLL_MS = 10L
    }
}
