/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/**
 * What a long press on a candidate offers ([Session.offers]), one rule for every session. Nothing without
 * [learning], as forgetting, pinning and blocking each record the word. A custom phrase offers
 * [Offer.DELETE_PHRASE] in place of a block, learning or not. Each question is asked only when it decides something.
 */
internal object OfferRules {
    fun offers(
        learning: Boolean,
        forgets: () -> Boolean,
        pins: () -> Boolean = { false },
        pinned: () -> Boolean = { false },
        blocks: () -> Boolean = { false },
        phrase: Boolean = false,
    ): Set<Offer> {
        if (!learning) return if (phrase) setOf(Offer.DELETE_PHRASE) else emptySet()
        return buildSet {
            if (forgets()) add(Offer.FORGET)
            if (pins()) add(if (pinned()) Offer.UNPIN else Offer.PIN)
            if (phrase) add(Offer.DELETE_PHRASE) else if (blocks()) add(Offer.BLOCK)
        }
    }
}
