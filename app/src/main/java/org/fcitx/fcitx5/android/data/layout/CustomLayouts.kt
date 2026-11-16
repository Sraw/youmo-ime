/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.layout

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.input.layout.LayoutThumbnail
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The user's own layouts (061-custom-layout §4): `files/layouts/<id>.xml`, imported, exported, deleted and restored here;
 * what they are, and the rules of their names and sizes, CustomLayoutFiles' and LayoutImport's (ime-core). A file is put
 * in place whole: written beside it as `<random>.saving`, synced, and renamed over its name, under the lock the list
 * clears what a kill left of such a file under. The list reads each file once, kept by its modified time and size, off
 * the main thread. L3's CustomLayoutSource draws the keyboards from them. Every file is untrusted (one restored, or of an
 * older build's rules): what throws reading or checking one is one that does not read, never the app's crash.
 */
object CustomLayouts {
    const val DIR = "layouts"

    private const val SAVING = ".saving"

    private const val SAVING_LEFT_MS = 60_000L

    /** Where the layouts are: the app's files' `layouts/`. */
    fun dir(context: Context = appContext): File = File(context.filesDir, DIR)

    /**
     * A layout's file as listed: its [id] (its name's), [modified] and [size] as read, and [layout] as it reads (null: it
     * does not, shown so and only deleted), its [fragments] (the app's it includes), its keys in little ([cells]). Its
     * text is not kept: the 信息 reads it again ([text]).
     */
    class Entry(
        val id: String,
        val file: File,
        val modified: Long,
        val size: Long,
        val layout: Layout?,
        val fragments: Map<String, Fragment>,
        val cells: List<LayoutThumbnail.Cell>,
    ) {
        /** Its name as shown: what is not shown ([LayoutCheck.hidden]) left out, its id where nothing is left. */
        val name: String get() = layout?.name?.let(LayoutCheck::plain)?.takeIf { it.isNotBlank() } ?: id
        val family: String? get() = layout?.family
    }

    // by the file's path, as last read (each read once while unchanged)
    private val kept = HashMap<String, Entry>()

    /**
     * The layouts in [dir], read where new or changed since; by name. A `.saving` left by a kill is deleted, one a minute
     * old: one being written is younger (and written under this lock besides).
     */
    @Synchronized
    fun list(dir: File = dir(), context: Context = appContext): List<Entry> {
        val files = dir.listFiles().orEmpty()
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(SAVING) && now - it.lastModified() > SAVING_LEFT_MS }.forEach { it.delete() }
        val layouts = files.filter { it.isFile && CustomLayoutFiles.idOf(it.name) != null }
        kept.keys.retainAll(layouts.map { it.path }.toSet())
        return layouts.map { f ->
            val modified = f.lastModified()
            val size = f.length()
            kept[f.path]?.takeIf { it.modified == modified && it.size == size } ?: read(f, modified, size, context).also { kept[f.path] = it }
        }.sortedWith(compareBy({ it.name }, { it.id }))
    }

    private fun read(f: File, modified: Long, size: Long, context: Context): Entry {
        val id = CustomLayoutFiles.idOf(f.name)!!
        // its name its id's: a file of another id is not this layout (renamed by hand)
        val layout = text(f)?.let { t -> runCatching { LayoutImport.layout(t) }.onFailure { Timber.w(it, "layout %s unread", id) }.getOrNull() }
            ?.takeIf { it.id == id }
        val fragments = layout?.let { l -> runCatching { fragments(l, context) }.onFailure { Timber.w(it, "layout %s: its fragments", id) }.getOrNull() }
        if (layout == null || fragments == null) {
            Timber.w("layout %s does not read", id)
            return Entry(id, f, modified, size, null, emptyMap(), emptyList())
        }
        val cells = runCatching { LayoutThumbnail.cells(layout, fragments) }.onFailure { Timber.w(it, "layout %s: no thumbnail", id) }.getOrDefault(emptyList())
        return Entry(id, f, modified, size, layout, fragments, cells)
    }

    /** [file]'s text, read as an import reads one; null where it does not read so. */
    fun text(file: File): String? =
        runCatching { file.inputStream().use { CustomLayoutFiles.read(it) } }
            .onFailure { Timber.w(it, "layout %s unread", file.name) }.getOrNull()
            .let { (it as? CustomLayoutFiles.Read.Text)?.text }

    /** The app's fragments [layout] includes; null where one is not the app's (the check refuses such a layout). */
    fun fragments(layout: Layout, context: Context = appContext): Map<String, Fragment>? {
        val out = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            if (src !in BuiltInLayouts.FRAGMENTS) return null
            val text = BuiltInLayouts(context).text(src) ?: return null
            out[src] = (LayoutReader.fragment(text) as? Parsed.Ok)?.value ?: return null
        }
        return out
    }

    /** The app's boards and fragments as the check reads them: read on the first check, kept. */
    val sources: LayoutCheck.Sources by lazy {
        val app = BuiltInLayouts(appContext)
        val texts = BuiltInLayouts.RAW.keys.associateWith { app.text(it).orEmpty() }
        LayoutCheck.Sources(texts.filterKeys { it !in BuiltInLayouts.FRAGMENTS }, texts.filterKeys { it in BuiltInLayouts.FRAGMENTS })
    }

    /** A file picked to import, read and checked (§4.2): what [LayoutImport.judge] makes of it, or why it was not read. */
    sealed interface Opened {
        data object TooBig : Opened

        data object NotText : Opened

        class Judged(val text: String, val verdict: LayoutImport.Verdict) : Opened
    }

    /**
     * [input] read as a layout and checked strictly for an app of [versionName] (null: a dev build, any `min-app`) against
     * the layouts in [dir], [theme] (the one in use) for the blank keys' warning. Off the main thread: it builds every scene.
     */
    fun open(input: InputStream, versionName: String?, theme: Theme?, dir: File = dir()): Opened {
        val text = when (val read = CustomLayoutFiles.read(input)) {
            CustomLayoutFiles.Read.TooBig -> return Opened.TooBig
            CustomLayoutFiles.Read.NotText -> return Opened.NotText
            is CustomLayoutFiles.Read.Text -> read.text
        }
        val installed = list(dir).map { e -> e.layout?.let(LayoutImport::installed) ?: LayoutImport.Installed(e.id, e.id, null) }
        // a reader's or a scene's throw on a file made to: refused as one that does not read, not the app's crash
        val verdict = runCatching {
            LayoutImport.judge(text, LayoutCheck.check(text, sources, LayoutImport.appVersion(versionName), theme, strict = true), installed)
        }.getOrElse {
            Timber.w(it, "layout check failed")
            LayoutImport.Verdict.Refused(listOf(LayoutCheck.Finding(0, "read", listOf(it.javaClass.simpleName))), 0)
        }
        return Opened.Judged(text, verdict)
    }

    /** [text], a layout [id], put in [dir] whole, in place of one of its id (§4.1). */
    @Throws(IOException::class)
    fun save(id: String, text: String, dir: File = dir()) = putInPlace(File(dir, CustomLayoutFiles.fileName(id)), text)

    // [text] as [target] whole: written beside it, synced, renamed over it; the folder made where there is none. Under
    // the list's lock, which deletes a `.saving` it finds (one being written here would go from under the rename)
    @Synchronized
    @Throws(IOException::class)
    private fun putInPlace(target: File, text: String) {
        val dir = target.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make ${dir.name}")
        val temp = File(dir, UUID.randomUUID().toString() + SAVING)
        var done = false
        try {
            FileOutputStream(temp).use { it.write(text.encodeToByteArray()); it.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("cannot put ${target.name} in place")
            done = true
        } finally {
            if (!done) temp.delete()
        }
        kept.remove(target.path)
        CustomKeyboards.changed(dir)
    }

    /** Layout [id] taken out of [dir]: whether it is gone. The settings under its keys' names stay (§4.4, Q8). */
    @Synchronized
    fun delete(id: String, dir: File = dir()): Boolean {
        val file = File(dir, CustomLayoutFiles.fileName(id))
        kept.remove(file.path)
        return (file.delete() || !file.exists()).also { CustomKeyboards.changed(dir) }
    }

    /** Layout [id]'s file in [dir], as it is, into [out] (`<名字>.layout`, §2.1). */
    @Throws(IOException::class)
    fun export(id: String, out: OutputStream, dir: File = dir()) {
        File(dir, CustomLayoutFiles.fileName(id)).inputStream().use { it.copyTo(out) }
    }

    /**
     * A backup's `layouts/` ([from], unpacked) laid over the phone's [to] (§4.5), checked for an app of [versionName]
     * (null: a dev build): each layout of it that is one ([CustomLayoutFiles.restorable]) replaces the phone's of its id,
     * the phone's others stay, and one of a new id is taken only while the phone holds fewer than
     * [CustomLayoutFiles.MAX_LAYOUTS] (the phone's own kept, the rest of the backup's logged); the record of the packs
     * converted ([CustomLayoutFiles.CONVERTED]) is both's. Nothing to do where the backup has none (0.6.0's).
     */
    @Throws(IOException::class)
    fun restore(from: File, to: File, versionName: String?) {
        if (!from.isDirectory) return
        val appVersion = LayoutImport.appVersion(versionName)
        val phone = to.list().orEmpty().mapNotNull(CustomLayoutFiles::idOf).toMutableSet()
        val files = from.listFiles().orEmpty().filter { it.isFile }
        for (name in CustomLayoutFiles.restoredNames(files.map { it.name })) {
            val id = CustomLayoutFiles.idOf(name)!!
            if (id !in phone && phone.size >= CustomLayoutFiles.MAX_LAYOUTS) {
                Timber.w("layout %s of the backup not restored: the phone holds %d", name, CustomLayoutFiles.MAX_LAYOUTS)
                continue
            }
            val bytes = File(from, name).takeIf { it.length() <= CustomLayoutFiles.MAX_BYTES }?.readBytes()
            if (bytes != null && CustomLayoutFiles.restorable(name, bytes, sources, appVersion)) {
                save(id, bytes.decodeToString(), to)
                phone += id
            } else {
                Timber.w("layout %s of the backup not restored", name)
            }
        }
        val converted = File(from, CustomLayoutFiles.CONVERTED).takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_CONVERTED_BYTES }
        if (converted != null) {
            val mine = File(to, CustomLayoutFiles.CONVERTED)
            val phoneRecord = mine.takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_CONVERTED_BYTES }?.readText()
            putInPlace(mine, CustomLayoutFiles.mergeConverted(phoneRecord, converted.readBytes().let(CustomLayoutFiles::decode)))
        }
    }

    /** Whether a backup takes [file] of the layouts' folder: a layout's or the record, not one half written. */
    fun backedUp(file: File): Boolean = CustomLayoutFiles.idOf(file.name) != null || file.name == CustomLayoutFiles.CONVERTED

    /** What the list read, forgotten (tests). */
    @VisibleForTesting
    @Synchronized
    internal fun forgetAll() = kept.clear()
}
