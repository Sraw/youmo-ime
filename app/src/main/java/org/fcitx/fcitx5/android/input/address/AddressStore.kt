/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.learn.backup.AddressData

/**
 * The lists and the book as [AppPrefs] keeps them, read again only when changed. The learning
 * backups carry the book ([data], [put]) on the fcitx thread, the bar and the settings use it on the main one.
 */
object AddressStore {

    private val keyboard get() = AppPrefs.getInstance().keyboard
    private val internal get() = AppPrefs.getInstance().internal

    private var listsRead: Pair<String, String>? = null
    private var lists = AddressLists()
    private var bookRead: Pair<String, String>? = null
    private var book = AddressBook()

    @Synchronized
    fun lists(): AddressLists {
        val raw = keyboard.emailSuffixes.getValue() to keyboard.urlSuffixes.getValue()
        if (raw != listsRead) {
            lists = AddressLists(AddressLists.parse(raw.first, AddressLists::emailEntry), AddressLists.parse(raw.second, AddressLists::urlEntry))
            listsRead = raw
        }
        return lists
    }

    @Synchronized
    fun book(): AddressBook {
        val raw = internal.addressCounts.getValue() to internal.rememberedAddresses.getValue()
        if (raw != bookRead) {
            book = AddressBook.parse(raw.first, raw.second)
            bookRead = raw
        }
        return book
    }

    @Synchronized
    fun save(book: AddressBook) {
        internal.addressCounts.setValue(book.serializeCounts())
        internal.rememberedAddresses.setValue(book.serializeAddresses())
    }

    /** How many times the settings forgot addresses: what a field held before is not learned again ([AddressLearning]). */
    @Volatile
    var forgotten = 0
        private set

    /** [transform] of the book, saved: one step, so a restore's merge on the fcitx thread is not written over. */
    @Synchronized
    fun update(transform: (AddressBook) -> AddressBook) = save(transform(book()))

    /** What a field was left with ([AddressLearning]), learned where the habits are used. */
    fun learn(left: AddressLearning.Left) {
        if (!AppPrefs.getInstance().internal.useHabits.getValue()) return
        val remember = keyboard.rememberAddresses.getValue()
        update { it.left(left.field, left.text, lists(), remember, left.started) }
    }

    /** [transform], the settings forgetting addresses. */
    @Synchronized
    fun forget(transform: (AddressBook) -> AddressBook) {
        forgotten++
        update(transform)
    }

    /** The book as a learning backup carries it: as parsed, so bounded, whatever the prefs hold. */
    @Synchronized
    fun data() = book().let { AddressData(it.serializeCounts(), it.serializeAddresses()) }

    /** A backup's book merged into this one ([AddressBook.merged]), or put in its place (退回恢复前). */
    @Synchronized
    fun put(data: AddressData, merge: Boolean) {
        val theirs = AddressBook.parse(data.counts, data.addresses)
        update { if (merge) it.merged(theirs) else theirs }
    }

    fun setEmail(list: List<String>) = keyboard.emailSuffixes.setValue(AddressLists.serialize(list))

    fun setUrl(list: List<String>) = keyboard.urlSuffixes.setValue(AddressLists.serialize(list))
}
