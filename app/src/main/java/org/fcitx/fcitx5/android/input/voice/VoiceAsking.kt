/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.editing.EditingSession
import org.fcitx.fcitx5.android.input.learning.UndoChip

/** What the engine asks is shown above the keyboard where the field may be read ([sensitive] not, input [started]). */
class VoiceAsking(
    private val service: FcitxInputMethodService,
    private val voiceAsk: VoiceAsk,
    private val session: EditingSession,
    private val sensitive: () -> Boolean,
    private val started: () -> Boolean,
) {

    /** Whether the engine is given voice's text here, its talks' stretches too. */
    val asks: Boolean get() = AppPrefs.getInstance().internal.voiceAskRemember.getValue() && !sensitive()

    // read before the cursor now that it is in; elsewhere the engine is not given voice's text at all
    fun tell(text: String) {
        val prefs = AppPrefs.getInstance().internal
        val asking = asks
        voiceAsk.told(asking)
        if (!asking) {
            service.postFcitxJob { voiceEditsEnd() }
            return
        }
        val before = service.currentInputConnection?.getTextBeforeCursor(FcitxInputMethodService.ContextChars, 0)?.toString()
        // the engine not looking for it again
        val hinted = prefs.voiceAskHintShown.getValue()
        service.postFcitxJob { voiceSaid(text, before, hinted) }
    }

    // typed while a talk goes on, the following ended: the talk's text told once it is over
    fun put(text: String) {
        when (voiceAsk.putIn(text)) {
            VoiceAsk.Put.REPORT -> reportVoicePut(text)
            VoiceAsk.Put.END -> service.postFcitxJob { voiceEditsEnd() }
            VoiceAsk.Put.NONE -> Unit
        }
    }

    // shown once the field reads as the engine has it. The field read only while still the one [text] went in
    // (onFinishInput resets the window) and not a password's; its code in it not read as text
    private fun reportVoicePut(text: String) {
        val window = voiceAsk.current
        service.postFcitxJob {
            val reply = voicePutIn(text)
            val ask = reply.ask
            // nothing for the main thread
            if (reply.open && ask == null) return@postFcitxJob
            val read = withContext(Dispatchers.Main) {
                if (!reply.open) voiceAsk.shut(window)
                val ic = service.currentInputConnection
                if (ask == null || ic == null || !voiceFieldStill(window)) null
                else session.beforeCode(ic.getTextBeforeCursor(FcitxInputMethodService.ContextChars, 0))?.toString() to
                    session.afterCode(ic.getTextAfterCursor(FcitxInputMethodService.ContextChars, 0))?.toString()
            } ?: return@postFcitxJob
            val confirmed = voiceConfirm(read.first, read.second) ?: return@postFcitxJob
            withContext(Dispatchers.Main) { if (voiceFieldStill(window)) askVoiceWord(confirmed) }
        }
    }

    private fun voiceFieldStill(window: Int) = voiceAsk.current == window && started() && !sensitive()

    private fun askVoiceWord(ask: VoiceEdits.Ask) {
        val prefs = AppPrefs.getInstance().internal
        if (!prefs.voiceAskRemember.getValue()) return
        if (ask.hint) {
            if (prefs.voiceAskHintShown.getValue()) return
            prefs.voiceAskHintShown.setValue(true)
            service.say(R.string.voice_ask_hint)
            return
        }
        prefs.voiceAskShown.setValue(prefs.voiceAskShown.getValue() + 1)
        if (ask.always) {
            service.say(service.getString(R.string.voice_ask_always, ask.heard, ask.word), UndoChip.Undo({ rememberVoiceWord(ask) }, action = R.string.voice_ask_always_action))
        } else {
            service.say(service.getString(R.string.voice_ask_remember, ask.word), UndoChip.Undo({ rememberVoiceWord(ask) }, action = R.string.voice_ask_remember_action))
        }
    }

    // the word added drops the engine's sessions: the bar's 联想 of the old one reset away, not left to tap
    private fun rememberVoiceWord(ask: VoiceEdits.Ask) {
        service.postFcitxJob {
            val added = voiceRemember(ask)
            // a commit, not a drop: text written meanwhile is the user's to keep
            if (added) reset()
            withContext(Dispatchers.Main) {
                if (!added) return@withContext service.say(R.string.voice_ask_failed)
                val taken = AppPrefs.getInstance().internal.voiceAskTaken
                taken.setValue(taken.getValue() + 1)
                service.say(service.getString(if (ask.always) R.string.voice_ask_always_done else R.string.voice_ask_remembered, ask.word), UndoChip.Undo({
                    taken.setValue(maxOf(0, taken.getValue() - 1))
                    service.postFcitxJob { voiceForget(ask) }
                }))
            }
        }
    }
}
