/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.engine.runtime.ModelLevel

/** The native int8 kernel the matrix code runs: it changes speed only, never a sum. */
enum class Kernel { PORTABLE, MLAL, SDOT }

/** Where SDOT was found: the kernel's word, cpuinfo's MIDR list (to be tried on each core first), or nowhere. */
enum class Dotprod { HWCAP, MIDR, NONE }

/** The MIDR route's trial on each core: not needed, passed, to be tried again by the next process, or refused for good. */
enum class SdotTrial { NONE, PASSED, PENDING, FAILED }

/** Why [DeviceProfile.autoLevel] is what it is, kept with the profile; the about page words only OFFLINE and UNREAD: the rest would mean nothing to a user. */
enum class LevelReason { I8MM, NEW_BIG_CORE, LITTLE_ONLY, OFFLINE, UNREAD, NO_DOTPROD, DOTPROD }

/** What the device is decided to be, once per app and system version; [of] from the facts alone. */
data class DeviceProfile(
    val autoLevel: ModelLevel,
    val kernel: Kernel,
    val dotprod: Dotprod,
    /** The big cores to pin typing's computation to; 0 for none (all alike). */
    val bigMask: Long,
    /** Every possible core's MIDR was read. */
    val complete: Boolean,
    val reason: LevelReason,
    /** Each possible core and its MIDR, null where unread. */
    val midrs: List<Pair<Int, Long?>>,
    val trial: SdotTrial = SdotTrial.NONE,
    /** Processes in a row whose trial could not reach every core. */
    val untested: Int = 0,
) {
    val cores: Int get() = midrs.size
    val bigCores: Int get() = java.lang.Long.bitCount(bigMask)
    val unread: Int get() = midrs.count { it.second == null }

    /** Every possible core, what a pinned thread is put back on. */
    val possibleMask: Long get() = midrs.fold(0L) { mask, (cpu, _) -> if (cpu < Long.SIZE_BITS) mask or (1L shl cpu) else mask }

    fun logLine(source: String, cpuset: String?): String =
        "device: auto=$autoLevel kernel=$kernel dotprod=$dotprod big=0x${bigMask.toString(16)} complete=$complete " +
            "source=$source midr=[${midrs.joinToString(",") { (cpu, midr) -> "$cpu:${midr?.toString(16) ?: "?"}" }}] cpuset=${cpuset ?: "?"}"

    fun encode(): String = listOf(
        "version" to VERSION, "auto" to autoLevel.name, "kernel" to kernel.name, "dotprod" to dotprod.name,
        "big" to bigMask.toString(), "complete" to complete.toString(), "reason" to reason.name,
        "midr" to midrs.joinToString(",") { (cpu, midr) -> "$cpu:${midr ?: ""}" },
        "trial" to trial.name, "untested" to untested.toString(),
    ).joinToString("\n") { (k, v) -> "$k=$v" }

    companion object {
        private const val VERSION = "1"
        const val HWCAP_ASIMDDP = 1L shl 20
        const val HWCAP2_I8MM = 1L shl 13

        /** What a stored [encode] says; null for anything else, which is decided anew. */
        fun decode(text: String): DeviceProfile? = runCatching {
            val map = text.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (map["version"] != VERSION) return null
            DeviceProfile(
                autoLevel = ModelLevel.valueOf(map.getValue("auto")),
                kernel = Kernel.valueOf(map.getValue("kernel")),
                dotprod = Dotprod.valueOf(map.getValue("dotprod")),
                bigMask = map.getValue("big").toLong(),
                complete = map.getValue("complete").toBooleanStrict(),
                reason = LevelReason.valueOf(map.getValue("reason")),
                midrs = map.getValue("midr").split(',').filter { it.isNotEmpty() }.map { core ->
                    core.substringBefore(':').toInt() to core.substringAfter(':').takeIf { it.isNotEmpty() }?.toLong()
                },
                trial = SdotTrial.valueOf(map.getValue("trial")),
                untested = map.getValue("untested").toInt(),
            )
        }.getOrNull()

        /** The decision before any trial: a MIDR-only SDOT runs MLAL until [DeviceProfiles] has tried it on each core. */
        fun of(facts: CpuFacts): DeviceProfile {
            val hwcap = if (facts.arm64) facts.hwcap else 0
            val hwcap2 = if (facts.arm64) facts.hwcap2 else 0
            val cores = facts.cores
            val complete = cores.isNotEmpty() && cores.all { it.midr != null }
            val read = cores.mapNotNull { it.midr }
            val dotprod = when {
                !facts.arm64 -> Dotprod.NONE
                hwcap and HWCAP_ASIMDDP != 0L -> Dotprod.HWCAP
                complete && read.all(Midr::dotprod) -> Dotprod.MIDR
                else -> Dotprod.NONE
            }
            val bigMask = bigMask(cores)
            val big = cores.filter { bigMask == 0L || it.id < Long.SIZE_BITS && bigMask and (1L shl it.id) != 0L }.mapNotNull { it.midr }
            val (level, reason) = level(hwcap2 and HWCAP2_I8MM != 0L, big, read, complete, dotprod)
            val kernel = when {
                !facts.arm64 -> Kernel.PORTABLE
                dotprod == Dotprod.HWCAP -> Kernel.SDOT
                else -> Kernel.MLAL
            }
            return DeviceProfile(
                level, kernel, dotprod, bigMask, complete, reason, cores.map { it.id to it.midr },
                trial = if (dotprod == Dotprod.MIDR) SdotTrial.PENDING else SdotTrial.NONE,
            )
        }

        private fun level(i8mm: Boolean, big: List<Long>, read: List<Long>, complete: Boolean, dotprod: Dotprod) = when {
            // before i8mm: A510 and A520 have it, and are in-order little cores
            complete && read.all(Midr::little) -> ModelLevel.LOW to LevelReason.LITTLE_ONLY
            i8mm -> ModelLevel.HIGH to LevelReason.I8MM
            big.any(Midr::newBig) -> ModelLevel.HIGH to LevelReason.NEW_BIG_CORE
            // never low on part of the cores: the unread may be the big ones
            read.isEmpty() -> ModelLevel.MEDIUM to LevelReason.UNREAD
            !complete -> ModelLevel.MEDIUM to LevelReason.OFFLINE
            dotprod == Dotprod.NONE -> ModelLevel.MEDIUM to LevelReason.NO_DOTPROD
            else -> ModelLevel.MEDIUM to LevelReason.DOTPROD
        }

        /** Cores above the midpoint of `cpu_capacity`, or of the top frequency where capacities are all alike; 0 where both are. */
        fun bigMask(cores: List<CoreFacts>): Long = listOf(CoreFacts::capacity, CoreFacts::maxFreq).firstNotNullOfOrNull { source ->
            val known = cores.filter { it.id < Long.SIZE_BITS }.mapNotNull { core -> source(core)?.let { core.id to it } }
            val low = known.minOfOrNull { it.second }
            val high = known.maxOfOrNull { it.second }
            if (low == null || high == null || low == high) null
            else known.filter { 2 * it.second > low + high }.fold(0L) { mask, (id, _) -> mask or (1L shl id) }
        } ?: 0
    }
}
