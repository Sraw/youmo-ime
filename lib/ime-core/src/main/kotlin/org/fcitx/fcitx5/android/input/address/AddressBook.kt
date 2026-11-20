/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

data class RememberedAddress(val address: String, val count: Int)

/**
 * What address suggestions learned as a field is left: how often each suffix ended an address, and where kept (on by
 * default) whole email addresses, latest first. Of a URL only its suffix. Nothing from fields that ask not to be learned
 * from; the caller asks no [left] there. Stored one entry a line, count first and a tab (neither has a tab: [isAddress]).
 */
class AddressBook(
    val counts: Map<String, Int> = emptyMap(),
    val addresses: List<RememberedAddress> = emptyList(),
) {

    fun count(suffix: String): Int = counts[suffix] ?: 0

    /** Those it [started] with (a reply's 收件人, a browser's page) are not counted; a suffix gone from the lists keeps no count. */
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

    fun forget(address: String) = AddressBook(counts, addresses.filterNot { it.address.equals(address, ignoreCase = true) })

    /** The suffixes' counts stay. */
    fun forgetAddresses() = AddressBook(counts, emptyList())

    /** A backup's put back: larger counts, this book's addresses first; putting the same back twice loses nothing. */
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

        /** So what is written stays under a learning backup string's 64 KB (`writeUTF`), whatever the prefs hold. */
        const val MAX_COUNTS = 64

        /** A name, one `@`, a domain with a dot, no space nor separator. */
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

        /** A bad line dropped; bounded as written. */
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
