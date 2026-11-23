/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.layout

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.data.theme.ThemePackLayouts
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.PanelLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.input.layout.RenamedKeys
import org.fcitx.fcitx5.android.input.layout.ThemeLayoutSwitch
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

/**
 * Lists a theme pack's boards in 外观 → 布局 as user layouts (packs no longer replace the app's boards): old packs at
 * the first start, a backup's after a restore, an import with 「一起导入」. Recorded by `file@stamp`, so the same pack
 * again does nothing; a reimport updates layouts it wrote, keeping user-changed ones.
 */
object ThemeLayoutsToList {

    class Listed(val board: String, val done: PackLayouts.Converted.Done, val put: CustomLayoutFiles.Put = CustomLayoutFiles.Put.NEW)

    /** Device-local: a restore's packs are handled by [restored]. */
    @VisibleForTesting
    internal var listedFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-listed") }

    @VisibleForTesting
    internal var restoredFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-restored") }

    /** A start finding it ended midway (killed, common as settings close) and retries, at most [MAX_TRIES]. */
    @VisibleForTesting
    internal var tryingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-trying") }

    private const val SWITCH = "switch"
    private const val LIST = "list"

    const val MAX_TRIES = 3

    fun appVersion(): String = LayoutImport.appVersion(ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME))

    /** What throws making one is one not made: a pack is untrusted. */
    fun convert(
        layouts: File,
        theme: String,
        meta: Meta?,
        appVersion: String = appVersion(),
        boards: Collection<String> = KeyData.LISTED,
        ids: (String) -> String = { PackLayouts.idOf(theme, it) },
    ): List<Pair<String, PackLayouts.Converted>> {
        val fragments = PackLayouts.FRAGMENTS.mapNotNull { src -> ThemePackLayouts.read(layouts, src)?.let { src to it } }.toMap()
        return boards.mapNotNull { board ->
            val file = File(layouts, "$board.xml")
            if (!file.isFile) return@mapNotNull null
            if (file.length() > ThemeAssetLimits.MAX_LAYOUT_BYTES) return@mapNotNull board to PackLayouts.Converted.Not("too big")
            board to runCatching { PackLayouts.convert(theme, meta, board, file.readText(), fragments, CustomLayouts.sources, appVersion, ids(board)) }
                .getOrElse { PackLayouts.Converted.Not(it.toString()) }
        }
    }

    /** [same]: the recorded import is listed again; [made]: a board's digest under an id, null where not made. */
    fun targets(
        file: String,
        same: Boolean = false,
        dir: File = CustomKeyboards.dir(),
        made: (String, String) -> String? = { _, _ -> null },
    ): Map<String, CustomLayoutFiles.Target> {
        val record = CustomLayouts.record(dir)
        return KeyData.LISTED.associateWith { board ->
            CustomLayoutFiles.target(record, file, board, PackLayouts.idOf(file, board), same, { made(board, it) }) { digestOf(dir, it) }
        }
    }

    private fun madeDigest(layouts: File, theme: String, meta: Meta?, appVersion: String, board: String, id: String): String? =
        (convert(layouts, theme, meta, appVersion, listOf(board)) { id }.singleOrNull()?.second as? PackLayouts.Converted.Done)
            ?.let { CustomLayoutFiles.digest(it.text.encodeToByteArray()) }

    // empty where unreadable (so not free)
    private fun digestOf(dir: File, id: String): String? {
        val file = File(dir, CustomLayoutFiles.fileName(id))
        if (!file.exists()) return null
        return file.takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_BYTES }
            ?.let { runCatching { CustomLayoutFiles.digest(it.readBytes()) }.getOrNull() }.orEmpty()
    }

    class Staged(val listed: List<Pair<String, PackLayouts.Converted>>, val replaced: List<String>, val notFitting: Int)

    /** For the confirmation's words only; [run] decides again on OK. */
    fun staged(layouts: File, theme: String, meta: Meta?, appVersion: String, dir: File = CustomKeyboards.dir()): Staged {
        val targets = targets(theme, dir = dir) { board, id -> madeDigest(layouts, theme, meta, appVersion, board, id) }
        val listed = convert(layouts, theme, meta, appVersion) { targets.getValue(it).id }
        val done = listed.filter { it.second is PackLayouts.Converted.Done }.map { targets.getValue(it.first) }
        val replaced = done.filter { it.put == CustomLayoutFiles.Put.REPLACE }.map { CustomKeyboards.choice(it.id)?.name ?: it.id }
        val room = CustomLayoutFiles.MAX_LAYOUTS - dir.list().orEmpty().count { CustomLayoutFiles.idOf(it) != null }
        val new = done.count { it.put == CustomLayoutFiles.Put.NEW }
        return Staged(listed, replaced, (new - room.coerceAtLeast(0)).coerceAtLeast(0))
    }

    /** An update over its own, else a free id; null when already recorded and not [again]. A full list saves no new id. */
    @Synchronized
    @Throws(IOException::class)
    fun run(pack: Theme.Pack, dir: File = CustomKeyboards.dir(), again: Boolean = false): List<Listed>? {
        val line = "${pack.file}@${pack.stamp}"
        val same = line in CustomLayouts.record(dir).packs
        if (same && !again) return null
        val layouts = File(pack.dir, ThemePackFiles.LAYOUTS)
        val appVersion = appVersion()
        val targets = targets(pack.file, same, dir) { board, id -> madeDigest(layouts, pack.file, pack.scene.meta, appVersion, board, id) }
        val listed = ArrayList<Listed>()
        val written = ArrayList<String>()
        for ((board, made) in convert(layouts, pack.file, pack.scene.meta, appVersion) { targets.getValue(it).id }) {
            val target = targets.getValue(board)
            val why = when (made) {
                is PackLayouts.Converted.Not -> made.why
                is PackLayouts.Converted.Done -> if (target.put == CustomLayoutFiles.Put.NEW) full(made, dir) else null
            }
            if (made !is PackLayouts.Converted.Done || why != null) {
                Timber.i("theme pack %s: %s not listed: %s", pack.file, board, why)
                continue
            }
            if (target.put != CustomLayoutFiles.Put.KEEP) {
                CustomLayouts.save(made.id, made.text, dir)
                written += CustomLayoutFiles.Made(made.id, board, CustomLayoutFiles.digest(made.text.encodeToByteArray()), pack.file).line
            }
            listed += Listed(board, made, target.put)
            Timber.i("theme pack %s: %s listed as %s (%s)", pack.file, board, made.id, target.put)
        }
        CustomLayouts.recordConverted(written + line, dir)
        return listed
    }

    private fun full(made: PackLayouts.Converted.Done, dir: File): String? {
        val ids = dir.list().orEmpty().mapNotNull(CustomLayoutFiles::idOf)
        return if (made.id !in ids && ids.size >= CustomLayoutFiles.MAX_LAYOUTS) "${ids.size} layouts already" else null
    }

    /** So restoring a backup holding it does not list its boards either. */
    @Synchronized
    @Throws(IOException::class)
    fun notListed(pack: Theme.Pack, dir: File = CustomKeyboards.dir()) {
        CustomLayouts.recordConverted(listOf("${pack.file}@${pack.stamp}"), dir)
    }

    /** On the main thread, where the settings pages change them too, so no write races. */
    fun copySettings(renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val prefs = AppPrefs.getInstance()
        RenamedKeys.keyLists(prefs.storedKeyLists(), renames)?.let(prefs::setStoredKeyLists)
        RenamedKeys.keySwipes(prefs.internal.keySwipes.getValue(), renames)?.let { prefs.internal.keySwipes.setValue(it) }
    }

    /** Listed at the next start; two restores without a start between are switched where either is. */
    @Synchronized
    fun restored(names: Collection<String>, switch: Boolean) {
        if (names.isEmpty()) return
        val before = readRestored()
        val all = (before?.second.orEmpty() + names).distinct()
        val file = restoredFile()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".new")
        val text = (listOf(if (switch || before?.first == true) SWITCH else LIST) + all).joinToString("\n", postfix = "\n")
        FileOutputStream(temp).use { it.write(text.encodeToByteArray()); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("cannot record the packs restored")
    }

    private fun readRestored(file: File = restoredFile()): Pair<Boolean, List<String>>? {
        val lines = file.takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() } ?: return null
        return (lines.firstOrNull() == SWITCH) to lines.drop(1)
    }

    // those a restore recorded since are kept
    private fun unrestored(file: File, names: List<String>) {
        val (switch, all) = readRestored(file) ?: return
        val left = all.filter { it !in names }
        if (left.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write((listOf(if (switch) SWITCH else LIST) + left).joinToString("\n", postfix = "\n").encodeToByteArray()); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("cannot record the packs restored")
    }

    /** [other]: the other dark-mode slot's (null off). */
    class InUse(val shown: Theme?, val other: Theme?, val dark: Boolean)

    @VisibleForTesting
    internal class Started(
        val all: Boolean,
        val listed: List<Listed>,
        val read: List<Pair<ThemeLayoutSwitch.Made, Listed>>,
        val restored: List<String> = emptyList(),
    )

    /** Longer than a one-line message's 1 s. */
    const val NOTICE_MS = 4000L

    /** Converting a pack's boards takes seconds. */
    @VisibleForTesting
    internal var background: Executor = Executor { Thread(it, "theme-layouts").apply { isDaemon = true }.start() }

    @VisibleForTesting
    internal var mainThread: Executor = Executor { ContextCompat.getMainExecutor(appContext).execute(it) }

    @VisibleForTesting
    internal class Start(val listed: File, val restored: File, val trying: File) {
        val listing = CountDownLatch(1)
        val ended = CountDownLatch(1)

        // under ThemeLayoutsToList's lock
        var dropped = false

        // begun: no longer droppable, waited for instead
        var switching = false
    }

    @Volatile
    private var current: Start? = null

    private const val START_WAIT_S = 60L

    /**
     * Lists on [background], switches on [mainThread]: till then keyboards draw the app's boards. Never throws; retried
     * by the next start. Runs after ThemeSeparators.addSeparatorsOnce, so the boards have their 分词.
     */
    fun atStart(packs: List<Theme.Pack>, inUse: () -> InUse) {
        val start = Start(listedFile(), restoredFile(), tryingFile())
        current = start
        background.execute {
            var started: Started? = null
            try {
                started = listAtStart(start, packs)
            } finally {
                start.listing.countDown()
                if (started == null) start.ended.countDown()
            }
            val listed = started ?: return@execute
            mainThread.execute {
                try {
                    chooseAtStart(start, listed, inUse)
                } finally {
                    start.ended.countDown()
                }
            }
        }
    }

    /** Not on the main thread, where the switch is made. [restoring]: only the listing is waited for. Gives up after a minute. */
    fun awaitStart(restoring: Boolean = false) {
        val start = current ?: return
        if (!(if (restoring) start.listing else start.ended).await(START_WAIT_S, TimeUnit.SECONDS)) {
            Timber.w("theme packs' layouts: the start not waited for longer")
        }
    }

    /** The switch would write the phone's settings over a restored backup's, so it is dropped; one begun is waited for. */
    fun dropStart() {
        val start = current ?: return
        val begun = synchronized(this) {
            start.dropped = true
            start.switching
        }
        if (!begun) return
        if (!start.ended.await(START_WAIT_S, TimeUnit.SECONDS)) Timber.w("theme packs' layouts: the switch not waited for longer")
        AppPrefs.getInstance().awaitWritten()
    }

    /** Null when no pack had a board, or on failure (the count left for the next start). Layouts read off the main thread. */
    @Synchronized
    @VisibleForTesting
    @Suppress("TooGenericExceptionCaught")
    internal fun listAtStart(start: Start, packs: List<Theme.Pack>): Started? {
        try {
            if (!ThemeFilesManager.writable) return null
            val all = !start.listed.exists()
            val restored = readRestored(start.restored)
            if (!all && restored == null) return null
            val names = restored?.second.orEmpty()
            val tries = tries(start.trying)
            if (tries >= MAX_TRIES) {
                Timber.w("theme packs' layouts not listed: %d starts ended as they listed them, given up", tries)
                ended(start, all, names)
                return null
            }
            writeTries(start.trying, tries + 1)
            if (all) CustomLayouts.recordConverted(emptyList())
            val switch = all || restored?.first == true
            val began = System.nanoTime()
            val made = packs.distinctBy { it.file }.filter { all || it.file in names }.flatMap { pack ->
                runCatching { run(pack, again = true) }.onFailure { Timber.w(it, "theme pack %s: layouts not listed", pack.file) }
                    .getOrNull().orEmpty().map { pack to it }
            }
            // one lacking what 0.6.0 refused it for (it drew the app's): listed, not switched in, with no confirmation to tell it
            val read = if (!switch) emptyList() else made.filter { (_, l) -> !l.done.refusedBy060 }.mapNotNull { (pack, l) ->
                CustomKeyboards.choice(l.done.id)?.let { ThemeLayoutSwitch.Made(pack.file, l.board, it) to l }
            }
            Timber.i("theme packs' layouts listed: %d in %d ms (try %d)", made.size, (System.nanoTime() - began) / NANOS_A_MS, tries + 1)
            if (made.isEmpty()) {
                ended(start, all, names)
                return null
            }
            return Started(all, made.map { it.second }, read, names)
        } catch (e: Exception) {
            Timber.w(e, "theme packs' layouts not listed: tried again at the next start")
            return null
        }
    }

    /** The end is written after the switch, or a start killed between would never switch. */
    @VisibleForTesting
    @Suppress("TooGenericExceptionCaught")
    internal fun chooseAtStart(start: Start, started: Started, inUse: () -> InUse) {
        try {
            val switching = synchronized(this) {
                if (!start.dropped) start.switching = true
                start.switching
            }
            if (switching) {
                started.listed.forEach { copySettings(it.done.renames) }
                if (started.read.isNotEmpty()) {
                    val use = inUse()
                    switchIn(started.read, use.shown as? Theme.Pack, use.other as? Theme.Pack, use.dark)
                }
            }
            synchronized(this) { ended(start, started.all, started.restored) }
        } catch (e: Exception) {
            Timber.w(e, "theme packs' layouts not chosen: tried again at the next start")
        }
    }

    private fun ended(start: Start, all: Boolean, names: List<String>) {
        if (all) written(start.listed)
        if (names.isNotEmpty()) unrestored(start.restored, names)
        start.trying.delete()
    }

    // an empty file (from before the count): one
    private fun tries(file: File): Int {
        if (!file.exists()) return 0
        return file.takeIf { it.length() <= MAX_TRIES_BYTES }?.readText()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    private const val MAX_TRIES_BYTES = 16L

    private fun writeTries(file: File, n: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(n.toString().encodeToByteArray()); it.fd.sync() }
    }

    private const val NANOS_A_MS = 1_000_000

    // one that does not read is still listed (PanelLayouts.listedUnder)
    private fun listedFile(id: String) = CustomLayoutFiles.validId(id) && File(CustomKeyboards.dir(), CustomLayoutFiles.fileName(id)).isFile

    private fun switchIn(read: List<Pair<ThemeLayoutSwitch.Made, Listed>>, shown: Theme.Pack?, other: Theme.Pack?, dark: Boolean) {
        val keyboard = AppPrefs.getInstance().keyboard
        // a gone or other-family layout's setting is none; an unreadable file's is kept
        val chosen = keyboard.panelLayouts()
        val listed = chosen.values.filter { it.isNotEmpty() && listedFile(it) }.associateWith { id ->
            PanelLayouts.listedUnder(CustomKeyboards.choice(id)?.family, id, chosen)
        }
        val panels = chosen.entries.associate { (panel, id) -> panel.family to id.takeUnless { PanelLayouts.missing(panel, chosen, listed) }.orEmpty() }
        // the 双拼 scheme was read seconds before as the engine started
        val plan = ThemeLayoutSwitch.plan(keyboard.imKeyboards(), EngineState.shuangpinScheme, panels, shown?.file, other?.file, read.map { it.first })
        plan.slots.forEach { (slot, id) -> keyboard.setImKeyboard(ImKeyboards.imOf(slot), ImKeyboard.CUSTOM, id) }
        plan.panels[ThemeLayoutSwitch.NUMBER]?.let { keyboard.numberKeyboardLayout.setValue(it) }
        plan.panels[ThemeLayoutSwitch.SYMBOLS]?.let { keyboard.symbolsPanelLayout.setValue(it) }
        val own = read.associate { (m, l) -> m to l.done.own }
        val told = notice(appContext, plan, own, other?.file, !dark)
        if (told.isNotEmpty()) {
            AppPrefs.getInstance().internal.themeLayoutsUnseen.setValue(told)
            // the engine told what was unseen before this was
            tellUnseen()
        }
        Timber.i("theme packs' layouts chosen: %s %s", plan.slots, plan.panels)
    }

    /** Told till seen, then forgotten. */
    fun tellUnseen() {
        val unseen = AppPrefs.getInstance().internal.themeLayoutsUnseen
        val told = unseen.getValue()
        if (told.isNotEmpty()) EngineNotices.toldUntilSeen(R.string.theme_layouts_told, told, ms = NOTICE_MS) { unseen.setValue("") }
    }

    /** E.g. 「主题「薄荷」带的17 键已放进 外观 → 布局，拼音正在用」; empty where none is switched. */
    @VisibleForTesting
    internal fun notice(ctx: Context, plan: ThemeLayoutSwitch.Plan, own: Map<ThemeLayoutSwitch.Made, String>, other: String?, otherDark: Boolean): String {
        val separator = ctx.getString(R.string.layout_list_separator)
        val lines = plan.uses.entries.groupBy { it.key.pack }.map { (pack, uses) ->
            val names = uses.joinToString(separator) { own[it.key] ?: it.key.choice.name }
            val users = uses.flatMap { it.value }.distinct().joinToString(separator) { userName(ctx, it) }
            ctx.getString(R.string.theme_layouts_in_use, pack, names, users)
        }.toMutableList()
        if (lines.isEmpty()) return ""
        if (other != null && plan.aside.isNotEmpty()) {
            val names = plan.aside.joinToString(separator) { own[it] ?: it.choice.name }
            lines += ctx.getString(if (otherDark) R.string.theme_layouts_aside_dark else R.string.theme_layouts_aside_light, other, names)
        }
        return lines.joinToString("\n")
    }

    private fun userName(ctx: Context, user: String): String = ctx.getString(
        when (user) {
            InputMethodCatalog.PINYIN -> R.string.layout_im_pinyin
            InputMethodCatalog.SHUANGPIN -> R.string.layout_im_shuangpin
            InputMethodCatalog.ENGLISH -> R.string.layout_im_english
            ThemeLayoutSwitch.NUMBER -> R.string.layout_section_number
            ThemeLayoutSwitch.SYMBOLS -> R.string.layout_section_symbols
            else -> R.string.layout_im_table
        },
    )

    private fun written(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.fd.sync() }
    }
}
