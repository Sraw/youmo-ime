/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

/** An email address put in an email field, and how many times. */
data class RememberedAddress(val address: String, val count: Int)

/**
 * What the address suggestions learned, as the field is left ([left]): how often each suffix of
 * the lists ended an address ([counts], an email's by `@qq.com`, a URL's by `.com`), and, where the
 * user keeps them (on by default), the whole email addresses ([addresses], the latest used first, at
 * most [MAX_ADDRESSES]). Of a URL nothing but its suffix; nothing of a name but in a whole address.
 * Nothing of a field that asks not to be learned from, nor of a password: the caller asks no [left]
 * there.
 *
 * Kept as two strings ([serializeCounts], [serializeAddresses]), one entry a line, its count first
 * and a tab: neither a suffix nor an address has a tab or a line break ([isAddress]).
 */
class AddressBook(
    val counts: Map<String, Int> = emptyMap(),
    val addresses: List<RememberedAddress> = emptyList(),
) {

    /** How often [suffix] (`@qq.com`, `.com`) ended an address. */
    fun count(suffix: String): Int = counts[suffix] ?: 0

    /**
     * The book after [field] was left with [text] in it: each whole address in an email field
     * counts its suffix where [lists] have it, and is remembered where [remember], but those it
     * [started] with (a reply's 收件人, the app's), each as many times as it had them; a URL counts
     * its host's longest suffix of [lists], but not a host it [started] with. A suffix no longer in the lists keeps no count.
     */
    fun left(field: AddressField, text: CharSequence, lists: AddressLists, remember: Boolean, started: CharSequence = ""): AddressBook {
        val listed = lists.email.mapTo(HashSet()) { AddressLists.EMAIL_MARK + it } + lists.url
        val counted = counts.filterKeys { it in listed }.toMutableMap()
        var kept = addresses
        when (field) {
            AddressField.EMAIL -> for (address in added(split(text), split(started)).filter(::isAddress)) {
                val domain = address.substringAfter('@').lowercase()
                if (domain in lists.email) counted.merge(AddressLists.EMAIL_MARK + domain, 1, Int::plus)
                if (remember) kept = remembered(kept, address)
            }
            AddressField.URL -> {
                val host = hostOf(text)
                // the app's own (a browser's page, only added to): not the user's to count
                if (host != hostOf(started)) lists.url.filter { host.length > it.length && host.endsWith(it) }.maxByOrNull { it.length }
                    ?.let { counted.merge(it, 1, Int::plus) }
            }
        }
        return AddressBook(counted, kept)
    }

    private fun hostOf(text: CharSequence) = text.toString().trim().substringAfter("://").takeWhile { it !in "/?#:" }.lowercase()

    /** The book without [address]. */
    fun forget(address: String) = AddressBook(counts, addresses.filterNot { it.address.equals(address, ignoreCase = true) })

    /** The book with no address: what the suffixes counted stays. */
    fun forgetAddresses() = AddressBook(counts, emptyList())

    /**
     * This book with [other], a backup's, put back into it: each suffix's larger count, and the
     * addresses of both, this book's first in its order, then the others' new to it, one in both
     * with the larger count; [MAX_ADDRESSES] at most. Nothing is lost by putting the same back twice.
     */
    fun merged(other: AddressBook): AddressBook {
        val counted = counts.toMutableMap()
        other.counts.forEach { (key, n) -> counted.merge(key, n, ::maxOf) }
        val theirs = other.addresses.associateBy { it.address.lowercase() }
        val mine = addresses.map { a -> theirs[a.address.lowercase()]?.let { a.copy(count = maxOf(a.count, it.count)) } ?: a }
        val known = addresses.mapTo(HashSet()) { it.address.lowercase() }
        val added = other.addresses.filter { it.address.lowercase() !in known }.distinctBy { it.address.lowercase() }
        return AddressBook(counted, (mine + added).take(MAX_ADDRESSES))
    }

    fun serializeCounts(): String = counts.entries.joinToString("\n") { "${it.value}\t${it.key}" }

    fun serializeAddresses(): String = addresses.joinToString("\n") { "${it.count}\t${it.address}" }

    companion object {
        const val MAX_ADDRESSES = 50

        private const val MAX_LENGTH = 254

        /**
         * At most this many suffixes counted, the most used: with [MAX_ADDRESSES] of [MAX_LENGTH] each,
         * what [serializeCounts] and [serializeAddresses] write stays under the 64 KB a learning backup's
         * string may be (`writeUTF`), whatever the prefs hold (a 备份全部 zip from elsewhere).
         */
        const val MAX_COUNTS = 64

        /** A whole email address: a name, one `@`, a domain of labels with a dot in it, no space nor separator. */
        fun isAddress(s: String): Boolean {
            if (s.length > MAX_LENGTH || s.any { AddressSuggestions.isSeparator(it) || it in "<>\"" }) return false
            val at = s.indexOf('@')
            if (at <= 0 || s.indexOf('@', at + 1) >= 0) return false
            val domain = s.substring(at + 1)
            // labels between single dots: not me@qq.com。 nor a@b.com/x, kept and offered for good
            return '.' in domain && AddressLists.isDomain(domain)
        }

        private fun split(text: CharSequence): List<String> = buildList {
            var start = 0
            for (i in 0..text.length) {
                if (i == text.length || AddressSuggestions.isSeparator(text[i])) {
                    if (i > start) add(text.substring(start, i))
                    start = i + 1
                }
            }
        }

        // [now] but as many of each as [before] had: what the user added
        private fun added(now: List<String>, before: List<String>): List<String> {
            val had = before.toMutableList()
            return now.filterNot { had.remove(it) }
        }

        // [address] put first, its count one more
        private fun remembered(kept: List<RememberedAddress>, address: String): List<RememberedAddress> {
            val old = kept.firstOrNull { it.address.equals(address, ignoreCase = true) }
            return (listOf(RememberedAddress(address, (old?.count ?: 0) + 1)) + kept.filter { it !== old }).take(MAX_ADDRESSES)
        }

        /**
         * The book [serializeCounts] and [serializeAddresses] wrote: a line that is not one dropped,
         * [MAX_COUNTS] and [MAX_ADDRESSES] at most, none longer than [MAX_LENGTH].
         */
        fun parse(counts: String, addresses: String): AddressBook = AddressBook(
            entries(counts).filter { (_, key) -> key.isNotEmpty() && key.length <= MAX_LENGTH && '\t' !in key }
                .groupBy({ it.second }, { it.first }).mapValues { it.value.max() }
                .entries.sortedByDescending { it.value }.take(MAX_COUNTS).associate { it.key to it.value },
            entries(addresses).filter { (_, a) -> isAddress(a) }.map { (n, a) -> RememberedAddress(a, n) }
                .distinctBy { it.address.lowercase() }.take(MAX_ADDRESSES),
        )

        private fun entries(raw: String): List<Pair<Int, String>> = raw.lines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val n = line.substring(0, tab.coerceAtLeast(0)).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            n to line.substring(tab + 1)
        }
    }
}
