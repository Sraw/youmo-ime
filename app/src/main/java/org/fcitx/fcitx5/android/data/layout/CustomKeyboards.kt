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
 * The user's layouts as keyboards: each read from its file, held to [CustomLayoutChoices], cached by mtime and size so a
 * replaced file is reread ([CustomChoice.stamp]). Asked on the main and fcitx threads: one stat a call when unchanged.
 */
object CustomKeyboards {
    private class Kept(val modified: Long, val size: Long, val choice: CustomChoice?, val problems: List<LayoutCheck.Finding>)

    private val kept = HashMap<String, Kept>()

    @VisibleForTesting
    var dir: () -> File = { CustomLayouts.dir() }
        set(value) {
            field = value
            forget()
            forgetListed()
        }

    // written by the lister alone; a listing begun before a [changed] is dropped, so a slow stale one never overwrites a newer
    @Volatile
    private var listed: List<CustomChoice>? = null

    private val generation = AtomicInteger()

    // a listing's result kept only where none came since
    private val listing = Any()

    // one thread, so a list asked after a change ends after one begun before it
    private val lister by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, LISTER).apply { isDaemon = true } } }

    /** Its `min-app=`s (a dev build: any). */
    private val appVersion: String get() = LayoutImport.appVersion(ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME))

    /** [layout] as it would be chosen once saved: an import asks it before the file is there. */
    fun choiceOf(layout: Layout): CustomChoice = CustomLayoutChoices.of(layout, CustomLayouts.sources, appVersion, "").choice

    /** [CustomChoice.drawn] false where refused; null where there is no such file or it does not read. */
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

    /** As last read; empty where drawn or unreadable. */
    @Synchronized
    fun problems(id: String): List<LayoutCheck.Finding> {
        val file = fileOf(id)?.takeIf { it.isFile } ?: return emptyList()
        return kept(id, file, file.lastModified(), file.length()).problems
    }

    private fun kept(id: String, file: File, modified: Long, size: Long): Kept =
        kept[id]?.takeIf { it.modified == modified && it.size == size } ?: read(id, file, modified, size).also { kept[id] = it }

    /** Reads each new or changed file (thumbnail made, checked): off the main thread where many. */
    fun all(): List<CustomChoice> = CustomLayouts.list(dir()).mapNotNull { choice(it.id) }

    /** No file touched: what each input start asks. Empty till first listed, which this starts; relisted on [changed]. */
    fun listed(): List<CustomChoice> = listed ?: emptyList<CustomChoice>().also { relist() }

    /** Off the main thread: as the keyboard starts, and after a change. */
    fun relist() {
        lister.execute {
            val asked = generation.get()
            runCatching { all() }
                .onSuccess { synchronized(listing) { if (generation.get() == asked) listed = it } }
                .onFailure { Timber.w(it, "layouts not listed") }
        }
    }

    /** Relisted off the main thread where [folder] is ours. */
    fun changed(folder: File) {
        if (runCatching { dir() }.getOrNull() != folder) return
        forgetListed()
        relist()
    }

    private fun forgetListed() = synchronized(listing) {
        generation.incrementAndGet()
        listed = null
    }

    /** Not on the lister, which would wait on itself. */
    @VisibleForTesting
    fun awaitListed() {
        check(Thread.currentThread().name != LISTER) { "awaitListed on the lister thread" }
        lister.submit {}.get(LISTED_WAIT_S, TimeUnit.SECONDS)
    }

    /**
     * Why it is not drawn as its file was at [stamp]: another id, family or base than chosen by (the file replaced in
     * between), or [LayoutCheck.loaded]'s finding; null where drawn.
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

    /** Of its own family ([LayoutCheck.baseId]). */
    fun baseOf(layout: Layout): String? = LayoutCheck.baseId(layout, CustomLayouts.sources)

    /** Null where there is none or it does not read. */
    fun text(id: String): String? = fileOf(id)?.takeIf { it.isFile }?.let(CustomLayouts::text)

    // null for an id no file can have (a restored or hand-edited pref: fileName would throw, on the fcitx thread too)
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

    /** Reread next asked (a test; a layout deleted). */
    @Synchronized
    fun forget(id: String? = null) {
        if (id == null) kept.clear() else kept.remove(id)
    }

    private const val LISTED_WAIT_S = 10L

    private const val LISTER = "custom-layouts"
}
