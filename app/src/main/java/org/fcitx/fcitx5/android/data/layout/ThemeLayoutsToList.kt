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
 * A theme pack's boards the user picks among their own layouts (`qwerty`, `k14`, `k18`, `nine`, `number`, `symbols`:
 * [KeyData.LISTED]) put in 外观 → 布局 as layouts of the user's (061-custom-layout §11, L7): since 0.6.1 they stand for
 * the app's no more. The same for each of the three ways a pack comes: the packs installed before, at the first start of
 * 0.6.1 (each chosen board switched to its layout, [ThemeLayoutSwitch]); the packs a backup brings, at the start after its
 * restore (switched where the backup is of before 0.6.1); a pack imported with 「一起导入」 ticked (only listed; one
 * unticked recorded, not listed: [notListed]). Once a pack's `file@stamp` ([CustomLayoutFiles.CONVERTED]): the same pack
 * again does nothing, one imported again (its stamp new) updates the layouts it made where they are as it wrote them
 * ([CustomLayoutFiles.target]: one the user changed, or not this pack's, kept, the pack's under a free id). The pack's
 * files are left as they are.
 */
object ThemeLayoutsToList {

    /** A pack's board [board] (`k18`) put in the list as [done], [put] so. */
    class Listed(val board: String, val done: PackLayouts.Converted.Done, val put: CustomLayoutFiles.Put = CustomLayoutFiles.Put.NEW)

    /**
     * Where it is kept that this install's packs were put in the list ([atStart]): this phone's, not backed up, as a
     * restore's packs are its own matter ([restored]).
     */
    @VisibleForTesting
    internal var listedFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-listed") }

    /** The packs a backup restored, to be put in the list at the next start: its first line `switch` or `list`, a name a line after. */
    @VisibleForTesting
    internal var restoredFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-restored") }

    /**
     * There while a start puts packs in the list, its count of starts that tried: one that finds it there ended in the
     * middle (killed: common, the app's process ends as the settings close), and tries again, [MAX_TRIES] at most.
     */
    @VisibleForTesting
    internal var tryingFile: () -> File = { File(appContext.noBackupFilesDir, "theme-layouts-trying") }

    private const val SWITCH = "switch"
    private const val LIST = "list"

    /** The most starts that try to put the packs in the list ([atStart]): one more ended in the middle, given up (logged). */
    const val MAX_TRIES = 3

    /** The app the layouts made are held to: its `min-app=`s (a dev build: any). */
    fun appVersion(): String = LayoutImport.appVersion(ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME))

    /**
     * The pack's boards in [layouts] (its `layouts/`) made layouts of the user's, nothing saved: each listed board it
     * has a file of, by board, in [KeyData.LISTED]'s order; [theme] the pack's name, [meta] its theme's, [ids] the id
     * each is made ([targets]'); [boards] those looked at. What throws making one is one not made (a pack is untrusted).
     */
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

    /**
     * Where pack [file]'s boards go in [dir]'s list ([CustomLayoutFiles.target]), by board; [same], the import of it
     * recorded already is listed again; [made] what a board makes under an id, its digest (null where not made).
     */
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

    // what [layouts]' [board] makes under [id] (as [convert]), its digest; null where it is not made
    private fun madeDigest(layouts: File, theme: String, meta: Meta?, appVersion: String, board: String, id: String): String? =
        (convert(layouts, theme, meta, appVersion, listOf(board)) { id }.singleOrNull()?.second as? PackLayouts.Converted.Done)
            ?.let { CustomLayoutFiles.digest(it.text.encodeToByteArray()) }

    // the digest of layout [id]'s file in [dir]; null where there is none, empty where it cannot be read (not free)
    private fun digestOf(dir: File, id: String): String? {
        val file = File(dir, CustomLayoutFiles.fileName(id))
        if (!file.exists()) return null
        return file.takeIf { it.isFile && it.length() <= CustomLayoutFiles.MAX_BYTES }
            ?.let { runCatching { CustomLayoutFiles.digest(it.readBytes()) }.getOrNull() }.orEmpty()
    }

    /** What an import of a pack would put in the list ([staged]): [listed] as [convert], [replaced] the names of the layouts it writes over, [notFitting] those of new ids the list has no room for. */
    class Staged(val listed: List<Pair<String, PackLayouts.Converted>>, val replaced: List<String>, val notFitting: Int)

    /**
     * Pack [theme]'s boards in [layouts] as its import with 「一起导入」 would list them in [dir]'s ([targets]): what its
     * confirmation says (§4.2 第 4 步: 「替换已有的『…』」; the list full).
     */
    fun staged(layouts: File, theme: String, meta: Meta?, appVersion: String, dir: File = CustomKeyboards.dir()): Staged {
        val targets = targets(theme, dir = dir) { board, id -> madeDigest(layouts, theme, meta, appVersion, board, id) }
        val listed = convert(layouts, theme, meta, appVersion) { targets.getValue(it).id }
        val done = listed.filter { it.second is PackLayouts.Converted.Done }.map { targets.getValue(it.first) }
        val replaced = done.filter { it.put == CustomLayoutFiles.Put.REPLACE }.map { CustomKeyboards.choice(it.id)?.name ?: it.id }
        val room = CustomLayoutFiles.MAX_LAYOUTS - dir.list().orEmpty().count { CustomLayoutFiles.idOf(it) != null }
        val new = done.count { it.put == CustomLayoutFiles.Put.NEW }
        return Staged(listed, replaced, (new - room.coerceAtLeast(0)).coerceAtLeast(0))
    }

    /**
     * [pack]'s boards put in [dir]'s list ([convert]): each saved where [targets] says (over the one it made before, an
     * update; under a free id; one the user changed of the same import listed [again] left as it is); the pack and each
     * layout written recorded. The settings under a name it renamed are the caller's to copy, on the main thread
     * ([copySettings]). Null where the record has it already and not [again] (nothing done); the boards listed else,
     * none where it has none. One of a new id is not saved where [dir] holds [CustomLayoutFiles.MAX_LAYOUTS] (logged).
     */
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

    // why [made] is not saved in [dir]: a new id where the list is full (CustomLayoutFiles.MAX_LAYOUTS); null it is
    private fun full(made: PackLayouts.Converted.Done, dir: File): String? {
        val ids = dir.list().orEmpty().mapNotNull(CustomLayoutFiles::idOf)
        return if (made.id !in ids && ids.size >= CustomLayoutFiles.MAX_LAYOUTS) "${ids.size} layouts already" else null
    }

    /**
     * [pack], just imported with 「一起导入」 unticked, recorded as if listed: a restore of a backup holding it does not put
     * its boards in the list either (L7 review NIT1; UserDataManager leaves out the packs the backup's record has).
     */
    @Synchronized
    @Throws(IOException::class)
    fun notListed(pack: Theme.Pack, dir: File = CustomKeyboards.dir()) {
        CustomLayouts.recordConverted(listOf("${pack.file}@${pack.stamp}"), dir)
    }

    /**
     * The settings of the names [renames] a layout listed renamed ([Listed.done]'s), copied under the new ones (§2.3: the
     * old kept, one set kept): on the main thread, where the settings pages change them too (not a write over one made there).
     */
    fun copySettings(renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val prefs = AppPrefs.getInstance()
        RenamedKeys.keyLists(prefs.storedKeyLists(), renames)?.let(prefs::setStoredKeyLists)
        RenamedKeys.keySwipes(prefs.internal.keySwipes.getValue(), renames)?.let { prefs.internal.keySwipes.setValue(it) }
    }

    /**
     * The packs [names] a backup restored (UserDataManager), each replacing the phone's of its name: put in the list at the
     * next start ([atStart]), its settings then the backup's; [switch] where the backup is of before 0.6.1 (what it chose
     * is a board its packs stood for). Added to: two backups restored without a start between, switched where either is.
     */
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

    // whether to switch, and the names; null none
    private fun readRestored(file: File = restoredFile()): Pair<Boolean, List<String>>? {
        val lines = file.takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() } ?: return null
        return (lines.firstOrNull() == SWITCH) to lines.drop(1)
    }

    // [names] taken off [file] (the packs a start listed), those a restore recorded since kept (L7 review B4)
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

    /** The themes in use as a start switches ([atStart]): [shown] drawn, [other] the other slot's of 跟随系统深色模式 (null off), [dark] the phone. */
    class InUse(val shown: Theme?, val other: Theme?, val dark: Boolean)

    /**
     * What a start listed off the main thread ([listAtStart]), for [chooseAtStart] to switch on it: [all], the install's
     * packs; [restored], the names of a restore's it took.
     */
    @VisibleForTesting
    internal class Started(
        val all: Boolean,
        val listed: List<Listed>,
        val read: List<Pair<ThemeLayoutSwitch.Made, Listed>>,
        val restored: List<String> = emptyList(),
    )

    /** How long the notice of the boards chosen stays ([tellUnseen]): a line a pack, more than a line said's 1 s reads. */
    const val NOTICE_MS = 4000L

    /** Where a start lists: a thread of its own, as converting a pack's boards takes seconds (2.7 s a 26 keys' measured). */
    @VisibleForTesting
    internal var background: Executor = Executor { Thread(it, "theme-layouts").apply { isDaemon = true }.start() }

    /** Where it switches: the main thread, where the settings are changed and the keyboards read them. */
    @VisibleForTesting
    internal var mainThread: Executor = Executor { ContextCompat.getMainExecutor(appContext).execute(it) }

    /**
     * A start's: its files as they were as it began (not another's set after, a test's), its listing ended, it ended
     * (its switch made, or none to make), its switch dropped (a restore came first: [awaitStart]).
     */
    @VisibleForTesting
    internal class Start(val listed: File, val restored: File, val trying: File) {
        val listing = CountDownLatch(1)
        val ended = CountDownLatch(1)

        // under ThemeLayoutsToList's lock
        var dropped = false
    }

    // the last start begun: what [awaitStart] waits for
    @Volatile
    private var current: Start? = null

    private const val START_WAIT_S = 60L

    /**
     * At the app's start (ThemeManager, the packs listed; [inUse] the themes in use, read as it switches): the packs
     * of before 0.6.1 put in the list once an install, each chosen board switched to its layout; the packs a backup
     * restored ([restored]) too. Listed on [background], switched on [mainThread] after: till then the keyboards draw
     * the app's boards, then (their settings changed) the pack's, once an upgrade or a restore. Not while the files
     * cannot be written (A4: the next start after the unlock). It never throws (a pack that fails is left as it is,
     * logged); one that ended in the middle (killed, an Error, its switch thrown) is tried again by the next start,
     * [MAX_TRIES] starts at most. Called once ThemeManager's packs are listed, so after ThemeSeparators.addSeparatorsOnce:
     * the boards made have their 分词 (ST-143).
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

    /**
     * Waits for the start ([atStart]) to end, its switch made: what writes the packs or the layouts (a pack imported)
     * does not do so under it, nor reads the list or the choices before they are made (外观 → 布局 opened at once). Not
     * on the main thread, where the switch is made. [restoring] (a backup restored): its listing alone waited for, its
     * switch, not yet made, dropped (the settings copied in are the backup's; its marker of trying cleared, the next
     * start finds the packs it listed recorded). One that does not end in a minute is not waited for longer.
     */
    fun awaitStart(restoring: Boolean = false) {
        val start = current ?: return
        if (!(if (restoring) start.listing else start.ended).await(START_WAIT_S, TimeUnit.SECONDS)) {
            Timber.w("theme packs' layouts: the start not waited for longer")
        }
        if (restoring) synchronized(this) { start.dropped = true }
    }

    /**
     * The packs [start] puts in the list, saved, its count of tries written ([tryingFile]); null where none had a board to
     * list, it then ended here ([ended]), or where it failed (logged: the count left for the next start). Each of the
     * packs it takes is listed again ([run] again: one a start that ended in the middle listed is switched on by its
     * record's id, L7 review S5), its `.converted` written [CustomLayoutFiles.CURRENT] (NIT1). The packs a restore
     * recorded are taken here, under the lock: one that records after is the next start's. Each layout read here too,
     * for the switch, not on the main thread; the switch planned there, on the settings then (B3).
     */
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
            val read = if (!switch) emptyList() else made.mapNotNull { (pack, l) ->
                CustomKeyboards.choice(l.done.id)?.let { ThemeLayoutSwitch.Made(pack.file, l.board, it) to l }
            }
            Timber.i("theme packs' layouts listed: %d in %d ms (try %d)", made.size, (System.nanoTime() - began) / NANOS_A_MS, tries + 1)
            if (made.isEmpty()) {
                // nothing for the main thread to do (no pack had one): ended here
                ended(start, all, names)
                return null
            }
            return Started(all, made.map { it.second }, read, names)
        } catch (e: Exception) {
            Timber.w(e, "theme packs' layouts not listed: tried again at the next start")
            return null
        }
    }

    /**
     * On the main thread, what [listAtStart] listed: the settings of the keys they renamed copied, the boards chosen
     * switched ([switchIn], planned on the settings now, told once; [inUse] read now), then its end written ([ended]:
     * not before, or a start killed between would never switch, L7 review B1). Dropped ([awaitStart]: a restore's
     * settings are the backup's): its end alone. One that throws is tried again by the next start (logged).
     */
    @VisibleForTesting
    @Suppress("TooGenericExceptionCaught")
    internal fun chooseAtStart(start: Start, started: Started, inUse: () -> InUse) {
        try {
            if (!synchronized(this) { start.dropped }) {
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

    // [start] done: the install's marker written where [all], the restore's [names] taken off its record, the count of tries cleared
    private fun ended(start: Start, all: Boolean, names: List<String>) {
        if (all) written(start.listed)
        if (names.isNotEmpty()) unrestored(start.restored, names)
        start.trying.delete()
    }

    // the starts that tried, as [file] counts them (0: none; one of before the count, empty: one)
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

    // whether the user's layout [id] has a file (one that does not read still listed, PanelLayouts.listedUnder)
    private fun listedFile(id: String) = CustomLayoutFiles.validId(id) && File(CustomKeyboards.dir(), CustomLayoutFiles.fileName(id)).isFile

    // [read] chosen where its board was (ThemeLayoutSwitch), and told once ([tellUnseen])
    private fun switchIn(read: List<Pair<ThemeLayoutSwitch.Made, Listed>>, shown: Theme.Pack?, other: Theme.Pack?, dark: Boolean) {
        val keyboard = AppPrefs.getInstance().keyboard
        // a setting of a layout gone or of another family now is none, as the app's card says (PanelLayouts.missing: L5's
        // restore leaves it as it was); one whose file does not read is kept (读不出，暂用内置的)
        val chosen = keyboard.panelLayouts()
        val listed = chosen.values.filter { it.isNotEmpty() && listedFile(it) }.associateWith { id ->
            PanelLayouts.listedUnder(CustomKeyboards.choice(id)?.family, id, chosen)
        }
        val panels = chosen.entries.associate { (panel, id) -> panel.family to id.takeUnless { PanelLayouts.missing(panel, chosen, listed) }.orEmpty() }
        // planned here, on the settings and the engine's 双拼 scheme as they are now (L7 review B3, NIT7): the scheme read
        // as the engine started, seconds before; its default where it has not yet (as for any choice made then)
        val plan = ThemeLayoutSwitch.plan(keyboard.imKeyboards(), EngineState.shuangpinScheme, panels, shown?.file, other?.file, read.map { it.first })
        plan.slots.forEach { (slot, id) -> keyboard.setImKeyboard(ImKeyboards.imOf(slot), ImKeyboard.CUSTOM, id) }
        plan.panels[ThemeLayoutSwitch.NUMBER]?.let { keyboard.numberKeyboardLayout.setValue(it) }
        plan.panels[ThemeLayoutSwitch.SYMBOLS]?.let { keyboard.symbolsPanelLayout.setValue(it) }
        val own = read.associate { (m, l) -> m to l.done.own }
        val told = notice(appContext, plan, own, other?.file, !dark)
        if (told.isNotEmpty()) {
            AppPrefs.getInstance().internal.themeLayoutsUnseen.setValue(told)
            // the engine told what was unseen as it started, before this was
            tellUnseen()
        }
        Timber.i("theme packs' layouts chosen: %s %s", plan.slots, plan.panels)
    }

    /** The boards a start chose told till seen (EngineNotices.toldUntilSeen, [NOTICE_MS]), then forgotten: as the engine starts, and as they are chosen. */
    fun tellUnseen() {
        val unseen = AppPrefs.getInstance().internal.themeLayoutsUnseen
        val told = unseen.getValue()
        if (told.isNotEmpty()) EngineNotices.toldUntilSeen(R.string.theme_layouts_told, told, ms = NOTICE_MS) { unseen.setValue("") }
    }

    /**
     * What a switch ([plan]) tells, a line a pack: 「主题「薄荷」带的17 键已放进 外观 → 布局，拼音正在用」, and of the
     * other slot's pack ([other], dark where [otherDark]) its boards set aside (Q15); empty where none is switched.
     * [own]: each layout's own name.
     */
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

    // a slot's or a panel's name as 外观 → 布局 says it
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

    // [file] there, empty, on the disk
    private fun written(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.fd.sync() }
    }
}
