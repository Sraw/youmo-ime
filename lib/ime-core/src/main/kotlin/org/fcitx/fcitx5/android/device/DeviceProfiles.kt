/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.core.CoreLog

/** The profile's storage: [write] is synchronous (a process may die right after it), a null value removes its key. */
interface ProfileStore {
    fun read(): Map<String, String>
    fun write(values: Map<String, String?>)
}

/** What only native code does of the self-trial; absent where the native library did not load. */
interface SdotChecks : Affinity {
    /** One SDOT dot product on the calling thread, already pinned to [cpu] alone. */
    fun sdotSelfTest(cpu: Int): SdotResult
}

enum class SdotResult { PASSED, ELSEWHERE, FAILED }

/**
 * The process's one [DeviceProfile]: stored per [key] (app and system version), so a core offline or a cpuset of
 * the moment never moves the level between starts; decided and tried only when the key changed.
 */
class DeviceProfiles(
    private val store: ProfileStore,
    private val key: String,
    private val facts: () -> CpuFacts,
    private val checks: SdotChecks?,
    private val cpuset: () -> String? = { null },
) {
    private var profile: DeviceProfile? = null

    /** Decided on the first caller's thread, which a trial leaves on every possible core. */
    @Synchronized
    fun get(): DeviceProfile = profile ?: load().also { profile = it }

    private fun load(): DeviceProfile {
        val saved = store.read()
        val stored = saved[PROFILE]?.takeIf { saved[KEY] == key }?.let(DeviceProfile::decode)
        val made = when {
            stored == null -> tried(DeviceProfile.of(facts()))
            // a trial left over: its process died in it, SIGILL on some core
            saved[TRIAL] != null -> stored.copy(kernel = Kernel.MLAL, trial = SdotTrial.FAILED).also { save(it) }
            stored.trial == SdotTrial.PENDING -> tried(stored)
            else -> stored
        }
        CoreLog.d { made.logLine(if (stored == null) "probed" else "stored", cpuset()) }
        return made
    }

    // the level is stored before the trial: a process dying in it keeps it
    private fun tried(profile: DeviceProfile): DeviceProfile {
        if (profile.trial != SdotTrial.PENDING || checks == null) return profile.also { save(it) }
        store.write(mapOf(KEY to key, PROFILE to profile.encode(), TRIAL to key))
        val result = trial(checks, profile.midrs.map { it.first }, profile.possibleMask)
        val done = when (result) {
            SdotResult.PASSED -> profile.copy(kernel = Kernel.SDOT, trial = SdotTrial.PASSED)
            SdotResult.FAILED -> profile.copy(kernel = Kernel.MLAL, trial = SdotTrial.FAILED)
            SdotResult.ELSEWHERE -> (profile.untested + 1).let { runs ->
                profile.copy(kernel = Kernel.MLAL, untested = runs, trial = if (runs >= UNTESTED_RUNS) SdotTrial.FAILED else SdotTrial.PENDING)
            }
        }
        return done.also { save(it) }
    }

    // ELSEWHERE where a core cannot be reached: out of this process's cpuset, offline, or isolated
    private fun trial(checks: SdotChecks, cpus: List<Int>, possible: Long): SdotResult {
        try {
            for (cpu in cpus) {
                if (cpu >= Long.SIZE_BITS || checks.set(1L shl cpu) < 0) return SdotResult.ELSEWHERE
                val result = checks.sdotSelfTest(cpu)
                if (result != SdotResult.PASSED) return result
            }
            return SdotResult.PASSED
        } finally {
            checks.release(possible)
        }
    }

    private fun save(profile: DeviceProfile) = store.write(mapOf(KEY to key, PROFILE to profile.encode(), TRIAL to null))

    companion object {
        const val KEY = "key"
        const val PROFILE = "profile"
        const val TRIAL = "sdot_trial"

        /** Processes in a row a trial may miss a core before MLAL is the verdict. */
        const val UNTESTED_RUNS = 3
    }
}
