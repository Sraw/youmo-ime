/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

/** MIDR_EL1's fields and the cores the profile knows by them (part numbers as pytorch/cpuinfo's `src/arm/uarch.c` has them). */
object Midr {
    fun of(implementer: Long, variant: Long, part: Long, revision: Long): Long =
        (implementer and 0xff shl 24) or (variant and 0xf shl 20) or (0xfL shl 16) or (part and 0xfff shl 4) or (revision and 0xf)

    fun variant(midr: Long): Int = (midr shr 20 and 0xf).toInt()

    /** Implementer and part, the rest masked off. */
    fun core(midr: Long): Long = midr and 0xff00fff0

    private const val ARM = 0x41L
    private const val HISILICON = 0x48L
    private const val QUALCOMM = 0x51L
    private const val SAMSUNG = 0x53L

    private fun id(implementer: Long, part: Long) = of(implementer, 0, part, 0) and 0xff00fff0

    private val A55 = id(ARM, 0xd05)
    private val A75 = id(ARM, 0xd0a)

    // cpuinfo's MIDR list for kernels that do not report ASIMDDP, aarch64-isa.c at fd8b9121, but the server cores
    private val DOTPROD = setOf(
        id(ARM, 0xd0b), // Cortex-A76
        id(ARM, 0xd0d), // Cortex-A77
        id(ARM, 0xd0e), // Cortex-A76AE
        id(HISILICON, 0xd40), // Cortex-A76 (HiSilicon)
        id(QUALCOMM, 0x804), // Kryo 485 Gold
        id(QUALCOMM, 0x805), // Kryo 485 Silver
        id(SAMSUNG, 0x003), // Exynos M4
        id(SAMSUNG, 0x004), // Exynos M5
    )

    /** Whether cpuinfo counts this core as having SDOT where the kernel does not say: A55 from r1, A75 from r2. */
    fun dotprod(midr: Long): Boolean = when (core(midr)) {
        A55 -> variant(midr) >= 1
        A75 -> variant(midr) >= 2
        else -> core(midr) in DOTPROD
    }

    // a big core of these makes the auto level high: A77 and newer, and Qualcomm's Oryon
    private val NEW_BIG = setOf(
        id(ARM, 0xd0d), // Cortex-A77
        id(ARM, 0xd41), // Cortex-A78
        id(ARM, 0xd4b), // Cortex-A78C
        id(ARM, 0xd44), // Cortex-X1
        id(ARM, 0xd4c), // Cortex-X1C (Linux's cputype.h; cpuinfo does not list it)
        id(ARM, 0xd47), // Cortex-A710
        id(ARM, 0xd48), // Cortex-X2
        id(ARM, 0xd4d), // Cortex-A715
        id(ARM, 0xd4e), // Cortex-X3
        id(ARM, 0xd81), // Cortex-A720
        id(ARM, 0xd82), // Cortex-X4
        id(ARM, 0xd85), // Cortex-X925
        id(ARM, 0xd87), // Cortex-A725
        id(ARM, 0xd8b), // C1-Pro
        id(ARM, 0xd8c), // C1-Ultra
        id(ARM, 0xd90), // C1-Premium
        id(ARM, 0xd96), // C2-Ultra
        id(QUALCOMM, 0x001), // Oryon
        id(QUALCOMM, 0x002), // Oryon v3
        id(QUALCOMM, 0x003), // Oryon v4
    )

    fun newBig(midr: Long): Boolean = core(midr) in NEW_BIG

    // in-order little cores: a phone of these alone gets the low level
    private val LITTLE = setOf(
        id(ARM, 0xd04), // Cortex-A35
        id(ARM, 0xd03), // Cortex-A53
        id(ARM, 0xd05), // Cortex-A55
        id(ARM, 0xd46), // Cortex-A510
        id(ARM, 0xd80), // Cortex-A520
        id(QUALCOMM, 0x801), // Kryo 260/280 Silver (A53)
        id(QUALCOMM, 0x803), // Kryo 385 Silver (A55)
        id(QUALCOMM, 0x805), // Kryo 485 Silver (A55)
    )

    fun little(midr: Long): Boolean = core(midr) in LITTLE
}
