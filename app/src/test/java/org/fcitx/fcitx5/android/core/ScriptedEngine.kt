/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.EngineHost
import java.io.File
import java.util.Collections

/** Pinyin as far as a case needs: the letters typed are the preedit, two candidates, a pick commits one. */
class ScriptedEngine(private val home: File) : KotlinHost.EngineSide, EngineHost {
    val events: MutableList<Int> = Collections.synchronizedList(ArrayList())
    val settings: MutableList<EngineSettings> = Collections.synchronizedList(ArrayList())
    private var typed = ""

    override val host: EngineHost get() = this
    // fcitx's data under the home, as fcitx kept it
    override fun data() = HostConfig(File(home, "data"), writable = true)

    // the config under the home, as fcitx kept it (an older build's tree of files reads in place)
    override fun config() = HostConfig(File(home, "config"), writable = true)
    override fun start() = Unit
    override fun close() = Unit

    override fun event(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot {
        events += event
        var commit = ""
        when (event) {
            // a letter in handwriting stands for a character written (the pad's reading is HandwritingUatTest's)
            EngineEvent.CHAR -> if (arg !in 'a'.code..'z'.code) {
                return idle()
            } else if (im == InputMethodCatalog.HANDWRITING) {
                typed += WRITTEN
            } else {
                typed += arg.toChar()
            }
            EngineEvent.PICK -> if (typed.isNotEmpty()) {
                commit = WORDS[arg]
                typed = ""
            }
            EngineEvent.RESET -> typed = ""
            // Return: what is typed committed as it is (pinyin's raw letters)
            EngineEvent.ENTER -> if (typed.isNotEmpty()) {
                commit = typed
                typed = ""
            } else {
                return idle()
            }
            else -> return idle()
        }
        val shown = if (typed.isEmpty()) emptyList() else WORDS
        return Snapshot(commit, typed, shown, 0, false, false, handled = true, predicting = false)
    }

    private fun idle() = Snapshot("", typed, emptyList(), 0, false, false, handled = false, predicting = false)

    override fun candidates(im: String, from: Int, count: Int) =
        (if (typed.isEmpty()) emptyList() else WORDS).drop(from).take(count).map { Choice(it) }

    override fun offers(im: String, index: Int) = emptySet<Offer>()

    override fun settings(settings: EngineSettings) {
        this.settings += settings
    }

    companion object {
        val WORDS = listOf("你好", "你")

        /** What a letter typed in handwriting is written as. */
        const val WRITTEN = "中"
    }
}
