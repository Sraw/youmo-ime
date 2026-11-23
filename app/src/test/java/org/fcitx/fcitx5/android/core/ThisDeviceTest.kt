/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Looper
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.device.DeviceProfiles
import org.fcitx.fcitx5.android.device.ProfileStore
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThisDeviceTest {

    private class Store : ProfileStore {
        override fun read() = emptyMap<String, String>()
        override fun write(values: Map<String, String?>) = true
    }

    /** Profiles whose facts record the thread that asked for them. */
    private class Recorded {
        val threads: MutableList<Thread> = Collections.synchronizedList(ArrayList())
        val asked = CountDownLatch(1)
        val profiles = DeviceProfiles(Store(), "", {
            threads += Thread.currentThread()
            asked.countDown()
            ThisDevice.TEST_FACTS
        }, null)
    }

    @After
    fun forget() {
        ThisDevice.forget()
    }

    @Test
    fun warmingDecidesOffTheCallersThreadAndOnce() {
        val recorded = Recorded()
        ThisDevice.profiles = recorded.profiles
        ThisDevice.warm()!!.join()
        ThisDevice.profile
        assertEquals(1, recorded.threads.size)
        assertNotSame(Thread.currentThread(), recorded.threads.single())
    }

    // each rotation of the settings calls it: no thread once the profile is decided
    @Test
    fun warmingADecidedProfileStartsNoThread() {
        ThisDevice.profiles = Recorded().profiles
        ThisDevice.profile
        assertNull(ThisDevice.warm())
    }

    // after an update the settings may be the first to ask: the activity starts the decision before any page reads it
    @Test
    fun theSettingsWarmTheProfileOffTheMainThread() {
        val recorded = Recorded()
        ThisDevice.profiles = recorded.profiles
        SettingsHarness().use { it.activity }
        assertTrue("the profile was not decided", recorded.asked.await(5, TimeUnit.SECONDS))
        assertNotSame(Looper.getMainLooper().thread, recorded.threads.first())
    }

    // without the library the engine runs MatrixKernel.JVM, whatever the profile says
    @Test
    fun theKernelNamedIsTheJvmsWithoutTheLibrary() {
        ThisDevice.profiles = Recorded().profiles
        assertEquals("JVM", ThisDevice.kernelName)
    }

    @Test
    fun theKernelNativeSaysItTookIsNamedAsTheProfilesAreAndAnUnknownOneAsItsNumber() {
        assertRows(
            "portable" to { assertEquals("PORTABLE", ThisDevice.nameOf(NativeMatrixKernel.PORTABLE)) },
            "MLAL" to { assertEquals("MLAL", ThisDevice.nameOf(NativeMatrixKernel.MLAL)) },
            "SDOT" to { assertEquals("SDOT", ThisDevice.nameOf(NativeMatrixKernel.SDOT)) },
            "none it knows" to { assertEquals("9", ThisDevice.nameOf(9)) },
        )
    }

    @Test
    fun thePrefsStoreReadsStringsAloneAndRemovesOnNull() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("profile-store-test", Context.MODE_PRIVATE)
        prefs.edit().putInt("other", 1).commit()
        val store = PrefsProfileStore(prefs)
        assertTrue(store.write(mapOf(DeviceProfiles.KEY to "k", DeviceProfiles.TRIAL to "k")))
        assertEquals(mapOf(DeviceProfiles.KEY to "k", DeviceProfiles.TRIAL to "k"), store.read())
        assertTrue(store.write(mapOf(DeviceProfiles.TRIAL to null, DeviceProfiles.PROFILE to "p")))
        assertEquals(mapOf(DeviceProfiles.KEY to "k", DeviceProfiles.PROFILE to "p"), store.read())
    }
}
