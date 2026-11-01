/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSuggestionsTest {

    private val lists = AddressLists()

    private fun email(before: String, after: String = "", book: AddressBook? = AddressBook(), l: AddressLists = lists) =
        AddressSuggestions.suggest(AddressField.EMAIL, before, after, l, book)

    private fun url(before: String, after: String = "", book: AddressBook? = AddressBook(), l: AddressLists = lists) =
        AddressSuggestions.suggest(AddressField.URL, before, after, l, book)

    // the field as a tap leaves it, the cursor a |
    private fun applied(before: String, after: String, s: AddressSuggestion): String {
        val kept = before.dropLast(s.deleteBefore) + s.text.substring(0, s.cursor)
        return kept + "|" + s.text.substring(s.cursor) + after.drop(s.deleteAfter)
    }

    @Test
    fun anEmptyEmailFieldOffersEverySuffixTheCursorLeftBeforeTheAt() {
        val offered = email("")
        assertEquals(
            listOf("@qq.com", "@163.com", "@gmail.com", "@outlook.com", "@126.com", "@foxmail.com", "@icloud.com"),
            offered.map { it.shown },
        )
        assertEquals("|@qq.com", applied("", "", offered[0]))
    }

    @Test
    fun aNameTypedGetsTheSuffixAfterItTheCursorAtTheEnd() {
        val offered = email("zhang")
        assertEquals("zhang@gmail.com|", applied("zhang", "", offered.first { it.shown == "@gmail.com" }))
    }

    @Test
    fun afterTheAtOnlyTheSuffixesThatGoOnFromWhatFollowsIt() {
        val offered = email("zhang@g")
        assertEquals(listOf("@gmail.com"), offered.map { it.shown })
        assertEquals("zhang@gmail.com|", applied("zhang@g", "", offered[0]))
        assertEquals(listOf("@126.com"), email("a@1").filter { it.shown.startsWith("@12") }.map { it.shown })
        assertEquals(listOf("@163.com", "@126.com"), email("a@1").map { it.shown })
        // the whole suffix typed already, or one not in the list
        assertEquals(emptyList<AddressSuggestion>(), email("a@gmail.com"))
        assertEquals(emptyList<AddressSuggestion>(), email("a@x"))
        // as typed: GM is gmail's
        assertEquals(listOf("@gmail.com"), email("a@GM").map { it.shown })
    }

    @Test
    fun theCursorInsideAnAddressGetsNoSuffix() {
        assertEquals(emptyList<AddressSuggestion>(), email("zh", "ang"))
        assertEquals(emptyList<AddressSuggestion>(), email("", "@qq.com"))
        assertEquals(emptyList<AddressSuggestion>(), email("a@@"))
    }

    @Test
    fun theAddressAtTheCursorAmongSeveral() {
        val offered = email("a@qq.com, b@g")
        assertEquals(listOf("@gmail.com"), offered.map { it.shown })
        assertEquals("a@qq.com, b@gmail.com|", applied("a@qq.com, b@g", "", offered[0]))
    }

    @Test
    fun theSuffixesUsedMostComeFirstOfTheSameCountInTheListsOrder() {
        val book = AddressBook(mapOf("@gmail.com" to 3, "@126.com" to 3, "@icloud.com" to 1))
        assertEquals(listOf("@gmail.com", "@126.com", "@icloud.com", "@qq.com"), email("", book = book).take(4).map { it.shown })
        // not used: the lists' order alone
        assertEquals("@qq.com", email("", book = null).first().shown)
    }

    @Test
    fun rememberedAddressesComeFirstByWhatIsTyped() {
        val book = AddressBook(
            addresses = listOf(RememberedAddress("zhang.san@work.cn", 1), RememberedAddress("zhou@qq.com", 4), RememberedAddress("li@163.com", 9)),
        )
        val offered = email("zh", book = book)
        assertEquals(listOf("zhou@qq.com", "zhang.san@work.cn", "@qq.com"), offered.take(3).map { it.shown })
        assertEquals("zhang.san@work.cn|", applied("zh", "", offered[1]))
        // by what is before the cursor, the whole address taking the place of what is typed, wherever the cursor is in it
        val inside = email("zh", "ang", book = book)
        assertEquals(listOf("zhou@qq.com", "zhang.san@work.cn"), inside.map { it.shown })
        assertEquals("zhang.san@work.cn|", applied("zh", "ang", inside[1]))
        // the one typed already is not offered, nor what a suffix would make of it again
        assertEquals(listOf("zhou@qq.com"), email("zhou@q", book = book).map { it.shown })
        assertEquals(emptyList<AddressSuggestion>(), email("zhou@qq.com", book = book))
        // an empty field: the most used, at most five
        val many = AddressBook(addresses = (1..8).map { RememberedAddress("u$it@x.cn", it) })
        assertEquals(listOf("u8@x.cn", "u7@x.cn", "u6@x.cn", "u5@x.cn", "u4@x.cn", "@qq.com"), email("", book = many).take(6).map { it.shown })
        // not used: none
        assertEquals("@qq.com", email("zh", book = null).first().shown)
    }

    @Test
    fun anEmptyUrlFieldOffersTheStarts() {
        val offered = url("")
        assertEquals(listOf("https://", "www."), offered.map { it.shown })
        assertEquals("https://|", applied("", "", offered[0]))
        assertEquals(listOf("www."), url("https://").map { it.shown })
        assertEquals(emptyList<AddressSuggestion>(), url("https://www."))
    }

    @Test
    fun aHostGetsTheSuffixesAtTheCursor() {
        val offered = url("www.baidu")
        assertEquals(listOf(".com", ".cn", ".com.cn", ".net", ".org"), offered.map { it.shown })
        assertEquals("www.baidu.com|/s", applied("www.baidu", "/s", url("www.baidu", "/s")[0]))
        assertEquals(listOf(".com", ".cn", ".com.cn", ".net", ".org"), url("https://example").map { it.shown })
    }

    @Test
    fun aHostThatHasASuffixAlreadyGetsNone() {
        assertEquals(emptyList<AddressSuggestion>(), url("baidu.com"))
        assertEquals(emptyList<AddressSuggestion>(), url("sina.com.cn"))
        assertEquals(emptyList<AddressSuggestion>(), url("BAIDU.COM"))
        // typing on: a dot, a path, a port, the middle of the host
        assertEquals(emptyList<AddressSuggestion>(), url("www."))
        assertEquals(emptyList<AddressSuggestion>(), url("example.com/pa"))
        assertEquals(emptyList<AddressSuggestion>(), url("localhost:80"))
        assertEquals(emptyList<AddressSuggestion>(), url("bai", "du"))
    }

    @Test
    fun theUsersOwnListsAreOffered() {
        val own = AddressLists(email = listOf("work.cn", "qq.com"), url = listOf(".io"))
        assertEquals(listOf("@work.cn", "@qq.com"), email("", l = own).map { it.shown })
        assertEquals(listOf(".io"), url("github", l = own).map { it.shown })
        assertEquals(emptyList<AddressSuggestion>(), url("x.io", l = own))
        val none = AddressLists(email = emptyList(), url = emptyList())
        assertEquals(emptyList<AddressSuggestion>(), email("a", l = none))
        assertEquals(listOf(".com"), url("x", book = AddressBook(mapOf(".com" to 1)), l = AddressLists(url = listOf(".com"))).map { it.shown })
    }

    @Test
    fun aSuffixHalfTypedIsFinishedNotAddedAgain() {
        val offered = url("baidu.c")
        // .com, .cn, .com.cn begin with .c; .net and .org do not
        assertEquals(listOf(".com", ".cn", ".com.cn"), offered.map { it.shown })
        assertEquals("baidu.com|", applied("baidu.c", "", offered[0]))
        assertEquals("baidu.com.cn|", applied("baidu.c", "", offered[2]))
        assertEquals(listOf(".com", ".com.cn"), url("https://baidu.co").map { it.shown })
        // .com.c: .com.cn's n alone, not .com nor .cn after the .com the host has
        assertEquals(listOf(".com.cn" to "n"), url("sina.com.c").map { it.shown to it.text })
        // a suffix begun where the host has one already: nothing, not the whole suffixes after the c (sina.cn.c.com)
        assertEquals(emptyList<AddressSuggestion>(), url("sina.cn.c"))
        // a host with no name before the dot: as before, every suffix
        assertEquals(lists.url, url("baidu").map { it.shown })
    }

    @Test
    fun aChineseCommaPartsAddressesToo() {
        for (sep in listOf("，", "；", "、")) {
            assertEquals(sep, listOf("@qq.com"), email("a@qq.com${sep}b@q").map { it.shown })
        }
    }

    @Test
    fun anAddressCutAtWhatWasReadGetsNothing() {
        val long = "x".repeat(256)
        assertEquals(emptyList<AddressSuggestion>(), AddressSuggestions.suggest(AddressField.EMAIL, long, "", lists, AddressBook(), read = 256))
        assertEquals(emptyList<AddressSuggestion>(), AddressSuggestions.suggest(AddressField.URL, long, "", lists, AddressBook(), read = 256))
        // a separator read: the address after it is whole
        val parted = "x".repeat(250) + " zhang"
        assertEquals(7, AddressSuggestions.suggest(AddressField.EMAIL, parted, "", lists, AddressBook(), read = 256).size)
        // cut on the right too: what was not read would stay after the address put in
        assertEquals(emptyList<AddressSuggestion>(), AddressSuggestions.suggest(AddressField.EMAIL, "a", long, lists, AddressBook(addresses = listOf(RememberedAddress("a@qq.com", 1))), read = 256))
        assertEquals(emptyList<AddressSuggestion>(), AddressSuggestions.suggest(AddressField.URL, "", long, lists, AddressBook(), read = 256))
    }

    @Test
    fun aChineseWordGetsNoSuffix() {
        // a search typed in the address bar, a name before its address
        assertEquals(emptyList<AddressSuggestion>(), url("天气"))
        assertEquals(emptyList<AddressSuggestion>(), email("张三"))
        assertEquals(emptyList<AddressSuggestion>(), email("张三@q"))
        // a +tag in the name is still a name: its domains offered
        assertTrue(email("a+tag").any { it.shown == "@qq.com" })
        assertEquals(lists.url, url("my-site_1").map { it.shown })
    }
}
