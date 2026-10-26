/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.session.Offer

/**
 * What the host does for the engine and the modules plugged into it, fcitx's `InputContext` calls
 * they make: [HostCore] implements it, through the [KeyPipeline], so every commit goes through the
 * commit filters and every panel update is coalesced.
 */
interface HostServices {
    /** `InputContext::commitString` / `commitStringWithCursor`: the commit filters, then out at once. */
    fun commit(ic: InputContext, text: String, cursor: Int = -1)

    /** `InputContext::deleteSurroundingText(-before, before + after)`, out at once. */
    fun deleteSurrounding(ic: InputContext, before: Int, after: Int)

    /** `InputContext::forwardKey`: the forward listeners see it, then the application gets it. */
    fun forwardKey(ic: InputContext, key: Key, release: Boolean = false)

    /**
     * `InputContext::updatePreedit` (`fcitx/inputcontext.cpp:473-485`): the client preedit goes out
     * at once, only with the Preedit capability, and two empty ones in a row once.
     */
    fun updatePreedit(ic: InputContext)

    /** `updateUserInterface(InputPanel)`: the panel goes out once, at the end of the event. */
    fun updatePanel(ic: InputContext)

    /** `Instance::inputMethod`: the input method [ic] is on. */
    fun inputMethod(ic: InputContext): String

    /** `notifications`' `showTip` (`androidnotification.cpp:71-86`): a short message on the keyboard. */
    fun tip(text: String)

    /**
     * A module's state that lives in [conf] (`conf/chttrans.conf`'s `EnabledIM`, punctuation's
     * `Enabled`) changed: the config writer saves it now, not when a settings page stops (fcitx's
     * `save()`), so a force stop does not lose it.
     */
    fun persistConf(conf: String)

    /** A temp mode's whole panel on [ic] ([TempModeHost.showPanel]), sent at the end of the event. */
    fun showPanel(ic: InputContext, panel: TempPanel)

    /** [ic]'s status area changed: sent again, once, at the end of the event (`updateUserInterface(StatusArea)`). */
    fun updateStatusArea(ic: InputContext)

    /** [text] through the output filters (`Instance::outputFilter`), as the frontend shows it. */
    fun filterOutput(ic: InputContext, text: String): String
}

/**
 * The texts the host shows that fcitx took from gettext (design §1.1): the app passes its string
 * resources in, so they read as today in each language.
 */
interface HostStrings {
    /** fcitx's `_("Default")`, the first group's name on a new install (`inputmethodmanager.cpp:147-162`). */
    val defaultGroup: String

    /** chinese-addons' `(Half)`, beside a punctuation key's own mark (`androidengine.cpp:290`). */
    val half: String

    /** A long-press menu item (`androidengine.cpp:65-73`). */
    fun offer(offer: Offer): String

    /** A module's tip as `androidnotification.cpp:84` shows it, "summary: body", in the app's language. */
    fun tip(tip: Tip): String = when (tip) {
        Tip.PunctuationEnabled -> "Punctuation: Full width punctuation is enabled."
        Tip.PunctuationDisabled -> "Punctuation: Full width punctuation is disabled."
        Tip.TraditionalEnabled -> "Switch to Traditional Chinese: Traditional Chinese is enabled."
        Tip.SimplifiedEnabled -> "Switch to Simplified Chinese: Simplified Chinese is enabled."
    }

    /** quickphrase's `_("Quick Phrase: ")`, the aux label. */
    val quickPhrase: String get() = "Quick Phrase: "

    /** unicode's aux, hint and the names fcitx makes up. */
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
                Offer.BLOCK -> "Never show this word"
            }
        }
    }
}

/**
 * Punctuation as the engine asks it (`IPunctuation`, `ca/punctuation/punctuation_public.h`), for
 * the marks a Chinese input method types for a key its session passes on
 * (`androidengine.cpp:515-548`). S2's punctuation module implements it; null while it is not
 * plugged in, as `punctuation()` is null when the addon did not load.
 */
interface EnginePunctuation {
    /** `getPunctuationCandidates("zh_CN", unicode)`: every mark the key has; empty when off. */
    fun candidates(unicode: Int): List<String>

    /** `getPunctuation("zh_CN", unicode)`: the key's mark and, for a pair, its other half. */
    fun get(unicode: Int): Pair<String, String>

    /**
     * `pushPunctuationV2("zh_CN", ic, unicode)`: what the key types now (and after it, a pair typed
     * together), turning a pair typed a half at a time over; empty when it types nothing.
     */
    fun push(ic: InputContext, unicode: Int): Pair<String, String>
}

/**
 * Told when an input method is activated on a focused context, before the engine's session is
 * reset (`Instance::activateInputMethod`, `fcitx/instance.cpp:2491-2493`): the status area's
 * modules put their toggles up for a Chinese one (`androidengine.cpp:497-510`).
 */
fun interface ActivationListener {
    fun onActivated(ic: InputContext, im: String, chinese: Boolean)
}
