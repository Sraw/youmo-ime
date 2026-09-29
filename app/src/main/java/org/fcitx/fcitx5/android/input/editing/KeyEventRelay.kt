/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.util.LruCache
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Round trip of a hardware key through fcitx. The key goes to the engine as a (sym, states,
 * timestamp) triple; if the engine hands it back unconsumed, the editor should get the
 * original Android [KeyEvent] rather than one rebuilt from the sym, which loses the device,
 * scan code, flags and repeat count. So each event is [remember]ed under a number that travels
 * to fcitx as the timestamp, and [replay]ed when fcitx returns that number.
 *
 * Main thread only.
 */
class KeyEventRelay(private val connection: () -> InputConnection?) {

    private val cache = LruCache<Int, KeyEvent>(CAPACITY)
    private var nextId = 0

    /** Meta state of a hardware keyboard with sticky modifiers, to clear them in order. */
    private val stickyMetaState = StickyMetaState()

    /**
     * @return the number to hand to fcitx as this key's timestamp. A counter rather than
     * `event.eventTime`: a key's down and up can carry the same time.
     */
    fun remember(event: KeyEvent): Int {
        val id = nextId++
        cache.put(id, event)
        return id
    }

    /**
     * Send the event remembered under [id] to the editor.
     *
     * @return false if there is none (evicted, already replayed, or the key did not come from
     * [remember]); the caller then has to make up an event of its own.
     */
    fun replay(id: Int): Boolean {
        val event = cache.remove(id) ?: return false
        val ic = connection()
        if (ForwardedKeys.opensCharacterPicker(event.unicodeChar)) {
            // re-sent as a virtual-keyboard key (deviceId -1), whose character no longer maps
            // to PICKER_DIALOG_INPUT: the key still reaches the editor, but the default
            // QwertyKeyListener does not pop up a Gingerbread-style CharacterPickerDialog
            ic?.sendKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, event.keyCode,
                    event.repeatCount, event.metaState, -1,
                    event.scanCode, event.flags, event.source
                )
            )
            return true
        }
        ic?.sendKeyEvent(event)
        if (KeyEvent.isModifierKey(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> stickyMetaState.onModifierDown(event.metaState)
                KeyEvent.ACTION_UP -> {
                    // tracked even with no editor to tell
                    val released = stickyMetaState.onModifierUp(event.metaState)
                    connection()?.clearMetaKeyStates(released)
                }
            }
        }
        return true
    }

    /** Forget every pending event, e.g. when the editor goes away. Ids are not reused. */
    fun clear() {
        // ids keep counting: a key still on its way to fcitx would otherwise come back
        // carrying the id of some later key
        cache.evictAll()
    }

    companion object {
        /** more than a fast typist can have in flight to the engine at once */
        const val CAPACITY = 78
    }
}
