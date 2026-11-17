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
 * The input methods the androidengine addon lists (InputMethodCatalog), each with its session made
 * on first use. [blobs] gives a data file by its path in the app's assets; what the user picks, in
 * pinyin and in each table, and the English words they commit, are kept under [userDir] (in memory
 * only if null). What the user added to pinyin ([additions]) is read at first need, and again on
 * [reload]. The sentence models' int8 weights are multiplied by [kernel], if given (in C++, say); if
 * not, by Kotlin. What the first keys of pinyin and of English want is read ahead of them on
 * [warmUps], a thread each unless given.
 *
 * A facade over its collaborators, which it makes and wires: [EngineAssets] (the data read from the
 * assets), [UserLexicon] (the user's words), [EnglishLexicon] (the English words the user committed),
 * [TableRegistry] (the table input methods); [LearningFacade] (the learning record, its backups and
 * the user's acts on it), [LexiconFacade] (the words as the settings show them), [SessionFactory]
 * (each input method's session, made as the settings have it) and [FieldContext] (the field typed
 * in). Here are the keyboards over the sessions, the settings, and [invalidate], which has every
 * session made anew.
 *
 * Everything here runs on one thread, the one fcitx runs on, but what is handed to [warmUps].
 */
class Engines(
    private val blobs: Blobs,
    private val userDir: UserFiles?,
    private val onError: (IOException) -> Unit = {},
    private val additions: () -> Additions? = { null },
    private val kernel: MatrixKernel? = null,
    warmUps: Executor = JvmThreads.each("engine-warm-up"),
    /** Whether the phone can draw an emoji: those it cannot are no candidates. */
    private val emojiShows: (String) -> Boolean = { true },
    /** Whether the phone can draw a text: a rare character it cannot is no candidate. */
    private val textShows: (String) -> Boolean = { true },
    /** The app's traditional text to simplified, as learning reads it (OpenCC's tables, [Simplifier.read]): asked once, as the first pinyin session is made. */
    private val simplifier: () -> Simplifier = { Simplifier.NONE },
    /** Where this install's id is kept, a directory backups do not go to ([Backups.installId]); none: another install each time. */
    installFile: LogFile? = null,
    /** The app's version, named in the backups it makes. */
    appVersion: String = "",
    /** libime's own learning files, an older build's: gone with what U-86 and U-88 「今天」 clear (review-E-engine S3). */
    private val legacyFiles: List<LogFile> = emptyList(),
    /**
     * The time and zone learning stamps its records with, its backups, imports and phrases dated
     * by, and the spans the user clears (今天, the last hour) are read against; and the monotonic
     * milliseconds a table reads how soon a space follows a code that committed itself by: the
     * device's, or a replay's, so the same keys learn the same way in a test as on the phone (design §7.2).
     */
    private val clock: Clock = JvmClock,
    /**
     * Where a log's compaction (RecordStore, at most every few thousand records) and a backup's write
     * run, so a key does not wait on the disk: a thread each unless given; a test's runs them in place.
     */
    background: Executor = JvmThreads.each("record-compaction"),
    /**
     * What the field's last context said learning leaves out: the host's, given to each engines it
     * makes, so those made anew in the same field (after a close) have them (review-int N1).
     */
    private val fieldFlags: FieldFlags = FieldFlags(),
    /** What the app's address bar learned, carried by the learning backups as it is ([AddressData]); none: no such. */
    addresses: () -> AddressData? = { null },
    /** A backup's address book merged into the app's (true), or put in its place (false, 退回恢复前). */
    putAddresses: (AddressData, merge: Boolean) -> Unit = { _, _ -> },
    /**
     * Told of a call that comes off the thread that made these engines, if given (the debug build): everything
     * here is the fcitx thread's, unlocked, and nothing but this holds it (review B 3.1). Unless
     * given, this module's tests fail on one ([HostThread.ofTests]); the app gives its own.
     */
    private val offHostThread: ((String) -> Unit)? = HostThread.ofTests(),
    /**
     * Told once, at the first thing learned that did not reach the disk (no [userDir], the learning
     * record not opened, a write of it refused): what is learned from then on may live in memory
     * alone, gone with the process, which the user is to be told ([LearnLog]). Told once by each
     * learning record opened (one more as the host is made anew, the record not opened put in memory):
     * once a process is the app's to keep.
     */
    unkept: () -> Unit = {},
    /**
     * A seam for tests: how the learning record is opened for appends. A test's refuses a
     * write, as a full disk would (EnginesRecordFaultTest, EnginesOlderTableLogTest); the app gives none.
     * A constructor's argument handed down to the record's store, as its `openAppend`, in place of the
     * setters on the store the tests used to reach for (0.6.1 audit engine-12).
     */
    openAppend: (LogFile) -> OutputStream = LogFile::append,
) : Closeable {

    private val hostThread = HostThread(offHostThread)

    // every public entry is checked: the app calls each on the fcitx thread alone
    private inline fun <T> checked(block: () -> T): T {
        hostThread.check()
        return block()
    }

    /**
     * What the user added to pinyin, as libime kept it: its custom phrases file, and the lines
     * of its dictionaries turned on, in libime's text format (`你好 ni'hao 0`), read only when
     * the user's words are made: they can be many, and are kept in the model, not here.
     * [dictionaries] tells the dictionaries apart (their names, sizes and times, say): read again
     * on [reload] only if it changed. The custom phrases the user changes from the keyboard
     * ([PhraseBook]) are handed to [savePhrases], all of them.
     */
    class Additions(
        val phrases: String,
        val dictionaries: String,
        val savePhrases: (CustomPhrases) -> Unit = {},
        /**
         * The dictionaries turned on, merged into the base dictionary, compiled (DictionaryCompiler)
         * and opened, or null for none: asked once per these additions, on the fcitx thread, so the
         * file is mapped, never read through (a 1.9-million-word one ran 0.3.1's heap out, read).
         * @throws java.io.IOException if it cannot be opened: typing goes on without it, told once
         */
        val dictionary: () -> DictionaryFile?,
        /** Where the tables keep their index of [dictionary]'s words (CodedIndex), beside it; null for none kept. */
        val indexes: UserFiles? = null,
        /**
         * Whether [dictionary] was compiled against another base dictionary (an update's, its
         * recompile not done): its words the base has now are left to the base till then (review S7).
         */
        val dictionaryStale: Boolean = false,
        /**
         * Whether the dictionaries turned on are not all in [dictionary] yet (compiled, or one
         * failed): a word they may have is not known to be none of theirs, so removing it
         * forgets no learning, as when [dictionary] does not open (review M4).
         */
        val dictionaryPending: () -> Boolean = { false },
        /** The common words' file as read ([CommonWords.format]), and where the keyboard's deleting one goes, as [savePhrases]. */
        val commonWords: String = "",
        val saveCommonWords: (CommonWords) -> Unit = {},
        /** 生僻字屏蔽's file as read ([org.fcitx.fcitx5.android.engine.user.RareChoice.format]); empty for none, off. */
        val rareChars: String = "",
    )

    private val assets = EngineAssets(blobs, onError, kernel, emojiShows, textShows)

    // [background], counted: by default a thread for each compaction an append makes due, none kept,
    // so the logs opened again after close() still have one
    private val compactions = BackgroundWork.counted(background)

    // counted for the debug build's idle signal (BackgroundWork)
    private val warmUps = BackgroundWork.counted(warmUps)

    // its lambdas reach parts made below as they are called, never as it is made (review B 1.2)
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

    // the learning record, opened at its first use, not here (review B 1.3)
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

    // 语音先问再记: what voice put in the field, followed as the user changes it; on Engines, not a
    // session, as a word added drops the sessions (061-voice-learn-from-edits.md, review R8)
    private val simplified by lazy(LazyThreadSafetyMode.NONE) { simplifier() }
    private val voiceEdits = VoiceEditsGlue(words, lexicon, assets) { simplified.simplify(it) }

    /**
     * Does [op] (PLAN §2.8) in [im], the input method in use; [act] has the host send a long
     * press's event for the candidate at an index, as it always did, so it shows the list left
     * (the addon's; by default the keyboard's here).
     */
    fun learning(
        im: String?,
        op: LearningOp,
        /** A candidate's text as the host shows it (繁體: chttrans converts the list), which the op names. */
        shown: (String) -> String = { it },
        act: (event: Int, index: Int, text: String) -> Boolean = { event, index, _ -> pressShown(im, event, index) },
    ): LearningResult = checked { learner.apply(im, op, shown, act) }

    // the long press's event for the keyboard [im] shows; false with none made, where a pair would
    // make one just to take it
    internal fun pressShown(im: String?, event: Int, index: Int): Boolean = im?.takeIf { keyboards.containsKey(pairs.shownSession(it)) }?.let { pairs[it]?.onEvent(event, index) ?: keyboards[it]?.onEvent(event, index) } != null

    // internal: a test reaches the record opened as its first use opens it (EnginesRecordFaultTest)
    internal fun learnLog(): LearnLog = learner.learnLog()

    /** U-93 「用我的输入习惯」: [LearningFacade.useHabits]. */
    var useHabits: Boolean
        get() = checked { learner.useHabits }
        set(value) {
            hostThread.check()
            learner.useHabits = value
            // voice's text not followed while off, nor what was before (review S4)
            if (!value) voiceEdits.end()
        }

    /** [LexiconFacade.userWords]. */
    fun userWords(): List<UserWord> = checked { words.userWords() }

    /** [LexiconFacade.userWords]: [kind]'s alone, dated. */
    fun userWords(kind: UserWord.Kind): List<UserWord> = checked { words.userWords(kind) }

    /** [LexiconFacade.userWordCounts]. */
    fun userWordCounts(): Map<UserWord.Kind, Int> = checked { words.userWordCounts() }

    /** [LexiconFacade.sameCodes]: 「五笔：与 X 同码」 (D8). */
    fun sameCodes(texts: Collection<String>, ims: Collection<String>): Map<String, List<Pair<String, String>>> = checked { words.sameCodes(texts, ims) }

    /** [LexiconFacade.pinyinOf]. */
    fun pinyinOf(text: String): String? = checked { words.pinyinOf(text) }

    /** [LexiconFacade.pinyinsOf]. */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = checked { words.pinyinsOf(texts) }

    /** [LexiconFacade.wordBoundaries]. */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = checked { words.wordBoundaries(texts) }

    /** [LexiconFacade.logProbs]. */
    fun logProbs(texts: List<String>): FloatArray = checked { words.logProbs(texts) }

    /** [LexiconFacade.voiceHomophones]. */
    fun voiceHomophones(words: List<UserWord>): VoiceHomophones = checked { this.words.voiceHomophones(words) }

    /** The tests' own gate in place of the user's choice of 生僻字屏蔽 ([UserLexicon.gateOverride]); read at the next [reload]. */
    internal var rareGate: CharGate?
        get() = lexicon.gateOverride
        set(value) {
            lexicon.gateOverride = value
        }

    /** How many times the words held back were found ([UserLexicon.heldMade]): for the tests. */
    internal val rareHeldMade: Int get() = lexicon.heldMade

    /** How many times 生僻字屏蔽's grid walked a merged dictionary ([LexiconFacade.mergedWalks]): for the tests. */
    internal val rareMergedWalks: Int get() = words.mergedWalks

    /** [LexiconFacade.rareHeld]. */
    fun rareHeld(texts: Collection<String>): Set<String> = checked { words.rareHeld(texts) }

    /** [LexiconFacade.rareGrid]. */
    fun rareGrid(): RareGrid? = checked { words.rareGrid() }

    /** [LexiconFacade.voiceHomophonesAllowed]. */
    fun voiceHomophonesAllowed(spans: List<VoiceHomophones.Span>): BooleanArray = checked { words.voiceHomophonesAllowed(spans) }

    /** [LexiconFacade.charReading]. */
    fun charReading(c: String): String = checked { words.charReading(c) }

    /** Adds [text] read as [pinyin] (see [WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = checked { words.addWord(text, pinyin) }

    /** Blocks [text] read as [pinyin] from the settings; false if it does not read so. */
    fun blockWord(text: String, pinyin: String): Boolean = checked { words.blockWord(text, pinyin) }

    /** Takes [word] off its list: an added word is no longer typeable, a learned one forgotten, a blocked one offered again. */
    fun removeWord(word: UserWord) = removeWords(listOf(word))

    /** [removeWord] for each of [words], a list written once. */
    fun removeWords(words: Collection<UserWord>) = checked { this.words.removeWords(words) }

    /** [LexiconFacade.englishWords]. */
    fun englishWords(): List<UserWord> = checked { words.englishWords() }

    /** [LexiconFacade.addEnglishWord]. */
    fun addEnglishWord(text: String): Boolean = checked { words.addEnglishWord(text) }

    /** [LexiconFacade.forgetEnglishWords]. */
    fun forgetEnglishWords(texts: Collection<String>) = checked { words.forgetEnglishWords(texts) }

    /** [LexiconFacade.restoreEnglishCorrection]. */
    fun restoreEnglishCorrection(texts: Collection<String>) = checked { words.restoreEnglishCorrection(texts) }

    /**
     * What [importWords] made of the lines: words added, blocked and learned that were not already,
     * lines it could not read, words imported (U-101).
     */
    data class Imported(val added: Int, val blocked: Int, val unread: Int, val learned: Int = 0, val imported: Int = 0)

    /** [LexiconFacade.importWords]: a list the user imports, named [file]. */
    fun importWords(lines: Sequence<String>, file: String = ""): Imported = checked { words.importWords(lines, file) }

    /** The batches of words imported, oldest first (U-102). */
    fun importBatches(): List<ImportLayer.Batch> = checked { learner.imports.batches() }

    /** The words only imported, of [batch] if given (来自导入: U-62, U-80). */
    fun importedWords(batch: Int? = null): List<ImportLayer.Word> = checked { learner.imports.words(batch) }

    /** Undoes the import [batch] (U-102), the user's own words staying: how many went; null for no such batch. */
    fun dropImport(batch: Int): Int? = checked { learner.imports.drop(batch) }

    /** Puts back what [dropImport] took of [batch] (撤销): false if it cannot be, the record read afresh since. */
    fun restoreImport(batch: Int): Boolean = checked { learner.imports.restore(batch) }

    /** [LexiconFacade.exportWords]. */
    fun exportWords(): List<String> = checked { words.exportWords() }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    // the latest piece of handwriting ended by a session dropped: a reading late for it is not let in
    // by the session made in its place ([HandwritingSession.latestPiece])
    private var inkEndedBefore = HandwritingSession.NO_PIECE

    // every handwriting session made: those written over the keys ([InkOver])
    private fun handwritingOf(s: Session): HandwritingSession? = (s as? InkOver)?.handwritingMade

    // the most of [of] over them and what the dropped ended, no list made: the pad asks at every stroke ([inkEnded])
    private inline fun inkMost(of: (HandwritingSession) -> Int): Int =
        sessions.values.fold(inkEndedBefore) { m, s -> handwritingOf(s)?.let { maxOf(m, of(it)) } ?: m }

    // the latest piece any of them heard of: one made now starts with it ended, a pad numbering its pieces on across them
    private fun latestPiece(): Int = inkMost { it.latestPiece }

    // the input method's session as made, under what [SessionFactory.make] wraps it in (InkOver, StrokeFilter), whichever order
    private tailrec fun innermost(s: Session): Session = when (s) {
        is InkOver -> innermost(s.session)
        is StrokeFilter -> innermost(s.session)
        else -> s
    }

    /**
     * Every session made anew at its next use, what was typed dropped, and what they share
     * ([SessionFactory.invalidate], the decoders' extensions): the user's words, the user model or
     * the habits changed. What changes any of those calls it; a setting, [dropSessions].
     */
    internal fun invalidate() {
        dropSessions()
        assets.dropDecoderExtensions()
    }

    /** How many look-ahead arrays the decoders were given ([EngineAssets.extensionsMade]). */
    internal val extensionsMade: Int get() = assets.extensionsMade

    /** The texts of the longer words the decoders' look-ahead picked so far ([EngineAssets.extensionsPicked]). */
    internal fun lookAheadPicks(): Set<String> = assets.extensionsPicked().mapTo(HashSet()) { assets.pinyinData.vocabulary.word(it) }

    // the sessions and what they share but the decoders' look-ahead, which is kept while nothing was
    // learned since it was made ([EngineAssets.dropDecoderExtensionsIfLearned])
    private fun dropSessions() {
        inkEndedBefore = latestPiece()
        keyboards.clear()
        pairs.values.forEach(KeyboardPair::drop)
        sessions.clear()
        factory.invalidate()
    }

    /**
     * What the user set; the sessions are made again for a change, what was typed dropped. But for
     * 候选词注音 alone (the handwriting pad's 注): what is typed stays, and the host has what is on show
     * read again ([Keyboard.RECALL]), annotated the new way. And for a layout's own groups alone
     * ([EngineSettings.ownKeys], told at a key: EngineBridge.syncOwnKeys): only the session typing on them
     * is made anew, told the field's text as one made later is ([KeyboardPair.dropKeyed]).
     */
    var settings = EngineSettings()
        set(value) {
            hostThread.check()
            if (value == field) return
            val readingOnly = value == field.copy(candidateReading = value.candidateReading)
            val ownKeysOnly = value == field.copy(ownKeys = value.ownKeys)
            val hadOwnKeys = ownKeys
            field = value
            // a layout's own keyboard told (060-render-p3 T9b): the one its codes are read by from now on;
            // none told after one was: none read by it any more (NineKeyLetters.ofCode), not the last one kept
            ownKeys = value.ownKeyboard?.also(NineKeyLetters::inUse) != null
            if (hadOwnKeys && !ownKeys) NineKeyLetters.clearInUse()
            if (readingOnly) return
            // told at the field's first key, after its context (review M1): the other sessions, what they
            // typed and knew, and what the decoders share stay; the text before the cursor reaches the new one
            if (ownKeysOnly) return dropOwnKeys()
            dropSessions()
            assets.dropDecoderExtensionsIfLearned()
            // what a lower level no longer runs leaves memory, read again if the level goes up
            if (value.modelLevel < ModelLevel.MEDIUM) assets.sentenceModel.drop()
            if (value.modelLevel < ModelLevel.HIGH) assets.refiningModel.drop()
        }

    // the session typing on a layout's own groups alone: made anew at its next code, on the groups told now
    private fun dropOwnKeys() {
        // its handwriting's pieces ended with it, as [dropSessions] has them: a late reading not let in by the one made next
        inkEndedBefore = latestPiece()
        pairs.getValue(PINYIN).dropKeyed(PINYIN_KEYS_OWN)
        keyboards.remove(PINYIN_KEYS_OWN)
        sessions.remove(PINYIN_KEYS_OWN)
    }

    // whether [settings] name a layout's own keyboard (EngineSettings.ownKeyboard): what its codes are read by
    private var ownKeys = false

    // a layout's own keyboard's code or tap: none but that keyboard reads it
    private fun isOwnKeysCode(c: Int) = OwnKeysTurn.isCode(c)

    private val latin = LatinSync { w -> assets.englishData?.let { it.id(w) != NO_WORD } == true }

    // off with the habits (U-93): nothing English learned is spelled, and no user model is made for it
    // learner's, not the checked [useHabits]: an entry reading it would be told again for the one call
    private fun syncLatin() = latin.sync(
        settings.latinWords && learner.useHabits, if (learner.useHabits) english.user() else EnglishUser.OFF,
        { if (learner.useHabits) learner.learnLog().state else null }, lexicon,
    )

    // internal: a test counts what the sessions made share (EnginesSessionFactoryTest)
    internal val factory: SessionFactory = SessionFactory(
        assets, lexicon, tables, learner, english, { settings }, latin.words,
        englishData = { field.warmUpEnglish() }, inkEndedBefore = ::latestPiece,
        uptimeMillis = clock::uptimeMillis, learnClock = clock, simplifier = simplifier,
    )

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. [payload], what the key carries: [Keyboard.onEvent]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true, payload: Action? = null): Snapshot {
        hostThread.check()
        if (im == PINYIN) syncLatin()
        // a layout's own keyboard's code (a tap's too) with none told: no keys to read it by, swallowed, what is
        // shown left as it is, never handed back to be typed as it is (the app tells its keyboard first: EngineBridge)
        if (!ownKeys && event == EngineEvent.CHAR && isOwnKeysCode(arg)) {
            // a refine or a pick of the snapshot before is not this key's (review L7)
            val shown = pairs[im]?.showing ?: keyboard(im).showing
            return annotated(im, shown.copy(commit = "", deleteBefore = 0, handled = true, refines = false, picked = false))
        }
        // 联想 on show: a pick of it is no word typed (VoiceEdits); the session's record of its last
        // commit, a new one of which says how what this commits was typed
        val predicting = (pairs[im]?.showing ?: keyboards[im]?.showing)?.predicting == true
        // looked for only while voice's text is followed: nothing else reads it
        val following = voiceEdits.edits.open
        val shownBefore = if (following) pairs.shownSession(im) else null
        val recordBefore = shownBefore?.let { sessions[it]?.lastCommit }
        // handwriting shown before it: what this commits is the handwriting's, not the input method's typing
        val inkingBefore = shownBefore != null && (sessions[shownBefore] as? InkOver)?.inking == true
        val s = pairs[im]?.onEvent(event, arg, learning, payload)
            ?: keyboard(im).onEvent(if (event == EngineEvent.HARD_CHAR) EngineEvent.CHAR else event, arg, learning, payload)
        // the session that typed it, shown before the event or after it: a new record of its last commit says
        // how. One shown only after has no record before to compare, so one left from an earlier commit of it
        // may be read; readingOf takes that only where it spells what went in (the same word, typed so then)
        val reading = if (s.commit.isEmpty() || !following) null else
            VoiceEditsGlue.readingOf(s.commit, listOf(shownBefore, pairs.shownSession(im)).firstNotNullOfOrNull { id -> sessions[id]?.lastCommit?.takeIf { it !== recordBefore } })
        voiceEdits.answered(im, event, s, predicting, learning, reading, inkingBefore)
        return annotated(im, s)
    }

    /**
     * Voice's [text], one talk's, in the field, [before] the cursor read once it was in (null: not
     * read): followed as the user changes it ([VoiceEdits.said]) where the user's habits are used; else
     * nothing is followed. Told only where the app asks (its switch on, a field that learns: review S6;
     * else [voiceEditsEnd]); [hinted]: the once-only hint said already, not looked for (review S7).
     */
    fun voiceSaid(text: String, before: String?, hinted: Boolean) = checked {
        voiceEdits.edits.hinted = hinted
        if (learner.useHabits) voiceEdits.edits.said(text, before) else voiceEdits.end()
    }

    /** The app put [text] in the field: [VoiceEdits.putIn]. */
    fun voicePutIn(text: String): VoiceEdits.Reply = checked { voiceEdits.edits.putIn(text) }

    /** The field read as the prompt is to show: [VoiceEdits.confirm]. */
    fun voiceConfirm(before: String?, after: String?): VoiceEdits.Ask? = checked { voiceEdits.edits.confirm(before, after) }

    /** Nothing of voice's text followed any more: another field, input finished. */
    fun voiceEditsEnd() = checked { voiceEdits.end() }

    /** Whether voice's edits hold the user's words read for them: for tests (review F8342). */
    internal val voiceEditsHoldWords: Boolean get() = voiceEdits.holdsWords

    /** 「记住」 tapped: [ask]'s word added by hand; false if it does not read so (nothing added). */
    fun voiceRemember(ask: VoiceEdits.Ask): Boolean = checked { voiceEdits.remember(ask) }

    /** Its 撤销: [word] taken off the added words, nothing forgotten (UserLexicon.unAdd). */
    fun voiceForget(word: String) = checked { voiceEdits.forget(word) }

    // 候选词注音 of the reading for each candidate shown: the factory's, whose pinyin blocks a polyphone by its parts in the one shown
    private val candidateReadings get() = factory.candidateReadings

    // the session [im]'s candidates are of, if they are any 候选词注音 reads: not English's
    private fun annotatedSession(im: String): Session? =
        if (settings.candidateReading == ReadingStyle.OFF) null else sessions[pairs.shownSession(im)]?.takeIf { it !is EnglishSession }

    /** [s] with each candidate's reading and hint as [readingAndHint] has them; as it is where there are none to show. */
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
     * The reading drawn above the candidate [text] at [index] of [session]'s, and the hint left beside
     * it; the one place the snapshot and the candidates read ([candidates]) work them out. A pinyin's or
     * 双拼's [hint] that is a reading (a misreading's true one, 角色 typed jiaose: jué sè; a character by
     * its parts', a stroke lookup's, one reading of a polyphone's several: A5) is drawn above, 候选词注音 on
     * or off (off: as its pinyin, in the bar's height, the user, 2026-11-02), and leaves no hint; any other hint stays. While 候选词注音 is on
     * ([session] not null) a candidate with no such hint is read as the session typed it.
     */
    private fun readingAndHint(im: String, session: Session?, index: Int, text: String, hint: String): Pair<List<String>, String> {
        val style = settings.candidateReading
        val above = above(im, index, text, hint, if (style == ReadingStyle.OFF) ReadingStyle.PINYIN else style)
        if (above.isNotEmpty()) return above to ""
        return (if (session == null) emptyList() else read(session, index, text, style)) to hint
    }

    /**
     * The candidate [text] at [index] of [session]'s, [hint] its hint, read in [style] (not [ReadingStyle.OFF]) as
     * [readingAndHint] reads it while 候选词注音 is on: the same two steps, so a held candidate's card ([card]) and the
     * bar never read a character apart (a misreading's true reading, 角色 jué sè, by its hint; any other as typed).
     */
    private fun readingsOf(im: String, session: Session, index: Int, text: String, hint: String, style: ReadingStyle): List<String> =
        above(im, index, text, hint, style).ifEmpty { read(session, index, text, style) }

    // a pinyin's or 双拼's hint that is a reading, drawn above the candidate (readingAndHint)
    private fun above(im: String, index: Int, text: String, hint: String, style: ReadingStyle): List<String> =
        if (im in pairs) hintAbove(im, index, text, hint, style) else emptyList()

    // the candidate at [index] of [session]'s as 候选词注音 writes it: handwriting's last character with all its readings.
    // Only while it shows handwriting: pinyin's own lookedUp (by parts, by strokes) is read as typed
    private fun read(session: Session, index: Int, text: String, style: ReadingStyle): List<String> {
        val before = if ((session as? InkOver)?.inking == true) session.lookedUp(index) else null
        return if (before != null) candidateReadings.allOf(text, style, before) else candidateReadings.of(text, style, session.readingOf(index))
    }

    /**
     * What the menu of the [index]th of [im]'s candidates ([paged], of the page shown: the paged window's) heads
     * with ([CandidateCard]), [text] the candidate as the app shows it ([shown] converts the engine's so: 繁體's
     * 銀行 of 银行, as [learning]'s menu checks it); null if it is no longer there or has no Han character in it.
     * A character looked up (handwriting's, by its parts or strokes: [Session.lookedUp]) has its card; any other
     * its reading as [readingsOf] reads it, of the engine's text, the characters the shown text's where they are
     * as many. More than [WordCard.MAX_CHARS] Han characters, its reading alone, nothing looked up.
     * Measured 2026-11-14 on the API 36 emulator, the Mac under other builds (load 40+), 50 runs a held candidate,
     * the median: a character 0.1–0.8 ms, a word of 2 or 8 characters 0.2–0.9 ms, a sentence of 9 (its line
     * alone) 0.2–0.4 ms, a prediction 0.2 ms, the slowest of 50 mostly under 3 ms but single runs of 15–145 ms
     * (the host's load: short words too); a custom phrase of 200 characters, read by its text, 2.2–3.9 ms, a tenth
     * of the runs 38 ms or more. [charCardOf], a word's row tapped: 0.05 ms (061-word-card-impl-report.md).
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
        // the shown characters where each is one of the engine's (国 國), else the engine's throughout
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

    /**
     * A long candidate's line ([CandidateCard.Line]): read as the bar reads it; past [WordCard.LINE_CHARS] Han
     * characters (a custom phrase of a paragraph) its first whole words up to so many alone, then …: what is
     * read is what costs (the 200 of one, 2–4 ms, a tenth of the runs 38 ms or more, read whole). The two steps
     * of [readingsOf] still: a hint that is a reading (all Han, a syllable each: cheap) is the bar's, so its first
     * so many; else the cut part read by the session's words, as the bar reads those.
     */
    private fun longLine(im: String, session: Session, at: Int, c: Choice, chars: String, style: ReadingStyle): String {
        if (WordCard.hanCount(c.text) <= WordCard.LINE_CHARS) return WordCard.line(chars, readingsOf(im, session, at, c.text, c.hint, style))
        // the words the session read it as, those wholly in; none (a first word longer than that alone) or none
        // known, so many characters, read by their text
        val words = session.readingOf(at)?.takeIf { w -> w.joinToString("") { it.text } == c.text }
        val kept = words?.let { w ->
            var han = 0
            w.takeWhile { han += WordCard.hanCount(it.text); han <= WordCard.LINE_CHARS }.takeIf { it.isNotEmpty() }
        }
        val cut = kept?.joinToString("") { it.text } ?: WordCard.firstHan(c.text, WordCard.LINE_CHARS)
        val count = cut.codePointCount(0, cut.length)
        val shown = chars.substring(0, chars.offsetByCodePoints(0, count))
        // a hint's readings are one a code point of an all-Han text: the cut part's the first so many
        val readings = above(im, at, c.text, c.hint, style).take(count).ifEmpty { candidateReadings.of(cut, style, kept) }
        return WordCard.line(shown, readings) + " " + CandidateReadings.MORE
    }

    /**
     * The card of [char], one Han character, [reading] the one first ([CharCard.of]): a word card's row tapped.
     * Of the data alone, not the session: the same whatever the candidates are now.
     */
    fun charCardOf(char: String, reading: CardReading?): CharCard? = checked {
        CharCard.of(char, "", candidateReadings, assets.pinyinData.charReadings, assets.charInfo, assets::strokes, first = reading)
    }

    // the compact line's style: 候选词注音's; off, zhuyin on the zhuyin keyboards (the user, 2026-11-14, Q6), else pinyin
    private fun lineStyle(session: String): ReadingStyle = when (settings.candidateReading) {
        ReadingStyle.OFF -> if (session == ZHUYIN || session == ZHUYIN_NINE) ReadingStyle.ZHUYIN else ReadingStyle.PINYIN
        else -> settings.candidateReading
    }

    // a character's several readings hinted are drawn as the one it has after what was put before it (Session.lookedUp)
    private fun hintAbove(im: String, index: Int, text: String, hint: String, style: ReadingStyle): List<String> {
        if (hint.isEmpty()) return emptyList()
        val before = if (CandidateReadings.SEPARATOR in hint) sessions[pairs.shownSession(im)]?.lookedUp(index).orEmpty() else ""
        return candidateReadings.ofHint(text, hint, style, before)
    }

    private fun keyboard(im: String): Keyboard = keyboards[im] ?: Keyboard(session(im), nineKeys = im in KeyboardPair.KEYED).also {
        keyboards[im] = it
        // one made after the field's context (its first key, sessions made anew): the field's flags all
        // the same, its text not known as a context would make it (review-int S5)
        if (fieldFlags.value != 0) it.fieldFlags(fieldFlags.value)
    }

    // pinyin's and 双拼's keyboards: grouped keys' codes each to a session of their own, made at the first (design
    // §1.3, §1.4; I1 the nine keys, pinyin's 18 and 双拼's 14); a pair's sessionOf is the session an input method's
    // candidates are now of
    private val pairs: Map<String, KeyboardPair> = mapOf(PINYIN to KeyboardPair.pinyin(::keyboard), SHUANGPIN to KeyboardPair.shuangpin(::keyboard) { settings.scheme })

    private val field: FieldContext = FieldContext(assets, this.warmUps, { settings }, learner, english, tables, keyboards, sessions, { pairs }, fieldFlags)

    /**
     * [before] is the text before the cursor, put where the engines did not: see
     * [Keyboard.context]; [learning] off in a sensitive field, told "" (not known), so each
     * learns as its [onEvent] would. [im] is the input method the field starts with. [cleared],
     * [started], [after] and [selected] as [Action.Context][org.fcitx.fcitx5.android.engine.session.Action.Context]
     * has them. True where English, [im], now offers a word in place of the one before the cursor (a
     * correction taken back, U-31), for the host to show at once with a [Keyboard.RECALL]. What
     * learning leaves out ([learnFlags]) is kept for the engines made anew in the field ([FieldFlags]).
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
     * The keys no letter [im], a table input method, reads now, as its session has them, for
     * [readsAsKey][org.fcitx.fcitx5.android.engine.InputMethodCatalog.readsAsKey]: 二笔's `;',./` of
     * its codes, 仓颉's `*` for any key, 二笔's `[` that starts a pinyin lookup, the lookup's `'`. None
     * for another input method, nor a table with no session yet, whose table is not read to answer:
     * the keyboard waits.
     */
    fun tableKeys(im: String): Set<Char> {
        hostThread.check()
        val options = TABLES[im]?.options ?: return emptySet()
        val session = sessions[im] ?: return emptySet()
        // made with its session: read already
        val keys = tables.table(im).dictionary.keys + listOfNotNull(options.matchingKey, options.pinyinKey, SyllableGraph.SEPARATOR) + options.selectionKeys.asIterable()
        return keys.filterTo(HashSet()) { !it.isLetter() && session.reads(it) }
    }

    /**
     * What table [im]'s keyboard shows of it ([TableKeyboard]); null for another input method. Its
     * roots and 拼 key from the settings alone; the keys of its codes once its session is made (on
     * the input method's activate), the table read with it: asked as the keyboard shows, a table
     * that cannot be read is not read here, outside the host's latch for it ([tableKeys] as well).
     * The same answer while nothing it says changed: asked on each key a table answers.
     */
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

    // the last [tableKeyboard] answered, and what it was of
    private class LastTableKeyboard(val im: String, val session: Session?, val settings: EngineSettings, val keyboard: TableKeyboard) {
        fun isFor(im: String, session: Session?, settings: EngineSettings, on: Boolean) =
            this.im == im && this.session === session && this.settings === settings && keyboard.lookingUp == on
    }

    private var lastTableKeyboard: LastTableKeyboard? = null

    /** Makes [im]'s session if it is not yet (its data read), for a caller that tells its failure from another's (the bridge's latches). */
    fun make(im: String) {
        hostThread.check()
        session(pairs.shownSession(im))
    }

    /**
     * Handwriting's session made for [ink], what the pad read or a piece of it finished, which the
     * [Keyboard.INK] sent with it ([shown]) shows; with none sent held for the next
     * ([HandwritingSession.hold]). [im] the input method written on over its keys ([InkOver], its
     * handwriting made now). On the fcitx thread.
     */
    fun ink(ink: Action.Ink, shown: Boolean, im: String) {
        hostThread.check()
        val s = (session(pairs.shownSession(im)) as? InkOver)?.handwriting() ?: return
        if (!shown) s.hold(ink)
    }

    /**
     * After the [Keyboard.INK] carrying [ink] was sent: one the session never had (a temp mode, quick
     * phrase or Unicode over handwriting, filtered the key; the field had lost focus) is held for the
     * next, as an [ink] told with no key is, so its piece is not lost. On the fcitx thread.
     */
    fun inkSent(ink: Action.Ink, im: String) {
        hostThread.check()
        val s = (sessions[pairs.shownSession(im)] as? InkOver)?.handwritingMade ?: return
        if (!s.took(ink)) s.hold(ink)
    }

    /**
     * The keyboard's glide across the letters as [im] reads it, for the [Keyboard.GLIDE] sent with
     * it: English's session the glide, its words found at the key; a pinyin's or a table's its
     * readings read now ([GlideSession.read]), pinyin's nine keys' session's for one over them
     * (KeyboardPair.glidedBy, which the key goes to). Null for one that does not glide ([glides]). On the
     * fcitx thread.
     */
    fun glide(im: String, glide: Glide): Action.Glide? {
        hostThread.check()
        if (!glides(im)) return null
        return when (val s = session(pairs[im]?.glidedBy(glide.layout) ?: im).let(::innermost)) {
            is EnglishSession -> Action.Glide(glide)
            is GlideSession -> s.read(glide)
            else -> null
        }
    }

    /** The latest piece of handwriting ended ([HandwritingSession.ended], of those written over the keys: [InkOver]), for the pad to wipe; on the fcitx thread. */
    val inkEnded: Int get() = checked { inkMost { it.ended } }

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
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

    /** What a long press on the [index]th of [im]'s candidates offers; nothing if it has no session yet. */
    fun offers(im: String, index: Int): Set<Offer> = checked { sessions[pairs.shownSession(im)]?.offers(index).orEmpty() }

    private fun session(im: String): Session = sessions.getOrPut(im) { factory.make(im) }

    /**
     * Reads [additions] again, for the user changed them: the sessions are made anew, what was
     * typed dropped. If the dictionaries changed, the user's words are read again from the log,
     * with them; with no log, what was learned is only in memory, so the new words are added to it
     * and the removed ones kept till the next start.
     */
    fun reload() {
        hostThread.check()
        val before = lexicon.added.drop()
        invalidate()
        lexicon.reloadDictionaries(before)
    }

    override fun close() {
        hostThread.check()
        learner.close()
        // used after all the same: the record read again as it is opened anew
        lexicon.closed()
        english.closed()
        tables.closed()
        invalidate()
    }

    init {
        learner.takeStashes()
    }

    companion object {

        /** Beside the record once the tables' older logs were read into it (LearningFacade.keepOlderTablePhrases): never read again. */
        const val TABLE_LOGS_READ = "learn.table-logs-read"
    }
}

/** Closes [closeable], what fails reported to [onError] rather than thrown. */
internal fun closeQuietly(closeable: Closeable, onError: (IOException) -> Unit) {
    try {
        closeable.close()
    } catch (e: IOException) {
        onError(e)
    }
}

// the session whose candidates [im] shows: a pair's keyed one where it took the last key
private fun Map<String, KeyboardPair>.shownSession(im: String): String = this[im]?.sessionOf(im) ?: im
