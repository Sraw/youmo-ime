/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.layout

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.layout.CustomLayoutChoices
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The user's layouts as keyboards (061-custom-layout §5, L3): each one an input method chose read from its file
 * ([CustomLayouts.dir]) and held to what it must be to be drawn ([CustomLayoutChoices]), kept by the file's time and
 * size, so a file replaced is read again and its keyboard made anew ([CustomChoice.stamp]). Asked on the main thread
 * (the keyboard, the settings) and the fcitx thread (EngineBridge.configure): one stat a call where the file is as it was.
 */
object CustomKeyboards {
    private class Kept(val modified: Long, val size: Long, val choice: CustomChoice?, val problems: List<LayoutCheck.Finding>)

    // by id
    private val kept = HashMap<String, Kept>()

    /** Where the files are read from: the app's; a test's. */
    @VisibleForTesting
    var dir: () -> File = { CustomLayouts.dir() }
        set(value) {
            field = value
            forget()
            forgetListed()
        }

    // [all] as last listed (null: not yet, or changed since), for what asks on each input start; written by the lister
    // alone, a listing begun before a change ([changed]: [generation] moved on) dropped, so none is older than one asked
    // after (L3 fix re-check S-2: the main thread's own [all] wrote a slow stale one over a newer)
    @Volatile
    private var listed: List<CustomChoice>? = null

    private val generation = AtomicInteger()

    // what [generation] and [listed] change under: a listing's result kept only where none came since
    private val listing = Any()

    // one thread, so a list asked after a change ends after one begun before it
    private val lister by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, LISTER).apply { isDaemon = true } } }

    /** The app the files are held to: its `min-app=`s (a dev build: any). */
    private val appVersion: String get() = LayoutImport.appVersion(ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME))

    /** Layout [id] as the keyboard options see it ([CustomChoice.drawn] false where it is refused); null where there is no such file or it does not read. */
    @Synchronized
    fun choice(id: String): CustomChoice? {
        val file = fileOf(id) ?: return null
        val modified = file.lastModified()
        val size = file.length()
        if (!file.isFile) {
            kept.remove(id)
            return null
        }
        return kept(id, file, modified, size).choice
    }

    /** Why layout [id] is not drawn as last read ([choice]; empty where it is, or does not read at all). */
    @Synchronized
    fun problems(id: String): List<LayoutCheck.Finding> {
        val file = fileOf(id)?.takeIf { it.isFile } ?: return emptyList()
        return kept(id, file, file.lastModified(), file.length()).problems
    }

    // what [file] reads as, kept by its time and size
    private fun kept(id: String, file: File, modified: Long, size: Long): Kept =
        kept[id]?.takeIf { it.modified == modified && it.size == size } ?: read(id, file, modified, size).also { kept[id] = it }

    /**
     * Every layout of the user's that reads, in the list's order: what the input methods may be offered
     * (ImKeyboards.customs). It lists the folder and reads each file new or changed (its thumbnail made, held to what it
     * must be): off the main thread where it may read many; ⋯ and the settings ask it as they open.
     */
    fun all(): List<CustomChoice> = CustomLayouts.list(dir()).mapNotNull { choice(it.id) }

    /**
     * [all] as last listed, no file touched: what each input start asks (KeyboardPicker.offers, the toolbar arranged: L3
     * review S3). Empty till first listed, which this starts ([relist]); listed anew as a layout is saved, deleted or
     * restored ([changed]).
     */
    fun listed(): List<CustomChoice> = listed ?: emptyList<CustomChoice>().also { relist() }

    /** [all] listed off the main thread: as the keyboard starts, and after a change. */
    fun relist() {
        lister.execute {
            val asked = generation.get()
            runCatching { all() }
                .onSuccess { synchronized(listing) { if (generation.get() == asked) listed = it } }
                .onFailure { Timber.w(it, "layouts not listed") }
        }
    }

    /** The layouts in [folder] changed (one saved, deleted, restored): where they are these, listed anew off the main thread. */
    fun changed(folder: File) {
        if (runCatching { dir() }.getOrNull() != folder) return
        forgetListed()
        relist()
    }

    private fun forgetListed() = synchronized(listing) {
        generation.incrementAndGet()
        listed = null
    }

    /** Waits for the listings asked so far to end (tests); not on the lister, which would wait on itself. */
    @VisibleForTesting
    fun awaitListed() {
        check(Thread.currentThread().name != LISTER) { "awaitListed on the lister thread" }
        lister.submit {}.get(LISTED_WAIT_S, TimeUnit.SECONDS)
    }

    /**
     * Why [layout], read from layout [id]'s file to be drawn as its file was at [stamp], is not (CustomLayoutSource):
     * another id than its file's, another family or base than the keyboard was chosen by (the file replaced in between:
     * a letters keyboard would draw grouped keys), or what [LayoutCheck.loaded] finds; null where it is drawn.
     */
    fun refusal(id: String, stamp: String, layout: Layout): String? {
        if (layout.id != id) return "its id is ${layout.id}, its file's $id"
        val chosen = choice(id) ?: return "its file does not read"
        if (chosen.stamp != stamp) return "its file changed since: ${chosen.stamp}, not $stamp"
        val base = baseOf(layout)
        if (layout.family != chosen.family || base != chosen.basedOn) return "a ${layout.family} on $base, chosen as a ${chosen.family} on ${chosen.basedOn}"
        val problems = runCatching { LayoutCheck.loaded(layout, CustomLayouts.sources, appVersion) }.getOrElse { return it.toString() }
        return problems.takeIf { it.isNotEmpty() }?.joinToString { "${it.key} ${it.args} (line ${it.line})" }
    }

    /** The app's board [layout] is drawn as where it cannot be, of its own family ([LayoutCheck.baseId]). */
    fun baseOf(layout: Layout): String? = LayoutCheck.baseId(layout, CustomLayouts.sources)

    /** The text of layout [id]'s file; null where there is none or it does not read. */
    fun text(id: String): String? = fileOf(id)?.takeIf { it.isFile }?.let(CustomLayouts::text)

    // its file; null for an id no file is named so (a pref restored or edited by hand: fileName would throw, on the fcitx thread too)
    private fun fileOf(id: String): File? =
        id.takeIf(CustomLayoutFiles::validId)?.let { File(dir(), CustomLayoutFiles.fileName(it)) }

    private fun read(id: String, file: File, modified: Long, size: Long): Kept {
        val none = Kept(modified, size, null, emptyList())
        val layout = CustomLayouts.text(file)?.let { t -> runCatching { LayoutImport.layout(t) }.getOrNull() }?.takeIf { it.id == id }
            ?: return none.also { Timber.w("layout %s does not read", id) }
        val read = runCatching { CustomLayoutChoices.of(layout, CustomLayouts.sources, appVersion, "$modified-$size") }
            .onFailure { Timber.w(it, "layout %s not taken", id) }.getOrNull() ?: return none
        if (read.problems.isNotEmpty()) Timber.w("layout %s not drawn: %s", id, read.problems.joinToString { "${it.key} ${it.args}" })
        return Kept(modified, size, read.choice, read.problems)
    }

    /** What was read, forgotten: read again as next asked (a test; a layout deleted). */
    @Synchronized
    fun forget(id: String? = null) {
        if (id == null) kept.clear() else kept.remove(id)
    }

    private const val LISTED_WAIT_S = 10L

    private const val LISTER = "custom-layouts"
}
