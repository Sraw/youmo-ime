/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/** What only native code reads or sets of the CPU; the probe and the pin take it so tests can fake it. */
interface CpuNative {
    /** AT_HWCAP and AT_HWCAP2, in that order; zeros where the kernel does not say. */
    fun hwcaps(): LongArray

    /**
     * The calling thread's CPU mask as the kernel reads it back, without the cores offline or isolated; 0 where unreadable.
     * For tests only: a pinned thread goes back on every possible core, never on this.
     */
    fun affinityGet(): Long

    /** Sets the calling thread's CPU mask: 0, or -1 when refused (EINVAL: no core of [mask] in the cpuset or online). */
    fun affinitySet(mask: Long): Long

    /**
     * One 16-column SDOT dot product on the calling thread, which the caller has pinned to [cpu] alone:
     * [SDOT_PASSED], [SDOT_ELSEWHERE] or [SDOT_FAILED].
     */
    fun sdotSelfTest(cpu: Int): Int

    companion object {
        /** The right sum, run on [sdotSelfTest]'s cpu. */
        const val SDOT_PASSED = 0

        /** Run on another core, which a kernel isolating cores may do despite the mask: the cpu is untested. */
        const val SDOT_ELSEWHERE = 1

        /** The wrong sum. */
        const val SDOT_FAILED = 2
    }
}
