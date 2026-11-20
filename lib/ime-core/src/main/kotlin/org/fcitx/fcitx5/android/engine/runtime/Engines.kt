/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS_OWN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ZHUYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ZHUYIN_NINE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.glides
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkOver
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.learn.backup.AddressData
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.phrase.CommonWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.platform.Blobs
import org.fcitx.fcitx5.android.engine.platform.Clock
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmThreads
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.reading.CardReading
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.input.voice.VoiceEdits
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * The input methods the androidengine addon lists, each session made on first use; user data under [userDir]
 * (memory only if null). A facade that makes and wires [EngineAssets], [UserLexicon], [EnglishLexicon],
 * [TableRegistry], [LearningFacade], [LexiconFacade], [SessionFactory] and [FieldContext]; [invalidate] remakes
 * every session. Everything runs on the fcitx thread except what goes to [warmUps].
 */
class Engines(
    private val blobs: Blobs,
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit = {},
    private val additions: () -> Additions? = { null },
    private val kernel: MatrixKernel? = null,
    warmUps: Executor = JvmThreads.each("engine-warm-up"),
    /** Emoji the phone cannot draw are no candidates. */
    private val emojiShows: (String) -> Boolean = { true },
    /** Rare characters the phone cannot draw are no candidates. */
    private val textShows: (String) -> Boolean = { true },
    /** Traditional to simplified as learning reads it ([Simplifier.read]): asked once, at the first pinyin session. */
    private val simplifier: () -> Simplifier = { Simplifier.NONE },
    /** Where this install's id lives, outside backups ([Backups.installId]); none means a new install each time. */
    installFile: LogFile? = null,
    /** Named in backups. */
    appVersion: String = "",
    /** libime's own learning files, an older build's: deleted with what the clears clear. */
    private val legacyFiles: List<LogFile> = emptyList(),
    /** Learning's time and zone and tables' monotonic ms: a replay's in tests, so the same keys learn the same as on the phone. */
    private val clock: Clock = JvmClock,
    /** Runs log compactions and backup writes so keys do not wait on disk; tests run them in place. */
    background: Executor = JvmThreads.each("record-compaction"),
    /** What the field's last context said learning leaves out: the host's, so engines remade in the same field keep it. */
    private val fieldFlags: FieldFlags = FieldFlags(),
    /** The address bar's learning, carried by backups as is ([AddressData]). */
    addresses: () -> AddressData? = { null },
    /** A backup's address book merged into the app's (true) or replacing it (false, 退回恢复前). */
    putAddresses: (AddressData, merge: Boolean) -> Unit = { _, _ -> },
    /** Told of calls off the creating thread: everything here is the fcitx thread's and unlocked. Tests fail on one by default ([HostThread.ofTests]). */
    private val offHostThread: ((String) -> Unit)? = HostThread.ofTests(),
    /** Told once per learning record opened when learning first fails to reach disk, so the user can be told it lives only in memory ([LearnLog]). */
    unkept: () -> Unit = {},
    /** Test seam for the record's appends: a test's refuses a write as a full disk would (EnginesRecordFaultTest). */
    openAppend: (LogFile) -> OutputStream = LogFile::append,
) : Closeable {

    private val hostThread = HostThread(offHostThread)

    // every public entry is checked: the app calls each on the fcitx thread alone
    private inline fun <T> checked(block: () -> T): T {
        hostThread.check()
        return block()
    }

    /**
     * What the user added to pinyin as libime kept it: custom phrases and enabled dictionaries' lines (`你好 ni'hao 0`),
     * read only when the user's words are made. [dictionaries] identifies them so [reload] rereads only on change;
     * keyboard edits to phrases go to [savePhrases].
     */
    class Additions(
        val phrases: String,
        val dictionaries: String,
        val savePhrases: (CustomPhrases) -> Unit = {},
        /**
         * Enabled dictionaries merged with the base, compiled and opened (null for none): asked once per additions,
         * mapped, never read through (a 1.9-million-word one exhausted the heap when read).
         * @throws java.io.IOException if it cannot be opened: typing goes on without it, told once
         */
        val dictionary: () -> DictionaryFile?,
        /** Where tables keep their index of [dictionary]'s words (CodedIndex); null for none kept. */
        val indexes: UserFiles? = null,
        /** Compiled against another base (recompile pending): its words the base now has are left to the base till then. */
        val dictionaryStale: Boolean = false,
        /** Some enabled dictionaries are not in [dictionary] yet, so a removed word is not known to be none of theirs and forgets no learning. */
        val dictionaryPending: () -> Boolean = { false },
        /** The common words as read ([CommonWords.format]); keyboard deletions go out as [savePhrases]. */
        val commonWords: String = "",
        val saveCommonWords: (CommonWords) -> Unit = {},
        /** 生僻字屏蔽 as read ([org.fcitx.fcitx5.android.engine.user.RareChoice.format]); empty for off. */
        val rareChars: String = "",
    )

    // internal: a test sees what the warm-ups built (EnginesWarmUpTest)
    internal val assets = EngineAssets(blobs, onError, kernel, emojiShows, textShows)

    // counted; by default a fresh thread per compaction, none kept, so logs reopened after close() still have one
    private val compactions = BackgroundWork.counted(background)

    // counted for the debug build's idle signal (BackgroundWork)
    private val warmUps = BackgroundWork.counted(warmUps)

    // its lambdas reach parts made below only when called, never during construction
    private val lexicon: UserLexicon = UserLexicon(
        userDir, onError, additions, assets,
        forgetInTables = { tables.forgetInTables(it) },
        dropSessions = ::invalidate,
        forgotten = { texts -> texts.forEach(learner.habits::forget) },
        attach = { learner.attach(it) },
        blocked = { learner.unpinEverywhere(it) },
        owned = { learner.own(it) },
        imports = { file, words, known -> learner.imports.import(file, words, known) },
    )

    // `this.`: in an initializer the bare name is the constructor's uncounted executor.
    private val tables: TableRegistry = TableRegistry(blobs, onError, this.warmUps, assets, lexicon,
        knows = { learner.habits.knows(it) }, state = { learner.learnLog().state }, dropSaved = { learner.learnLog().dropTableWords(it) },
        indexes = lexicon.added::indexes)

    private val english: EnglishLexicon = EnglishLexicon { learner.learnLog() }

    // the learning record opens at first use, not here
    private val learner: LearningFacade = LearningFacade(
        userDir, onError, clock, compactions, installFile, appVersion, legacyFiles, lexicon, tables, english,
        invalidate = ::invalidate,
        shown = { sessions[pairs.shownSession(it)] },
        first = { keyboards[pairs.shownSession(it)]?.first ?: 0 },
        addresses = addresses,
        putAddresses = putAddresses,
        unkept = unkept,
        openAppend = openAppend,
    )

    private val words: LexiconFacade = LexiconFacade(lexicon, english, assets, tables, learner) { settings }

    // 语音先问再记 follows voice text as the user edits it; on Engines, not a session, since adding a word drops sessions
    private val simplified by lazy(LazyThreadSafetyMode.NONE) { simplifier() }
    private val voiceEdits = VoiceEditsGlue(words, lexicon, assets) { simplified.simplify(it) }

    /** Does [op] in [im], the input method in use; [act] has the host send a long press's event for a candidate index. */
    fun learning(
        im: String?,
        op: LearningOp,
        /** As the host shows it (繁體 via chttrans), which the op names. */
        shown: (String) -> String = { it },
        act: (event: Int, index: Int, text: String) -> Boolean = { event, index, _ -> pressShown(im, event, index) },
    ): LearningResult = checked { learner.apply(im, op, shown, act) }

    // false with no keyboard made, where a pair would make one just to take it
    internal fun pressShown(im: String?, event: Int, index: Int): Boolean = im?.takeIf { keyboards.containsKey(pairs.shownSession(it)) }?.let { pairs[it]?.onEvent(event, index) ?: keyboards[it]?.onEvent(event, index) } != null

    // internal: a test reaches the record its first use opens (EnginesRecordFaultTest)
    internal fun learnLog(): LearnLog = learner.learnLog()

    /** 「用我的输入习惯」: [LearningFacade.useHabits]. */
    var useHabits: Boolean
        get() = checked { learner.useHabits }
        set(value) {
            hostThread.check()
            learner.useHabits = value
            // voice text is not followed while off, nor what came before
            if (!value) voiceEdits.end()
        }

    fun userWords(): List<UserWord> = checked { words.userWords() }

    fun userWords(kind: UserWord.Kind): List<UserWord> = checked { words.userWords(kind) }

    fun userWordCounts(): Map<UserWord.Kind, Int> = checked { words.userWordCounts() }

    /** 「五笔：与 X 同码」. */
    fun sameCodes(texts: Collection<String>, ims: Collection<String>): Map<String, List<Pair<String, String>>> = checked { words.sameCodes(texts, ims) }

    fun pinyinOf(text: String): String? = checked { words.pinyinOf(text) }

    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = checked { words.pinyinsOf(texts) }

    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = checked { words.wordBoundaries(texts) }

    fun logProbs(texts: List<String>): FloatArray = checked { words.logProbs(texts) }

    fun voiceHomophones(words: List<UserWord>): VoiceHomophones = checked { this.words.voiceHomophones(words) }

    /** Tests' gate in place of the user's 生僻字屏蔽 ([UserLexicon.gateOverride]); read at the next [reload]. */
    internal var rareGate: CharGate?
        get() = lexicon.gateOverride
        set(value) {
            lexicon.gateOverride = value
        }

    /** For the tests. */
    internal val rareHeldMade: Int get() = lexicon.heldMade

    /** For the tests. */
    internal val rareMergedWalks: Int get() = words.mergedWalks

    fun rareHeld(texts: Collection<String>): Set<String> = checked { words.rareHeld(texts) }

    fun rareGrid(): RareGrid? = checked { words.rareGrid() }

    /** [VoiceHomophones.pick] on [text] after [before], the stretches the dictionary allows: one call for both. */
    fun voicePick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>): String =
        checked { words.voicePick(homophones, text, before, blocked) }

    fun charReading(c: String): String = checked { words.charReading(c) }

    /** Adds [text] read as [pinyin] ([WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = checked { words.addWord(text, pinyin) }

    /** False if [text] does not read as [pinyin]. */
    fun blockWord(text: String, pinyin: String): Boolean = checked { words.blockWord(text, pinyin) }

    /** An added word becomes untypeable, a learned one forgotten, a blocked one offered again. */
    fun removeWord(word: UserWord) = removeWords(listOf(word))

    /** Writes the list once. */
    fun removeWords(words: Collection<UserWord>) = checked { this.words.removeWords(words) }

    fun englishWords(): List<UserWord> = checked { words.englishWords() }

    fun addEnglishWord(text: String): Boolean = checked { words.addEnglishWord(text) }

    fun forgetEnglishWords(texts: Collection<String>) = checked { words.forgetEnglishWords(texts) }

    fun restoreEnglishCorrection(texts: Collection<String>) = checked { words.restoreEnglishCorrection(texts) }

    /** Counts of words added, blocked and learned that were new, unreadable lines, and words imported. */
    data class Imported(val added: Int, val blocked: Int, val unread: Int, val learned: Int = 0, val imported: Int = 0)

    /** [file] names the list. */
    fun importWords(lines: Sequence<String>, file: String = ""): Imported = checked { words.importWords(lines, file) }

    /** Oldest first. */
    fun importBatches(): List<ImportLayer.Batch> = checked { learner.imports.batches() }

    /** Words only imported, of [batch] if given (来自导入). */
    fun importedWords(batch: Int? = null): List<ImportLayer.Word> = checked { learner.imports.words(batch) }

    /** The user's own words stay; how many went, null for no such batch. */
    fun dropImport(batch: Int): Int? = checked { learner.imports.drop(batch) }

    /** 撤销 of [dropImport]: false if the record was reread since. */
    fun restoreImport(batch: Int): Boolean = checked { learner.imports.restore(batch) }

    fun exportWords(): List<String> = checked { words.exportWords() }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    // the latest handwriting piece ended by a dropped session, so the replacement does not let a late reading in
    private var inkEndedBefore = HandwritingSession.NO_PIECE

    // handwriting sessions written over keys ([InkOver])
    private fun handwritingOf(s: Session): HandwritingSession? = (s as? InkOver)?.handwritingMade

    // no list made: the pad asks at every stroke ([inkEnded])
    private inline fun inkMost(of: (HandwritingSession) -> Int): Int =
        sessions.values.fold(inkEndedBefore) { m, s -> handwritingOf(s)?.let { maxOf(m, of(it)) } ?: m }

    // a new session starts with it ended, so the pad numbers pieces on across sessions
    private fun latestPiece(): Int = inkMost { it.latestPiece }

    // beneath what [SessionFactory.make] wraps it in (InkOver, StrokeFilter), in either order
    private tailrec fun innermost(s: Session): Session = when (s) {
        is InkOver -> innermost(s.session)
        is StrokeFilter -> innermost(s.session)
        else -> s
    }

    /** Remakes every session and what they share at next use, dropping input: for changed user words, model or habits; a setting uses [dropSessions]. */
    internal fun invalidate() {
        dropSessions()
        assets.dropDecoderExtensions()
    }

    internal val extensionsMade: Int get() = assets.extensionsMade

    /** For the tests. */
    internal fun lookAheadPicks(): Set<String> = assets.extensionsPicked().mapTo(HashSet()) { assets.pinyinData.vocabulary.word(it) }

    // as [invalidate] but keeping the look-ahead while nothing was learned since ([EngineAssets.dropDecoderExtensionsIfLearned])
    private fun dropSessions() {
        inkEndedBefore = latestPiece()
        keyboards.clear()
        pairs.values.forEach(KeyboardPair::drop)
        sessions.clear()
        factory.invalidate()
    }

    /**
     * Changing it remakes the sessions, dropping input, except: 候选词注音 alone keeps input and has the host
     * re-read what is shown ([Keyboard.RECALL]); a layout's own groups alone remake only the session typing on
     * them, told the field's text ([KeyboardPair.dropKeyed]).
     */
    var settings = EngineSettings()
        set(value) {
            hostThread.check()
            if (value == field) return
            val readingOnly = value == field.copy(candidateReading = value.candidateReading)
            val ownKeysOnly = value == field.copy(ownKeys = value.ownKeys)
            val hadOwnKeys = ownKeys
            field = value
            // the layout's own keyboard now reads its codes; none told after one was: no keyboard reads them any more
            ownKeys = value.ownKeyboard?.also(NineKeyLetters::inUse) != null
            if (hadOwnKeys && !ownKeys) NineKeyLetters.clearInUse()
            if (readingOnly) return
            // told at the field's first key: other sessions and the decoders' shared state stay; the new one gets the text before the cursor
            if (ownKeysOnly) return dropOwnKeys()
            dropSessions()
            assets.dropDecoderExtensionsIfLearned()
            // what a lower level no longer runs leaves memory, read again if the level goes up
            if (value.modelLevel < ModelLevel.MEDIUM) assets.sentenceModel.drop()
            if (value.modelLevel < ModelLevel.HIGH) assets.refiningModel.drop()
        }

    // the session typing on a layout's own groups alone, remade at its next code on the groups told now
    private fun dropOwnKeys() {
        // its handwriting pieces end with it, as in [dropSessions]
        inkEndedBefore = latestPiece()
        pairs.getValue(PINYIN).dropKeyed(PINYIN_KEYS_OWN)
        keyboards.remove(PINYIN_KEYS_OWN)
        sessions.remove(PINYIN_KEYS_OWN)
    }

    // whether [settings] name a layout's own keyboard, which reads its codes
    private var ownKeys = false

    private fun isOwnKeysCode(c: Int) = OwnKeysTurn.isCode(c)

    private val latin = LatinSync { w -> assets.englishData?.let { it.id(w) != NO_WORD } == true }

    // off with the habits: nothing English learned is spelled and no user model is made. Reads learner's, not the
    // checked [useHabits], which would be reported again for the one call
    private fun syncLatin() = latin.sync(
        settings.latinWords && learner.useHabits, if (learner.useHabits) english.user() else EnglishUser.OFF,
        { if (learner.useHabits) learner.learnLog().state else null }, lexicon,
    )

    // internal: a test counts what the sessions share (EnginesSessionFactoryTest)
    internal val factory: SessionFactory = SessionFactory(
        assets, lexicon, tables, learner, english, { settings }, latin.words,
        englishData = { field.warmUpEnglish() }, inkEndedBefore = ::latestPiece,
        uptimeMillis = clock::uptimeMillis, learnClock = clock, simplifier = simplifier,
    )

    /** Nothing picked is learned unless [learning]; [payload] as [Keyboard.onEvent]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true, payload: Action? = null): Snapshot {
        hostThread.check()
        if (im == PINYIN) syncLatin()
        // a layout's own-keyboard code with none told: swallowed, nothing changed, never handed back to be typed
        // (the app tells its keyboard first: EngineBridge)
        if (!ownKeys && event == EngineEvent.CHAR && isOwnKeysCode(arg)) {
            // a refine or pick of the previous snapshot is not this key's
            val shown = pairs[im]?.showing ?: keyboard(im).showing
            return annotated(im, shown.copy(commit = "", deleteBefore = 0, handled = true, refines = false, picked = false))
        }
        // 联想 on show: picking it is no typed word (VoiceEdits)
        val predicting = (pairs[im]?.showing ?: keyboards[im]?.showing)?.predicting == true
        // looked for only while voice text is followed: nothing else reads it
        val following = voiceEdits.edits.open
        val shownBefore = if (following) pairs.shownSession(im) else null
        val recordBefore = shownBefore?.let { sessions[it]?.lastCommit }
        // handwriting shown before: what this commits is handwriting's, not the input method's
        val inkingBefore = shownBefore != null && (sessions[shownBefore] as? InkOver)?.inking == true
        val s = pairs[im]?.onEvent(event, arg, learning, payload)
            ?: keyboard(im).onEvent(if (event == EngineEvent.HARD_CHAR) EngineEvent.CHAR else event, arg, learning, payload)
        // the session that typed it, shown before or after the event; one shown only after may have a stale record,
        // which readingOf takes only where it spells what went in
        val reading = if (s.commit.isEmpty() || !following) null else
            VoiceEditsGlue.readingOf(s.commit, listOf(shownBefore, pairs.shownSession(im)).firstNotNullOfOrNull { id -> sessions[id]?.lastCommit?.takeIf { it !== recordBefore } })
        voiceEdits.answered(im, event, s, predicting, learning, reading, inkingBefore)
        return annotated(im, s)
    }

    /** Voice's [text] of one talk with [before] the cursor (null: unread), followed as the user edits it when habits are used. Called only where the app's switch is on and the field learns; [hinted]: the one-time hint was already said. */
    fun voiceSaid(text: String, before: String?, hinted: Boolean) = checked {
        voiceEdits.edits.hinted = hinted
        if (learner.useHabits) voiceEdits.edits.said(text, before) else voiceEdits.end()
    }

    /** A talk's stretches as voice kept them, for 记住 to be judged on the stretch voice judged. */
    fun voiceStretches(texts: List<String>) = checked { voiceEdits.stretches(texts) }

    /** [VoiceEdits.putIn]. */
    fun voicePutIn(text: String): VoiceEdits.Reply = checked { voiceEdits.edits.putIn(text) }

    /** [VoiceEdits.confirm]. */
    fun voiceConfirm(before: String?, after: String?): VoiceEdits.Ask? = checked { voiceEdits.edits.confirm(before, after) }

    /** Stops following voice text: another field, input finished. */
    fun voiceEditsEnd() = checked { voiceEdits.end() }

    /** For the tests. */
    internal val voiceEditsHoldWords: Boolean get() = voiceEdits.holdsWords

    /** 「记住」 tapped: [ask]'s word added; false if it does not read so. */
    fun voiceRemember(ask: VoiceEdits.Ask): Boolean = checked { voiceEdits.remember(ask) }

    /** 撤销 of [voiceRemember]: unadded, nothing forgotten (UserLexicon.unAdd). */
    fun voiceForget(ask: VoiceEdits.Ask) = checked { voiceEdits.forget(ask) }

    // the factory's, whose pinyin blocks a polyphone by its parts in the reading shown
    private val candidateReadings get() = factory.candidateReadings

    // the session [im]'s candidates come from, if 候选词注音 reads them (not English)
    private fun annotatedSession(im: String): Session? =
        if (settings.candidateReading == ReadingStyle.OFF) null else sessions[pairs.shownSession(im)]?.takeIf { it !is EnglishSession }

    /** [s] with each candidate's reading and hint from [readingAndHint]; unchanged if none to show. */
    private fun annotated(im: String, s: Snapshot): Snapshot {
        val off = settings.candidateReading == ReadingStyle.OFF
        if (off && (im !in pairs || s.hints.none { it.isNotEmpty() })) return s
        val session = if (off) null else annotatedSession(im) ?: return s
        if (s.candidates.isEmpty()) return s
        val both = s.candidates.mapIndexed { i, text -> readingAndHint(im, session, s.first + i, text, s.hints.getOrElse(i) { "" }) }
        val hints = s.hints.mapIndexed { i, h -> both.getOrNull(i)?.second ?: h }
        // on, with every hint drawn above: no hints at all
        return s.copy(readings = both.map { it.first }, hints = if (!off && hints.all { it.isEmpty() }) emptyList() else hints)
    }

    /**
     * A candidate's reading drawn above and the hint left beside, for both snapshot and [candidates]. A pinyin
     * hint that is a reading (角色 typed jiaose: jué sè; parts or stroke lookups; one of a polyphone's) is drawn
     * above whether 候选词注音 is on or off and leaves no hint. While it is on ([session] not null) others read as typed.
     */
    private fun readingAndHint(im: String, session: Session?, index: Int, text: String, hint: String): Pair<List<String>, String> {
        val style = settings.candidateReading
        val above = above(im, index, text, hint, if (style == ReadingStyle.OFF) ReadingStyle.PINYIN else style)
        if (above.isNotEmpty()) return above to ""
        return (if (session == null) emptyList() else read(session, index, text, style)) to hint
    }

    /** [readingAndHint]'s reading with 候选词注音 on in [style], so a held candidate's card and the bar never disagree. */
    private fun readingsOf(im: String, session: Session, index: Int, text: String, hint: String, style: ReadingStyle): List<String> =
        above(im, index, text, hint, style).ifEmpty { read(session, index, text, style) }

    // a pinyin hint that is a reading, drawn above the candidate (readingAndHint)
    private fun above(im: String, index: Int, text: String, hint: String, style: ReadingStyle): List<String> =
        if (im in pairs) hintAbove(im, index, text, hint, style) else emptyList()

    // handwriting's last character gets all its readings, only while handwriting shows; pinyin's own lookups read as typed
    private fun read(session: Session, index: Int, text: String, style: ReadingStyle): List<String> {
        val before = if ((session as? InkOver)?.inking == true) session.lookedUp(index) else null
        return if (before != null) candidateReadings.allOf(text, style, before) else candidateReadings.of(text, style, session.readingOf(index))
    }

    /**
     * What the menu of [im]'s [index]th candidate heads with ([CandidateCard]); [text] as the app shows it ([shown]
     * converts, e.g. 繁體). Null if gone or without Han. Looked-up characters get their card; others their reading
     * via [readingsOf]; past [WordCard.MAX_CHARS] the line alone. Usually under 1 ms; a 200-character phrase 2–4 ms.
     */
    fun card(im: String, index: Int, text: String, paged: Boolean = false, shown: (String) -> String = { it }): CandidateCard? = checked {
        val id = pairs.shownSession(im)
        val session = sessions[id] ?: return@checked null
        val at = index + if (paged) keyboards[id]?.first ?: 0 else 0
        val c = session.candidates(at, 1).firstOrNull() ?: return@checked null
        if (c.text != text && shown(c.text) != text) return@checked null
        // the text first: what lookedUp reads is what candidates read up to
        session.lookedUp(at)?.let { before -> CharCard.of(text, before, candidateReadings, assets.pinyinData.charReadings, assets.charInfo, assets::strokes) }
            ?.let { return@checked CandidateCard.Looked(it) }
        val han = WordCard.hanCount(c.text)
        if (han == 0) return@checked null
        // the shown characters if they match the engine's one for one (国 國), else the engine's
        val chars = if (text.codePointCount(0, text.length) == c.text.codePointCount(0, c.text.length)) text else c.text
        val style = lineStyle(id)
        if (han > WordCard.MAX_CHARS) return@checked CandidateCard.Line(text, longLine(im, session, at, c, chars, style))
        val pinyin = readingsOf(im, session, at, c.text, c.hint, ReadingStyle.PINYIN)
        val zhuyin = readingsOf(im, session, at, c.text, c.hint, ReadingStyle.ZHUYIN)
        val word = WordCard.of(chars, pinyin, zhuyin, assets.charInfo)
        val line = word.line(style)
        if (han > 1 || chars.codePointCount(0, chars.length) > 1) return@checked CandidateCard.Word(line, word)
        val row = word.rows.single()
        val card = charCardOf(chars, row.reading) ?: return@checked null
        CandidateCard.Char(candidateReadings.lineOf(c.text, if (style == ReadingStyle.ZHUYIN) row.zhuyin else row.pinyin, style), card)
    }

    /** A long candidate's line read as the bar reads it; past [WordCard.LINE_CHARS] Han characters only the first whole words, then …, since reading is what costs. */
    private fun longLine(im: String, session: Session, at: Int, c: Choice, chars: String, style: ReadingStyle): String {
        if (WordCard.hanCount(c.text) <= WordCard.LINE_CHARS) return WordCard.line(chars, readingsOf(im, session, at, c.text, c.hint, style))
        // the session's words wholly within the cut; none known, read by text
        val words = session.readingOf(at)?.takeIf { w -> w.joinToString("") { it.text } == c.text }
        val kept = words?.let { w ->
            var han = 0
            w.takeWhile { han += WordCard.hanCount(it.text); han <= WordCard.LINE_CHARS }.takeIf { it.isNotEmpty() }
        }
        val cut = kept?.joinToString("") { it.text } ?: WordCard.firstHan(c.text, WordCard.LINE_CHARS)
        val count = cut.codePointCount(0, cut.length)
        val shown = chars.substring(0, chars.offsetByCodePoints(0, count))
        // a hint's readings are one per code point of an all-Han text: take the cut part's
        val readings = above(im, at, c.text, c.hint, style).take(count).ifEmpty { candidateReadings.of(cut, style, kept) }
        return WordCard.line(shown, readings) + " " + CandidateReadings.MORE
    }

    /** A word card's row tapped: from the data alone, not the session. */
    fun charCardOf(char: String, reading: CardReading?): CharCard? = checked {
        CharCard.of(char, "", candidateReadings, assets.pinyinData.charReadings, assets.charInfo, assets::strokes, first = reading)
    }

    // the compact line's style: 候选词注音's; when off, zhuyin on zhuyin keyboards, else pinyin
    private fun lineStyle(session: String): ReadingStyle = when (settings.candidateReading) {
        ReadingStyle.OFF -> if (session == ZHUYIN || session == ZHUYIN_NINE) ReadingStyle.ZHUYIN else ReadingStyle.PINYIN
        else -> settings.candidateReading
    }

    // a polyphone hint is drawn as the reading it has after what was put before it (Session.lookedUp)
    private fun hintAbove(im: String, index: Int, text: String, hint: String, style: ReadingStyle): List<String> {
        if (hint.isEmpty()) return emptyList()
        val before = if (CandidateReadings.SEPARATOR in hint) sessions[pairs.shownSession(im)]?.lookedUp(index).orEmpty() else ""
        return candidateReadings.ofHint(text, hint, style, before)
    }

    private fun keyboard(im: String): Keyboard = keyboards[im] ?: Keyboard(session(im), nineKeys = im in KeyboardPair.KEYED).also {
        keyboards[im] = it
        // made after the field's context: its flags apply, though its text is unknown
        if (fieldFlags.value != 0) it.fieldFlags(fieldFlags.value)
    }

    // pinyin's and 双拼's grouped keys' codes go to a session of their own, made at first use; sessionOf is the
    // session whose candidates show now
    private val pairs: Map<String, KeyboardPair> = mapOf(PINYIN to KeyboardPair.pinyin(::keyboard), SHUANGPIN to KeyboardPair.shuangpin(::keyboard) { settings.scheme })

    private val field: FieldContext = FieldContext(assets, this.warmUps, { settings }, learner, english, tables, keyboards, sessions, { pairs }, fieldFlags)

    /**
     * [before] is the text before the cursor ([Keyboard.context]); [learning] off in a sensitive field; [im] is the
     * field's starting input method. True where English now offers a word in place of the one before the cursor
     * (a correction taken back), for the host to show via [Keyboard.RECALL]. [learnFlags] are kept for engines
     * remade in the field ([FieldFlags]).
     */
    fun context(
        im: String,
        before: String,
        learning: Boolean = true,
        cleared: Boolean = false,
        learnFlags: Int = 0,
        started: Boolean = false,
        after: String? = null,
        selected: Boolean = false,
        said: Boolean = false,
    ): Boolean {
        hostThread.check()
        voiceEdits.context(before, learning, cleared, after, selected, said)
        return field.context(im, before, learning, cleared, learnFlags, started, after, selected, said)
    }

    /**
     * Non-letter keys table [im] reads now ([readsAsKey][org.fcitx.fcitx5.android.engine.InputMethodCatalog.readsAsKey]):
     * 二笔's `;',./` and `[`, 仓颉's `*`, the lookup's `'`. None for other input methods or a table with no session
     * yet, whose table is not read here: the keyboard waits.
     */
    fun tableKeys(im: String): Set<Char> {
        hostThread.check()
        val options = TABLES[im]?.options ?: return emptySet()
        val session = sessions[im] ?: return emptySet()
        // made with its session: read already
        val keys = tables.table(im).dictionary.keys + listOfNotNull(options.matchingKey, options.pinyinKey, SyllableGraph.SEPARATOR) + options.selectionKeys.asIterable()
        return keys.filterTo(HashSet()) { !it.isLetter() && session.reads(it) }
    }

    /** What table [im]'s keyboard shows ([TableKeyboard]); null for other input methods. Code keys only once its session is made, so an unreadable table is not read outside the host's latch. Cached while nothing changes: asked on every key. */
    fun tableKeyboard(im: String): TableKeyboard? {
        hostThread.check()
        val method = TABLES[im] ?: return null
        val session = sessions[im]
        val on = factory.lookUpMode(im).on
        lastTableKeyboard?.takeIf { it.isFor(im, session, settings, on) }?.let { return it.keyboard }
        val options = tableOptions(method, settings)
        val keys = if (session == null) emptySet() else (tables.table(im).dictionary.keys + listOfNotNull(options.matchingKey)).filterTo(HashSet()) { !it.isLetter() }
        return TableKeyboard(im, keys, on, options.keyLabels, options.roots).also { lastTableKeyboard = LastTableKeyboard(im, session, settings, it) }
    }

    private class LastTableKeyboard(val im: String, val session: Session?, val settings: EngineSettings, val keyboard: TableKeyboard) {
        fun isFor(im: String, session: Session?, settings: EngineSettings, on: Boolean) =
            this.im == im && this.session === session && this.settings === settings && keyboard.lookingUp == on
    }

    private var lastTableKeyboard: LastTableKeyboard? = null

    /** Makes [im]'s session if not yet, for a caller that tells its failure apart (the bridge's latches). */
    fun make(im: String) {
        hostThread.check()
        session(pairs.shownSession(im))
    }

    /** Handwriting's session for [ink], shown if a [Keyboard.INK] carried it ([shown]), else held for the next ([HandwritingSession.hold]); [im] is written over ([InkOver]). On the fcitx thread. */
    fun ink(ink: Action.Ink, shown: Boolean, im: String) {
        hostThread.check()
        val s = (session(pairs.shownSession(im)) as? InkOver)?.handwriting() ?: return
        if (!shown) s.hold(ink)
    }

    /** After a [Keyboard.INK] was sent: ink the session never got (a mode filtered the key, focus lost) is held for the next, so its piece is not lost. On the fcitx thread. */
    fun inkSent(ink: Action.Ink, im: String) {
        hostThread.check()
        val s = (sessions[pairs.shownSession(im)] as? InkOver)?.handwritingMade ?: return
        if (!s.took(ink)) s.hold(ink)
    }

    /** [im]'s reading of a glide for the [Keyboard.GLIDE] sent with it ([GlideSession.read]); the nine keys' session for one over them (KeyboardPair.glidedBy). Null if it does not glide. On the fcitx thread. */
    fun glide(im: String, glide: Glide): Action.Glide? {
        hostThread.check()
        if (!glides(im)) return null
        return when (val s = session(pairs[im]?.glidedBy(glide.layout) ?: im).let(::innermost)) {
            is EnglishSession -> Action.Glide(glide)
            is GlideSession -> s.read(glide)
            else -> null
        }
    }

    /** The latest handwriting piece ended (over the keys: [InkOver]), for the pad to wipe; on the fcitx thread. */
    val inkEnded: Int get() = checked { inkMost { it.ended } }

    /** None if [im] has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = checked {
        val choices = sessions[pairs.shownSession(im)]?.candidates(from, count).orEmpty()
        val off = settings.candidateReading == ReadingStyle.OFF
        if (off && im !in pairs) return@checked choices
        val session = if (off) null else annotatedSession(im) ?: return@checked choices
        choices.mapIndexed { i, c ->
            val (readings, hint) = readingAndHint(im, session, from + i, c.text, c.hint)
            if (readings.isEmpty() && hint == c.hint) c else Choice(c.text, hint, readings)
        }
    }

    /** Nothing if [im] has no session yet. */
    fun offers(im: String, index: Int): Set<Offer> = checked { sessions[pairs.shownSession(im)]?.offers(index).orEmpty() }

    private fun session(im: String): Session = sessions.getOrPut(im) { factory.make(im) }

    /** Rereads changed [additions], remaking sessions. If dictionaries changed, user words are reread from the log; without a log (memory only) new words are added and removed ones kept till restart. */
    fun reload() {
        hostThread.check()
        val before = lexicon.added.drop()
        invalidate()
        lexicon.reloadDictionaries(before)
    }

    override fun close() {
        hostThread.check()
        learner.close()
        // in use after all: the record is reread when reopened
        lexicon.closed()
        english.closed()
        tables.closed()
        invalidate()
    }

    init {
        learner.takeStashes()
    }

    companion object {

        /** Marks the old table logs read into the record: the clears and the app's backups go by it. */
        const val TABLE_LOGS_READ = "learn.table-logs-read"
    }
}

/** Failures go to [onError] rather than being thrown. */
internal fun closeQuietly(closeable: Closeable, onError: (IOException) -> Unit) {
    try {
        closeable.close()
    } catch (e: IOException) {
        onError(e)
    }
}

// a pair's keyed session where it took the last key
private fun Map<String, KeyboardPair>.shownSession(im: String): String = this[im]?.sessionOf(im) ?: im
