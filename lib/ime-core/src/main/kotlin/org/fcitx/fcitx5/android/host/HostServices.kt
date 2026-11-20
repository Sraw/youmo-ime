/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.session.Offer

/** What the host does for the engine and modules ([HostCore], through the [KeyPipeline]): every commit filtered, every panel update coalesced. */
interface HostServices {
    /** `commitString` / `commitStringWithCursor`: the commit filters, then out at once. */
    fun commit(ic: InputContext, text: String, cursor: Int = -1)

    /** Through no commit filter: what the user wrote down, as written. */
    fun commitAsIs(ic: InputContext, text: String) = commit(ic, text)

    /** `deleteSurroundingText(-before, before + after)`, out at once. */
    fun deleteSurrounding(ic: InputContext, before: Int, after: Int)

    /** The forward listeners see it, then the application. */
    fun forwardKey(ic: InputContext, key: Key, release: Boolean = false)

    /** Out at once, only with the Preedit capability; two empty ones in a row once. */
    fun updatePreedit(ic: InputContext)

    /** `updateUserInterface(InputPanel)`: once, at the end of the event. */
    fun updatePanel(ic: InputContext)

    /** `Instance::inputMethod`. */
    fun inputMethod(ic: InputContext): String

    /** `showTip`: a short message on the keyboard. */
    fun tip(text: String)

    /** A module's state in [conf] changed: saved now, not when a settings page stops, so a force stop does not lose it. */
    fun persistConf(conf: String)

    /** Sent at the end of the event. */
    fun showPanel(ic: InputContext, panel: TempPanel)

    /** Sent again, once, at the end of the event. */
    fun updateStatusArea(ic: InputContext)

    fun punctuationAsTyped(ic: InputContext, marks: Set<String>) {}

    /** `Instance::outputFilter`, as the frontend shows it. */
    fun filterOutput(ic: InputContext, text: String): String
}

/** The texts fcitx took from gettext, passed in from the app's string resources. */
interface HostStrings {
    /** fcitx's `_("Default")`, the first group's name on a new install. */
    val defaultGroup: String

    /** chinese-addons' `(Half)`, beside a punctuation key's own mark. */
    val half: String

    fun offer(offer: Offer): String

    /** "summary: body", in the app's language. */
    fun tip(tip: Tip): String = when (tip) {
        Tip.PunctuationEnabled -> "Punctuation: Full width punctuation is enabled."
        Tip.PunctuationDisabled -> "Punctuation: Full width punctuation is disabled."
        Tip.TraditionalEnabled -> "Switch to Traditional Chinese: Traditional Chinese is enabled."
        Tip.SimplifiedEnabled -> "Switch to Simplified Chinese: Simplified Chinese is enabled."
    }

    /** quickphrase's `_("Quick Phrase: ")`. */
    val quickPhrase: String get() = "Quick Phrase: "

    /** Quick input's last candidate with nothing typed: a new entry of the user's. */
    val quickPhraseAdd: String get() = "+ Add"

    /** With nothing typed and none of the user's entries yet. */
    val quickPhraseHint: String get() = "type a key, or tap + Add for your own"

    val unicode: UnicodeStrings get() = UnicodeStrings()

    companion object {
        /** fcitx's own English, for tests and replays. */
        val ENGLISH = object : HostStrings {
            override val defaultGroup = "Default"
            override val half = "(Half)"
            override fun offer(offer: Offer) = when (offer) {
                Offer.FORGET -> "Forget word"
                Offer.PIN -> "Pin to top as custom phrase"
                Offer.UNPIN, Offer.DELETE_PHRASE -> "Delete from custom phrase"
                Offer.DELETE_COMMON_WORD -> "Delete from common words"
                Offer.BLOCK -> "Never show this word"
            }
        }
    }
}

/** `IPunctuation` as the engine asks it; null while not plugged in, as `punctuation()` is when the addon did not load. */
interface EnginePunctuation {
    /** Empty when off. */
    fun candidates(unicode: Int): List<String>

    /** The key's mark and, for a pair, its other half. */
    fun get(unicode: Int): Pair<String, String>

    /** `pushPunctuationV2`: a pair typed a half at a time turns over; empty when it types nothing. */
    fun push(ic: InputContext, unicode: Int): Pair<String, String>

    /** None where off: sent with the status ([HostEvent.StatusArea.punctuation]). */
    fun keys(ic: InputContext): Map<String, String> = emptyMap()

    /** Before a context has an input method. */
    fun keys(chinese: Boolean): Map<String, String> = emptyMap()
}

/** Before the engine's session is reset (`Instance::activateInputMethod`): modules put their toggles up for a Chinese one. */
fun interface ActivationListener {
    fun onActivated(ic: InputContext, im: String, chinese: Boolean)
}
