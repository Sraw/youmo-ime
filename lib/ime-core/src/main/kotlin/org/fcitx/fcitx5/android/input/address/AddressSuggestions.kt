/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

/** A field that declares itself an address's: only those get suggestions, none is guessed. */
enum class AddressField { EMAIL, URL }

/**
 * One suggestion on the bar: [shown]; tapped, [deleteBefore] and [deleteAfter] UTF-16 units either
 * side of the cursor go, [text] goes in their place and the cursor ends [cursor] units into it.
 */
data class AddressSuggestion(
    val shown: String,
    val text: String,
    val cursor: Int = text.length,
    val deleteBefore: Int = 0,
    val deleteAfter: Int = 0,
)

/**
 * What the bar offers in an address field while nothing is typed into the engine: the ends of
 * addresses from the user's two lists ([AddressLists]), the ones used most first, and in an email
 * field the whole addresses used there before ([AddressBook.addresses]), ahead of those.
 *
 * An email field: before an `@`, every suffix, put after the name with the cursor at the end (the
 * address done), or with no name yet before the `@` (the name typed next); after it, those that go
 * on from what follows it, the rest of each put in. A URL field: empty, `https://` and `www.`; after
 * a host, the suffixes it does not end with already, at the cursor; a suffix half typed (`baidu.c`),
 * those it begins, the rest of each put in, but none after a suffix the host has already
 * (`sina.com.c`: `.com.cn`'s `n` alone). Suffixes only after a name or host of ASCII letters,
 * digits and `.-_`: none after 天气 or 张三. Only at the end of what is typed: in the middle of an
 * address, the remembered ones alone, which take its place.
 */
object AddressSuggestions {

    /** How many remembered addresses are offered at most, the most used first. */
    const val ADDRESSES_SHOWN = 5

    private const val SCHEME = "://"
    private val URL_STARTS = listOf("https://", "www.")

    /**
     * [before] and [after] are the text either side of the cursor (as much as was read, [read] at
     * most: a token that fills it was cut, and gets nothing), [book] what was learned, null where it
     * is not used (「不用我的输入习惯」): the lists' order alone then.
     */
    fun suggest(
        field: AddressField,
        before: CharSequence,
        after: CharSequence,
        lists: AddressLists,
        book: AddressBook?,
        read: Int = Int.MAX_VALUE,
    ): List<AddressSuggestion> {
        val start = tokenStart(before)
        // its start not read: replaced in part, a surrogate pair maybe split
        if (start == 0 && before.length >= read) return emptyList()
        val end = tokenEnd(after)
        // its end not read: what follows the read part would stay after the address put in
        if (end == after.length && after.length >= read) return emptyList()
        val head = before.substring(start)
        val tail = after.substring(0, end)
        return when (field) {
            AddressField.EMAIL -> email(head, tail, lists, book)
            AddressField.URL -> url(head, tail, lists, book)
        }
    }

    /** What parts addresses in a field of several: a space, a comma, a semicolon, a Chinese one's too. */
    fun isSeparator(c: Char) = c.isWhitespace() || c in SEPARATORS

    private const val SEPARATORS = ",;，；、"

    private fun tokenStart(before: CharSequence): Int = before.indexOfLast(::isSeparator) + 1

    // what a host or an email's name is made of: a word in Chinese (天气, 张三) is neither
    private fun isHostChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-_"

    // an email typed up to [at] (its @, or -1): a name may carry a +tag (a+tag@), a domain may not
    private fun isAddressHead(head: String, at: Int) = head.withIndex().all { (i, c) -> i == at || isHostChar(c) || c == '+' && (at < 0 || i < at) }

    private fun tokenEnd(after: CharSequence): Int = after.indexOfFirst(::isSeparator).takeIf { it >= 0 } ?: after.length

    private fun email(head: String, tail: String, lists: AddressLists, book: AddressBook?): List<AddressSuggestion> {
        val token = head + tail
        val remembered = book?.addresses.orEmpty()
            .filter { it.address.startsWith(head, ignoreCase = true) && !it.address.equals(token, ignoreCase = true) }
            // stable: of the same count, the latest used first, as the book keeps them
            .sortedByDescending { it.count }
            .take(ADDRESSES_SHOWN)
            .map { AddressSuggestion(it.address, it.address, deleteBefore = head.length, deleteAfter = tail.length) }
        if (tail.isNotEmpty()) return remembered
        val at = head.indexOf('@')
        if (!isAddressHead(head, at)) return remembered
        val suffixes = if (at < 0) {
            ranked(lists.email, book, AddressLists.EMAIL_MARK).map {
                val suffix = AddressLists.EMAIL_MARK + it
                // no name yet: the cursor before the @, for the name next
                AddressSuggestion(suffix, suffix, cursor = if (head.isEmpty()) 0 else suffix.length)
            }
        } else {
            val domain = head.substring(at + 1)
            if ('@' in domain) return remembered
            ranked(lists.email, book, AddressLists.EMAIL_MARK)
                .filter { it.length > domain.length && it.startsWith(domain, ignoreCase = true) }
                .map { AddressSuggestion(AddressLists.EMAIL_MARK + it, it.substring(domain.length)) }
        }
        // a suffix that makes an address offered already is not offered again
        val made = remembered.mapTo(HashSet()) { it.text.lowercase() }
        return remembered + suffixes.filter { (head + it.text).lowercase() !in made }
    }

    private fun url(head: String, tail: String, lists: AddressLists, book: AddressBook?): List<AddressSuggestion> {
        if (head.isEmpty() && tail.isEmpty()) return URL_STARTS.map { AddressSuggestion(it, it) }
        val scheme = head.indexOf(SCHEME).let { if (it < 0) 0 else it + SCHEME.length }
        val host = head.substring(scheme)
        // where the host ends: the path, the query, the port
        if (tail.isNotEmpty() && tail[0] !in "/?#:") return emptyList()
        if (host.isEmpty()) {
            // after the scheme alone
            return if (scheme > 0 && tail.isEmpty()) listOf(AddressSuggestion("www.", "www.")) else emptyList()
        }
        if (host.any { it in "/?#:@" } || host.endsWith('.')) return emptyList()
        if (!host.all(::isHostChar)) return emptyList()
        if (lists.url.any { host.endsWith(it, ignoreCase = true) }) return emptyList()
        val ranked = ranked(lists.url, book, "")
        // a suffix begun (baidu.c, sina.com.c): those that go on from it, the rest put in; none of
        // them where the host has one already (sina.cn.c), not the whole suffixes after the c
        val begun = ranked.mapNotNull { suffix -> begun(host, suffix)?.let { suffix to it } }
        if (begun.isNotEmpty()) {
            return begun
                // sina.com.c: not .com nor .cn after the .com it has, .com.cn's n alone
                .filter { (_, n) -> lists.url.none { host.dropLast(n).endsWith(it, ignoreCase = true) } }
                .map { (suffix, n) -> AddressSuggestion(suffix, suffix.substring(n)) }
                .distinctBy { it.text.lowercase() }
        }
        return ranked.map { AddressSuggestion(it, it) }
    }

    // how much of [suffix] the host ends with, its dot and more, after a name: the longest
    private fun begun(host: String, suffix: String): Int? = (suffix.length - 1 downTo 2)
        .firstOrNull { n -> host.length > n && host.endsWith(suffix.substring(0, n), ignoreCase = true) }

    // [list] the most used first, of the same count in the list's order
    private fun ranked(list: List<String>, book: AddressBook?, mark: String): List<String> =
        if (book == null) list else list.sortedByDescending { book.count(mark + it) }
}
