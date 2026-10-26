/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.host.CharNames
import org.fcitx.fcitx5.android.host.HostStrings
import org.fcitx.fcitx5.android.host.Tip
import org.fcitx.fcitx5.android.host.UnicodeStrings
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The host's texts as the app's AppHostStrings reads them, from the app's string resources in one
 * locale ([qualifier], `zh-rCN` as the parity redroids run: uat.py sets persist.sys.locale), the
 * default `values/` where that locale has none. A parity recording shows the device's language in
 * the quick input and Unicode prompts; the replay has to show the same.
 */
class ResourceHostStrings(res: File, qualifier: String) : HostStrings {
    private val strings: Map<String, String> = read(File(res, "values/strings.xml")) + read(File(res, "values-$qualifier/strings.xml"))

    private fun s(name: String): String = checkNotNull(strings[name]) { "no string $name" }

    override val defaultGroup = s("host_default_group")
    override val half = s("host_half")
    override val quickPhrase = s("host_quick_phrase_aux")
    override val unicode = UnicodeStrings(
        aux = s("host_unicode_aux"),
        hint = s("host_unicode_hint"),
        names = CharNames(
            cjkIdeograph = s("host_cjk_ideograph"),
            hangulSyllable = s("host_hangul_syllable"),
            nonPrivateHighSurrogate = s("host_non_private_high_surrogate"),
            privateHighSurrogate = s("host_private_high_surrogate"),
            lowSurrogate = s("host_low_surrogate"),
            privateUse = s("host_private_use"),
            tangutIdeograph = s("host_tangut_ideograph"),
            plane15PrivateUse = s("host_plane15_private_use"),
            plane16PrivateUse = s("host_plane16_private_use"),
            notAssigned = s("host_not_assigned"),
        ),
    )

    override fun offer(offer: Offer): String = s(
        when (offer) {
            Offer.FORGET -> "host_offer_forget"
            Offer.PIN -> "host_offer_pin"
            Offer.UNPIN, Offer.DELETE_PHRASE -> "host_offer_delete_phrase"
            Offer.BLOCK -> "host_offer_block"
        },
    )

    override fun tip(tip: Tip): String = s(
        when (tip) {
            Tip.PunctuationEnabled -> "host_tip_punctuation_enabled"
            Tip.PunctuationDisabled -> "host_tip_punctuation_disabled"
            Tip.TraditionalEnabled -> "host_tip_traditional"
            Tip.SimplifiedEnabled -> "host_tip_simplified"
        },
    )

    companion object {
        /** The app's strings in the parity devices' locale; null when not run by Gradle (no `uat.res`). */
        fun device(): ResourceHostStrings? = System.getProperty("uat.res")?.let { ResourceHostStrings(File(it), "zh-rCN") }

        // the host_ strings of a strings.xml, as aapt reads them: a quoted value's quotes taken off
        // (spaces kept), \' and \" unescaped
        private fun read(file: File): Map<String, String> {
            if (!file.isFile) return emptyMap()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) }
                .map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
                .filter { it.first.startsWith("host_") }
                .associate { (name, raw) -> name to unescape(raw) }
        }

        internal fun unescape(raw: String): String {
            val quoted = raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
            val text = if (quoted) raw.substring(1, raw.length - 1) else raw.trim()
            return text.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }
}
