/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import android.Manifest
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Document
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
import java.io.File
import java.io.FileNotFoundException
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
    fun aSiblingSharingTheRootsNamePrefixIsOutside() {
        File(pkg, "${rootId}2").mkdirs()
        assertNull(served("${rootId}2"))
    }

    @Test
    fun aSymlinkOutOfTheRootIsRefused() {
        val link = File(root, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), secret.parentFile!!.toPath()) }.isSuccess)
        assertNull(served("$rootId/link/clipboard.db"))
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
}
