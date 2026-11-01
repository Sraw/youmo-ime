/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * What a field's start wants read ahead of its first key, as [FieldContext] hands it to the
 * platform's executor (Engines' `warmUps`): [steps] by name, run in their order on the one thread
 * the executor gives them, so each reads what the one before it read (the lazies' lock modes are
 * as they were: review B 1.7). Whatever a step throws ends the rest when [quiet]: what was not
 * read is read again as the session is made, on the fcitx thread, where it is reported; let out
 * of a bare thread it would end the app. Memory run out is no Exception, and is not caught.
 */
internal class WarmUp(val steps: List<Step>, private val quiet: Boolean) : Runnable {
    /** One of [steps]: what it reads, by [name], and the read. */
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
