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
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.reading.CardReading
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.OwnKeysTurn
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
import org.fcitx.fcitx5.android.host.HandwritingRetire
import org.fcitx.fcitx5.android.host.HostCore
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.HostEvents
import org.fcitx.fcitx5.android.host.InputMethodEntries
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
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseHooks
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.fcitx.fcitx5.android.pack.PackKind
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
 * The Kotlin host behind [HostApi]: [HostCore] on one thread ([HostDispatcher]), the engines through its
 * [EngineBridge], fcitx's files at the same paths and INI semantics ([ConfigWriter]). Its event flow keeps every
 * event (no 15-event buffer dropping the oldest).
 */
@Suppress("TooManyFunctions", "LargeClass")
class KotlinHost(
    private val context: Context,
    /** Null for this host's [bridge]; a test's scripted engine on the JVM. */
    engine: EngineSide? = null,
) : HostInstance {

    /** The instrumented tests turn it off (TestHost). */
    @VisibleForTesting
    @Volatile
    var showInputMethodInformation = true

    internal val bridge = EngineBridge()

    private val engine: EngineSide = engine ?: EngineSide.App(bridge)

    /** Each called once a start, on the host thread. */
    interface EngineSide {
        val host: EngineHost
        fun data(): HostConfig
        fun config(): HostConfig
        fun start()
        fun close()

        /** An event for [im], not the one shown, at any time: nothing it answers is shown. */
        fun aside(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?) {
            host.event(im, event, arg, learning, payload)
        }

        class App(private val bridge: EngineBridge) : EngineSide {
            override val host: EngineHost get() = bridge.host

            override fun aside(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?) =
                bridge.aside(im) { bridge.engines.onEvent(im, event, arg, learning, payload) }

            override fun data(): HostConfig = bridge.data()

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

    // every event kept, however slow a collector: tryEmit never fails
    private val eventsOut = MutableSharedFlow<HostEvent>(extraBufferCapacity = Int.MAX_VALUE)

    override val eventFlow = eventsOut.asSharedFlow()

    override val isReady get() = lifecycle.currentState == HostLifecycle.State.READY

    // the seven below: written on the host thread, read on main
    private val noInputMethod = InputMethodEntry(context.getString(R.string._not_available_))

    @Volatile
    override var inputMethodEntryCached = noInputMethod
        private set

    @Volatile
    override var enabledImeCached: List<InputMethodEntry> = emptyList()
        private set

    @Volatile
    override var statusAreaCached: List<StatusEntry> = emptyList()
        private set

    @Volatile
    override var punctuationCached: Map<String, String>? = null
        private set

    @Volatile
    override var fullWidthCached: Boolean = false
        private set

    // kept for the next start, the keyboard's before this host is up
    private fun HostCore.keepStartKeys() = PunctuationStartKeys.write(context, startPunctuationKeys())

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

    private var core: HostCore? = null
    private var modules: ChineseModules? = null
    private val options = EngineOptions()
    private var writer: ConfigWriter? = null

    // ends when idle, so a stopped host keeps no thread
    private val configThread = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, "host-config")
    }.apply { allowCoreThreadTimeOut(true) }
    private lateinit var punctuationMaps: PunctuationMaps
    // with none, the built-in tables only
    private var dataHome: File? = null

    // with none, every setting at its default
    private var configHome: File? = null

    // a reload then settles nothing either
    private var directBoot = false

    // a directory not listed is unread, not empty
    private var configNotMoved = false

    private var configUnreadableTold = false

    // what the next write keeps of them
    private val confTexts = HashMap<String, String?>()

    // Unicode lists its characters when it opens
    @Volatile
    private var clipboardText = ""

    @Keep
    private val onClipboardUpdate = ClipboardManager.OnClipboardUpdateListener {
        // fcitx's clipboard kept passwords out of what it offered
        clipboardText = if (it.sensitive) "" else it.text
    }

    private var firstRun by AppPrefs.getInstance().internal.firstRun

    private val host: HostCore get() = core ?: throw IllegalStateException("the Kotlin host did not start")

    private suspend inline fun <T> onHost(crossinline block: HostCore.() -> T): T = withContext(dispatcher) { block(host) }

    override suspend fun save() = withContext(dispatcher) { writer?.flush() ?: Unit }

    override suspend fun reloadConfig() = onHost { readModuleFiles() }
    override suspend fun reloadEngine() = withContext(dispatcher) { bridge.reload() }

    /** What the engine types with now: EngineEvalRunner's run compares with ime-eval's only where they agree. */
    internal suspend fun engineSettings() = withContext(dispatcher) { bridge.engines.settings }
    override suspend fun userWords() = withContext(dispatcher) { bridge.engines.userWords() }
    override suspend fun userWords(kind: UserWord.Kind) = withContext(dispatcher) { bridge.engines.userWords(kind) }
    override suspend fun userWordCounts() = withContext(dispatcher) { bridge.engines.userWordCounts() }
    override suspend fun sameCodes(texts: List<String>, ims: List<String>) = withContext(dispatcher) { bridge.engines.sameCodes(texts, ims) }
    override suspend fun pinyinOf(text: String) = withContext(dispatcher) { bridge.engines.pinyinOf(text) }
    override suspend fun pinyinsOf(texts: List<String>) = withContext(dispatcher) { bridge.engines.pinyinsOf(texts) }
    // 繁體: the candidate as shown, converted; the engine checks it against its own
    override suspend fun candidateCard(index: Int, text: String, paged: Boolean) =
        onHost { inputMethodEntryCached.uniqueName.takeIf(EngineBridge::isEngines)?.let { bridge.engines.card(it, index, text, paged, ::shownText) } }

    override suspend fun charCardOf(char: String, reading: CardReading?) = onHost { bridge.engines.charCardOf(char, reading) }
    override suspend fun wordBoundaries(texts: List<String>) = withContext(dispatcher) {
        aTextAtATime(texts) { bridge.engines.wordBoundaries(it) }
    }
    override suspend fun logProbs(texts: List<String>) = withContext(dispatcher) { bridge.engines.logProbs(texts) }
    override suspend fun voiceHomophones(words: List<UserWord>) = withContext(dispatcher) { bridge.engines.voiceHomophones(words) }
    override suspend fun voicePick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>) =
        withContext(dispatcher) { bridge.engines.voicePick(homophones, text, before, blocked) }
    override suspend fun voiceStretches(texts: List<String>) = withContext(dispatcher) { bridge.engines.voiceStretches(texts) }
    override suspend fun rareHeld(texts: Collection<String>) = withContext(dispatcher) { bridge.engines.rareHeld(texts) }
    override suspend fun rareGrid() = withContext(dispatcher) { bridge.engines.rareGrid() }
    override suspend fun addUserWord(text: String, pinyin: String) = withContext(dispatcher) { bridge.engines.addWord(text, pinyin) }
    override suspend fun voiceSaid(text: String, before: String?, hinted: Boolean) = withContext(dispatcher) { bridge.engines.voiceSaid(text, before, hinted) }
    override suspend fun voicePutIn(text: String) = withContext(dispatcher) { bridge.engines.voicePutIn(text) }
    override suspend fun voiceConfirm(before: String?, after: String?) = withContext(dispatcher) { bridge.engines.voiceConfirm(before, after) }
    override suspend fun voiceEditsEnd() = withContext(dispatcher) { bridge.engines.voiceEditsEnd() }
    override suspend fun voiceRemember(ask: VoiceEdits.Ask) = withContext(dispatcher) { bridge.engines.voiceRemember(ask) }
    override suspend fun voiceForget(ask: VoiceEdits.Ask) = withContext(dispatcher) { bridge.engines.voiceForget(ask) }
    override suspend fun blockUserWord(text: String, pinyin: String) = withContext(dispatcher) { bridge.engines.blockWord(text, pinyin) }
    override suspend fun removeUserWords(words: List<UserWord>) = withContext(dispatcher) { bridge.engines.removeWords(words) }
    override suspend fun englishWords() = withContext(dispatcher) { bridge.engines.englishWords() }
    override suspend fun addEnglishWord(text: String) = withContext(dispatcher) { bridge.engines.addEnglishWord(text) }
    override suspend fun forgetEnglishWords(texts: List<String>) = withContext(dispatcher) { bridge.engines.forgetEnglishWords(texts) }
    override suspend fun importUserWords(lines: Sequence<String>, fileName: String) = withContext(dispatcher) { bridge.engines.importWords(lines, fileName) }
    override suspend fun importBatches() = withContext(dispatcher) { bridge.engines.importBatches() }
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

    // the table of the last offer tapped: its notice stays up across a switch, and pinyin or another table ignores its 撤销
    private var rememberedIn: String? = null

    override suspend fun rememberWord(id: Int, index: Int, before: String) = onHost {
        rememberedIn = inputMethodEntryCached.uniqueName
        sendVirtual(Keyboard.REMEMBER, Action.Remember(id, index, before))
    }

    override suspend fun unrememberWord(text: String, code: String): Unit = onHost {
        val undo = Action.Unremember(text, code)
        val im = rememberedIn
        if (im == null || im == inputMethodEntryCached.uniqueName) {
            sendVirtual(Keyboard.REMEMBER, undo)
        } else {
            try {
                engine.aside(im, EngineEvent.CHAR, Keyboard.REMEMBER, learning = true, payload = undo)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "a remembered word's undo in $im")
            }
        }
    }

    override suspend fun offerShown(id: Int) = onHost { sendVirtual(Keyboard.REMEMBER, Action.OfferShown(id)) }

    // the Ink key carries what the keys' ink told, so they cannot come apart
    private fun HostCore.inkKey(piece: Action.Ink) =
        bridge.sendInk(piece, inputMethodEntryCached.uniqueName) { sendVirtual(Keyboard.INK, piece) }

    override suspend fun learning(op: LearningOp) = onHost {
        // 繁體: the menu names the candidate as shown, the engine has it simplified; acts go by index and engine text
        bridge.learning(op, inputMethodEntryCached.uniqueName, ::shownText) { event, index, text -> actOnCandidate(index, event, text) }
            .also { (_, again) -> if (again) sendVirtual(Keyboard.AGAIN) }.first
    }

    override suspend fun offerGlide(glide: Glide) = onHost {
        val read = bridge.glide(glide, inputMethodEntryCached.uniqueName)
        read?.let { sendVirtual(Keyboard.GLIDE, it) }
        read != null
    }

    private fun HostCore.sendVirtual(char: Int, payload: Action? = null) {
        sendKey(Key(UNICODE + char, HostKeyStates.VIRTUAL), virtual = true, payload = payload)
    }

    override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int, touch: Touch?) =
        onHost { key(KeySyms.fromString(key), states.toInt(), up, timestamp, touch) }

    // a lone surrogate is no key
    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(KeySyms.fromString(c.toString()), states.toInt(), up, timestamp) }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        onHost { key(sym, states.toInt(), up, timestamp) }

    override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int, touch: Touch?) =
        onHost { key(sym.sym, states.toInt(), up, timestamp, touch) }

    // virtual is the state's bit, as fcitx's KeyEvent::isVirtual reads it; [touch] rides as the payload
    private fun HostCore.key(sym: Int, states: Int, up: Boolean, timestamp: Int, touch: Touch? = null) {
        // a key on other own groups than the ones typed on commits the code first, as Return does, rather than losing it
        // with the remade session; a key before it (Backspace, space, a syllable) still belongs to the old groups
        val own = EngineState.ownKeys
        val unicode = KeySyms.toUnicode(sym)
        val turn = bridge.turnAt(own, unicode)
        if (turn == OwnKeysTurn.Step.COMMIT_AND_TURN) commitCode()
        if (turn != OwnKeysTurn.Step.WAIT) bridge.syncOwnKeys(own)
        // the payload carries only the tap's place (Keyboard.char reads only .touch): its char is no key's for a code past the BMP
        val payload = touch?.takeUnless { up }?.let { Action.Key(unicode.toChar(), touch = it) }
        sendKey(Key(sym, states), release = up, virtual = states and HostKeyStates.VIRTUAL != 0, timestamp = timestamp, payload = payload)
    }

    override suspend fun select(idx: Int, text: String?): Boolean = onHost { select(idx, text) }
    override suspend fun isEmpty(): Boolean = onHost { isEmpty() }
    override suspend fun reset(drop: Boolean) = onHost { reset(drop) }
    override suspend fun dropPredictions() = onHost { dropPredictions() }
    override suspend fun clearTyped(keyboard: (im: String) -> Any?) = onHost { clearTyped(keyboard) }
    override suspend fun retype(typing: HostCore.Retyping, keyboard: Any?) = onHost { retype(typing, keyboard) }
    override suspend fun moveCursor(position: Int) = onHost { moveCursor(position) }

    override suspend fun availableIme() = withContext(dispatcher) { InputMethodCatalog.LISTED.map { InputMethodEntries.of(it.name) }.toTypedArray() }

    override suspend fun enabledIme() = onHost { contexts.group.inputMethods.map(InputMethodEntries::of).toTypedArray() }

    override suspend fun setEnabledIme(array: Array<String>) = onHost { setInputMethods(array.toList()) }
    override suspend fun toggleIme() = onHost { toggle() }
    override suspend fun activateIme(ime: String) = onHost { setCurrentInputMethod(ime) }
    override suspend fun switchIme(ime: String) = onHost { switchInputMethod(ime) }
    override suspend fun enumerateIme(forward: Boolean) = onHost { enumerate(forward) }
    override suspend fun currentIme() = onHost { InputMethodEntries.of(currentInputMethod()) }

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
                // the executor would keep it and the host stay STARTING, a dead keyboard with no word said: end the process instead
                Timber.e(e, "Kotlin host failed to start")
                // read the text pack through first, so a damaged one is marked rather than failing every start
                runCatching { PackStore.verifyAfterFailure(PackKind.TEXT, e) }
                val thread = Thread.currentThread()
                // never null: a thread without its own handler answers with its group
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
                // not before unlock: the writer is not writable then
                writer?.flush()
                core = null
                modules = null
                engine.close()
            }.let { if (it.isNotEmpty()) Timber.w("${it.size} job(s) didn't get a chance to run!") }
        } finally {
            forgetCaches()
            // stopped even when the last step threw, so a later start can run; the throw goes on
            lifecycleRegistry.postEvent(HostLifecycle.Event.ON_STOPPED)
        }
    }

    // a view remade before the next host speaks would show a dead engine's code row and candidates
    private fun forgetCaches() {
        inputMethodEntryCached = noInputMethod
        enabledImeCached = emptyList()
        statusAreaCached = emptyList()
        punctuationCached = null
        fullWidthCached = false
        clientPreeditCached = FormattedText.Empty
        inputPanelCached = HostEvent.InputPanel.EMPTY
        candidatesCached = HostEvent.Candidates.EMPTY
        pagedCached = HostEvent.Paged.EMPTY
    }

    /** A failed [startup] leaves STARTING, which [stop] skips: tear down its thread and clipboard listener so later tests do not see them. */
    @VisibleForTesting
    internal fun tearDownFailedStart() {
        ClipboardManager.removeOnUpdateListener(onClipboardUpdate)
        dispatcher.stop {}
    }

    private fun startup() {
        val started = System.nanoTime()
        var lap = started
        // debug builds: each step's time in logcat (HostStartup)
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
        // an edited table not read leaves the built-in one; the user told
        punctuationMaps = PunctuationMaps(data.dir, onUnreadable = { dataUnreadable() }) { lang ->
            try {
                context.assets.open("$BUILT_IN/punctuation/punc.mb.$lang")
            } catch (_: IOException) {
                null
            }
        }
        // nothing written before the first unlock (the process restarts then) nor where the config is not settled
        val sink = config.dir?.let { dir -> ConfigWriter.files(dir) { path, e -> Timber.w(e, "cannot write %s", path) } }
        writer = ConfigWriter(!directBoot && config.writable && sink != null, configThread, sink ?: { _, _ -> false })
        if (!directBoot && !config.writable) EngineNotices.failedUntilSeen(R.string.config_not_settled)
        if (!directBoot && !data.writable) dataUnreadable()
        step("punctuation maps, writer")
        val strings = AppHostStrings(context)
        val available = InputMethodCatalog.LISTED.map { it.name }.toSet()
        val loaded = Profile.load(readConfig(PROFILE).getOrNull(), available + HandwritingRetire.OLD, strings.defaultGroup)
        val retired = retireHandwriting(loaded, directBoot)
        this.directBoot = directBoot
        loadOptions(readConfig(EngineOptions.PATH).getOrNull(), directBoot)
        for (path in CONF_FILES) confTexts[path] = readConfig(path).getOrNull()
        retireTraditionalHandwriting(retired, directBoot)
        if (!directBoot) tellUnseen()
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
            engine.host, dispatcher, retired.profile, available, strings, firstRun = wasFirstRun,
            autoLevel = ThisDevice.profile.autoLevel, showInputMethodInformation = showInputMethodInformation, persist = ::persist, modules = chinese::build,
        )
        made.events += HostEvents(::forward)
        made.configure(options)
        core = made.also(::keepEnabledIme)
        // HostCore's constructor builds the modules: 简繁's list exists only now
        publishTraditional()
        step("HostCore")
        if (wasFirstRun) firstRun = false
        Timber.i("Kotlin host ready in %d ms", (System.nanoTime() - started) / NANOS_PER_MILLI)
        lifecycleRegistry.postEvent(HostLifecycle.Event.ON_READY)
        // before any context is focused: the keyboard draws the marks from Ready on
        punctuationCached = made.punctuationKeys()
        made.keepStartKeys()
        emit(HostEvent.Ready)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lifecycle.launchWhenReady { SubtypeManager.syncWith(enabledIme()) }
        }
    }

    // fcitx's reloadConfig, as one event: the status sent once at the end
    private fun HostCore.readModuleFiles() = batch { readModuleFilesNow() }

    private fun HostCore.readModuleFilesNow() {
        // one that cannot be read now keeps what was read before
        for (path in CONF_FILES) readConfig(path).onSuccess { confTexts[path] = it }
        modules?.punctuation?.let {
            it.setProfile(punctuationProfile())
            // read, not changed: not written back
            it.setConfigQuietly(ModuleConfigs.punctuation(confTexts[ModuleConfigs.PUNCTUATION]))
            punctuationCached = punctuationKeys()
            keepStartKeys()
        }
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

    /** Null if absent (fcitx writes only what is set). One there but unreadable is a failure, never its defaults: held for this run and the user told. */
    private fun readConfig(path: String): Result<String?> {
        val home = configHome ?: return Result.success(null)
        val file = File(home, path)
        return try {
            if (!file.exists()) {
                if (!statFailed(file, home)) return Result.success(null).also { writer?.release(path) }
                // a first write renamed in place between the two looks (a reload racing a change)
                if (!file.exists()) throw IOException("$path is listed but cannot be read")
            }
            Result.success(file.readText().also { writer?.release(path) })
        } catch (e: IOException) {
            Timber.e(e, "cannot read %s: kept as it is, not written this run", path)
            writer?.hold(path)
            configUnreadable()
            Result.failure(e)
        }
    }

    // the external storage's FUSE can fail a stat as "no file", which would write defaults over it: trust a listing instead
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

    // once a process, as the theme list and the engine's phrases say it
    private fun dataUnreadable() = EngineNotices.failedOnce(R.string.user_data_unreadable)

    // once a run, till seen on the keyboard
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

    // 0.4 s on a debug build, so off the host thread; a lookup before they are read waits for them
    private fun quickPhrasesLater(): () -> QuickPhraseBook {
        val table = FutureTask { quickPhrases() }
        Thread(table, "host-quickphrase").start()
        return table::get
    }

    // the user's unreadable: the built-in ones still, the user told
    private fun quickPhrases(): QuickPhraseBook {
        val builtIn = AssetPkgDataDir(context.assets, BUILT_IN)
        val user = dataHome?.let(::FilePkgDataDir)
        if (user != null) {
            try {
                return QuickPhraseBook.load(user, builtIn)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // any slip, not only IOException: never a key's lookup failing in the FutureTask
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

    private val quickPhraseHooks = object : QuickPhraseHooks {
        // read at every key: parsed again only when the setting's text changed
        private var nineKeysRead: Pair<String, NineKeyLetters>? = null

        override val nineKeys: NineKeyLetters
            get() {
                val raw = AppPrefs.getInstance().keyboard.t9Letters.getValue()
                nineKeysRead?.let { (seen, letters) -> if (seen == raw) return letters }
                return NineKeyLetters.parse(raw).also { nineKeysRead = raw to it }
            }

        override val withMine: Boolean get() = AppPrefs.getInstance().internal.useHabits.getValue()

        override fun used(entry: QuickPhraseEntry, at: Long) {
            // on the writer's thread: a key is never held on a file
            configThread.execute {
                try {
                    QuickPhraseManager.markUsed(entry, at)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // a bad file costs the use counts, never the process
                    Timber.w(e, "quick phrase use")
                }
            }
        }

        override fun add() {
            ContextCompat.getMainExecutor(context).execute { AppUtil.launchQuickPhraseEditor(context) }
        }
    }

    // OpenCC s2tw, mapped in place in the APK, at the first traditional text
    private fun converter(): ChineseConverter? = try {
        ChineseConverter.s2tw(OpenCCData.read(bridge.asset(OPENCC)))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "no OpenCC data: text stays simplified")
        null
    }

    // 4.5 MB, read on the host thread at Unicode's first trigger, as fcitx does
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
     * Drops the retired handwriting input method from the profile at every start (an upgrade or an old backup); written
     * only once unlocked. The user is told each time ([tellUnseen]); the toolbar's 手写 is pinned only the first time
     * ([AppPrefs.Internal.handwritingRetired]), so later changes by the user are not undone.
     */
    private fun retireHandwriting(loaded: Profile, directBoot: Boolean): Profile.Retired {
        val retired = loaded.retireHandwriting()
        if (!retired.changed || directBoot) return retired.copy(changed = false)
        val prefs = AppPrefs.getInstance()
        if (!prefs.internal.handwritingRetired.getValue()) {
            prefs.pinHandwriting()
            if (retired.moved) prefs.internal.handwritingOpen.setValue(true)
            prefs.internal.handwritingRetired.setValue(true)
        }
        prefs.internal.handwritingRetiredUnseen.setValue(if (retired.moved) "$RETIRED_TOOLBAR,$RETIRED_MOVED" else RETIRED_TOOLBAR)
        persist(Persist.ProfileChanged(retired.profile))
        return retired
    }

    /** chttrans' list without the retired handwriting: its place goes to pinyin if pinyin took its first place. */
    private fun retireTraditionalHandwriting(retired: Profile.Retired, directBoot: Boolean) {
        if (directBoot) return
        val old = confTexts[ModuleConfigs.CHTTRANS]
        val out = HandwritingRetire.traditional(ModuleConfigs.chttrans(old), retired.moved) ?: return
        val text = ModuleConfigs.chttransText(old, out.enabled)
        confTexts[ModuleConfigs.CHTTRANS] = text
        writer?.write(ModuleConfigs.CHTTRANS) { text }
        val internal = AppPrefs.getInstance().internal
        if (out.lost && retired.changed) internal.handwritingRetiredUnseen.setValue(internal.handwritingRetiredUnseen.getValue() + ",$RETIRED_TRADITIONAL")
    }

    /**
     * Pins installs from before 双拼's scheme migration with none stored to 自然码 and moves a dropped scheme to 小鹤,
     * telling once. Settled only once what the file says reads so again, so a pin not written is retried at the next
     * start. [directBoot] reads the defaults and settles nothing.
     */
    private fun loadOptions(read: String?, directBoot: Boolean) {
        val internal = AppPrefs.getInstance().internal
        // direct boot's prefs are a copy and its config none of the user's
        options.load(read.orEmpty(), upgrade = !directBoot && !firstRun && !internal.schemeSettled.getValue())
        if (directBoot) return
        val settle: () -> Unit = { internal.schemeSettled.setValue(true) }
        val migrated = options.schemeMigrated
        val retired = migrated?.retired
        when {
            migrated == null -> settle()
            // read again it is dropped again: settled whether or not 小鹤 is written
            retired != null -> {
                if (!internal.schemeRetiredTold.getValue()) internal.schemeRetiredUnseen.setValue(retired)
                settle()
                val written = options.text()
                writer?.write(EngineOptions.PATH, null) { written }
            }
            // the pin reads back only once on disk: settled then, not before
            else -> {
                val written = options.text()
                writer?.write(EngineOptions.PATH, settle) { written }
            }
        }
    }

    /** At each start, so a notice whose process ended before any keyboard showed is told at the next. */
    private fun tellUnseen() {
        val internal = AppPrefs.getInstance().internal
        val handwriting = internal.handwritingRetiredUnseen.getValue().split(',').filterTo(HashSet()) { it.isNotEmpty() }
        if (handwriting.isNotEmpty()) {
            val more = buildString {
                if (RETIRED_MOVED in handwriting) append(context.getString(R.string.handwriting_retired_moved))
                if (RETIRED_TRADITIONAL in handwriting) append(context.getString(R.string.handwriting_retired_traditional))
            }
            EngineNotices.toldUntilSeen(R.string.handwriting_retired, more) { internal.handwritingRetiredUnseen.setValue("") }
        }
        if (internal.keyHandwritingUnseen.getValue()) {
            EngineNotices.toldUntilSeen(R.string.key_handwriting_retired) { internal.keyHandwritingUnseen.setValue(false) }
        }
        ThemeLayoutsToList.tellUnseen()
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

    private fun keepEnabledIme(core: HostCore) {
        enabledImeCached = core.contexts.group?.inputMethods.orEmpty().map(InputMethodEntries::of)
    }

    private fun persist(change: Persist) {
        // the keyboard reads 简繁's list from here (EngineState.traditionalIms)
        if (change is Persist.ConfChanged && change.conf == ModuleConfigs.CHTTRANS) publishTraditional()
        // every change to the enabled list is the profile's
        if (change is Persist.ProfileChanged) core?.let(::keepEnabledIme)
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

    private fun forward(event: HostEvent) {
        if (event is HostEvent.Tip) {
            EngineNotices.tip(event.text)
            return
        }
        emit(event)
    }

    private fun emit(event: HostEvent) {
        // the type only: the contents are what the user types, password fields included
        if (BuildConfig.DEBUG) Timber.d("Handling %s", event.javaClass.simpleName)
        when (event) {
            is HostEvent.InputMethodChanged -> {
                // never what is typed
                Timber.d("input method now %s", event.im)
                inputMethodEntryCached = event.entry
            }
            is HostEvent.StatusArea -> {
                statusAreaCached = event.entries
                event.punctuation?.let { punctuationCached = it }
                fullWidthCached = event.fullWidth
                core?.keepStartKeys()
                // a sub mode changed, which no InputMethodChanged tells: told first, as Fcitx's handler does
                if (event.entry != inputMethodEntryCached) {
                    inputMethodEntryCached = event.entry
                    eventsOut.tryEmit(HostEvent.InputMethodChanged(event.entry))
                }
            }
            is HostEvent.ClientPreedit -> clientPreeditCached = event.text
            is HostEvent.InputPanel -> inputPanelCached = event
            // one or the other by paging mode: the one not sent last is no longer the engine's
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

    internal companion object {
        private const val PROFILE = "profile"

        // Internal.handwritingRetiredUnseen holds comma-separated markers
        private const val RETIRED_TOOLBAR = "toolbar"
        private const val RETIRED_MOVED = "moved"
        private const val RETIRED_TRADITIONAL = "traditional"

        private val CONF_FILES = listOf(ModuleConfigs.PUNCTUATION, ModuleConfigs.CHTTRANS)
        const val ZH_CN = "zh_CN"
        private const val BUILT_IN = "pkgdata"
        private const val OPENCC = "engine/opencc-s2tw.data"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val IDLE_SECONDS = 30L
    }
}

// yields between texts, so a key typed meanwhile goes first and a caller that gave up stops the rest
internal suspend fun aTextAtATime(texts: List<String>, of: (List<String>) -> Map<String, IntArray>): Map<String, IntArray> =
    buildMap {
        for (text in texts) {
            yield()
            putAll(of(listOf(text)))
        }
    }
