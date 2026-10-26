/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.host.ChineseConverter
import org.fcitx.fcitx5.android.host.ChineseModules
import org.fcitx.fcitx5.android.host.ConfigWriter
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.host.FcitxEvents
import org.fcitx.fcitx5.android.host.FilePkgDataDir
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.host.ModuleInputs
import org.fcitx.fcitx5.android.host.OpenCCData
import org.fcitx.fcitx5.android.host.Persist
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.Punctuation
import org.fcitx.fcitx5.android.host.PunctuationConfig
import org.fcitx.fcitx5.android.host.PunctuationEntry
import org.fcitx.fcitx5.android.host.PunctuationProfile
import org.fcitx.fcitx5.android.host.QuickPhraseTable
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import org.fcitx.fcitx5.android.host.KeyStates as HostKeyStates

/**
 * The Kotlin host behind [FcitxAPI] (fcitx-replacement §4): [HostCore] on one thread
 * ([HostDispatcher], still `fcitx-main`), the engines through [EngineBridge.host], the modules as the
 * app's fcitx loads them ([ChineseModules]), fcitx's files at the same paths with the same INI
 * semantics, written as they change ([ConfigWriter]). Callers see the same [FcitxAPI] and the same
 * [FcitxEvent] flow as on the native host; only the flow keeps every event (no buffer of 15 that drops
 * the oldest). The app's only host (FcitxDaemon).
 */
@Suppress("TooManyFunctions", "LargeClass")
class KotlinFcitx(
    private val context: Context,
    /** The engine side: the app's [EngineBridge]; a test's scripted engine on the JVM, where the data is not. */
    private val engine: EngineSide = EngineSide.App,
) : FcitxInstance {

    /** fcitx's `ShowInputMethodInformation`, read at [start]: the instrumented tests turn it off (TestFcitx). */
    @VisibleForTesting
    @Volatile
    var showInputMethodInformation = true

    /** What the host needs of the engine besides its port: where fcitx's files are, and the engine's start and end. */
    interface EngineSide {
        val host: EngineHost
        fun home(): File
        fun start()
        fun close()

        object App : EngineSide {
            override val host: EngineHost get() = EngineBridge.host
            override fun home(): File = EngineBridge.fcitxHome()

            override fun start() {
                EngineMigration.run(File(home(), "config"), EngineBridge.userDir())
                try {
                    EngineBridge.reload()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "cannot reload the engine")
                }
            }

            override fun close() = EngineBridge.close()
        }
    }

    private val lifecycleRegistry = FcitxLifecycleRegistry()

    override val lifecycle: FcitxLifecycle get() = lifecycleRegistry

    // every event kept, however slow a collector (design §4): tryEmit never fails
    private val eventsOut = MutableSharedFlow<FcitxEvent<*>>(extraBufferCapacity = Int.MAX_VALUE)

    override val eventFlow = eventsOut.asSharedFlow()

    override val isReady get() = lifecycle.currentState == FcitxLifecycle.State.READY

    // the four written on the host thread, read on the main one (runImmediately)
    @Volatile
    override var inputMethodEntryCached = InputMethodEntry(context.getString(R.string._not_available_))
        private set

    @Volatile
    override var statusAreaActionsCached: Array<Action> = emptyArray()
        private set

    @Volatile
    override var clientPreeditCached = FormattedText.Empty
        private set

    @Volatile
    override var inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        private set

    private val dispatcher = HostDispatcher()

    // the host thread's own, made by startup
    private var core: HostCore? = null
    private var modules: ChineseModules? = null
    private val options = EngineOptions()
    private var writer: ConfigWriter? = null

    // the writer's thread, the same at every start: it ends when idle, so a stopped host keeps none
    private val configThread = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "host-config")
    }.apply { allowCoreThreadTimeOut(true) }
    private lateinit var punctuationMaps: PunctuationMaps
    private lateinit var home: File

    // the module files' text as last read or written, what the next write keeps of them
    private val confTexts = HashMap<String, String?>()

    // the clipboard's latest text, which Unicode lists the characters of when it opens
    @Volatile
    private var clipboardText = ""

    @Keep
    private val onClipboardUpdate = ClipboardManager.OnClipboardUpdateListener {
        // not a password's: fcitx's clipboard kept those out of what it offered
        clipboardText = if (it.sensitive) "" else it.text
    }

    private var firstRun by AppPrefs.getInstance().internal.firstRun

    private val host: HostCore get() = core ?: throw IllegalStateException("the Kotlin host did not start")

    private suspend inline fun <T> onHost(crossinline block: HostCore.() -> T): T = withContext(dispatcher) { block(host) }

    // ---- the engine's own calls, as Fcitx makes them

    /** Every change is written as it happens; this writes what is still pending, as `save()` did. */
    override suspend fun save() = withContext(dispatcher) { writer?.flush() ?: Unit }

    override suspend fun reloadConfig() = onHost { readModuleFiles() }
    override suspend fun reloadEngine() = withContext(dispatcher) { EngineBridge.reload() }
    override suspend fun userWords() = withContext(dispatcher) { EngineBridge.engines.userWords() }
    override suspend fun userWords(kind: Engines.UserWord.Kind) = withContext(dispatcher) { EngineBridge.engines.userWords(kind) }
    override suspend fun userWordCounts() = withContext(dispatcher) { EngineBridge.engines.userWordCounts() }
    override suspend fun sameCodes(texts: List<String>, ims: List<String>) = withContext(dispatcher) { EngineBridge.engines.sameCodes(texts, ims) }
    override suspend fun pinyinOf(text: String) = withContext(dispatcher) { EngineBridge.engines.pinyinOf(text) }
    override suspend fun wordBoundaries(texts: List<String>) = withContext(dispatcher) { EngineBridge.engines.wordBoundaries(texts) }
    override suspend fun logProbs(texts: List<String>) = withContext(dispatcher) { EngineBridge.engines.logProbs(texts) }
    override suspend fun addUserWord(text: String, pinyin: String) = withContext(dispatcher) { EngineBridge.engines.addWord(text, pinyin) }
    override suspend fun blockUserWord(text: String, pinyin: String) = withContext(dispatcher) { EngineBridge.engines.blockWord(text, pinyin) }
    override suspend fun removeUserWords(words: List<Engines.UserWord>) = withContext(dispatcher) { EngineBridge.engines.removeWords(words) }
    override suspend fun englishWords() = withContext(dispatcher) { EngineBridge.engines.englishWords() }
    override suspend fun addEnglishWord(text: String) = withContext(dispatcher) { EngineBridge.engines.addEnglishWord(text) }
    override suspend fun forgetEnglishWords(texts: List<String>) = withContext(dispatcher) { EngineBridge.engines.forgetEnglishWords(texts) }
    override suspend fun importUserWords(lines: Sequence<String>, fileName: String) = withContext(dispatcher) { EngineBridge.engines.importWords(lines, fileName) }
    override suspend fun importBatches() = withContext(dispatcher) { EngineBridge.engines.importBatches() }
    override suspend fun importedWords(batch: Int?) = withContext(dispatcher) { EngineBridge.engines.importedWords(batch) }
    override suspend fun dropImport(batch: Int) = withContext(dispatcher) { EngineBridge.engines.dropImport(batch) }
    override suspend fun restoreImport(batch: Int) = withContext(dispatcher) { EngineBridge.engines.restoreImport(batch) }
    override suspend fun tableKeys(im: String) = withContext(dispatcher) { EngineBridge.tableKeys(im) }

    override suspend fun engineContext(
        before: String,
        learning: Boolean,
        cleared: Boolean,
        learnFlags: Int,
        started: Boolean,
        after: String?,
        selected: Boolean,
        said: Boolean,
    ) = onHost {
        if (EngineBridge.context(
                before, learning, inputMethodEntryCached.uniqueName, cleared = cleared, learnFlags = learnFlags,
                started = started, after = after, selected = selected, said = said,
            )) {
            sendVirtual(Keyboard.RECALL)
        }
    }

    override suspend fun offerInk(reading: InkReading, piece: Int) =
        onHost { EngineBridge.offerInk(reading, piece, inputMethodEntryCached.uniqueName).also { if (it) sendVirtual(Keyboard.INK) } }

    override suspend fun inkFinished(piece: Int) =
        onHost { EngineBridge.inkFinished(piece, inputMethodEntryCached.uniqueName).also { if (it) sendVirtual(Keyboard.INK) } }

    override suspend fun enginePaused(idle: Boolean) = withContext(dispatcher) { EngineBridge.paused(idle) }

    override suspend fun learning(op: LearningOp) = onHost {
        val candidate = (op as? LearningOp.OnPage)?.op ?: op as? LearningOp.OnCandidate
        EngineBridge.learning(op, inputMethodEntryCached.uniqueName) act@{ event ->
            // as the menu sends it: asked first, at the index the list has (Fcitx.learning)
            if (candidate == null) return@act false
            if (candidateActions(candidate.index).none { it.id == event }) return@act false
            triggerCandidateAction(candidate.index, event, candidate.text)
            true
        }.also { (_, again) -> if (again) sendVirtual(Keyboard.AGAIN) }.first
    }

    override suspend fun offerGlide(glide: Glide) = onHost {
        EngineBridge.offerGlide(glide, inputMethodEntryCached.uniqueName).also { if (it) sendVirtual(Keyboard.GLIDE) }
    }

    // a private-use key the engine reads, sent in the same turn of the host thread as what it shows
    private fun HostCore.sendVirtual(char: Int) {
        sendKey(Key(UNICODE + char, HostKeyStates.VIRTUAL), virtual = true)
    }

    // ---- keys and the panel

    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(KeySyms.fromString(key), states.toInt(), up, timestamp) }

    // a UTF-16 unit, as native-lib read it: a lone surrogate is no key
    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(KeySyms.fromString(c.toString()), states.toInt(), up, timestamp) }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(sym, states.toInt(), up, timestamp) }

    override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(sym.sym, states.toInt(), up, timestamp) }

    // the frontend's keyEvent: virtual is the state's bit, as fcitx's KeyEvent::isVirtual reads it
    private fun HostCore.key(sym: Int, states: Int, up: Boolean, timestamp: Int) {
        sendKey(Key(sym, states), release = up, virtual = states and HostKeyStates.VIRTUAL != 0, timestamp = timestamp)
    }

    override suspend fun select(idx: Int, text: String?): Boolean = onHost { select(idx, text) }
    override suspend fun isEmpty(): Boolean = onHost { isEmpty() }
    override suspend fun reset() = onHost { reset() }
    override suspend fun moveCursor(position: Int) = onHost { moveCursor(position) }

    // ---- input methods

    override suspend fun availableIme() = withContext(dispatcher) { Engines.LISTED.map { FcitxEvents.entry(it.name) }.toTypedArray() }

    override suspend fun enabledIme() = onHost { contexts.group.inputMethods.map(FcitxEvents::entry).toTypedArray() }

    override suspend fun setEnabledIme(array: Array<String>) = onHost { setInputMethods(array.toList()) }
    override suspend fun toggleIme() = onHost { toggle() }
    override suspend fun activateIme(ime: String) = onHost { setCurrentInputMethod(ime) }
    override suspend fun enumerateIme(forward: Boolean) = onHost { enumerate(forward) }
    override suspend fun currentIme() = onHost { FcitxEvents.entry(currentInputMethod()) }

    // ---- settings pages (design §5)

    // fcitx's global options are not read (design §1.4): nothing to show or set
    override suspend fun getGlobalConfig() = RawConfig()

    override suspend fun setGlobalConfig(config: RawConfig) = Unit

    override suspend fun getAddonSubConfig(addon: String, path: String) = withContext(dispatcher) {
        if (addon != PUNCTUATION_ADDON || path != PUNCTUATION_MAP + ZH_CN) return@withContext RawConfig()
        val entries = punctuationMaps.load(ZH_CN)
        // the entries under "cfg", as PunctuationManager.parseRawConfig reads them
        page(RawConfig(arrayOf(RawConfig(PunctuationManager.ENTRIES, entries.mapIndexed { i, it -> it.toRawConfig(i) }.toTypedArray()))))
    }

    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) = onHost {
        if (addon != PUNCTUATION_ADDON || path != PUNCTUATION_MAP + ZH_CN) return@onHost
        val entries = config.findByName(PunctuationManager.ENTRIES)?.subItems.orEmpty().map(::PunctuationMapEntry)
        // the edit page's table (PunctuationProfile::set) saved whole, then used
        val profile = PunctuationProfile.fromEntries(entries.map { PunctuationEntry(it.key, it.mapping, it.altMapping) })
        try {
            punctuationMaps.save(ZH_CN, profile.entries.map { PunctuationMapEntry(it.key, it.mapping, it.altMapping) })
        } catch (e: IOException) {
            Timber.w(e, "punctuation map")
        }
        modules?.punctuation?.setProfile(profile)
    }

    override suspend fun getImConfig(key: String) = withContext(dispatcher) {
        options.page(key)?.let { page(it.toRawConfig(CFG)) } ?: RawConfig()
    }

    override suspend fun setImConfig(key: String, config: RawConfig) = onHost {
        if (!options.setPage(key, config.toConfigTree())) return@onHost
        val text = options.text()
        writer?.write(EngineOptions.PATH) { text }
        configure(options)
    }

    // the page and a description, which only says the page is there (the app draws it, design §5)
    private fun page(cfg: RawConfig) = RawConfig(arrayOf(cfg.copy(name = CFG), RawConfig(DESC, arrayOf())))

    // ---- quick input, focus, status area, candidates

    override suspend fun triggerQuickPhrase() = onHost { triggerQuickPhrase(); Unit }
    override suspend fun triggerUnicode() = onHost { triggerUnicode(); Unit }
    override suspend fun focus(focus: Boolean) = onHost { focus(focus) }
    override suspend fun focusOutIn() = onHost { focusOutIn() }
    override suspend fun activate(uid: Int, pkgName: String) = onHost { activate(uid) }
    override suspend fun deactivate(uid: Int) = onHost { deactivate(uid) }
    override suspend fun setCapFlags(flags: CapabilityFlags) = onHost { setCapabilities(flags.toLong()); Unit }
    override suspend fun statusArea(): Array<Action> = onHost { statusArea().map(FcitxEvents::action).toTypedArray() }
    override suspend fun activateAction(id: Int) = onHost { activateAction(id); Unit }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        onHost { candidates(offset, limit).map { CandidateWord(it.label, it.text, it.comment) }.toTypedArray() }

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> =
        onHost { candidateActions(idx).map { CandidateAction(it.id, it.text, isSeparator = false, icon = "", isCheckable = false, isChecked = false) }.toTypedArray() }

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) = onHost { triggerCandidateAction(idx, actionIdx, text) }
    override suspend fun setCandidatePagingMode(mode: Int) = onHost { setCandidatePagingMode(mode) }
    override suspend fun offsetCandidatePage(delta: Int) = onHost { offsetCandidatePage(delta) }

    // no module gives the list a tab (design §1.4)
    override suspend fun triggerCandidateListTabAction(id: Int) = Unit

    // ---- start and stop

    override fun start() {
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED) {
            Timber.w("Skip starting fcitx: not at stopped state!")
            return
        }
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_START)
        ClipboardManager.lastEntry?.let { clipboardText = if (it.sensitive) "" else it.text }
        ClipboardManager.addOnUpdateListener(onClipboardUpdate)
        dispatcher.start()
        dispatcher.dispatch(EmptyCoroutineContext) {
            try {
                startup()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // the executor would keep it and the host stay STARTING, the keyboard dead with no
                // word said: the process ends instead, as the native host's startup failure did
                Timber.e(e, "Kotlin host failed to start")
                val thread = Thread.currentThread()
                // never null: a live thread without its own handler answers with its group
                thread.uncaughtExceptionHandler.uncaughtException(thread, e)
            }
        }
    }

    override fun stop() {
        if (lifecycle.currentState != FcitxLifecycle.State.READY) {
            Timber.w("Skip stopping fcitx: not at ready state!")
            return
        }
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        Timber.i("KotlinFcitx stop()")
        ClipboardManager.removeOnUpdateListener(onClipboardUpdate)
        try {
            dispatcher.stop {
                // what is pending goes to disk (not before unlock: the writer is not writable then)
                writer?.flush()
                core = null
                modules = null
                // the engine gone with the host, as with fcitx's exit
                engine.close()
            }.let { if (it.isNotEmpty()) Timber.w("${it.size} job(s) didn't get a chance to run!") }
        } finally {
            // stopped even when the last step threw, so a later start (another restart, a new client's
            // connect) can run; the throw goes on, so this restart's own start is skipped
            lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        }
    }

    /**
     * A host left STARTING by a failed [startup] (which [stop] skips): its thread and clipboard
     * listener taken away, as the process's end does in production, so a test's later ones do not
     * see them (process-wide counts, F4296).
     */
    @VisibleForTesting
    internal fun tearDownFailedStart() {
        ClipboardManager.removeOnUpdateListener(onClipboardUpdate)
        dispatcher.stop {}
    }

    /** `KotlinFcitx.start` of design §4: migrations, the engine, the files, the core, then ready. */
    private fun startup() {
        val started = System.nanoTime()
        var lap = started
        // debug builds: each step's time (logcat HostStartup), for the cold-start comparison of gate 5
        val step = { name: String ->
            if (BuildConfig.DEBUG) {
                val now = System.nanoTime()
                Timber.tag("HostStartup").d("%s %d ms", name, (now - lap) / NANOS_PER_MILLI)
                lap = now
            }
        }
        val directBoot = FcitxApplication.getInstance().isDirectBootMode
        home = engine.home()
        val configHome = File(home, "config")
        engine.start()
        step("engine.start")
        punctuationMaps = PunctuationMaps(File(home, "data")) { lang ->
            try {
                context.assets.open("$BUILT_IN/punctuation/punc.mb.$lang")
            } catch (_: IOException) {
                null
            }
        }
        // before the first unlock nothing is written (design §2.2): the process restarts at unlock
        writer = ConfigWriter(!directBoot, configThread, ConfigWriter.files(configHome) { path, e ->
            Timber.w(e, "cannot write %s", path)
        })
        step("punctuation maps, writer")
        val strings = AppHostStrings(context)
        val available = Engines.LISTED.map { it.name }.toSet()
        val profile = Profile.load(read(File(configHome, PROFILE)), available, strings.defaultGroup)
        options.load(read(File(configHome, EngineOptions.PATH)).orEmpty())
        readConfTexts(configHome)
        step("strings, profile, confs")
        val punctuationProfile = punctuationProfile()
        step("punctuation table")
        val quickPhraseTable = quickPhrasesLater()
        val chinese = ChineseModules(
            ModuleInputs(
                punctuationProfile = punctuationProfile,
                punctuationConfig = ModuleConfigs.punctuation(confTexts[ModuleConfigs.PUNCTUATION]),
                chttransEnabled = ModuleConfigs.chttrans(confTexts[ModuleConfigs.CHTTRANS]),
                converter = ::converter,
                quickPhrases = quickPhraseTable,
                charSelectData = ::charSelectData,
                clipboard = { clipboardText },
            ),
            strings,
        )
        modules = chinese
        step("ChineseModules")
        val wasFirstRun = firstRun
        val made = HostCore(
            engine.host, dispatcher, profile, available, strings, firstRun = wasFirstRun,
            showInputMethodInformation = showInputMethodInformation, persist = ::persist, modules = chinese::build,
        )
        made.events.handlers += ::forward
        made.configure(options)
        core = made
        step("HostCore")
        if (wasFirstRun) firstRun = false
        Timber.i("Kotlin host ready in %d ms", (System.nanoTime() - started) / NANOS_PER_MILLI)
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_READY)
        emit(FcitxEvent.ReadyEvent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lifecycle.launchWhenReady { SubtypeManager.syncWith(enabledIme()) }
        }
    }

    // the module files read again (fcitx's reloadConfig): the punctuation table, the modules' files, quick phrases, the engine options
    private fun HostCore.readModuleFiles() {
        val configHome = File(home, "config")
        readConfTexts(configHome)
        modules?.punctuation?.let {
            it.setProfile(punctuationProfile())
            // read, not changed: not written back
            it.setConfigQuietly(ModuleConfigs.punctuation(confTexts[ModuleConfigs.PUNCTUATION]))
        }
        // traditional per input method as the file now says, the toggle's icon told anew
        modules?.chttrans?.reload(ModuleConfigs.chttrans(confTexts[ModuleConfigs.CHTTRANS]))
        contexts.active?.let(::updateStatusArea)
        modules?.quickPhrase?.reload(quickPhrasesLater())
        options.load(read(File(configHome, EngineOptions.PATH)).orEmpty())
        configure(options)
    }

    private fun readConfTexts(configHome: File) {
        for (path in listOf(ModuleConfigs.PUNCTUATION, ModuleConfigs.CHTTRANS)) confTexts[path] = read(File(configHome, path))
    }

    private fun Punctuation.setConfigQuietly(config: PunctuationConfig) {
        if (config != this.config) setConfig(config)
    }

    private fun punctuationProfile(): PunctuationProfile =
        PunctuationProfile.fromEntries(punctuationMaps.load(ZH_CN).map { PunctuationEntry(it.key, it.mapping, it.altMapping) })

    // fcitx loads them as the addon loads, in C++ at no cost; here 0.4 s on a debug build, so on a
    // thread of their own, at the start in parallel with the engine and at a reload with the keys
    // typed meanwhile; a lookup before they are read waits for them
    private fun quickPhrasesLater(): () -> QuickPhraseTable {
        val table = FutureTask { quickPhrases() }
        Thread(table, "host-quickphrase").start()
        return table::get
    }

    // the user's (files/data, which backups and the editors keep) before the built-in ones
    private fun quickPhrases() = try {
        QuickPhraseTable.load(listOf(FilePkgDataDir(File(home, "data")), AssetPkgDataDir(context.assets, BUILT_IN)))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "quick phrases")
        QuickPhraseTable.EMPTY
    }

    // OpenCC s2tw, mapped where it lies in the APK, at the first traditional text
    private fun converter(): ChineseConverter? = try {
        ChineseConverter.s2tw(OpenCCData.read(EngineBridge.asset(OPENCC)))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "no OpenCC data: text stays simplified")
        null
    }

    // 4.5 MB, read on the host thread at Unicode's first trigger (fcitx's own moment); its index is built off it
    private fun charSelectData(): ByteArray? = try {
        val t0 = System.nanoTime()
        context.assets.open("$BUILT_IN/unicode/charselectdata").use { it.readBytes() }.also {
            Timber.d("charselectdata read in %d ms", (System.nanoTime() - t0) / NANOS_PER_MILLI)
        }
    } catch (e: IOException) {
        Timber.w(e, "no charselectdata")
        null
    }

    private fun read(file: File): String? = try {
        file.takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        Timber.w(e, "cannot read %s", file.name)
        null
    }

    private fun persist(change: Persist) {
        val writer = writer ?: return
        when (change) {
            is Persist.ProfileChanged -> {
                val text = change.profile.text()
                writer.write(PROFILE) { text }
            }
            is Persist.ConfChanged -> {
                val text = modules?.conf(change.conf, confTexts[change.conf]) ?: return
                confTexts[change.conf] = text
                writer.write(change.conf) { text }
            }
        }
    }

    // ---- events: the host's, as the app's, after the handlers the native host ran first

    private fun forward(event: HostEvent) {
        if (event is HostEvent.Tip) {
            // on the keyboard if it is up (Fcitx.showToast)
            EngineNotices.tip(event.text)
            return
        }
        FcitxEvents.of(event)?.let(::emit)
    }

    private fun emit(event: FcitxEvent<*>) {
        // the type only: the contents are what the user types, password fields included
        Timber.d("Handling ${event.eventType}")
        when (event) {
            is FcitxEvent.IMChangeEvent -> {
                // the input method's name only, never what is typed
                Timber.d("input method now %s", event.data.uniqueName)
                inputMethodEntryCached = event.data
            }
            is FcitxEvent.StatusAreaEvent -> {
                val (actions, im) = event.data
                statusAreaActionsCached = actions
                // a sub mode changed, which no IMChangeEvent tells: told first, as Fcitx's handler does
                if (im != inputMethodEntryCached) {
                    inputMethodEntryCached = im
                    val change = FcitxEvent.IMChangeEvent(im)
                    eventsOut.tryEmit(change)
                }
            }
            is FcitxEvent.ClientPreeditEvent -> clientPreeditCached = event.data
            is FcitxEvent.InputPanelEvent -> inputPanelCached = event.data
            else -> {}
        }
        eventsOut.tryEmit(event)
    }

    private companion object {
        const val PROFILE = "profile"
        const val CFG = "cfg"
        const val DESC = "desc"
        const val PUNCTUATION_ADDON = "punctuation"
        const val PUNCTUATION_MAP = "punctuationmap/"
        const val ZH_CN = "zh_CN"
        const val BUILT_IN = "pkgdata"
        const val OPENCC = "engine/opencc-s2tw.data"
        const val NANOS_PER_MILLI = 1_000_000L
        const val IDLE_SECONDS = 30L
    }
}
