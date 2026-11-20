/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * A field start's read-ahead ([FieldContext]): [steps] run in order on one executor thread, each reading what the
 * previous read. With [quiet], a throwing step ends the rest silently: the session rereads on the fcitx thread,
 * where it is reported, since uncaught on a bare thread it would end the app. OOM is not an Exception, not caught.
 */
internal class WarmUp(val steps: List<Step>, private val quiet: Boolean) : Runnable {
    class Step(val name: String, val read: () -> Unit)

    val names: List<String> get() = steps.map { it.name }

    override fun run() {
        if (!quiet) return steps.forEach { it.read() }
        try {
            steps.forEach { it.read() }
        } catch (_: Exception) {
            // the session reads it again as it is made
        }
    }

    companion object {
        const val PINYIN_WORDS = "pinyin's data and word index"
        const val CHAR_SYLLABLES = "characters' readings for 联想"
        const val EMOJI = "emoji"
        const val CHAIZI = "characters by their parts"
        const val CHAR_READINGS = "characters' readings for 笔画查字"
        const val ENGLISH = "English's keys and word index"
    }
}
