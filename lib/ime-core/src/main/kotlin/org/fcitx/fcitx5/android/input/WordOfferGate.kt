/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.WordOffer

/**
 * When a table's offered words go up on the notice strip: [PAUSE_MS] after the last finger left the keys,
 * never over another notice. The caller times it: [due] after the pause each [timeFrom] asks for. And when
 * the one up counts as seen: [seen] once it has stayed [SEEN_MS].
 */
class WordOfferGate {
    private var offer: WordOffer? = null
    private var fingers = 0

    // the offer up on the strip and when it went up; null once it went or was told seen
    private var up: Int? = null
    private var upAt = 0L

    /** The session's offer, or null as it withdrew it. Whether to time the pause from now. */
    fun offered(offer: WordOffer?): Boolean {
        this.offer = offer
        // the session changes its offer only by withdrawing the one up, which takes it off the strip
        up = null
        return timeFrom()
    }

    /** A finger down anywhere on the keyboard: no pause while one is held. */
    fun down() {
        fingers++
    }

    /** Whether to time the pause from now: the last finger left. */
    fun up(): Boolean {
        fingers = (fingers - 1).coerceAtLeast(0)
        return timeFrom()
    }

    /** Every finger gone at once (a cancel, the keyboard hidden). Whether to time the pause from now. */
    fun released(): Boolean {
        fingers = 0
        return timeFrom()
    }

    /** Another notice came: this offer is not shown, nor the one up seen. */
    fun noticed() {
        offer = null
        hidden()
    }

    /** The one up was put away: not seen unless told so already. */
    fun hidden() {
        up = null
    }

    /** Offer [id] went up at [now]. */
    fun shown(id: Int, now: Long) {
        up = id
        upAt = now
    }

    /**
     * The offer to tell seen at [now], once: up [SEEN_MS] and still there. Null for one gone sooner, a flash
     * the next key took, so that it may be offered again in the field.
     */
    fun seen(now: Long): Int? {
        val id = up?.takeIf { now - upAt >= SEEN_MS } ?: return null
        up = null
        return id
    }

    private fun timeFrom() = offer != null && fingers == 0

    /**
     * The pause ended: the offer to show, or null where it would cover another notice, a screen reader
     * would read it after every character, or the letters are not shown. Each offer at most once.
     * Only words the field reads back [before] the cursor: a tap checks it, so another would do nothing.
     */
    fun due(noticeShown: Boolean, touchExploring: Boolean, letters: Boolean, before: () -> CharSequence?): Due? {
        if (fingers > 0) return null
        val o = offer ?: return null
        offer = null
        if (noticeShown || touchExploring || !letters) return null
        val read = before() ?: return null
        val words = o.words.withIndex().filter { read.endsWith(it.value.text) }
        return if (words.isEmpty()) null else Due(o.id, words)
    }

    /** Offer [id]'s words to show, each with its index there, which a tap names. */
    data class Due(val id: Int, val words: List<IndexedValue<OfferedWord>>)

    companion object {
        /** Longer than between characters typed on, shorter than stopping to look. */
        const val PAUSE_MS = 800L

        /** Up this long, an offer was read and left: not offered again in the field. */
        const val SEEN_MS = 1000L
    }
}
