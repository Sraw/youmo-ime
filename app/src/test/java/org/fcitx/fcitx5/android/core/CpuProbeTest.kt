/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.device.CoreFacts
import org.fcitx.fcitx5.android.device.CorePin
import org.fcitx.fcitx5.android.device.DeviceProfile
import org.fcitx.fcitx5.android.device.Kernel
import org.fcitx.fcitx5.android.device.LevelReason
import org.fcitx.fcitx5.android.device.SdotResult
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class CpuProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeNative(val caps: LongArray = longArrayOf(0xff, 0), val sdot: Int = CpuNative.SDOT_PASSED) : CpuNative {
        var mask = 0xffL
        override fun hwcaps() = caps
        override fun affinityGet() = mask
        override fun affinitySet(mask: Long): Long {
            this.mask = mask
            return 0
        }
        override fun sdotSelfTest(cpu: Int) = sdot
    }

    private fun File.put(path: String, text: String) = File(this, path).apply { parentFile!!.mkdirs() }.writeText(text)

    // the 6T (SD845): no cpu_capacity, the clusters told apart by their top frequency; cpu7 offline, read through cpuinfo it is not
    private fun sixT(root: File, offline: Int? = null) {
        root.put("sys/devices/system/cpu/possible", "0-7\n")
        val cpuinfo = StringBuilder()
        for (cpu in 0..7) {
            val dir = "sys/devices/system/cpu/cpu$cpu"
            root.put("$dir/cpufreq/cpuinfo_max_freq", if (cpu < 4) "1766400\n" else "2803200\n")
            if (cpu == offline) continue
            val (variant, part, revision) = if (cpu < 4) Triple(7, 0x803, 0xc) else Triple(6, 0x802, 0xd)
            if (cpu % 2 == 0) {
                root.put("$dir/regs/identification/midr_el1", "0x00000000%08x\n".format(0x51000000 or (variant shl 20) or 0xf0000 or (part shl 4) or revision))
            }
            cpuinfo.append("processor\t: $cpu\nCPU implementer\t: 0x51\nCPU architecture: 8\nCPU variant\t: 0x$variant\n")
                .append("CPU part\t: 0x${part.toString(16)}\nCPU revision\t: $revision\n\n")
        }
        root.put("proc/cpuinfo", cpuinfo.toString())
        root.put("proc/self/status", "Name:\tyoumo\nCpus_allowed:\tff\nCpus_allowed_list:\t0-7\n")
    }

    @Test
    fun theSixTIsMediumWithoutDotprodPinnedToItsFastCluster() {
        val root = tmp.root
        sixT(root)
        val probe = CpuProbe(root, FakeNative(), arm64 = true)
        val facts = probe.facts()
        assertEquals((0..7).toList(), facts.cores.map(CoreFacts::id))
        // regs/ where present, cpuinfo for the rest: the same value either way
        assertEquals(facts.cores[0].midr, facts.cores[1].midr)
        assertEquals(facts.cores[4].midr, facts.cores[5].midr)
        val profile = DeviceProfile.of(facts)
        assertEquals(ModelLevel.MEDIUM, profile.autoLevel)
        assertEquals(LevelReason.NO_DOTPROD, profile.reason)
        assertEquals(Kernel.MLAL, profile.kernel)
        assertEquals(0xf0L, profile.bigMask)
        assertEquals("0-7", probe.cpuset())
    }

    @Test
    fun anOfflineCoreLeavesItIncompleteAndNothingReadableIsUnread() {
        sixT(tmp.root, offline = 7)
        val profile = DeviceProfile.of(CpuProbe(tmp.root, null, arm64 = true).facts())
        assertEquals(LevelReason.OFFLINE, profile.reason)
        assertEquals(1, profile.unread)
        val empty = tmp.newFolder()
        val probe = CpuProbe(empty, null, arm64 = true)
        assertEquals(LevelReason.UNREAD, DeviceProfile.of(probe.facts()).reason)
        assertNull(probe.cpuset())
        assertEquals(0L, probe.facts().hwcap)
    }

    // a newer kernel's cpu_capacity: the big cores by it though every top frequency is alike; the native HWCAPs as given
    @Test
    fun capacitiesTellTheBigCoresAndTheNativeHwcapsAreKept() {
        val root = tmp.root
        root.put("sys/devices/system/cpu/possible", "0-7\n")
        for (cpu in 0..7) {
            val dir = "sys/devices/system/cpu/cpu$cpu"
            root.put("$dir/cpu_capacity", if (cpu < 6) "400\n" else "1024\n")
            root.put("$dir/cpufreq/cpuinfo_max_freq", "2000000\n")
            root.put("$dir/regs/identification/midr_el1", "0x00000000410fd410\n")
        }
        val caps = longArrayOf(DeviceProfile.HWCAP_ASIMDDP, DeviceProfile.HWCAP2_I8MM)
        val facts = CpuProbe(root, FakeNative(caps), arm64 = true).facts()
        assertEquals(DeviceProfile.HWCAP_ASIMDDP, facts.hwcap)
        assertEquals(DeviceProfile.HWCAP2_I8MM, facts.hwcap2)
        assertEquals(List(6) { 400L } + List(2) { 1024L }, facts.cores.map(CoreFacts::capacity))
        val profile = DeviceProfile.of(facts)
        assertEquals(0xc0L, profile.bigMask)
        assertEquals(Kernel.SDOT, profile.kernel)
        assertEquals(LevelReason.I8MM, profile.reason)
    }

    @Test
    fun theEnginesKernelForwardsEachCallAndCarriesThePin() {
        assertEquals(listOf(NativeMatrixKernel.PORTABLE, NativeMatrixKernel.MLAL, NativeMatrixKernel.SDOT), Kernel.entries.map(ThisDevice::kind))
        val calls = ArrayList<String>()
        val inner = object : MatrixKernel {
            override fun times(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, y: FloatArray) {
                calls += "times"
            }
            override fun timesMany(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                calls += "timesMany"
            }
            override fun timesManyQ8(q: ByteBuffer, scales: FloatArray, rows: Int, columns: Int, x: FloatArray, m: Int, y: FloatArray) {
                calls += "timesManyQ8"
            }
            override fun geluF(x: FloatArray, n: Int) {
                calls += "geluF"
            }
            override fun logSumsF(logits: FloatArray, m: Int, vocab: Int, out: FloatArray) {
                calls += "logSumsF"
            }
        }
        val pin = object : CorePin {
            override fun <T> run(block: () -> T): T = block()
        }
        val kernel = ThisDevice.pinned(inner, pin)
        val q = ByteBuffer.allocate(0)
        val f = FloatArray(0)
        kernel.times(q, f, 0, 0, f, f)
        kernel.timesMany(q, f, 0, 0, f, 0, f)
        kernel.timesManyQ8(q, f, 0, 0, f, 0, f)
        kernel.geluF(f, 0)
        kernel.logSumsF(f, 0, 0, f)
        assertEquals(listOf("times", "timesMany", "timesManyQ8", "geluF", "logSumsF"), calls)
        assertSame(pin, kernel.pin)
    }

    @Test
    fun theNativeAnswersMapToTheTrialsOwn() {
        assertEquals(SdotResult.PASSED, ThisDevice.checks(FakeNative()).sdotSelfTest(0))
        assertEquals(SdotResult.ELSEWHERE, ThisDevice.checks(FakeNative(sdot = CpuNative.SDOT_ELSEWHERE)).sdotSelfTest(0))
        assertEquals(SdotResult.FAILED, ThisDevice.checks(FakeNative(sdot = CpuNative.SDOT_FAILED)).sdotSelfTest(0))
        val native = FakeNative()
        val checks = ThisDevice.checks(native)
        assertEquals(0L, checks.set(0x30))
        assertEquals(0x30L, native.mask)
    }
}
