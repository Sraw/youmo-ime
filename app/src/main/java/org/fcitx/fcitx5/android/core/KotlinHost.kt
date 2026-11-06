/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.host.ChineseConverter
import org.fcitx.fcitx5.android.host.ChineseModules
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.ConfigTree
import org.fcitx.fcitx5.android.host.ConfigWriter
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.host.FilePkgDataDir
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.HostEvents
import org.fcitx.fcitx5.android.host.InputMethodEntries
import org.fcitx.fcitx5.android.host.Key
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.ModuleConfigs
import org.fcitx.fcitx5.android.host.ModuleInputs
import org.fcitx.fcitx5.android.host.NineKeysMerge
import org.fcitx.fcitx5.android.host.OpenCCData
import org.fcitx.fcitx5.android.host.Persist
import org.fcitx.fcitx5.android.host.Profile
import org.fcitx.fcitx5.android.host.Punctuation
import org.fcitx.fcitx5.android.host.PunctuationConfig
import org.fcitx.fcitx5.android.host.PunctuationEntry
import org.fcitx.fcitx5.android.host.PunctuationProfile
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseHooks
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.utils.AppUtil
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
 * The Kotlin host behind [HostApi] (fcitx-replacement §4): [HostCore] on one thread
 * ([HostDispatcher], still `fcitx-main`), the engines through its own [EngineBridge], the modules as the
 * app's fcitx loads them ([ChineseModules]), fcitx's files at the same paths with the same INI
 * semantics, written as they change ([ConfigWriter]). Callers see the same [HostApi] as on the
 * native host, and the host's own [HostEvent]s as the event flow, which keeps every event (no buffer
 * of 15 that drops the oldest). The app's only host (HostDaemon).
 */
@Suppress("TooManyFunctions", "LargeClass")
class KotlinHost(
    private val context: Context,
    /** The engine side: null for this host's [bridge]; a test's scripted engine on the JVM, where the data is not. */
    engine: EngineSide? = null,
) : HostInstance {

    /** fcitx's `ShowInputMethodInformation`, read at [start]: the instrumented tests turn it off (TestHost). */
    @VisibleForTesting
    @Volatile
    var showInputMethodInformation = true

    /** The app's engines, this host's own: what the settings and the keyboard ask of them goes here, a scripted engine or not. */
    internal val bridge = EngineBridge()

    private val engine: EngineSide = engine ?: EngineSide.App(bridge)

    /**
     * What the host needs of the engine besides its port: where fcitx's data files are ([data]: the
     * punctuation maps, quick input) and its config ([config]), each called once a start, on the host
     * thread, and the engine's start and end.
     */
    interface EngineSide {
        val host: EngineHost
        fun data(): HostConfig
        fun config(): HostConfig
        fun start()
        fun close()

        class App(private val bridge: EngineBridge) : EngineSide {
            override val host: EngineHost get() = bridge.host
            // an older build's data moved in at the first start that can, as the config (A4)
            override fun data(): HostConfig = bridge.data()

            // an older build's config moved in at the first start that can (A4)
            override fun config(): HostConfig = HostConfigDir.settle()

            override fun start() {
                try {
                    bridge.reload()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "cannot reload the engine")
                }
            }

            override fun close() = bridge.close()
        }
    }

    private val lifecycleRegistry = HostLifecycleRegistry()

    override val lifecycle: HostLifecycle get() = lifecycleRegistry

    // every event kept, however slow a collector (design §4): tryEmit never fails
    private val eventsOut = MutableSharedFlow<HostEvent>(extraBufferCapacity = Int.MAX_VALUE)

    override val eventFlow = eventsOut.asSharedFlow()

    override val isReady get() = lifecycle.currentState == HostLifecycle.State.READY

    // the six written on the host thread, read on the main one (runImmediately)
    private val noInputMethod = InputMethodEntry(context.getString(R.string._not_available_))

    @Volatile
    override var inputMethodEntryCached = noInputMethod
        private set

    @Volatile
    override var statusAreaCached: List<StatusEntry> = emptyList()
        private set

    @Volatile
    override var clientPreeditCached = FormattedText.Empty
        private set

    @Volatile
    override var inputPanelCached = HostEvent.InputPanel.EMPTY
        private set

    @Volatile
    override var candidatesCached = HostEvent.Candidates.EMPTY
        private set

    @Volatile
    override var pagedCached = HostEvent.Paged.EMPTY
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
    // fcitx's data this run (EngineSide.data): with none, the built-in tables only
    private var dataHome: File? = null

    // the config's directory this run (EngineSide.config): none, every setting at its default
    private var configHome: File? = null

    // whether this run began before the user unlocked (startup's): a reload then settles nothing either
    private var directBoot = false

    // the config left where an older build kept it (ConfigHome.Problem.NOT_MOVED): its directory not listed is unread, not empty
    private var configNotMoved = false

    // the user told this run that the config could not be read
    private var configUnreadableTold = false

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
    override suspend fun reloadEngine() = withContext(dispatcher) { bridge.reload() }
    override suspend fun userWords() = withContext(dispatcher) { bridge.engines.userWords() }
    override suspend fun userWords(kind: UserWord.Kind) = withContext(dispatcher) { bridge.engines.userWords(kind) }
    override suspend fun userWordCounts() = withContext(dispatcher) { bridge.engines.userWordCounts() }
    override suspend fun sameCodes(texts: List<String>, ims: List<String>) = withContext(dispatcher) { bridge.engines.sameCodes(texts, ims) }
    override suspend fun pinyinOf(text: String) = withContext(dispatcher) { bridge.engines.pinyinOf(text) }
    override suspend fun pinyinsOf(texts: List<String>) = withContext(dispatcher) { bridge.engines.pinyinsOf(texts) }
    override suspend fun charCard(index: Int, text: String, paged: Boolean) = onHost { inputMethodEntryCached.uniqueName.takeIf(EngineBridge::isEngines)?.let { bridge.engines.charCard(it, index, text, paged) } }
    override suspend fun wordBoundaries(texts: List<String>) = withContext(dispatcher) {
        aTextAtATime(texts) { bridge.engines.wordBoundaries(it) }
    }
    override suspend fun logProbs(texts: List<String>) = withContext(dispatcher) { bridge.engines.logProbs(texts) }
    override suspend fun addUserWord(text: String, pinyin: String) = withContext(dispatcher) { bridge.engines.addWord(text, pinyin) }
    override suspend fun blockUserWord(text: String, pinyin: String) = withContext(dispatcher) { bridge.engines.blockWord(text, pinyin) }
    override suspend fun removeUserWords(words: List<UserWord>) = withContext(dispatcher) { bridge.engines.removeWords(words) }
    override suspend fun englishWords() = withContext(dispatcher) { bridge.engines.englishWords() }
    override suspend fun addEnglishWord(text: String) = withContext(dispatcher) { bridge.engines.addEnglishWord(text) }
    override suspend fun forgetEnglishWords(texts: List<String>) = withContext(dispatcher) { bridge.engines.forgetEnglishWords(texts) }
    override suspend fun importUserWords(lines: Sequence<String>, fileName: String) = withContext(dispatcher) { bridge.engines.importWords(lines, fileName) }
    override suspend fun importBatches() = withContext(dispatcher) { bridge.engines.importBatches() }
    override suspend fun importedWords(batch: Int?) = withContext(dispatcher) { bridge.engines.importedWords(batch) }
    override suspend fun dropImport(batch: Int) = withContext(dispatcher) { bridge.engines.dropImport(batch) }
    override suspend fun restoreImport(batch: Int) = withContext(dispatcher) { bridge.engines.restoreImport(batch) }
    override suspend fun tableKeys(im: String) = withContext(dispatcher) { bridge.tableKeys(im) }

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
        if (bridge.context(
                before, learning, inputMethodEntryCached.uniqueName, cleared = cleared, learnFlags = learnFlags,
                started = started, after = after, selected = selected, said = said,
            )) {
            sendVirtual(Keyboard.RECALL)
        }
    }

    override suspend fun offerInk(reading: InkReading, piece: Int) = onHost { inkKey(Action.Ink(piece, reading)) }

    override suspend fun inkFinished(piece: Int) = onHost { inkKey(Action.Ink(piece)) }

    // the Ink key carries what the pad told: it cannot come apart from it
    private fun HostCore.inkKey(piece: Action.Ink) = bridge.sendInk(piece, inputMethodEntryCached.uniqueName) { sendVirtual(Keyboard.INK, piece) }

    override suspend fun learning(op: LearningOp) = onHost {
        val candidate = (op as? LearningOp.OnPage)?.op ?: op as? LearningOp.OnCandidate
        bridge.learning(op, inputMethodEntryCached.uniqueName) act@{ event ->
            // as the menu sends it: asked first, at the index the list has (Fcitx.learning)
            if (candidate == null) return@act false
            if (candidateActions(candidate.index).none { it.id == event }) return@act false
            triggerCandidateAction(candidate.index, event, candidate.text)
            true
        }.also { (_, again) -> if (again) sendVirtual(Keyboard.AGAIN) }.first
    }

    override suspend fun offerGlide(glide: Glide) = onHost {
        val read = bridge.glide(glide, inputMethodEntryCached.uniqueName)
        read?.let { sendVirtual(Keyboard.GLIDE, it) }
        read != null
    }

    // a private-use key the engine reads, carrying [payload], what it reads there (KeyEvent.payload)
    private fun HostCore.sendVirtual(char: Int, payload: Action? = null) {
        sendKey(Key(UNICODE + char, HostKeyStates.VIRTUAL), virtual = true, payload = payload)
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
    override suspend fun clearTyped(keyboard: (im: String) -> Any?) = onHost { clearTyped(keyboard) }
    override suspend fun retype(typing: HostCore.Retyping, keyboard: Any?) = onHost { retype(typing, keyboard) }
    override suspend fun moveCursor(position: Int) = onHost { moveCursor(position) }

    // ---- input methods

    override suspend fun availableIme() = withContext(dispatcher) { InputMethodCatalog.LISTED.map { InputMethodEntries.of(it.name) }.toTypedArray() }

    override suspend fun enabledIme() = onHost { contexts.group.inputMethods.map(InputMethodEntries::of).toTypedArray() }

    override suspend fun setEnabledIme(array: Array<String>) = onHost { setInputMethods(array.toList()) }
    override suspend fun toggleIme() = onHost { toggle() }
    override suspend fun activateIme(ime: String) = onHost { setCurrentInputMethod(ime) }
    override suspend fun enumerateIme(forward: Boolean) = onHost { enumerate(forward) }
    override suspend fun currentIme() = onHost { InputMethodEntries.of(currentInputMethod()) }

    // ---- settings pages (design §5)

    override suspend fun imOptions(im: String) = withContext(dispatcher) { options.page(im)?.leaves() }

    override suspend fun setImOptions(im: String, values: Map<String, String>) = onHost {
        if (!options.setPage(im, ConfigTree.of(values))) return@onHost
        val text = options.text()
        writer?.write(EngineOptions.PATH) { text }
        configure(options)
    }

    override suspend fun punctuationMap(lang: String) = withContext(dispatcher) {
        if (lang != ZH_CN) emptyList() else punctuationMaps.load(ZH_CN)
    }

    override suspend fun setPunctuationMap(lang: String, entries: List<PunctuationMapEntry>) = onHost {
        if (lang != ZH_CN) return@onHost
        // the edit page's table (PunctuationProfile::set) saved whole, then used
        val profile = PunctuationProfile.fromEntries(entries.map { PunctuationEntry(it.key, it.mapping, it.altMapping) })
        // a save refused (the data not settled, the table not read) goes back to the page, which says so
        // (PunctuationSaves): used here anyway, the edit would hold this run and be gone at the next
        punctuationMaps.save(ZH_CN, profile.entries.map { PunctuationMapEntry(it.key, it.mapping, it.altMapping) })
        modules?.punctuation?.setProfile(profile)
    }

    // ---- quick input, focus, status area, candidates

    override suspend fun triggerQuickPhrase() = onHost { triggerQuickPhrase(); Unit }
    override suspend fun quickPhraseAt(index: Int) = onHost { quickPhraseAt(index) }
    override suspend fun triggerUnicode() = onHost { triggerUnicode(); Unit }
    override suspend fun focus(focus: Boolean) = onHost { focus(focus) }
    override suspend fun focusOutIn() = onHost { focusOutIn() }
    override suspend fun activate(uid: Int, pkgName: String) = onHost { activate(uid) }
    override suspend fun deactivate(uid: Int) = onHost { deactivate(uid) }
    override suspend fun setCapFlags(flags: CapabilityFlags) = onHost { setCapabilities(flags.toLong()); Unit }
    override suspend fun statusArea(): List<StatusEntry> = onHost { statusArea() }
    override suspend fun activateAction(id: Int) = onHost { activateAction(id); Unit }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        onHost { candidates(offset, limit).map { CandidateWord(it.label, it.text, it.comment, readings = it.readings) }.toTypedArray() }

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> =
        onHost { candidateActions(idx).map { CandidateAction(it.id, it.text, isSeparator = false, icon = "", isCheckable = false, isChecked = false) }.toTypedArray() }

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) = onHost { triggerCandidateAction(idx, actionIdx, text) }
    override suspend fun setCandidatePagingMode(mode: Int) = onHost { setCandidatePagingMode(mode) }
    override suspend fun offsetCandidatePage(delta: Int) = onHost { offsetCandidatePage(delta) }

    // ---- start and stop

    override fun start() {
        if (lifecycle.currentState != HostLifecycle.State.STOPPED) {
            Timber.w("Skip starting fcitx: not at stopped state!")
            return
        }
        lifecycleRegistry.postEvent(HostLifecycle.Event.ON_START)
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
        if (lifecycle.currentState != HostLifecycle.State.READY) {
            Timber.w("Skip stopping fcitx: not at ready state!")
            return
        }
        lifecycleRegistry.postEvent(HostLifecycle.Event.ON_STOP)
        Timber.i("KotlinHost stop()")
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
            forgetCaches()
            // stopped even when the last step threw, so a later start (another restart, a new client's
            // connect) can run; the throw goes on, so this restart's own start is skipped
            lifecycleRegistry.postEvent(HostLifecycle.Event.ON_STOPPED)
        }
    }

    // the stopped host's state: a view made anew before the next host speaks (a restart, the app's
    // language changed) would show its code row and candidates, a dead engine's
    private fun forgetCaches() {
        inputMethodEntryCached = noInputMethod
        statusAreaCached = emptyList()
        clientPreeditCached = FormattedText.Empty
        inputPanelCached = HostEvent.InputPanel.EMPTY
        candidatesCached = HostEvent.Candidates.EMPTY
        pagedCached = HostEvent.Paged.EMPTY
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

    /** `KotlinHost.start` of design §4: the config and data directories settled, the engine, the files, the core, then ready. */
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
        val data = engine.data()
        dataHome = data.dir
        val config = engine.config()
        configHome = config.dir
        configNotMoved = config.problem == ConfigHome.Problem.NOT_MOVED
        configUnreadableTold = false
        step("config")
        engine.start()
        step("engine.start")
        // an edited table not read is not saved over, nor one where the data is not settled; the user told
        punctuationMaps = PunctuationMaps(data.dir, writable = !directBoot && data.writable, onUnreadable = { dataUnreadable() }) { lang ->
            try {
                context.assets.open("$BUILT_IN/punctuation/punc.mb.$lang")
            } catch (_: IOException) {
                null
            }
        }
        // before the first unlock nothing is written (design §2.2): the process restarts at unlock; nor
        // where the config is not settled (ConfigHome.Problem), the user told
        val sink = config.dir?.let { dir -> ConfigWriter.files(dir) { path, e -> Timber.w(e, "cannot write %s", path) } }
        writer = ConfigWriter(!directBoot && config.writable && sink != null, configThread, sink ?: { _, _ -> false })
        if (!directBoot && !config.writable) EngineNotices.failedUntilSeen(R.string.config_not_settled)
        if (!directBoot && !data.writable) dataUnreadable()
        step("punctuation maps, writer")
        val strings = AppHostStrings(context)
        val available = InputMethodCatalog.LISTED.map { it.name }.toSet()
        val profile = mergeNineKeys(Profile.load(readConfig(PROFILE).getOrNull(), available + NineKeysMerge.OLD, strings.defaultGroup), directBoot)
        this.directBoot = directBoot
        loadOptions(readConfig(EngineOptions.PATH).getOrNull(), directBoot)
        if (!directBoot) tellUnseen()
        for (path in CONF_FILES) confTexts[path] = readConfig(path).getOrNull()
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
                quickPhraseHooks = quickPhraseHooks,
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
        made.events += HostEvents(::forward)
        made.configure(options)
        core = made
        // the modules are built by HostCore's constructor: 简繁's list is there only now
        publishTraditional()
        step("HostCore")
        if (wasFirstRun) firstRun = false
        Timber.i("Kotlin host ready in %d ms", (System.nanoTime() - started) / NANOS_PER_MILLI)
        lifecycleRegistry.postEvent(HostLifecycle.Event.ON_READY)
        emit(HostEvent.Ready)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lifecycle.launchWhenReady { SubtypeManager.syncWith(enabledIme()) }
        }
    }

    // the module files read again (fcitx's reloadConfig): the punctuation table, the modules' files, quick phrases, the engine options
    private fun HostCore.readModuleFiles() {
        // one that cannot be read now keeps what was read before
        for (path in CONF_FILES) readConfig(path).onSuccess { confTexts[path] = it }
        modules?.punctuation?.let {
            it.setProfile(punctuationProfile())
            // read, not changed: not written back
            it.setConfigQuietly(ModuleConfigs.punctuation(confTexts[ModuleConfigs.PUNCTUATION]))
        }
        // traditional per input method as the file now says, the toggle's icon told anew
        modules?.chttrans?.reload(ModuleConfigs.chttrans(confTexts[ModuleConfigs.CHTTRANS]))
        publishTraditional()
        contexts.active?.let(::updateStatusArea)
        modules?.quickPhrase?.reload(quickPhrasesLater())
        // open now (an entry deleted from its card): its list from the files as they are
        refreshQuickPhrase()
        readConfig(EngineOptions.PATH).onSuccess { loadOptions(it, directBoot) }
        if (!directBoot) tellUnseen()
        configure(options)
    }

    /**
     * The config file [path]'s text: null if it is not there (every option in it at its default, as
     * fcitx writes only what is set). One there that cannot be read is a failure, never its defaults
     * (A4: a start that read none put 通用输入 back to its defaults, written so at the next change):
     * held, so nothing replaces it this run, and the user told.
     */
    private fun readConfig(path: String): Result<String?> {
        val home = configHome ?: return Result.success(null)
        val file = File(home, path)
        return try {
            if (!file.exists()) {
                if (!statFailed(file, home)) return Result.success(null).also { writer?.release(path) }
                // a first write renamed in place between the two looks (a reload racing a change) is there now
                if (!file.exists()) throw IOException("$path is listed but cannot be read")
            }
            // read after all (a reload): saved again
            Result.success(file.readText().also { writer?.release(path) })
        } catch (e: IOException) {
            Timber.e(e, "cannot read %s: kept as it is, not written this run", path)
            writer?.hold(path)
            configUnreadable()
            Result.failure(e)
        }
    }

    // whether [file], which exists() says is not there, may be after all: a failed stat (the external
    // storage's FUSE) reads as no file, which would load the defaults and write them over it at the next
    // change. So: its directory lists it, or a directory on the way to [home] is there but cannot be listed
    private fun statFailed(file: File, home: File): Boolean {
        var f = file
        while (true) {
            val parent = f.parentFile ?: return false
            parent.list()?.let { return f.name in it }
            if (parent.exists()) return true
            if (parent == home) return configNotMoved
            f = parent
        }
    }

    // what the user added (custom phrases, quick input, an edited punctuation table) not read or not
    // settled: said once a process, as the theme list and the engine's phrases say it
    private fun dataUnreadable() = EngineNotices.failedOnce(R.string.user_data_unreadable)

    // said on the keyboard, once a run, till the user has seen it there
    private fun configUnreadable() {
        if (configUnreadableTold) return
        configUnreadableTold = true
        EngineNotices.failedUntilSeen(R.string.config_unreadable)
    }

    private fun Punctuation.setConfigQuietly(config: PunctuationConfig) {
        if (config != this.config) setConfig(config)
    }

    private fun punctuationProfile(): PunctuationProfile =
        PunctuationProfile.fromEntries(punctuationMaps.load(ZH_CN).map { PunctuationEntry(it.key, it.mapping, it.altMapping) })

    // fcitx loads them as the addon loads, in C++ at no cost; here 0.4 s on a debug build, so on a
    // thread of their own, at the start in parallel with the engine and at a reload with the keys
    // typed meanwhile; a lookup before they are read waits for them
    private fun quickPhrasesLater(): () -> QuickPhraseBook {
        val table = FutureTask { quickPhrases() }
        Thread(table, "host-quickphrase").start()
        return table::get
    }

    // the user's own (fcitx's data, which backups keep) and the built-in ones; the user's not read: the
    // built-in ones still, the user told (the settings page reads them anew before it writes)
    private fun quickPhrases(): QuickPhraseBook {
        val builtIn = AssetPkgDataDir(context.assets, BUILT_IN)
        val user = dataHome?.let(::FilePkgDataDir)
        if (user != null) {
            try {
                return QuickPhraseBook.load(user, builtIn)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // any slip reading the user's (not only IOException): the built-in ones, never a key's
                // lookup failing in the FutureTask
                Timber.e(e, "quick phrases: the built-in ones only")
                dataUnreadable()
            }
        }
        return try {
            QuickPhraseBook.load(null, builtIn)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "quick phrases")
            QuickPhraseBook.EMPTY
        }
    }

    // quick input's ties to the app: the nine keys' letters, the habits (借手机), the use kept, ＋添加
    private val quickPhraseHooks = object : QuickPhraseHooks {
        // read at every key quick input sees: parsed again only when the setting's text changed
        private var nineKeysRead: Pair<String, NineKeyLetters>? = null

        override val nineKeys: NineKeyLetters
            get() {
                val raw = AppPrefs.getInstance().keyboard.t9Letters.getValue()
                nineKeysRead?.let { (seen, letters) -> if (seen == raw) return letters }
                return NineKeyLetters.parse(raw).also { nineKeysRead = raw to it }
            }

        override val withMine: Boolean get() = AppPrefs.getInstance().internal.useHabits.getValue()

        override fun used(entry: QuickPhraseEntry, at: Long) {
            // on the writer's thread, never the host's: a key is not held on a file
            configThread.execute {
                try {
                    QuickPhraseManager.markUsed(entry, at)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // a file this cannot write or parse costs the use kept, never the input method's process
                    Timber.w(e, "quick phrase use")
                }
            }
        }

        override fun add() {
            ContextCompat.getMainExecutor(context).execute { AppUtil.launchQuickPhraseEditor(context) }
        }
    }

    // OpenCC s2tw, mapped where it lies in the APK, at the first traditional text
    private fun converter(): ChineseConverter? = try {
        ChineseConverter.s2tw(OpenCCData.read(bridge.asset(OPENCC)))
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

    /**
     * [loaded] with 0.4's 九键拼音 read as pinyin on the nine keys (NineKeysMerge), at every start: an
     * upgrade's profile and a 0.4 backup restored alike, nothing done where none names it. The profile
     * written once it is unlocked ([directBoot] writes nothing); pinyin's keyboard set and the user who
     * had both told once where the keyboards are switched now (I1 Q2), once by a flag of their own
     * ([AppPrefs.Internal.nineKeysMerged]): a profile that could not be written back is merged again at
     * the next start, which must not put back a keyboard the user changed since, nor tell it again.
     * The flag is the keyboard's (set with it, in the same prefs), not the profile's: whether the
     * profile is on disk changes nothing it reads, as the merge is made at each read of 九键拼音. The
     * notice waits in [AppPrefs.Internal.nineKeysUnseen] till it is seen ([tellUnseen]).
     */
    private fun mergeNineKeys(loaded: Profile, directBoot: Boolean): Profile {
        val prefs = AppPrefs.getInstance()
        val keyboard = prefs.keyboard.pinyinKeyboard
        val merged = loaded.mergeNineKeys(keyboard.getValue().keyboard)
        if (!merged.changed || directBoot) return merged.profile
        if (!prefs.internal.nineKeysMerged.getValue()) {
            keyboard.setValue(PinyinKeyboard.of(merged.pinyin))
            prefs.internal.nineKeysMerged.setValue(true)
            if (merged.both) prefs.internal.nineKeysUnseen.setValue(true)
        }
        persist(Persist.ProfileChanged(merged.profile))
        return merged.profile
    }

    /**
     * The options read, 双拼's scheme as 0.5 has them (SchemeMigration): an install from before 0.5
     * (not this one's first run, not [AppPrefs.Internal.schemeSettled]: a 0.4 backup's prefs restored
     * have neither) with none stored pinned to 自然码, a dropped one 小鹤 and the user told once;
     * written back. Settled where what the file says is read so again: a scheme kept or dropped (read
     * again, the same), or the pin once it is on disk; a pin not written (the config held as it could not
     * be read, not writable, a failed write) is made again at the next start. [directBoot] reads the
     * defaults and settles nothing. A dropped scheme's notice waits in [AppPrefs.Internal.schemeRetiredUnseen]
     * till it is seen ([tellUnseen]).
     */
    private fun loadOptions(read: String?, directBoot: Boolean) {
        val internal = AppPrefs.getInstance().internal
        // direct boot's prefs are a copy, its config none of the user's: the defaults, 小鹤
        options.load(read.orEmpty(), upgrade = !directBoot && !firstRun && !internal.schemeSettled.getValue())
        if (directBoot) return
        val settle: () -> Unit = { internal.schemeSettled.setValue(true) }
        val migrated = options.schemeMigrated
        val retired = migrated?.retired
        when {
            // the scheme kept: read again, the same
            migrated == null -> settle()
            // read again it is dropped again: settled whether or not 小鹤 is written
            retired != null -> {
                if (!internal.schemeRetiredTold.getValue()) internal.schemeRetiredUnseen.setValue(retired)
                settle()
                val written = options.text()
                writer?.write(EngineOptions.PATH, null) { written }
            }
            // the pin: the next start reads 自然码 only once it is on disk, so settled then and not before
            else -> {
                val written = options.text()
                writer?.write(EngineOptions.PATH, settle) { written }
            }
        }
    }

    /**
     * The I1 notices not yet seen ([mergeNineKeys], [loadOptions]) told until they are: at each start, so
     * one a process ended before any keyboard showed is told at the next; each kept till said on the
     * keyboard. Told again as a reload reads the config, the same notice (one per id) waiting.
     */
    private fun tellUnseen() {
        val internal = AppPrefs.getInstance().internal
        if (internal.nineKeysUnseen.getValue()) {
            EngineNotices.toldUntilSeen(R.string.nine_keys_merged) { internal.nineKeysUnseen.setValue(false) }
        }
        val retired = internal.schemeRetiredUnseen.getValue()
        if (retired.isNotEmpty()) {
            val name = ShuangpinNames.of(context, retired) ?: retired
            EngineNotices.toldUntilSeen(R.string.shuangpin_scheme_retired, name) {
                internal.schemeRetiredTold.setValue(true)
                internal.schemeRetiredUnseen.setValue("")
            }
        }
    }

    private fun publishTraditional() {
        EngineState.traditionalIms = modules?.chttrans?.enabledIm.orEmpty()
    }

    private fun persist(change: Persist) {
        // a toggle of 简繁 saves the list: the keyboard reads it from here (EngineState.traditionalIms)
        if (change is Persist.ConfChanged && change.conf == ModuleConfigs.CHTTRANS) publishTraditional()
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

    // ---- events: the host's, as they are, into the app's flow

    private fun forward(event: HostEvent) {
        if (event is HostEvent.Tip) {
            // on the keyboard if it is up (Fcitx.showToast)
            EngineNotices.tip(event.text)
            return
        }
        emit(event)
    }

    private fun emit(event: HostEvent) {
        // the type only: the contents are what the user types, password fields included; debug builds
        // alone, as it is every key's
        if (BuildConfig.DEBUG) Timber.d("Handling %s", event.javaClass.simpleName)
        when (event) {
            is HostEvent.InputMethodChanged -> {
                // the input method's name only, never what is typed
                Timber.d("input method now %s", event.im)
                inputMethodEntryCached = event.entry
            }
            is HostEvent.StatusArea -> {
                statusAreaCached = event.entries
                // a sub mode changed, which no InputMethodChanged tells: told first, as Fcitx's handler does
                if (event.entry != inputMethodEntryCached) {
                    inputMethodEntryCached = event.entry
                    eventsOut.tryEmit(HostEvent.InputMethodChanged(event.entry))
                }
            }
            is HostEvent.ClientPreedit -> clientPreeditCached = event.text
            is HostEvent.InputPanel -> inputPanelCached = event
            // one or the other, as the paging mode has it: the one not sent last is no longer the engine's
            is HostEvent.Candidates -> {
                candidatesCached = event
                pagedCached = HostEvent.Paged.EMPTY
            }
            is HostEvent.Paged -> {
                pagedCached = event
                candidatesCached = HostEvent.Candidates.EMPTY
            }
            else -> {}
        }
        eventsOut.tryEmit(event)
    }

    private companion object {
        const val PROFILE = "profile"

        // the modules' files, read at the start and again at reloadConfig
        val CONF_FILES = listOf(ModuleConfigs.PUNCTUATION, ModuleConfigs.CHTTRANS)
        const val ZH_CN = "zh_CN"
        const val BUILT_IN = "pkgdata"
        const val OPENCC = "engine/opencc-s2tw.data"
        const val NANOS_PER_MILLI = 1_000_000L
        const val IDLE_SECONDS = 30L
    }
}

// a text at a time, yielding between: a key typed meanwhile goes in before the next, and a caller that
// gave up (the clipboard's split after its second) stops the rest instead of holding the thread through them
internal suspend fun aTextAtATime(texts: List<String>, of: (List<String>) -> Map<String, IntArray>): Map<String, IntArray> =
    buildMap {
        for (text in texts) {
            yield()
            putAll(of(listOf(text)))
        }
    }
