/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.device.BigCorePin
import org.fcitx.fcitx5.android.device.CoreFacts
import org.fcitx.fcitx5.android.device.CorePin
import org.fcitx.fcitx5.android.device.CpuFacts
import org.fcitx.fcitx5.android.device.DeviceProfile
import org.fcitx.fcitx5.android.device.DeviceProfiles
import org.fcitx.fcitx5.android.device.Kernel
import org.fcitx.fcitx5.android.device.ProfileStore
import org.fcitx.fcitx5.android.device.SdotChecks
import org.fcitx.fcitx5.android.device.SdotResult
import org.fcitx.fcitx5.android.device.SdotTrial
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import timber.log.Timber
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** The CPU's raw facts, read under [root] (`/` on a device): no decision here, that is [DeviceProfile.of]'s. */
class CpuProbe(private val root: File, private val native: CpuNative?, private val arm64: Boolean) {

    fun facts(): CpuFacts {
        val caps = native?.hwcaps()
        // an offline core has no regs/ directory; /proc/cpuinfo lists the online alone, so it is a fallback, not more
        val cpuinfo by lazy { CpuFacts.midrsOfCpuinfo(read("proc/cpuinfo").orEmpty()) }
        val cores = CpuFacts.cpuList(read("sys/devices/system/cpu/possible").orEmpty()).map { cpu ->
            val dir = "sys/devices/system/cpu/cpu$cpu"
            CoreFacts(
                cpu,
                midr = number("$dir/regs/identification/midr_el1") ?: cpuinfo[cpu],
                capacity = number("$dir/cpu_capacity"),
                maxFreq = number("$dir/cpufreq/cpuinfo_max_freq"),
            )
        }
        return CpuFacts(arm64, caps?.getOrNull(0) ?: 0, caps?.getOrNull(1) ?: 0, cores)
    }

    /** This process's cpuset as `Cpus_allowed_list` says, for the log alone: it changes as the app moves between groups. */
    fun cpuset(): String? = read("proc/self/status")?.lineSequence()
        ?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()

    private fun number(path: String) = read(path)?.let(CpuFacts::number)

    // SELinux denies some of these on some devices: unreadable is unknown
    private fun read(path: String): String? = try {
        File(root, path).readText()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/** The process's [DeviceProfile], shared by the engine and the settings so the CPU is probed and tried once. */
object ThisDevice {
    private const val PREFS = "device_profile"
    private const val ROBOLECTRIC = "robolectric"
    private const val JVM = "JVM"

    /** What JVM tests get under Robolectric: nothing read, the medium level, never HostCore's default high. */
    val TEST_FACTS = CpuFacts(arm64 = false, hwcap = 0, hwcap2 = 0, cores = emptyList())

    @Volatile
    private var made: DeviceProfiles? = null

    var profiles: DeviceProfiles
        get() = made ?: synchronized(this) { made ?: create(appContext).also { made = it } }
        // under the lock: a warm-up creating the profiles meanwhile would replace the test's
        @VisibleForTesting set(value) = synchronized(this) {
            made = value
            decided = false
        }

    @Volatile
    private var decided = false

    /** Decided on the caller's thread the first time: a few milliseconds, the SDOT trial included. */
    val profile: DeviceProfile get() = profiles.get().also { if (!decided) decided = true }

    /**
     * Decides [profile] on a thread of its own, so a settings page asking next need not probe and try on the main thread;
     * none once decided (each rotation calls it).
     */
    fun warm(): Thread? {
        if (decided) return null
        return thread(name = "device-profile") {
            runCatching { profile }.onFailure { Timber.w(it, "device profile not decided early") }
        }
    }

    @VisibleForTesting
    fun forget() = synchronized(this) {
        made = null
        decided = false
    }

    @Volatile
    private var madeKernel: MatrixKernel? = null

    @Volatile
    private var usedKernel: String? = null

    /** The int8 loop [kernel] runs (`JVM` without the library), for the about page: what the native side took once made. */
    val kernelName: String
        get() = usedKernel ?: if (native() == null) JVM else profile.kernel.name

    /** The engine's kernel: the profile's int8 loop, its stretches pinned to the big cores; [MatrixKernel.JVM] without the library. */
    var kernel: MatrixKernel
        get() = madeKernel ?: synchronized(this) { madeKernel ?: makeKernel().also { madeKernel = it } }
        @VisibleForTesting set(value) {
            madeKernel = value
        }

    private fun makeKernel(): MatrixKernel {
        val native = native() ?: return MatrixKernel.JVM.also { usedKernel = JVM }
        val profile = profile
        val used = NativeMatrixKernel.useKernel(kind(profile.kernel), profile.trial == SdotTrial.PASSED)
        usedKernel = nameOf(used)
        Timber.d("kernel %d for %s, big cores 0x%x", used, profile.kernel, profile.bigMask)
        return pinned(NativeMatrixKernel, BigCorePin(checks(native), profile.bigMask, profile.possibleMask))
    }

    /** [NativeMatrixKernel.useKernel]'s answer as the about page names it: a number it does not know shown as it is. */
    @VisibleForTesting
    fun nameOf(used: Int): String = Kernel.entries.firstOrNull { kind(it) == used }?.name ?: used.toString()

    fun kind(kernel: Kernel) = when (kernel) {
        Kernel.PORTABLE -> NativeMatrixKernel.PORTABLE
        Kernel.MLAL -> NativeMatrixKernel.MLAL
        Kernel.SDOT -> NativeMatrixKernel.SDOT
    }

    // each call forwarded by hand, not `by`: a delegate compiled against an older MatrixKernel once threw AbstractMethodError
    fun pinned(kernel: MatrixKernel, pin: CorePin): MatrixKernel = object : MatrixKernel {
        override val pin = pin

        override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) =
            kernel.times(q, scales, rows, columns, x, y)

        override fun timesMany(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) =
            kernel.timesMany(q, scales, rows, columns, x, m, y)

        override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) =
            kernel.timesManyQ8(q, scales, rows, columns, x, m, y)

        override fun geluF(x: FloatArray, n: Int) = kernel.geluF(x, n)

        override fun logSumsF(logits: FloatArray, m: Int, vocab: Int, out: FloatArray) = kernel.logSumsF(logits, m, vocab, out)
    }

    /** The library's [CpuNative], null where it did not load (the JVM kernel runs, nothing native is asked). */
    private fun native(): CpuNative? = NativeMatrixKernel.loaded as? CpuNative

    private fun create(context: Context): DeviceProfiles {
        val native = native()
        val probe = CpuProbe(File("/"), native, Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        // device-protected: the same profile before the first unlock and after
        val store = PrefsProfileStore(context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        // JVM tests: never the host's /sys and /proc, which differ from machine to machine
        if (Build.FINGERPRINT == ROBOLECTRIC) return DeviceProfiles(store, key(), { TEST_FACTS }, null)
        return DeviceProfiles(store, key(), probe::facts, native?.let(::checks), probe::cpuset, trialKey = Build.FINGERPRINT)
    }

    // an app or system update may bring another kernel or other rules; a debug build's commit, rules changed in development
    private fun key() = "${Build.FINGERPRINT}|${BuildConfig.VERSION_CODE}|${BuildConfig.BUILD_GIT_HASH}"

    fun checks(native: CpuNative): SdotChecks = object : SdotChecks {
        override fun set(mask: Long) = native.affinitySet(mask)
        override fun sdotSelfTest(cpu: Int) = when (native.sdotSelfTest(cpu)) {
            CpuNative.SDOT_PASSED -> SdotResult.PASSED
            CpuNative.SDOT_ELSEWHERE -> SdotResult.ELSEWHERE
            else -> SdotResult.FAILED
        }
    }
}

/** [ProfileStore] on [prefs]: values not strings are not the profile's and read as absent. */
class PrefsProfileStore(private val prefs: SharedPreferences) : ProfileStore {
    override fun read(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    // commit, not apply: the SDOT trial's mark must be on disk before an instruction that may kill the process
    override fun write(values: Map<String, String?>): Boolean =
        prefs.edit().apply { values.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) } }.commit()
}
