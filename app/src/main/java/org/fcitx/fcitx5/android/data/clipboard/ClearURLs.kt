/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber

typealias RegexAsString = @Serializable(with = RegexSerializer::class) Regex

/**
 * Strips tracking parameters from copied URLs with the ClearURLs rule set
 * (res/raw/clearurls_rules.json, from https://github.com/ClearURLs/Rules).
 *
 * Built in since the clipboard-filter plugin was folded into the app.
 */
class ClearURLs(rawRules: String) {
    @Serializable
    data class ClearURLsProvider(
        /** all patterns starts with https? */
        val urlPattern: RegexAsString,
        val completeProvider: Boolean = false,
        val rules: List<RegexAsString> = emptyList(),
        val rawRules: List<RegexAsString> = emptyList(),
        val referralMarketing: List<RegexAsString> = emptyList(),
        val exceptions: List<RegexAsString> = emptyList(),
        val redirections: List<RegexAsString> = emptyList(),
        val forceRedirection: Boolean = false
    )

    private val catalog: Map<String, ClearURLsProvider> =
        Json.decodeFromString(catalogSerializer, rawRules)["providers"]
            ?: throw IllegalArgumentException("no \"providers\" in ClearURLs rules")

    fun transform(text: String): String {
        if (!urlPattern.matchesAt(text, 0)) return text
        return transformWith(text, catalog)
    }

    private fun transformWith(url: String, map: Map<String, ClearURLsProvider>): String {
        var x = url
        var matched = false
        // lazy, so each provider is matched against the URL as the previous ones left it
        val applicable = map.values.asSequence().filter { provider ->
            provider.urlPattern.containsMatchIn(x) && provider.exceptions.none { it.containsMatchIn(x) }
        }
        for (provider in applicable) {
            matched = true
            // apply redirections
            provider.redirections.forEach { redirection ->
                redirection.matchAt(x, 0)?.let { match ->
                    match.groupValues.getOrNull(1)?.let {
                        val start = match.groups[1]?.range?.first ?: 0
                        x = decodeURL(it, isQueryValue = x.getOrNull(start - 1) == '=')
                        log(if (BuildConfig.DEBUG) "$url ~> $x" else "(redirect)")
                        return x
                    }
                }
            }
            provider.rawRules.forEach { rawRule ->
                x = rawRule.replace(x, "")
            }
            /**
             * apply rules and referralMarketing
             * https://docs.clearurls.xyz/1.26.1/specs/rules/#referralmarketing
             * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/clearurls.js#L449
             */
            val rules = provider.rules + provider.referralMarketing
            val uri = Uri.parse(x)
            val query = uri.encodedQuery
            val fragment = uri.encodedFragment
            val cleanQuery = filterParams(query, rules)
            /**
             * clear #fragments too
             * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/clearurls.js#L109
             */
            val cleanFragment = filterParams(fragment, rules)
            // rebuilt only when a parameter went, so a link with nothing to clear is kept as copied
            if (cleanQuery != query || cleanFragment != fragment) {
                x = uri.buildUpon()
                    .encodedQuery(cleanQuery)
                    .encodedFragment(cleanFragment)
                    .toString()
            }
        }
        if (matched) {
            log(if (BuildConfig.DEBUG) "$url -> $x" else "(clear)")
        }
        return x
    }

    /**
     * decode once per layer of encoding, as many as the target's scheme shows: none for a raw tail
     * that reads "https://" (href.li's, govdelivery's), but one for a query value that does (google's
     * q=, adurl=), which the redirecting server decodes once too; one for a target without a scheme;
     * unlike the js impl, which decodes until nothing changes and so also decodes the target's
     * own %26 or %2B
     * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/core_js/tools.js#L243
     */
    private fun decodeURL(str: String, isQueryValue: Boolean): String {
        if (!isQueryValue && urlPattern.matchesAt(str, 0)) return str
        var decoded = Uri.decode(str)
        while (encodedSchemePattern.matchesAt(decoded, 0)) {
            val next = Uri.decode(decoded)
            if (next == decoded) break
            decoded = next
        }
        return decoded
    }

    /**
     * Kept parameters are written back as they were, as the js impl does for #fragments: decoding
     * and re-encoding them changes what %26, %2B or %20 in a value mean
     * https://github.com/ClearURLs/Addon/blob/d8da43ac297e5df51a9c7276579ac3332adfa801/core_js/utils/URLHashParams.js#L65
     *
     * @return [params] itself when no parameter matches
     */
    private fun filterParams(params: String?, rules: List<Regex>): String? {
        if (params.isNullOrEmpty()) return params
        val pairs = params.split('&').filter { it.isNotEmpty() }
        val kept = pairs.filter { pair ->
            /**
             * match rules with search parameter keys
             * https://github.com/ClearURLs/Addon/blob/deec80b763179fa5c3559a37e3c9a6f1b28d0886/clearurls.js#L122
             */
            val key = Uri.decode(pair.substringBefore('='))
            rules.none { it.matches(key) }
        }
        return if (kept.size == pairs.size) params else kept.joinToString("&")
    }

    private fun log(msg: String) {
        Timber.d(msg)
    }

    private companion object {
        val providersSerializer: KSerializer<Map<String, ClearURLsProvider>> = serializer()
        val catalogSerializer: KSerializer<Map<String, Map<String, ClearURLsProvider>>> =
            MapSerializer(serializer(), providersSerializer)

        val urlPattern = Regex("^https?://", RegexOption.IGNORE_CASE)

        // a redirect target still encoded reads "http%3A", or awstrack's "http:%2F", where its "://" belongs
        val encodedSchemePattern = Regex("^https?:?%", RegexOption.IGNORE_CASE)
    }
}
