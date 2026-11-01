/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AddressBookTest {

    private val lists = AddressLists()

    @Test
    fun anEmailFieldLeftCountsItsSuffixAndRemembersTheAddress() {
        val book = AddressBook().left(AddressField.EMAIL, "Zhang@GMail.com", lists, remember = true)
        assertEquals(1, book.count("@gmail.com"))
        assertEquals(listOf(RememberedAddress("Zhang@GMail.com", 1)), book.addresses)
        val again = book.left(AddressField.EMAIL, "li@qq.com, zhang@gmail.com", lists, remember = true)
        assertEquals(2, again.count("@gmail.com"))
        assertEquals(1, again.count("@qq.com"))
        // the latest used first, the same address once, as last typed
        assertEquals(listOf(RememberedAddress("zhang@gmail.com", 2), RememberedAddress("li@qq.com", 1)), again.addresses)
    }

    @Test
    fun onlyAWholeAddressAndOnlyTheListsSuffixesCount() {
        val book = AddressBook()
            .left(AddressField.EMAIL, "zhang", lists, remember = true)
            .left(AddressField.EMAIL, "zhang@", lists, remember = true)
            .left(AddressField.EMAIL, "zhang@work.cn", lists, remember = true)
        assertEquals(emptyMap<String, Int>(), book.counts)
        assertEquals(listOf("zhang@work.cn"), book.addresses.map { it.address })
    }

    @Test
    fun notRememberingCountsTheSuffixAlone() {
        val book = AddressBook().left(AddressField.EMAIL, "zhang@qq.com", lists, remember = false)
        assertEquals(1, book.count("@qq.com"))
        assertEquals(emptyList<RememberedAddress>(), book.addresses)
    }

    @Test
    fun aUrlLeftCountsItsHostsLongestSuffixAndNothingElse() {
        val book = AddressBook()
            .left(AddressField.URL, "https://www.sina.com.cn/news?id=1", lists, remember = true)
            .left(AddressField.URL, "baidu.com", lists, remember = true)
            .left(AddressField.URL, "example.io", lists, remember = true)
            .left(AddressField.URL, ".com", lists, remember = true)
        assertEquals(mapOf(".com.cn" to 1, ".com" to 1), book.counts)
        assertEquals(emptyList<RememberedAddress>(), book.addresses)
    }

    @Test
    fun atMostFiftyAddressesTheLeastRecentlyUsedDropped() {
        var book = AddressBook()
        for (i in 1..AddressBook.MAX_ADDRESSES + 3) book = book.left(AddressField.EMAIL, "u$i@x.cn", lists, remember = true)
        assertEquals(AddressBook.MAX_ADDRESSES, book.addresses.size)
        assertEquals("u53@x.cn", book.addresses.first().address)
        assertFalse(book.addresses.any { it.address == "u3@x.cn" })
    }

    @Test
    fun forgettingOneOrAllAddressesKeepsTheCounts() {
        val book = AddressBook().left(AddressField.EMAIL, "a@qq.com, b@qq.com", lists, remember = true)
        assertEquals(listOf("a@qq.com"), book.forget("B@QQ.com").addresses.map { it.address })
        val none = book.forgetAddresses()
        assertEquals(emptyList<RememberedAddress>(), none.addresses)
        assertEquals(2, none.count("@qq.com"))
    }

    @Test
    fun aBookReadsBackAsItWasWritten() {
        val book = AddressBook().left(AddressField.EMAIL, "a@qq.com b@163.com", lists, remember = true)
            .left(AddressField.URL, "x.org", lists, remember = true)
        val read = AddressBook.parse(book.serializeCounts(), book.serializeAddresses())
        assertEquals(book.counts, read.counts)
        assertEquals(book.addresses, read.addresses)
        // what is not an entry is dropped
        val torn = AddressBook.parse("3\t@qq.com\nx\t@163.com\n0\t.com\n2", "1\ta@qq.com\n1\tnot an address\n\n2\tA@QQ.com")
        assertEquals(mapOf("@qq.com" to 3), torn.counts)
        assertEquals(listOf(RememberedAddress("a@qq.com", 1)), torn.addresses)
        assertEquals(emptyList<RememberedAddress>(), AddressBook.parse("", "").addresses)
    }

    @Test
    fun whatIsAWholeAddress() {
        assertTrue(AddressBook.isAddress("a.b+c@mail.example.cn"))
        assertTrue(AddressBook.isAddress("me@qq.com"))
        assertRows(
            listOf(
                "a" to "no @",
                "@qq.com" to "no name",
                "a@" to "no domain",
                "a@qq" to "a domain of one label",
                "a@.qq.com" to "a domain starting with a dot",
                "a@qq.com." to "a domain ending with a dot",
                "a@qq..com" to "an empty label",
                "a b@qq.com" to "a space in the name",
                "a@b@qq.com" to "two @",
                "a@qq.com," to "a separator glued on",
                "me@qq.com。" to "a Chinese full stop glued on: not kept, not offered for good",
                "a@b.com/x" to "a path glued on",
                "a@b.c(m" to "a bracket in the domain",
                "a".repeat(250) + "@q.cn" to "longer than an address may be",
            ),
            { "${it.first.take(20)}: ${it.second}" },
        ) { (s, _) -> assertFalse(AddressBook.isAddress(s)) }
    }

    @Test
    fun theListsEntriesAsTyped() {
        assertEquals("qq.com", AddressLists.emailEntry(" @QQ.com "))
        assertEquals("work.cn", AddressLists.emailEntry("work.cn"))
        for (s in listOf("", "@", "qq", "q q.com", "a@qq.com", "qq..com", ".qq.com", "qq.com/")) assertNull(s, AddressLists.emailEntry(s))
        assertEquals(".com", AddressLists.urlEntry("COM"))
        assertEquals(".com.cn", AddressLists.urlEntry(".com.cn"))
        assertEquals(".中国", AddressLists.urlEntry("中国"))
        for (s in listOf("", ".", "..com", ".com/", "c m", ".com.")) assertNull(s, AddressLists.urlEntry(s))
        val list = listOf("qq.com", "work.cn")
        assertEquals(list, AddressLists.parse(AddressLists.serialize(list), AddressLists::emailEntry))
        assertEquals(listOf("qq.com"), AddressLists.parse("qq.com\n\n@QQ.COM\nbad", AddressLists::emailEntry))
        assertEquals(emptyList<String>(), AddressLists.parse("", AddressLists::urlEntry))
    }

    @Test
    fun aSuffixTakenOffTheListsKeepsNoCount() {
        val book = AddressBook(mapOf("@qq.com" to 3, "@old.com" to 2, ".io" to 1))
        val left = book.left(AddressField.EMAIL, "a@163.com", AddressLists(), remember = false)
        assertEquals(mapOf("@qq.com" to 3, "@163.com" to 1), left.counts)
    }

    @Test
    fun aChineseCommaPartsTheAddressesLeft() {
        val left = AddressBook().left(AddressField.EMAIL, "a@qq.com，b@163.com、c@qq.com；", AddressLists(), remember = true)
        assertEquals(listOf("c@qq.com", "b@163.com", "a@qq.com"), left.addresses.map { it.address })
        assertEquals(false, AddressBook.isAddress("a@qq.com，b"))
    }

    @Test
    fun aBackupPutBackKeepsTheLargerCountsAndBothAddresses() {
        val phone = AddressBook(mapOf("@qq.com" to 5, ".com" to 1), listOf(RememberedAddress("a@qq.com", 2), RememberedAddress("b@qq.com", 1)))
        val backup = AddressBook(mapOf("@qq.com" to 3, "@163.com" to 4), listOf(RememberedAddress("B@qq.com", 6), RememberedAddress("c@163.com", 1)))
        val merged = phone.merged(backup)
        assertEquals(mapOf("@qq.com" to 5, ".com" to 1, "@163.com" to 4), merged.counts)
        assertEquals(listOf("a@qq.com" to 2, "b@qq.com" to 6, "c@163.com" to 1), merged.addresses.map { it.address to it.count })
        val twice = merged.merged(backup)
        assertEquals(merged.counts to merged.addresses, twice.counts to twice.addresses)
        // at most fifty
        val many = AddressBook(addresses = (1..40).map { RememberedAddress("p$it@qq.com", 1) })
        val more = AddressBook(addresses = (1..40).map { RememberedAddress("b$it@qq.com", 1) })
        assertEquals(AddressBook.MAX_ADDRESSES, many.merged(more).addresses.size)
        assertEquals("p1@qq.com", many.merged(more).addresses.first().address)
    }

    @Test
    fun aBookFromAnythingStaysSmallEnoughForABackup() {
        // prefs from elsewhere: a megabyte of counts, long keys, addresses as long as allowed, in a script of 3 UTF bytes
        val long = "汉".repeat(240)
        val counts = (1..20_000).joinToString("\n") { "$it\t@$long$it.com" } + "\n7\t@qq.com\n9\t" + "x".repeat(300) + "\njunk"
        val addresses = (1..200).joinToString("\n") { "1\t$long$it@q.cn" }
        val book = AddressBook.parse(counts, addresses)
        assertEquals(AddressBook.MAX_COUNTS, book.counts.size)
        assertEquals(AddressBook.MAX_ADDRESSES, book.addresses.size)
        // the most used kept
        assertEquals(20_000, book.count("@${long}20000.com"))
        for (written in listOf(book.serializeCounts(), book.serializeAddresses())) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(written) }.size()
            assertTrue(bytes.toString(), bytes < 65_000)
        }
        // what it wrote reads back the same
        val again = AddressBook.parse(book.serializeCounts(), book.serializeAddresses())
        assertEquals(book.counts to book.addresses, again.counts to again.addresses)
    }

    @Test
    fun aUrlFieldLeftWithTheHostItStartedWithCountsNothing() {
        // a browser's address bar, its page's URL only added to: the app's host, not the user's to count
        val book = AddressBook()
            .left(AddressField.URL, "https://www.sina.com.cn/news?id=1", lists, remember = true, started = "https://www.sina.com.cn/")
            .left(AddressField.URL, "baidu.com", lists, remember = true, started = "https://www.sina.com.cn/")
        assertEquals(mapOf(".com" to 1), book.counts)
    }

    @Test
    fun theAddressesAFieldStartedWithAreNotLearned() {
        // a reply to all: the app's 收件人 are not the user's, the one added is
        val book = AddressBook().left(AddressField.EMAIL, "a@x.com, b@y.com, c@qq.com", AddressLists(), remember = true, started = "a@x.com, b@y.com")
        assertEquals(listOf("c@qq.com"), book.addresses.map { it.address })
        assertEquals(1, book.count("@qq.com"))
    }
}
