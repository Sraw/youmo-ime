/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.host.CharNames
import org.fcitx.fcitx5.android.host.HostStrings
import org.fcitx.fcitx5.android.host.Tip
import org.fcitx.fcitx5.android.host.UnicodeStrings

/**
 * The texts fcitx took from gettext (its own, chinese-addons' and our `po/`), from the app's
 * resources, so they read as the native host's did in each of the app's languages.
 */
class AppHostStrings(context: Context) : HostStrings {
    private val res = context.resources

    override val defaultGroup: String = res.getString(R.string.host_default_group)
    override val half: String = res.getString(R.string.host_half)
    override val quickPhrase: String = res.getString(R.string.host_quick_phrase_aux)
    override val unicode = UnicodeStrings(
        aux = res.getString(R.string.host_unicode_aux),
        hint = res.getString(R.string.host_unicode_hint),
        names = CharNames(
            cjkIdeograph = res.getString(R.string.host_cjk_ideograph),
            hangulSyllable = res.getString(R.string.host_hangul_syllable),
            nonPrivateHighSurrogate = res.getString(R.string.host_non_private_high_surrogate),
            privateHighSurrogate = res.getString(R.string.host_private_high_surrogate),
            lowSurrogate = res.getString(R.string.host_low_surrogate),
            privateUse = res.getString(R.string.host_private_use),
            tangutIdeograph = res.getString(R.string.host_tangut_ideograph),
            plane15PrivateUse = res.getString(R.string.host_plane15_private_use),
            plane16PrivateUse = res.getString(R.string.host_plane16_private_use),
            notAssigned = res.getString(R.string.host_not_assigned),
        ),
    )

    private val offers = mapOf(
        Offer.FORGET to res.getString(R.string.host_offer_forget),
        Offer.PIN to res.getString(R.string.host_offer_pin),
        Offer.UNPIN to res.getString(R.string.host_offer_delete_phrase),
        Offer.DELETE_PHRASE to res.getString(R.string.host_offer_delete_phrase),
        Offer.BLOCK to res.getString(R.string.host_offer_block),
    )

    override fun offer(offer: Offer): String = offers.getValue(offer)

    private val tips = mapOf(
        Tip.PunctuationEnabled to res.getString(R.string.host_tip_punctuation_enabled),
        Tip.PunctuationDisabled to res.getString(R.string.host_tip_punctuation_disabled),
        Tip.TraditionalEnabled to res.getString(R.string.host_tip_traditional),
        Tip.SimplifiedEnabled to res.getString(R.string.host_tip_simplified),
    )

    override fun tip(tip: Tip): String = tips.getValue(tip)
}
