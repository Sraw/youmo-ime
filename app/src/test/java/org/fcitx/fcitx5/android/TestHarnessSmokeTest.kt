/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.os.Build
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Proves the unit test harness itself works, so a failure here points at the build setup
 * rather than at product code. Delete nothing from this file without a replacement --
 * each test guards one tool the rest of the suite relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestHarnessSmokeTest {

    /** kotlinx-coroutines-test: virtual time, so timeout logic can be tested without waiting. */
    @Test
    fun coroutinesTestVirtualClockAdvancesWithoutRealDelay() = runTest {
        val wallClockStart = System.currentTimeMillis()
        delay(10_000)
        Assert.assertEquals("virtual clock advanced", 10_000, currentTime)
        Assert.assertTrue(
            "no real time was spent",
            System.currentTimeMillis() - wallClockStart < 5_000
        )
    }

    /** kotlinx-coroutines-test: explicit time control, needed for long-press/repeat logic. */
    @Test
    fun coroutinesTestAdvanceTimeByIsExplicit() = runTest {
        var fired = false
        val job = launchAfter(500) { fired = true }
        advanceTimeBy(499)
        Assert.assertFalse("not yet", fired)
        advanceTimeBy(2)
        Assert.assertTrue("fired after the delay elapsed", fired)
        job.join()
    }

    private fun CoroutineScope.launchAfter(ms: Long, block: () -> Unit): Job = launch {
        delay(ms)
        block()
    }

    /** Turbine: assert on Flow emissions, needed for event-stream tests. */
    @Test
    fun turbineObservesFlowEmissions() = runTest {
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        flow.test {
            flow.emit(1)
            flow.emit(2)
            Assert.assertEquals(1, awaitItem())
            Assert.assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** MockK: stub a final Kotlin class, which Mockito cannot do without extra setup. */
    @Test
    fun mockkStubsFinalClasses() {
        val ctx = mockk<Context>()
        every { ctx.packageName } returns "org.fcitx.fcitx5.android.test"
        Assert.assertEquals("org.fcitx.fcitx5.android.test", ctx.packageName)
    }

    /**
     * Robolectric: a real Context and real framework behaviour on the JVM.
     *
     * The SDK is pinned to 35 for every Robolectric test by `robolectric.properties`; see there
     * for why.
     */
    @RunWith(RobolectricTestRunner::class)
    class RobolectricSmoke {
        @Test
        fun providesRealContextAtPinnedSdk() {
            val ctx = RuntimeEnvironment.getApplication()
            Assert.assertNotNull("Robolectric supplies an Application context", ctx)
            Assert.assertEquals("running at the SDK robolectric.properties pins", 35, Build.VERSION.SDK_INT)
        }

        /**
         * Canary: remove the @Ignore after upgrading Robolectric. If it passes, raise the pin in
         * `robolectric.properties` to the app's compileSdk.
         */
        @Ignore("Robolectric 4.17 crashes at SDK 36 on JDK 21; retry after upgrading it")
        @Test
        @Config(sdk = [36])
        fun runsAtCompileSdk() {
            Assert.assertEquals(36, Build.VERSION.SDK_INT)
        }

        @Test
        fun realFrameworkBehaviourNotStubs() {
            // android.text.TextUtils is a real implementation here, not a `Stub!` throw
            Assert.assertTrue(android.text.TextUtils.isEmpty(""))
            Assert.assertFalse(android.text.TextUtils.isEmpty("a"))
        }
    }
}
