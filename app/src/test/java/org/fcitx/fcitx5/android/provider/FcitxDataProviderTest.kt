/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import android.Manifest
import android.content.pm.ProviderInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.FakeFcitxConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Document ids and display names come from other apps. None may lead out of the provider's root
 * into the IME's other data, which sits next to it.
 */
@RunWith(RobolectricTestRunner::class)
class FcitxDataProviderTest {

    // declared as in the manifest: DocumentsProvider.attachInfo refuses anything else
    private val provider = Robolectric.buildContentProvider(FcitxDataProvider::class.java).create(
        ProviderInfo().apply {
            authority = "org.fcitx.fcitx5.android.provider"
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
    ).get()

    // ids are paths from the root's parent; what is put there stands for the IME's private data
    private val root = RuntimeEnvironment.getApplication().getExternalFilesDir(null)!!
    private val pkg = root.parentFile!!
    private val rootId = root.name
    private val secret = File(pkg, "databases/clipboard.db").apply { parentFile!!.mkdirs(); writeText("") }

    /** The id [FcitxDataProvider.queryDocument] answers [docId] with, or null if it refuses it. */
    private fun served(docId: String) = try {
        provider.queryDocument(docId, arrayOf(Document.COLUMN_DOCUMENT_ID)).use {
            it.moveToFirst()
            it.getString(0)
        }
    } catch (_: FileNotFoundException) {
        null
    }

    @Test
    fun idsInsideTheRootAreServedNormalized() {
        File(root, "data").mkdirs()
        assertEquals(rootId, served(rootId))
        assertEquals("$rootId/data", served("$rootId/data/../data"))
        assertEquals("$rootId/data", served("$rootId/./data"))
    }

    @Test
    fun idsThatClimbOutOfTheRootAreRefused() {
        File(root, "data").mkdirs()
        File(pkg, "cache").mkdirs()
        // all of them exist, so only the provider's check can refuse them
        val ids = listOf(
            "",
            "$rootId/..",
            "$rootId/../databases/clipboard.db",
            "$rootId/data/../../cache",
            "$rootId/../../..",
        )
        for (id in ids) {
            assertNull(id, served(id))
        }
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument("$rootId/../databases/clipboard.db") }
        assertTrue(secret.exists())
    }

    @Test
    fun aDocumentIsOpenedToReadAndWriteOnlyInsideTheRoot() {
        File(root, "a.txt").writeText("abc")
        provider.openDocument("$rootId/a.txt", "r", null).use { assertEquals(3L, it.statSize) }
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument("$rootId/a.txt", "wt", null)).use {
            it.write("xy".toByteArray())
        }
        assertEquals("xy", File(root, "a.txt").readText())
        // "wt" truncates: had the check let it through, the secret would be empty
        secret.writeText("kept")
        for (mode in listOf("r", "wt")) {
            assertThrows(mode, FileNotFoundException::class.java) { provider.openDocument("$rootId/../databases/clipboard.db", mode, null) }
        }
        assertEquals("kept", secret.readText())
    }

    @Test
    fun aSiblingSharingTheRootsNamePrefixIsOutside() {
        File(pkg, "${rootId}2").mkdirs()
        assertNull(served("${rootId}2"))
    }

    @Test
    fun aSymlinkOutOfTheRootIsRefused() {
        val link = File(root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), secret.parentFile!!.toPath()) }.isSuccess)
        assertNull(served("$rootId/link/clipboard.db"))
        assertThrows(FileNotFoundException::class.java) { provider.openDocument("$rootId/link/clipboard.db", "r", null) }
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument("$rootId/link/clipboard.db") }
        assertTrue(secret.exists())
    }

    @Test
    fun aChildIsInsideItsParentByPathNotByIdPrefix() {
        assertTrue(provider.isChildDocument(rootId, "$rootId/data/x"))
        assertTrue(provider.isChildDocument("$rootId/data", "$rootId/data/x"))
        assertFalse(provider.isChildDocument("$rootId/data", "$rootId/database/x"))
        assertFalse(provider.isChildDocument("$rootId/data", "$rootId/data/../x"))
        assertFalse(provider.isChildDocument(rootId, "$rootId/../databases/clipboard.db"))
    }

    @Test
    fun aNewDocumentIsNamedNotPlaced() {
        assertEquals("$rootId/notes.txt", provider.createDocument(rootId, "text/plain", "notes.txt"))
        assertTrue(File(root, "notes.txt").isFile)
        for (name in listOf("", ".", "..", "../x", "a/b")) {
            assertThrows(name, FileNotFoundException::class.java) { provider.createDocument(rootId, "text/plain", name) }
        }
        assertFalse(File(pkg, "x").exists())
    }

    @Test
    fun aRenameKeepsTheDocumentInsideTheRoot() {
        File(root, "a.txt").writeText("")
        for (name in listOf("", "..", "../a.txt", "a/b")) {
            assertThrows(name, FileNotFoundException::class.java) { provider.renameDocument("$rootId/a.txt", name) }
        }
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument(rootId, "x") }
        assertTrue(root.isDirectory)
        assertFalse(File(pkg, "x").exists())
        assertEquals("$rootId/b.txt", provider.renameDocument("$rootId/a.txt", "b.txt"))
        assertTrue(File(root, "b.txt").isFile)
    }

    @Test
    fun aRenameOrMoveThatFailsSaysSoRatherThanNamingNoFile() {
        File(root, "dir").mkdirs()
        // gone since the file manager listed it: there is nothing to rename or move
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument("$rootId/gone.txt", "b.txt") }
        assertThrows(FileNotFoundException::class.java) { provider.moveDocument("$rootId/gone.txt", rootId, "$rootId/dir") }
        assertFalse(File(root, "b.txt").exists())
        assertFalse(File(root, "dir/gone.txt").exists())
    }

    @Test
    fun aRenameThatFailsSaysWhy() {
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")
        fun reason(id: String, name: String) =
            assertThrows(FileNotFoundException::class.java) { provider.renameDocument(id, name) }
                .message!!.substringAfter("failed: ")
        assertEquals("outside the root", reason(rootId, "x"))
        assertEquals("target exists", reason("$rootId/a.txt", "b.txt"))
        assertEquals("rename failed", reason("$rootId/gone.txt", "c.txt"))
        assertEquals("a", File(root, "a.txt").readText())
        assertEquals("b", File(root, "b.txt").readText())
    }

    @Test
    fun aChildIsWhereItsSymlinksLead() {
        File(root, "data").mkdirs()
        File(root, "other").mkdirs()
        val alias = File(root, "data/alias")
        assumeTrue(runCatching { Files.createSymbolicLink(alias.toPath(), File(root, "other").toPath()) }.isSuccess)
        assertTrue(provider.isChildDocument("$rootId/other", "$rootId/data/alias/x"))
        assertFalse(provider.isChildDocument("$rootId/data", "$rootId/data/alias/x"))
    }

    /** A client is promised FileNotFoundException; any other IOException reaches it as a crash. */
    @Test
    fun anIdThatCannotBeResolvedIsNotFound() {
        // the JVM resolves no path with a NUL in it
        val id = "$rootId/a\u0000b"
        assertNull(served(id))
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument(id) }
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument(id, "b.txt") }
        assertFalse(provider.isChildDocument(rootId, id))
    }

    /** What a write through the provider reloads fcitx for: it would otherwise save its own copy back over it. */
    @Test
    fun fcitxReadsItsConfigAndPunctuationAgainOnReload() {
        assertTrue(File(root, "config/config").isReadOnReload(root))
        assertTrue(File(root, "config/profile").isReadOnReload(root))
        assertTrue(File(root, "config/conf/punctuation.conf").isReadOnReload(root))
        assertTrue(File(root, "data/punctuation/punc.mb.zh_CN").isReadOnReload(root))
        assertFalse(File(root, "data/table/wbx.main.dict").isReadOnReload(root))
        assertFalse(File(root, "configs/x").isReadOnReload(root))
        assertFalse(File(root, "notes.txt").isReadOnReload(root))
    }

    /** The engine last: a reloadConfig alone once left the candidates unshown till fcitx restarted. */
    @Test
    fun aReloadReadsTheEngineAgainAfterFcitxsConfig() {
        val fcitx = FakeFcitxAPI()
        runBlocking { fcitx.reloadWritten() }
        assertEquals(listOf("reloadConfig()", "reloadEngine()"), fcitx.calls)
    }

    /** A write of a file fcitx reads on reload reloads it once closed; a read, or a write of another file, does not. */
    @Test
    fun aFinishedWriteOfAFileFcitxReadsOnReloadReloadsIt() {
        val reloaded = mutableListOf<String>()
        provider.reload = { reloaded += it.name }
        File(root, "config").mkdirs()
        File(root, "config/x").writeText("a")
        File(root, "notes.txt").writeText("a")
        fun write(id: String, text: String) =
            ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "wt", null)).use { it.write(text.toByteArray()) }
        provider.openDocument("$rootId/config/x", "r", null).close()
        write("$rootId/notes.txt", "b")
        write("$rootId/config/x", "b")
        // the close is reported on the main looper
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("b", File(root, "config/x").readText())
        assertEquals("b", File(root, "notes.txt").readText())
        assertEquals(listOf("x"), reloaded)
    }

    /**
     * What that reload asks of fcitx: [reloadWritten]'s config, then the engine. At minSdk: from
     * Android 14 the subtypes are synced after them, which needs the app these tests go without.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @Config(sdk = [23])
    fun aFinishedWriteHasFcitxReadItsConfigThenTheEngine() = runTest {
        val fcitx = FakeFcitxConnection(this)
        provider.connection = { fcitx }
        File(root, "config/config").apply { parentFile!!.mkdirs(); writeText("a") }
        // not through an AutoCloseOutputStream: at SDK 23 it closes the descriptor first, and Robolectric's
        // shadow, re-entered from the stream it closes, reports that one close twice; a device reports it once
        provider.openDocument("$rootId/config/config", "wt", null).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { it.write("b".toByteArray()) }
        }
        // the close is reported on the main looper, the reload then run in fcitx's scope
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertEquals(listOf("reloadConfig()", "reloadEngine()"), fcitx.api.calls)
    }
}
