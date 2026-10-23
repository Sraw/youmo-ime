/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.isPlainFileName
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class FcitxDataProvider : DocumentsProvider() {

    companion object {
        private const val MIME_TYPE_WILDCARD = "*/*"
        private const val MIME_TYPE_TEXT = "text/plain"
        private const val MIME_TYPE_BIN = "application/octet-stream"

        private val TEXT_EXTENSIONS = arrayOf(
            "conf",
            "mb",
            "yml",
            "yaml"
        )

        // path relative to baseDir that should be recognize as text files
        private val TEXT_FILES = arrayOf(
            "config/config",
            "config/profile",
            "data/punctuation/punc.mb.zh_CN",
            "data/punctuation/punc.mb.zh_HK",
            "data/punctuation/punc.mb.zh_TW"
        )

        // The default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        // The default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private const val SEARCH_RESULTS_LIMIT = 50
    }

    private lateinit var baseDir: File
    private lateinit var docIdPrefix: String
    private lateinit var textFilePaths: Array<String>

    // lazy: canonicalFile can throw, and onCreate runs at app start
    private val canonicalBaseDir by lazy { baseDir.canonical() }

    private val File.docId
        get() = absolutePath.removePrefix(docIdPrefix)

    // ids come from other apps: one like "files/../.." would reach the IME's internal storage with its permissions
    private fun fileFromDocId(docId: String) =
        File(docIdPrefix, docId).normalizedWithin(canonicalBaseDir) ?: throw outsideTheRoot(docId)

    // fileFromDocId's check, keeping what it resolved: isChildDocument runs on every tree-URI operation
    private fun canonicalFromDocId(docId: String) =
        File(docIdPrefix, docId).normalize().canonical().takeIf { it.startsWith(canonicalBaseDir) }
            ?: throw outsideTheRoot(docId)

    private fun outsideTheRoot(docId: String) =
        FileNotFoundException("document id=$docId is outside ${baseDir.docId}")

    override fun onCreate(): Boolean {
        baseDir = context!!.getExternalFilesDir(null) ?: return false
        docIdPrefix = "${baseDir.parent}${File.separator}"
        textFilePaths = Array(TEXT_FILES.size) { baseDir.resolve(TEXT_FILES[it]).absolutePath }
        return true
    }

    override fun queryRoots(projection: Array<String>?) =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, baseDir.docId)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, R.mipmap.app_icon)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_DOCUMENT_ID, baseDir.docId)
                add(Root.COLUMN_MIME_TYPES, MIME_TYPE_WILDCARD)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            newRowFromFile(fileFromDocId(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        fileFromDocId(parentDocumentId).listFiles()?.forEach {
            newRowFromFile(it)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFromDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) == 0 || !file.isReadOnReload(baseDir)) {
            return ParcelFileDescriptor.open(file, accessMode)
        }
        // fcitx holds these in memory and saves its own back over them: an edit applies only once it reloads
        return try {
            ParcelFileDescriptor.open(file, accessMode, Handler(Looper.getMainLooper())) { error ->
                // a writer that failed or died left part of a file: fcitx's own copy is better
                if (error == null) {
                    reload(file)
                } else {
                    Timber.w(error, "Not reloading fcitx: the write to %s did not finish", file.name)
                }
            }
        } catch (e: IOException) {
            // making the pipe that reports the close can fail too: a client is told only FileNotFoundException
            throw FileNotFoundException("openDocument id=$documentId failed: ${e.message}").apply { initCause(e) }
        }
    }

    // a test counts the calls instead: reloading needs fcitx running
    internal var reload: (File) -> Unit = { reloadFcitx(it) }

    // a test's fake instead, to see what a reload asks of fcitx
    internal var connection: () -> FcitxConnection? = { FcitxDaemon.getFirstConnectionOrNull() }

    // a reload waiting for fcitx to be ready reads every file written meanwhile: one is enough
    private val reloadQueued = AtomicBoolean(false)

    // fcitx that is not running reads the files when it starts
    private fun reloadFcitx(written: File) {
        val fcitx = connection()
        if (fcitx == null) {
            Timber.d("Not reloading fcitx for %s: it is not running", written.name)
            return
        }
        if (!reloadQueued.compareAndSet(false, true)) {
            Timber.d("Not reloading fcitx again for %s: the reload on its way reads it", written.name)
            return
        }
        // once ready: fcitx still starting may have read the file before it was written, and
        // runIfReady would drop the reload
        Timber.d("Reloading fcitx for %s once it is ready", written.name)
        var ran = false
        fcitx.lifecycleScope.launch { fcitx.runOnReady {
            // before the files are read: one written from here on has a reload of its own
            ran = true
            reloadQueued.set(false)
            reloadConfig()
            // what the removed status-area entry did: config/profile holds the enabled input methods
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SubtypeManager.syncWith(enabledIme())
            }
            Timber.d("Reloaded fcitx for %s", written.name)
        } }.invokeOnCompletion {
            // cancelled as fcitx stops, before it ran even: the next write is not left waiting on it.
            // Not once it ran: the flag may be a newer reload's by now
            if (!ran) reloadQueued.set(false)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = fileFromDocId(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val newFile = createAbstractFile(parentDocumentId, displayName)
        try {
            val ok = if (mimeType == Document.MIME_TYPE_DIR) {
                newFile.mkdir()
            } else {
                newFile.createNewFile()
            }
            if (!ok) {
                throw FileNotFoundException("createDocument id=${newFile.path} failed")
            }
        } catch (e: IOException) {
            throw FileNotFoundException("createDocument id=${newFile.path} failed: ${e.message}")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        fileFromDocId(documentId).apply {
            val ok = if (isDirectory) {
                deleteRecursively()
            } else {
                delete()
            }
            if (!ok) {
                throw FileNotFoundException("deleteDocument id=$documentId failed")
            }
        }
    }

    override fun getDocumentType(documentId: String): String {
        return fileFromDocId(documentId).mimeType
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            canonicalFromDocId(documentId).startsWith(canonicalFromDocId(parentDocumentId))
        } catch (_: IOException) {
            false
        }
    }

    @Throws(FileNotFoundException::class)
    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        val newFile = createAbstractFile(targetParentDocumentId, oldFile.name)
        oldFile.apply {
            try {
                val ok = if (isDirectory) {
                    copyRecursively(newFile)
                } else {
                    copyTo(newFile).exists()
                }
                if (!ok) {
                    throw FileNotFoundException("copyDocument id=$sourceDocumentId to ${newFile.docId} failed")
                }
            } catch (e: Exception) {
                throw FileNotFoundException("copyDocument id=$sourceDocumentId to ${newFile.docId} failed: ${e.message}")
            }
        }
        return newFile.docId
    }

    // a throw for each way it fails, each saying which
    @Suppress("ThrowsCount")
    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        val oldFile = fileFromDocId(documentId)
        requirePlainDisplayName(displayName)
        val failed = "renameDocument id=$documentId to $displayName failed"
        // a sibling of the root itself is outside it
        val newFile = oldFile.resolveSibling(displayName).normalizedWithin(canonicalBaseDir)
            ?: throw FileNotFoundException("$failed: outside the root")
        if (newFile.exists()) throw FileNotFoundException("$failed: target exists")
        if (!oldFile.renameTo(newFile)) throw FileNotFoundException("$failed: rename failed")
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        val newFile = createAbstractFile(targetParentDocumentId, oldFile.name)
        if (!oldFile.renameTo(newFile)) {
            throw FileNotFoundException("moveDocument id=$sourceDocumentId to ${newFile.docId} failed")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        val q = query.lowercase()
        fileFromDocId(rootId).walk()
            .filter { it.name.lowercase().contains(q) }
            .take(SEARCH_RESULTS_LIMIT)
            .forEach { newRowFromFile(it) }
    }

    private val File.mimeType: String
        get() = when {
            isDirectory -> Document.MIME_TYPE_DIR
            TEXT_EXTENSIONS.contains(extension) -> MIME_TYPE_TEXT
            textFilePaths.contains(absolutePath) -> MIME_TYPE_TEXT
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MIME_TYPE_BIN
        }

    private fun requirePlainDisplayName(displayName: String) {
        if (!isPlainFileName(displayName)) {
            throw FileNotFoundException("invalid display name $displayName")
        }
    }

    private fun createAbstractFile(parentDocumentId: String, displayName: String): File {
        requirePlainDisplayName(displayName)
        val parent = fileFromDocId(parentDocumentId)
        var newFile = parent.resolve(displayName)
        var noConflictId = 2
        while (newFile.exists()) {
            newFile = parent.resolve("$displayName ($noConflictId)")
            noConflictId += 1
        }
        return newFile
    }

    @Throws(FileNotFoundException::class)
    private fun MatrixCursor.newRowFromFile(file: File) {
        if (!file.exists()) {
            throw FileNotFoundException("File(path=${file.absolutePath}) not found")
        }

        val mimeType = file.mimeType
        var flags = Document.FLAG_SUPPORTS_COPY
        if (file.canWrite()) {
            flags = flags or if (file.isDirectory) {
                Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                Document.FLAG_SUPPORTS_WRITE
            }
        }
        if (file.parentFile?.canWrite() == true) {
            flags = flags or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME or
                    Document.FLAG_SUPPORTS_MOVE
        }
        if (mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.docId)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, file.length())
        }
    }
}

/** This file normalized, or null if it resolves, symlinks followed, outside [canonicalRoot]. */
internal fun File.normalizedWithin(canonicalRoot: File): File? =
    normalize().takeIf { it.canonical().startsWith(canonicalRoot) }

// DocumentsProvider passes a FileNotFoundException on to the client as such, a plain IOException as a crash
private fun File.canonical(): File = try {
    canonicalFile
} catch (e: IOException) {
    throw FileNotFoundException("cannot resolve $path: ${e.message}").apply { initCause(e) }
}

/** Whether fcitx reads [this] again on reload: its own and its addons' config, and the punctuation tables. */
internal fun File.isReadOnReload(root: File): Boolean =
    startsWith(root.resolve("config")) || startsWith(root.resolve("data/punctuation"))

