/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// MIDR rules checked against pytorch/cpuinfo fd8b9121 (src/arm/linux/aarch64-isa.c, src/arm/uarch.c)
class DeviceProfileTest {

    private fun midr(implementer: Int, variant: Int, part: Int) = Midr.of(implementer.toLong(), variant.toLong(), part.toLong(), 0)

    private fun arm(part: Int, variant: Int = 0) = midr(0x41, variant, part)

    private val a53 = arm(0xd03)
    private val a55r1 = arm(0xd05, variant = 1)
    private val a55r0 = arm(0xd05)
    private val a76 = arm(0xd0b)
    private val a77 = arm(0xd0d)
    private val a78 = arm(0xd41)
    private val x1 = arm(0xd44)
    private val a510 = arm(0xd46)
    private val a710 = arm(0xd47)
    private val x2 = arm(0xd48)
    private val unknown = arm(0xd99)

    // the 6T's (SD845) /proc/cpuinfo: Kryo 385 Silver r7p12, Gold r6p13
    private val kryo385Silver = Midr.of(0x51, 0x7, 0x803, 12)
    private val kryo385Gold = Midr.of(0x51, 0x6, 0x802, 13)

    private fun cores(vararg midrs: Long?, frequencies: List<Long>? = null) = midrs.mapIndexed { i, midr ->
        CoreFacts(i, midr, capacity = null, maxFreq = frequencies?.get(i))
    }

    // eight cores, four little and four big by frequency
    private fun bigLittle(little: Long?, big: Long?) = cores(
        little, little, little, little, big, big, big, big,
        frequencies = listOf(1766400L, 1766400, 1766400, 1766400, 2803200, 2803200, 2803200, 2803200),
    )

    private fun facts(cores: List<CoreFacts>, hwcap: Long = BASE_HWCAP, hwcap2: Long = 0, arm64: Boolean = true) =
        CpuFacts(arm64, hwcap, hwcap2, cores)

    private val dp = BASE_HWCAP or DeviceProfile.HWCAP_ASIMDDP

    @Test
    fun theSixTIsPinnedToItsGoldCores() {
        val profile = DeviceProfile.of(facts(bigLittle(kryo385Silver, kryo385Gold)))
        assertEquals(0xf0L, profile.bigMask)
        assertTrue(profile.complete)
        assertEquals(8, profile.cores)
        assertEquals(4, profile.bigCores)
    }

    private data class Row(val name: String, val facts: CpuFacts, val level: ModelLevel, val dotprod: Dotprod, val kernel: Kernel, val reason: LevelReason)

    @Test
    fun eachKindOfCoreHasItsLevelAndKernel() = assertRows(
        listOf(
            Row("i8mm (SD 8 Gen 1)", facts(bigLittle(a510, x2), dp, DeviceProfile.HWCAP2_I8MM), ModelLevel.HIGH, Dotprod.HWCAP, Kernel.SDOT, LevelReason.I8MM),
            Row("i8mm, half the cores unread", facts(bigLittle(a510, null), dp, DeviceProfile.HWCAP2_I8MM), ModelLevel.HIGH, Dotprod.HWCAP, Kernel.SDOT, LevelReason.I8MM),
            Row("A77 big (SD 865)", facts(bigLittle(a55r1, a77), dp), ModelLevel.HIGH, Dotprod.HWCAP, Kernel.SDOT, LevelReason.NEW_BIG_CORE),
            Row("A78 big, an old kernel", facts(bigLittle(a55r1, a78)), ModelLevel.HIGH, Dotprod.NONE, Kernel.MLAL, LevelReason.NEW_BIG_CORE),
            Row("X1 big", facts(bigLittle(a55r1, x1), dp), ModelLevel.HIGH, Dotprod.HWCAP, Kernel.SDOT, LevelReason.NEW_BIG_CORE),
            Row("A710 big, no i8mm said", facts(bigLittle(a510, a710), dp), ModelLevel.HIGH, Dotprod.HWCAP, Kernel.SDOT, LevelReason.NEW_BIG_CORE),
            Row("A76 big, dotprod said (SD 855 later)", facts(bigLittle(a55r1, a76), dp), ModelLevel.MEDIUM, Dotprod.HWCAP, Kernel.SDOT, LevelReason.DOTPROD),
            Row("Kryo 485 (SD 855), not said", facts(bigLittle(midr(0x51, 0xd, 0x805), midr(0x51, 0xd, 0x804))), ModelLevel.MEDIUM, Dotprod.MIDR, Kernel.MLAL, LevelReason.DOTPROD),
            Row("Kirin 980, not said", facts(bigLittle(a55r1, midr(0x48, 0, 0xd40))), ModelLevel.MEDIUM, Dotprod.MIDR, Kernel.MLAL, LevelReason.DOTPROD),
            Row("A55 r0 beside an A76", facts(bigLittle(a55r0, a76)), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.NO_DOTPROD),
            Row("A75 r2 and A55 r1", facts(bigLittle(a55r1, arm(0xd0a, variant = 2))), ModelLevel.MEDIUM, Dotprod.MIDR, Kernel.MLAL, LevelReason.DOTPROD),
            Row("Kryo 385 (6T, SD845)", facts(bigLittle(kryo385Silver, kryo385Gold)), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.NO_DOTPROD),
            Row("A75 r1", facts(bigLittle(a55r1, arm(0xd0a, variant = 1))), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.NO_DOTPROD),
            Row("Exynos M4", facts(bigLittle(a55r1, midr(0x53, 1, 0x003))), ModelLevel.MEDIUM, Dotprod.MIDR, Kernel.MLAL, LevelReason.DOTPROD),
            Row("A53 alone", facts(cores(a53, a53, a53, a53)), ModelLevel.LOW, Dotprod.NONE, Kernel.MLAL, LevelReason.LITTLE_ONLY),
            Row("A55 r1 alone", facts(cores(a55r1, a55r1)), ModelLevel.LOW, Dotprod.MIDR, Kernel.MLAL, LevelReason.LITTLE_ONLY),
            Row("A510 and A520 alone, i8mm said", facts(cores(a510, a510, arm(0xd80), arm(0xd80)), dp, DeviceProfile.HWCAP2_I8MM), ModelLevel.LOW, Dotprod.HWCAP, Kernel.SDOT, LevelReason.LITTLE_ONLY),
            Row("Kryo 280 Silver alone", facts(cores(midr(0x51, 0xa, 0x801))), ModelLevel.LOW, Dotprod.NONE, Kernel.MLAL, LevelReason.LITTLE_ONLY),
            Row("possible 8, the little online", facts(bigLittle(a53, null)), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.OFFLINE),
            Row("an unknown core, not all read", facts(bigLittle(unknown, null), dp), ModelLevel.MEDIUM, Dotprod.HWCAP, Kernel.SDOT, LevelReason.OFFLINE),
            Row("an unknown big core", facts(bigLittle(a55r0, unknown)), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.NO_DOTPROD),
            Row("nothing read", facts(emptyList()), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.MLAL, LevelReason.UNREAD),
            Row("x86_64, its own hwcap bits", facts(cores(null, null), hwcap = -1, hwcap2 = -1, arm64 = false), ModelLevel.MEDIUM, Dotprod.NONE, Kernel.PORTABLE, LevelReason.UNREAD),
        ),
        { it.name },
    ) { row ->
        val profile = DeviceProfile.of(row.facts)
        assertEquals("level", row.level, profile.autoLevel)
        assertEquals("dotprod", row.dotprod, profile.dotprod)
        assertEquals("kernel", row.kernel, profile.kernel)
        assertEquals("reason", row.reason, profile.reason)
        assertEquals("trial", if (row.dotprod == Dotprod.MIDR) SdotTrial.PENDING else SdotTrial.NONE, profile.trial)
    }

    private class BigRow(val why: String, val cores: List<CoreFacts>, val mask: Long, val level: ModelLevel, val reason: LevelReason)

    // only the cores decided big say a CPU is strong
    @Test
    fun aNewCoreCountsOnlyAsABigOne() = assertRows(
        listOf(
            // core 65 is not core 1: the A78 there would make it HIGH
            BigRow(
                "a core past the mask's 64 bits is never big",
                listOf(CoreFacts(0, a55r1, 100, null), CoreFacts(1, unknown, 1024, null), CoreFacts(65, a78, null, null)),
                0b10L, ModelLevel.MEDIUM, LevelReason.DOTPROD,
            ),
            BigRow(
                "an A78 under the midpoint beside an unknown big core is no sign",
                listOf(CoreFacts(0, a55r1, 100, null), CoreFacts(1, a78, 300, null), CoreFacts(2, unknown, 1024, null)),
                0b100L, ModelLevel.MEDIUM, LevelReason.DOTPROD,
            ),
            BigRow("all alike: every core counts", cores(a78, a78), 0L, ModelLevel.HIGH, LevelReason.NEW_BIG_CORE),
        ),
        { it.why },
    ) { row ->
        val profile = DeviceProfile.of(facts(row.cores, dp))
        assertEquals("mask", row.mask, profile.bigMask)
        assertEquals("level", row.level, profile.autoLevel)
        assertEquals("reason", row.reason, profile.reason)
    }

    @Test
    fun theBigCoresAreAboveTheMidpointOfCapacityThenOfFrequency() {
        fun mask(vararg cores: Pair<Long?, Long?>) = DeviceProfile.bigMask(cores.mapIndexed { i, (capacity, freq) -> CoreFacts(i, null, capacity, freq) })
        assertEquals(0b1100L, mask(400L to 1L, 400L to 1L, 1024L to 1L, 1000L to 1L))
        // the middle cluster of three above the midpoint too
        assertEquals(0b110L, mask(200L to null, 700L to null, 1024L to null))
        // capacities all alike: the top frequencies decide
        assertEquals(0b10L, mask(1024L to 1800L, 1024L to 2800L))
        assertEquals(0L, mask(1024L to 2800L, 1024L to 2800L))
        assertEquals(0L, mask(null to null, null to null))
        // a core not read is not big
        assertEquals(0b1L, mask(null to 2800L, null to null, null to 1800L))
    }

    @Test
    fun aStoredProfileReadsBackAndAnythingElseIsNone() {
        val profile = DeviceProfile.of(facts(bigLittle(kryo385Silver, null))).copy(trial = SdotTrial.PENDING, untested = 2)
        assertEquals(profile, DeviceProfile.decode(profile.encode()))
        val sixT = DeviceProfile.of(facts(bigLittle(kryo385Silver, kryo385Gold)))
        assertEquals(sixT, DeviceProfile.decode(sixT.encode()))
        assertNull(DeviceProfile.decode(""))
        assertNull(DeviceProfile.decode("version=1\nauto=MAX"))
        assertNull(DeviceProfile.decode(sixT.encode().replace("version=1", "version=0")))
    }

    @Test
    fun theLogLineSaysWhatWasDecidedAndWhence() {
        val line = DeviceProfile.of(facts(bigLittle(kryo385Silver, null))).logLine("probed", "0-7")
        assertTrue(line, line.startsWith("device: auto=MEDIUM kernel=MLAL dotprod=NONE big=0xf0 complete=false source=probed midr=[0:517f803c,"))
        assertTrue(line, line.endsWith("7:?] cpuset=0-7"))
        assertEquals(4, DeviceProfile.of(facts(bigLittle(kryo385Silver, null))).unread)
    }

    @Test
    fun cpuinfoAndCpuListsAreRead() {
        assertEquals(listOf(0, 1, 2, 3, 6), CpuFacts.cpuList("0-3,6\n"))
        assertEquals(emptyList<Int>(), CpuFacts.cpuList(""))
        assertEquals(emptyList<Int>(), CpuFacts.cpuList("0-x"))
        assertEquals(emptyList<Int>(), CpuFacts.cpuList("1-2-3"))
        val text = """
            processor	: 0
            BogoMIPS	: 38.40
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid
            CPU implementer	: 0x51
            CPU architecture: 8
            CPU variant	: 0x7
            CPU part	: 0x803
            CPU revision	: 12

            processor	: 4
            CPU implementer	: 0x51
            CPU architecture: 8
            CPU variant	: 0x6
            CPU part	: 0x802
            CPU revision	: 13

            processor	: 5
            CPU architecture: 8

            Hardware	: Qualcomm Technologies, Inc SDM845
        """.trimIndent()
        assertEquals(mapOf(0 to kryo385Silver, 4 to kryo385Gold), CpuFacts.midrsOfCpuinfo(text))
        assertEquals(0x517f803cL, kryo385Silver)
        assertEquals(0x51L, CpuFacts.number("0X51"))
        assertNull(CpuFacts.number("x"))
        assertFalse(Midr.dotprod(kryo385Gold))
    }

    private companion object {
        // fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm: no ASIMDDP
        const val BASE_HWCAP = 0x1fffL
    }
}
