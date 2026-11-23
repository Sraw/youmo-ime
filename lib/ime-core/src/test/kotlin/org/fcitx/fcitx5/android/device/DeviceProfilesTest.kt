/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceProfilesTest {

    private class MapStore : ProfileStore {
        val values = HashMap<String, String>()
        val writes = ArrayList<Map<String, String?>>()
        var fails = false
        override fun read(): Map<String, String> = HashMap(values)
        override fun write(values: Map<String, String?>): Boolean {
            if (fails) return false
            writes += values
            values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
            return true
        }
    }

    /** A thread's mask and the cores the trial ran on; [reachable] cores take a mask, [results] say how each went. */
    private class FakeChecks(
        val reachable: Set<Int> = (0..7).toSet(),
        val results: Map<Int, SdotResult> = emptyMap(),
        val dies: Int? = null,
    ) : SdotChecks {
        var mask = 0xffL
        val ran = ArrayList<Int>()
        override fun set(mask: Long): Long {
            if (java.lang.Long.bitCount(mask) == 1 && java.lang.Long.numberOfTrailingZeros(mask) !in reachable) return -1
            this.mask = mask
            return 0
        }
        override fun sdotSelfTest(cpu: Int): SdotResult {
            assertEquals(1L shl cpu, mask)
            if (cpu == dies) throw IllegalStateException("SIGILL")
            ran += cpu
            return results[cpu] ?: SdotResult.PASSED
        }
    }

    // Kryo 485 under a kernel that does not say dotprod: SDOT on cpuinfo's word, tried first
    private val sd855 = CpuFacts(
        arm64 = true, hwcap = 0xff, hwcap2 = 0,
        cores = (0..7).map { CoreFacts(it, Midr.of(0x51, 0xd, if (it < 4) 0x805 else 0x804, 0xe), null, if (it < 4) 1785600L else 2841600L) },
    )
    private val sixT = CpuFacts(
        arm64 = true, hwcap = 0xff, hwcap2 = 0,
        cores = (0..7).map { CoreFacts(it, Midr.of(0x51, if (it < 4) 0x7 else 0x6, if (it < 4) 0x803 else 0x802, 0), null, if (it < 4) 1766400L else 2803200L) },
    )

    private var probes = 0
    private fun probe(facts: CpuFacts): () -> CpuFacts = { probes++; facts }

    private fun profiles(store: ProfileStore, checks: SdotChecks?, facts: CpuFacts = sd855, key: String = KEY, trialKey: String = key) =
        DeviceProfiles(store, key, probe(facts), checks, cpuset = { "0-7" }, trialKey = trialKey)

    @Test
    fun aProfileIsDecidedOnceAndReadBackWhileTheKeyHolds() {
        val store = MapStore()
        val logs = ArrayList<String>()
        CoreLog.sink = { logs += it }
        try {
            val first = profiles(store, FakeChecks(), sixT)
            val profile = first.get()
            assertSame(profile, first.get())
            assertEquals(ModelLevel.MEDIUM, profile.autoLevel)
            // a later start reads it: the facts of the moment are not asked
            assertEquals(profile, profiles(store, FakeChecks(), sixT.copy(cores = emptyList())).get())
            assertEquals(1, probes)
            assertEquals(listOf("probed", "stored"), logs.map { it.substringAfter("source=").substringBefore(' ') })
            assertTrue(logs[1], logs[1].endsWith("cpuset=0-7"))
            // an app or system update decides anew
            assertEquals(LevelReason.UNREAD, profiles(store, FakeChecks(), sixT.copy(cores = emptyList()), key = "other").get().reason)
            assertEquals(2, probes)
            // a stored profile not read is decided anew
            store.values[DeviceProfiles.PROFILE] = "garbage"
            store.values[DeviceProfiles.KEY] = KEY
            assertEquals(profile, profiles(store, FakeChecks(), sixT).get())
            assertEquals(3, probes)
        } finally {
            CoreLog.sink = null
        }
    }

    @Test
    fun sdotTheKernelSaysIsNotTried() {
        val checks = FakeChecks()
        val profile = profiles(MapStore(), checks, sd855.copy(hwcap = DeviceProfile.HWCAP_ASIMDDP)).get()
        assertEquals(Kernel.SDOT, profile.kernel)
        assertEquals(SdotTrial.NONE, profile.trial)
        assertTrue(checks.ran.isEmpty())
    }

    @Test
    fun sdotByMidrIsTriedOnEveryCoreWithTheTrialStoredFirstAndTheThreadLeftOnEveryCore() {
        val store = MapStore()
        val checks = FakeChecks().apply { mask = 0x0f }
        val profile = profiles(store, checks).get()
        assertEquals(Kernel.SDOT, profile.kernel)
        assertEquals(SdotTrial.PASSED, profile.trial)
        assertEquals((0..7).toList(), checks.ran)
        // every possible core, not the 0-3 it had: a mask read back lacks the cores isolated or offline then
        assertEquals(0xffL, checks.mask)
        // the level and the trial written together before it, the trial cleared after
        assertEquals(KEY, store.writes.first()[DeviceProfiles.TRIAL])
        assertEquals(ModelLevel.MEDIUM, DeviceProfile.decode(store.writes.first().getValue(DeviceProfiles.PROFILE)!!)!!.autoLevel)
        assertNull(store.values[DeviceProfiles.TRIAL])
        assertEquals(profile, profiles(store, FakeChecks()).get())
    }

    // a trial whose mark did not reach the disk is not run: its death would go unseen and come back each start
    @Test
    fun noTrialRunsWithoutItsMarkWritten() {
        val checks = FakeChecks()
        val profile = profiles(MapStore().apply { fails = true }, checks).get()
        assertEquals(Kernel.MLAL, profile.kernel)
        assertEquals(SdotTrial.PENDING, profile.trial)
        assertTrue(checks.ran.isEmpty())
    }

    // an app update changes the key, not the trial's: a trial that died is not run again until the system changes
    @Test
    fun aTrialThatDiedIsNotRunAgainAfterAnAppUpdate() {
        val store = MapStore()
        try {
            profiles(store, FakeChecks(dies = 5), trialKey = "system").get()
            fail("the trial died")
        } catch (e: IllegalStateException) {
            assertEquals("SIGILL", e.message)
        }
        val updated = FakeChecks()
        val profile = profiles(store, updated, key = "app 2", trialKey = "system").get()
        assertEquals(Kernel.MLAL, profile.kernel)
        assertEquals(SdotTrial.FAILED, profile.trial)
        assertTrue(updated.ran.isEmpty())
        assertNull(store.values[DeviceProfiles.TRIAL])
        assertEquals(SdotTrial.FAILED, profiles(store, FakeChecks(), key = "app 3", trialKey = "system").get().trial)
        // a system update tries again
        val newSystem = FakeChecks()
        assertEquals(SdotTrial.PASSED, profiles(store, newSystem, key = "app 4", trialKey = "system 2").get().trial)
        assertEquals((0..7).toList(), newSystem.ran)
    }

    // a process that died in its trial (SIGILL) left the trial behind: MLAL for good, and no second try
    @Test
    fun aTrialLeftBehindIsMlalForGood() {
        val store = MapStore()
        try {
            profiles(store, FakeChecks(dies = 5)).get()
            fail("the trial died")
        } catch (e: IllegalStateException) {
            assertEquals("SIGILL", e.message)
        }
        assertEquals(KEY, store.values[DeviceProfiles.TRIAL])
        val checks = FakeChecks()
        val profile = profiles(store, checks).get()
        assertEquals(Kernel.MLAL, profile.kernel)
        assertEquals(SdotTrial.FAILED, profile.trial)
        assertEquals(ModelLevel.MEDIUM, profile.autoLevel)
        assertTrue(checks.ran.isEmpty())
        assertNull(store.values[DeviceProfiles.TRIAL])
        assertEquals(profile, profiles(store, FakeChecks()).get())
    }

    // a core out of the cpuset, offline, or one whose kernel ran the test elsewhere: untested, tried by the next process,
    // MLAL for good after three; the level never moves
    @Test
    fun aCoreTheTrialCannotReachIsTriedAgainThreeTimes() {
        for (checks in listOf({ FakeChecks(reachable = (0..6).toSet()) }, { FakeChecks(results = mapOf(6 to SdotResult.ELSEWHERE)) })) {
            val store = MapStore()
            probes = 0
            for (run in 1..DeviceProfiles.UNTESTED_RUNS) {
                val made = checks().apply { mask = 0x3c }
                val profile = profiles(store, made).get()
                assertEquals(Kernel.MLAL, profile.kernel)
                assertEquals(run, profile.untested)
                assertEquals(if (run < DeviceProfiles.UNTESTED_RUNS) SdotTrial.PENDING else SdotTrial.FAILED, profile.trial)
                assertEquals(ModelLevel.MEDIUM, profile.autoLevel)
                assertEquals(0xffL, made.mask)
                assertNull(store.values[DeviceProfiles.TRIAL])
            }
            val last = FakeChecks()
            assertEquals(SdotTrial.FAILED, profiles(store, last).get().trial)
            assertTrue(last.ran.isEmpty())
            assertEquals(1, probes)
        }
    }

    @Test
    fun aWrongSumIsMlalForGood() {
        val profile = profiles(MapStore(), FakeChecks(results = mapOf(2 to SdotResult.FAILED))).get()
        assertEquals(Kernel.MLAL, profile.kernel)
        assertEquals(SdotTrial.FAILED, profile.trial)
    }

    // the native library did not load: nothing native runs, and the next process with it tries
    @Test
    fun withoutTheNativeLibraryTheTrialWaits() {
        val store = MapStore()
        val profile = profiles(store, null).get()
        assertEquals(SdotTrial.PENDING, profile.trial)
        assertEquals(0, profile.untested)
        assertFalse(DeviceProfiles.TRIAL in store.values)
        assertEquals(SdotTrial.PASSED, profiles(store, FakeChecks()).get().trial)
    }

    private companion object {
        const val KEY = "fingerprint/1"
    }
}
