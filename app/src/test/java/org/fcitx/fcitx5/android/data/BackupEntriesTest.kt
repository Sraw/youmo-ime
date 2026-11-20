/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.utils.ZipEnd
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Entries counted before any is unpacked; UserDataManagerTest is too large for more. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BackupEntriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    // too many entries refused by the central directory before unpacking; one that lies is stopped while unpacking
    @Test
    @Uat("PR-52")
    fun aBackupOfTooManyEntriesIsRefusedBeforeAnyIsUnpacked() {
        fun entry(z: ZipOutputStream, i: Int) {
            z.putNextEntry(ZipEntry("d$i/").apply { time = 0 })
            z.closeEntry()
        }
        fun zip(n: Int) = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> repeat(n) { entry(z, it) } } }.toByteArray()
        // no comment: the last 22 bytes
        fun cdOffset(b: ByteArray) = java.nio.ByteBuffer.wrap(b, b.size - 6, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        // eleven local entries, the central directory of the first alone
        val lying = zip(11).let { all ->
            val one = zip(1)
            val head = all.copyOf(cdOffset(all))
            val tail = one.copyOfRange(cdOffset(one), one.size)
            java.nio.ByteBuffer.wrap(tail, tail.size - 6, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(head.size)
            head + tail
        }
        val tooMany = appContext.getString(R.string.exception_user_data_too_many_entries)
        assertRows(
            "11 entries, its central directory saying so: refused, nothing unpacked" to {
                val dir = folder.newFolder("honest")
                assertEquals(tooMany, runCatching { UserDataManager.extractWithinRoom(zip(11).inputStream(), dir, maxEntries = 10) }.exceptionOrNull()?.message)
                assertEquals(emptyList<String>(), dir.list()!!.toList())
                assertFalse(File(dir.parentFile, "honest.restore.zip").exists())
            },
            "11 entries, its central directory saying 1: stopped as they are unpacked" to {
                val dir = folder.newFolder("lying")
                assertEquals(tooMany, runCatching { UserDataManager.extractWithinRoom(lying.inputStream(), dir, maxEntries = 10) }.exceptionOrNull()?.message)
                assertFalse(File(dir.parentFile, "lying.restore.zip").exists())
            },
            "10 entries: unpacked, the copy gone" to {
                val dir = folder.newFolder("enough")
                assertEquals(10, UserDataManager.extractWithinRoom(zip(10).inputStream(), dir, maxEntries = 10).size)
                assertFalse(File(dir.parentFile, "enough.restore.zip").exists())
            },
        )
    }

    private fun stored(name: String, bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.setMethod(ZipOutputStream.STORED)
            z.putNextEntry(ZipEntry(name).apply { size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value })
            z.write(bytes)
            z.closeEntry()
        }
    }.toByteArray()

    // a non-zip is refused on its first bytes
    private class Counted(private val bytes: ByteArray) : InputStream() {
        var read = 0
        override fun read(): Int = if (read < bytes.size) bytes[read++].toInt() and 0xff else -1
    }

    // copied only within a cap and half the room; no end record, or an inconsistent one, is no backup
    @Test
    @Uat("PR-52")
    fun aFileIsCopiedOnlyIfAZipOfSizeAndRoomAndReadByItsEndRecord() {
        val notBackup = appContext.getString(R.string.exception_user_data_not_a_backup)
        fun refused(bytes: ByteArray, dir: File, room: Long = 1L shl 30, maxBytes: Long = 2L shl 30) =
            runCatching { UserDataManager.extractWithinRoom(bytes.inputStream(), dir, room = room, maxBytes = maxBytes) }.exceptionOrNull()?.message
        fun nothingLeft(dir: File) {
            assertEquals(emptyList<String>(), dir.list()!!.toList())
            assertFalse(File(dir.parentFile, "${dir.name}.restore.zip").exists())
        }
        val random = ByteArray(3000).also { java.util.Random(1).nextBytes(it) }
        assertRows(
            "a video (no zip's first bytes): refused on its first four, none copied" to {
                val dir = folder.newFolder("video")
                val video = Counted(ByteArray(1 shl 16) { 7 })
                assertEquals(notBackup, runCatching { UserDataManager.extractWithinRoom(video, dir) }.exceptionOrNull()?.message)
                assertEquals(4, video.read)
                nothingLeft(dir)
            },
            "a zip past the cap: refused as the copy passes it" to {
                val dir = folder.newFolder("big")
                assertEquals(appContext.getString(R.string.exception_user_data_too_big), refused(stored("a", random), dir, maxBytes = 2000))
                nothingLeft(dir)
            },
            "a zip past half the room: refused as it is copied, nothing unpacked (it would take as much again)" to {
                val dir = folder.newFolder("half")
                assertEquals(appContext.getString(R.string.exception_user_data_no_room), refused(stored("a", random), dir, room = 4096))
                nothingLeft(dir)
            },
            "a zip's local entries without its end record (cut short): no backup, nothing unpacked" to {
                val dir = folder.newFolder("cut")
                val whole = stored("metadata.json", "{}".toByteArray())
                assertEquals(notBackup, refused(whole.copyOf(whole.size - 22), dir))
                nothingLeft(dir)
            },
            "an end record saying more entries than its directory has room for: no backup" to {
                val dir = folder.newFolder("claims")
                val zip = stored("a", random).also { java.nio.ByteBuffer.wrap(it, it.size - 14, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(5000).putShort(5000) }
                assertEquals(notBackup, refused(zip, dir))
                nothingLeft(dir)
            },
            "65 536 entries: zip64's end record read for the count" to {
                val zip = folder.newFile("many.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { z -> repeat(65_536) { z.putNextEntry(ZipEntry("d$it/")); z.closeEntry() } }
                assertEquals(65_536L, ZipEnd.entries(zip))
                val past = runCatching { UserDataManager.extractWithinRoom(zip.inputStream(), folder.newFolder("many"), maxEntries = 65_535) }
                assertEquals(appContext.getString(R.string.exception_user_data_too_many_entries), past.exceptionOrNull()?.message)
            },
            "a restore's copy left an hour ago (the app killed midway) goes; one of the moment's stays" to {
                val stale = folder.newFile("old.restore.zip").apply { setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L) }
                val fresh = folder.newFile("now.restore.zip")
                UserDataManager.extractWithinRoom(stored("a", random).inputStream(), folder.newFolder("ok"))
                assertFalse(stale.exists())
                assertTrue(fresh.exists())
            },
        )
    }

    // hand-made end records: classic, zip64's locator at [recordAt] and its record, each where given
    private fun le(n: Int, v: Long) = ByteArray(n) { (v ushr (8 * it)).toByte() }
    private fun end(total: Int, size: Long, offset: Long) =
        le(4, 0x06054b50) + le(2, 0) + le(2, 0) + le(2, total.toLong()) + le(2, total.toLong()) + le(4, size) + le(4, offset) + le(2, 0)
    private fun locator(recordAt: Long) = le(4, 0x07064b50) + le(4, 0) + le(8, recordAt) + le(4, 1)
    private fun record64(total: Long, size: Long, offset: Long) =
        le(4, 0x06064b50) + le(8, 44) + le(2, 45) + le(2, 45) + le(4, 0) + le(4, 0) + le(8, total) + le(8, total) + le(8, size) + le(8, offset)
    private fun entriesOf(bytes: ByteArray) = ZipEnd.entries(folder.newFile().apply { writeBytes(bytes) })

    // a full classic field is zip64's only with its locator (or all three full); an inconsistent zip64 record is no backup
    @Test
    @Uat("PR-52")
    fun aFullFieldIsReadFromZip64OnlyWhereItsRecordIsThereAndHolds() {
        val n = 65_535
        val dir = 65_535L * 46
        val full = 0xffffffffL
        assertRows(
            "65 535 entries, no zip64 (the count full by chance): the classic record's count" to {
                assertEquals(n.toLong(), entriesOf(ByteArray(dir.toInt()) + end(n, dir, 0)))
            },
            "all three full, no locator: no backup" to {
                assertEquals(null, entriesOf(ByteArray(64) + end(n, full, full)))
            },
            "a locator pointing past the file's end, near Long's (an offset that wraps when added): no backup" to {
                assertEquals(null, entriesOf(ByteArray(64) + locator(Long.MAX_VALUE - 8) + end(n, full, full)))
            },
            "a locator pointing into its own bytes, no room for the record before it: no backup" to {
                assertEquals(null, entriesOf(ByteArray(40) + locator(30) + end(n, full, full)))
            },
            "a zip64 record whose count reads negative (past Long's range): no backup" to {
                val record = record64(-1, 0, 0)
                assertEquals(null, entriesOf(record + locator(0) + end(n, full, full)))
            },
            "a zip64 record that holds (its directory before it): its count" to {
                val record = record64(3, 3L * 46, 0)
                assertEquals(3L, entriesOf(ByteArray(3 * 46) + record + locator(3L * 46) + end(n, full, full)))
            },
        )
    }
}
