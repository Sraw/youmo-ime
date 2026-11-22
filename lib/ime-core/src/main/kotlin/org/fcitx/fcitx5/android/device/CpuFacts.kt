/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

/** One possible core as read: null where its file was missing or unreadable. */
data class CoreFacts(val id: Int, val midr: Long?, val capacity: Long?, val maxFreq: Long?)

/** The raw facts the app reads of the CPU; [DeviceProfile.of] decides from them alone. */
data class CpuFacts(
    /** An arm64 process: else [hwcap] and [hwcap2] are another architecture's bits and no kernel but portable runs. */
    val arm64: Boolean,
    val hwcap: Long,
    val hwcap2: Long,
    /** Each core of `cpu/possible`, in order. */
    val cores: List<CoreFacts>,
) {
    companion object {
        /** `0-3,6`, as `cpu/possible` and `Cpus_allowed_list` say; empty where unreadable. */
        fun cpuList(text: String): List<Int> = text.trim().split(',').filter { it.isNotBlank() }.flatMap { part ->
            val range = part.trim().split('-').map { it.toIntOrNull() ?: return emptyList() }
            when (range.size) {
                1 -> range
                2 -> (range[0]..range[1]).toList()
                else -> return emptyList()
            }
        }

        /** Each core's MIDR from `/proc/cpuinfo`, which lists online cores only, by `processor` number. */
        fun midrsOfCpuinfo(text: String): Map<Int, Long> {
            val midrs = HashMap<Int, Long>()
            var cpu: Int? = null
            val fields = HashMap<String, Long>()
            fun flush() {
                val id = cpu ?: return
                val implementer = fields["CPU implementer"] ?: return
                val part = fields["CPU part"] ?: return
                midrs[id] = Midr.of(implementer, fields["CPU variant"] ?: 0, part, fields["CPU revision"] ?: 0)
            }
            for (line in text.lineSequence()) {
                val name = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                if (name == "processor") {
                    flush()
                    fields.clear()
                    cpu = value.toIntOrNull()
                } else {
                    number(value)?.let { fields[name] = it }
                }
            }
            flush()
            return midrs
        }

        /** `0x51`, `12`. */
        fun number(text: String): Long? = text.trim().let {
            if (it.startsWith("0x") || it.startsWith("0X")) it.substring(2).toLongOrNull(16) else it.toLongOrNull()
        }
    }
}
