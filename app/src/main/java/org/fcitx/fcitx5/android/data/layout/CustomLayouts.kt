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

/** Every file is untrusted: anything thrown reading or checking one makes it unreadable, never a crash. */
object CustomLayouts {
    const val DIR = "layouts"

    private const val SAVING = ".saving"

    private const val SAVING_LEFT_MS = 60_000L

    fun dir(context: Context = appContext): File = File(context.filesDir, DIR)

    /** [layout] null: does not read, shown so and only deleted. [fragments]: the app's it includes. */
    class Entry(
        val id: String,
        val file: File,
        val modified: Long,
        val size: Long,
        val layout: Layout?,
        val fragments: Map<String, Fragment>,
        val cells: List<LayoutThumbnail.Cell>,
    ) {
        /** Hidden characters ([LayoutCheck.hidden]) left out, its id where nothing is left. */
        val name: String get() = layout?.name?.let(LayoutCheck::plain)?.takeIf { it.isNotBlank() } ?: id
        val family: String? get() = layout?.family
    }

    // by path, as last read (each read once while unchanged)
    private val kept = HashMap<String, Entry>()

    /** A minute-old `.saving` left by a kill is deleted (one being written is younger, and under this lock). */
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
        // a file of another id is not this layout (renamed by hand)
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

    fun text(file: File): String? =
        runCatching { file.inputStream().use { CustomLayoutFiles.read(it) } }
            .onFailure { Timber.w(it, "layout %s unread", file.name) }.getOrNull()
            .let { (it as? CustomLayoutFiles.Read.Text)?.text }

    /** Null where one is not the app's (the check refuses such a layout). */
    fun fragments(layout: Layout, context: Context = appContext): Map<String, Fragment>? {
        val out = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            if (src !in BuiltInLayouts.FRAGMENTS) return null
            val text = BuiltInLayouts(context).text(src) ?: return null
            out[src] = (LayoutReader.fragment(text) as? Parsed.Ok)?.value ?: return null
        }
        return out
    }

    val sources: LayoutCheck.Sources by lazy {
        val app = BuiltInLayouts(appContext)
        val texts = BuiltInLayouts.RAW.keys.associateWith { app.text(it).orEmpty() }
        LayoutCheck.Sources(texts.filterKeys { it !in BuiltInLayouts.FRAGMENTS }, texts.filterKeys { it in BuiltInLayouts.FRAGMENTS })
    }

    sealed interface Opened {
        data object TooBig : Opened

        data object NotText : Opened

        class Judged(val text: String, val verdict: LayoutImport.Verdict) : Opened
    }

    /** Null [versionName]: a dev build, any `min-app`. Off the main thread: it builds every scene. */
    fun open(input: InputStream, versionName: String?, theme: Theme?, dir: File = dir()): Opened {
        val text = when (val read = CustomLayoutFiles.read(input)) {
            CustomLayoutFiles.Read.TooBig -> return Opened.TooBig
            CustomLayoutFiles.Read.NotText -> return Opened.NotText
            is CustomLayoutFiles.Read.Text -> read.text
        }
        val installed = list(dir).map { e -> e.layout?.let(LayoutImport::installed) ?: LayoutImport.Installed(e.id, e.id, null) }
        // a reader's or scene's throw on a crafted file: refused as unreadable, not a crash
        val verdict = runCatching {
            LayoutImport.judge(text, LayoutCheck.check(text, sources, LayoutImport.appVersion(versionName), theme, strict = true), installed)
        }.getOrElse {
            Timber.w(it, "layout check failed")
            LayoutImport.Verdict.Refused(listOf(LayoutCheck.Finding(0, "read", listOf(it.javaClass.simpleName))), 0)
        }
        return Opened.Judged(text, verdict)
    }

    @Throws(IOException::class)
    fun save(id: String, text: String, dir: File = dir()) = putInPlace(File(dir, CustomLayoutFiles.fileName(id)), text)

    // under the list's lock, which deletes any `.saving` it finds
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
        // not the record, which is no layout: forgetting the list for it left KeyboardPicker.offers empty till relisted
        if (CustomLayoutFiles.idOf(target.name) != null) CustomKeyboards.changed(dir)
    }

    /** Settings under its keys' names stay. */
    @Synchronized
    fun delete(id: String, dir: File = dir()): Boolean {
        val file = File(dir, CustomLayoutFiles.fileName(id))
        kept.remove(file.path)
        return (file.delete() || !file.exists()).also { CustomKeyboards.changed(dir) }
    }

    @Throws(IOException::class)
    fun export(id: String, out: OutputStream, dir: File = dir()) {
        File(dir, CustomLayoutFiles.fileName(id)).inputStream().use { it.copyTo(out) }
    }

    /** Each restorable layout replaces the phone's of its id, others stay; a new id only below the limit. Record merged. */
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

    @Synchronized
    fun record(dir: File = dir()): CustomLayoutFiles.Record =
        CustomLayoutFiles.record(
            File(dir, CustomLayoutFiles.CONVERTED).takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_CONVERTED_BYTES }
                ?.readBytes()?.let(CustomLayoutFiles::decode),
        )

    @Synchronized
    @Throws(IOException::class)
    fun recordConverted(lines: List<String>, dir: File = dir()) {
        val mine = File(dir, CustomLayoutFiles.CONVERTED)
        val record = mine.takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_CONVERTED_BYTES }?.readBytes()?.let(CustomLayoutFiles::decode)
        putInPlace(mine, CustomLayoutFiles.mergeConverted(record, (listOf(CustomLayoutFiles.CURRENT) + lines).joinToString("\n")))
    }

    /** A layout's or the record, not one half written. */
    fun backedUp(file: File): Boolean = CustomLayoutFiles.idOf(file.name) != null || file.name == CustomLayoutFiles.CONVERTED

    @VisibleForTesting
    @Synchronized
    internal fun forgetAll() = kept.clear()
}
