/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordLists
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.Executor

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks, in pinyin
 * and in each table, and the English words they commit, are kept under [userDir] (in memory only
 * if null). When pinyin's log is made afresh, what libime's pinyin learned ([legacy], if the user
 * had it) is read into it first. What the user added to
 * pinyin ([additions]) is read at first need, and again on [reload]. The sentence models'
 * int8 weights are multiplied by [kernel], if given (in C++, say); if not, by Kotlin. What the
 * first keys of pinyin and of English want is read ahead of them on [warmUps], a thread each
 * unless given.
 *
 * A facade over its collaborators, which it makes and wires: [EngineAssets] (the data read from
 * the assets), [UserLexicon] (the user's words), [EnglishLexicon] (the English words the user
 * committed) and [TableRegistry] (the table input methods); here are the sessions, made from
 * them, and the settings they are made with.
 *
 * Everything here runs on one thread, the one fcitx runs on, but what is handed to [warmUps].
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit = {},
    private val legacy: () -> LibimeImport.Legacy? = { null },
    private val additions: () -> Additions? = { null },
    private val kernel: MatrixKernel? = null,
    private val warmUps: Executor = Executor { Thread(it, "engine-warm-up").apply { isDaemon = true }.start() },
    /** Whether the phone can draw an emoji: those it cannot are no candidates. */
    private val emojiShows: (String) -> Boolean = { true },
    /** Whether the phone can draw a text: a rare character it cannot is no candidate. */
    private val textShows: (String) -> Boolean = { true },
) : Closeable {

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
        /** The lines of the dictionaries turned on, merged into the base dictionary. */
        val dictionary: () -> Sequence<String>,
    )

    private val assets = EngineAssets(load, onError, kernel, emojiShows, textShows)

    // a thread for each compaction an append makes due, so a key does not wait on the disk: none
    // kept, so the logs opened again after close() still have one
    private val compactions = Executor { Thread(it, "record-compaction").apply { isDaemon = true }.start() }

    private val lexicon: UserLexicon = UserLexicon(
        userDir, onError, legacy, additions, assets, compactions,
        forgetInTables = { tables.forgetInTables(it) }, dropSessions = ::dropSessions,
    )

    private val tables = TableRegistry(load, userDir, onError, compactions, warmUps, assets, lexicon)

    private val english = EnglishLexicon(userDir, onError, compactions)

    /** A word of the user's, as the settings list them; [pinyin] its syllables apart by spaces. */
    data class UserWord(val text: String, val pinyin: String, val kind: Kind, val count: Float = 0f) {
        enum class Kind {
            /** Added by hand, in the settings. */
            ADDED,

            /** Put together by the user from pieces as they typed. */
            LEARNED,

            /** Blocked, a long press on a candidate or in the settings: never offered. */
            BLOCKED,
        }
    }

    /** The words the user added, those they made as they typed (most typed first), and those they blocked. */
    fun userWords(): List<UserWord> = lexicon.userWords()

    /** [kind]'s part of [userWords] alone: a list of the settings shows one kind. */
    fun userWords(kind: UserWord.Kind): List<UserWord> = lexicon.userWords(kind)

    /** How many words of each kind [userWords] lists, with none of them made a [UserWord]. */
    fun userWordCounts(): Map<UserWord.Kind, Int> = lexicon.userWordCounts()

    /**
     * How the dictionary reads [text]: as few of its words as make it up, each read as it most
     * likely is; syllables apart by spaces, null if some character is none of its words. For
     * the user to check, as a character of several readings may be read the other way; a first
     * character read as a word has it, though it never is so alone (了 liao in 了解).
     */
    fun pinyinOf(text: String): String? = assets.pinyinOf(text, strayFirst = true)

    /**
     * [pinyinOf] of each of [texts], the dictionary walked once for them all, and once more for
     * what those not read whole missed: a walk is the whole of it, a thousand words imported with
     * no pinyin would be a thousand walks.
     */
    fun pinyinsOf(texts: Collection<String>): Map<String, String?> = assets.pinyinsOf(texts, strayFirst = true)

    /** Where each of [texts] splits into words, as the model finds likeliest: TextWords.boundaries. */
    fun wordBoundaries(texts: Collection<String>): Map<String, IntArray> = assets.wordBoundaries(texts)

    /** log10 P of each of [texts] under the pinyin model (TextWords.logProb): for ranking what voice input heard. */
    fun logProbs(texts: List<String>): FloatArray = assets.logProbs(texts)

    /** A character's readings as the dictionary has them, the likeliest first: `zhang/chang`; empty for none. */
    fun charReading(c: String): String = assets.charReading(c)

    /** Adds [text] read as [pinyin] (see [WordLists.entry]); false if it does not read so. */
    fun addWord(text: String, pinyin: String): Boolean = lexicon.addWord(text, pinyin)

    /** Blocks [text] read as [pinyin] from the settings; false if it does not read so. */
    fun blockWord(text: String, pinyin: String): Boolean = lexicon.blockWord(text, pinyin)

    /** Takes [word] off its list: an added word is no longer typeable, a learned one forgotten, a blocked one offered again. */
    fun removeWord(word: UserWord) = removeWords(listOf(word))

    /** [removeWord] for each of [words], a list written once. */
    fun removeWords(words: Collection<UserWord>) = lexicon.removeWords(words)

    /** The English words the user committed, as written, the most typed first: [UserWord.Kind.LEARNED], no pinyin. */
    fun englishWords(): List<UserWord> = english.words()

    /**
     * Makes [text] an English word of the user's, added: offered by its keys, never corrected, and
     * kept however its count would fade. False for what is no word English types (letters, an
     * apostrophe or a hyphen within).
     */
    fun addEnglishWord(text: String): Boolean = english.add(text)

    /** Forgets each of the English words [texts]: their counts and the pairs they are in. */
    fun forgetEnglishWords(texts: Collection<String>) = english.forget(texts)

    /** What [importWords] made of the lines: words added, blocked and learned that were not already, lines it could not read. */
    data class Imported(val added: Int, val blocked: Int, val unread: Int, val learned: Int = 0)

    /**
     * The words of a list the user imports, a word a line (see [WordLists.line]): added, or
     * blocked where marked so; one with no pinyin read as the dictionary reads it ([pinyinOf]);
     * in the user's own export ([exportWords]), one marked learned learned again with its count.
     */
    fun importWords(lines: Sequence<String>): Imported = lexicon.importWords(lines)

    /**
     * The user's words as [importWords] reads them back: the added as `幽默 you'mo`, the learned
     * (most typed first) with a `~` apart before them and their count after (`~ 幽默 you'mo 3.0`, learned
     * again only from a file headed by the export's mark), the blocked with a `!` before them.
     */
    fun exportWords(): List<String> = lexicon.exportWords()

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    // English's data is read once and kept: warmed up once, however often its session is made
    private var englishWarmedUp = false

    // pinyin's data and word index, which the sessions of READS_PINYIN_DATA and the tables read as they
    // are made: read once, in the background
    private var pinyinWarmedUp = false

    // of what is placed beside the candidates (EMOJI_DATA, CHAIZI_DATA, CHAR_READINGS), what was warmed up
    private val asideWarmedUp = HashSet<String>()

    // the coarse words of pinyin's vocabulary but those the user added, one for handwriting and every
    // table: made again once the sessions are dropped, as every change to the added words drops them
    private var coarseWords: CoarseWords? = null

    // the latest piece of handwriting ended by a session dropped: a reading late for it is not let in
    // by the session made in its place ([HandwritingSession.latestPiece])
    private var inkEndedBefore = HandwritingSession.NO_PIECE

    private fun dropSessions() {
        (sessions[HANDWRITING] as? HandwritingSession)?.let { inkEndedBefore = maxOf(inkEndedBefore, it.latestPiece) }
        keyboards.clear()
        sessions.clear()
        coarseWords = null
        assets.dropDecoderExtensions()
    }

    /** What the user set; the sessions are made again for a change, what was typed dropped. */
    var settings = EngineSettings()
        set(value) {
            if (value == field) return
            field = value
            dropSessions()
            // what a lower level no longer runs leaves memory, read again if the level goes up
            if (value.modelLevel < ModelLevel.MEDIUM) assets.sentenceModel.drop()
            if (value.modelLevel < ModelLevel.HIGH) assets.refiningModel.drop()
        }

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        val keyboard = keyboards[im] ?: Keyboard(session(im), nineKeys = im == T9).also { keyboards[im] = it }
        return keyboard.onEvent(event, arg, learning)
    }

    /**
     * [before] is the text before the cursor, put where the engines did not: see
     * [Keyboard.context]; [learning] off in a sensitive field, told "" (not known), so each
     * learns as its [onEvent] would. [im] is the input method the field starts with. [cleared] as
     * [Action.Context.cleared].
     */
    fun context(im: String, before: String, learning: Boolean = true, cleared: Boolean = false) {
        if (im in READS_PINYIN_DATA || im in TABLES) warmUpPinyin(im)
        // the user's words coded for the table before its first key, which would otherwise start it
        if (im in TABLES) tables.prepare(im)
        keyboards.values.forEach { it.context(before, learning, cleared) }
    }

    // told as a field starts in an input method that reads pinyin's data (READS_PINYIN_DATA, a table,
    // which ranks and learns by them: TableLanguage), before its first key: the data read and its 270
    // thousand words indexed meanwhile, off the fcitx thread; one who types only English neither waits
    // for them nor holds them. A key that comes sooner waits for them, and a switch from English to one
    // of those reads them on the fcitx thread as it makes the session (activate's Reset). Whatever
    // reading them throws is dropped, the lazies left unset: the session reads them again as it is
    // made, on the fcitx thread, which hands it to the addon (EngineBridge.Unloadable); thrown out of
    // this thread, it would end the app. Memory run out is no Exception, and is not caught.
    // Beside it only what [im] shows as the settings have it, held from then on: no emoji in handwriting,
    // characters by their parts and their readings (笔画查字's) only in full pinyin
    private fun warmUpPinyin(im: String) {
        val aside = HashSet<String>()
        if (settings.emoji && im != HANDWRITING) aside += EMOJI_DATA
        if (im == PINYIN && settings.chaizi) aside += CHAIZI_DATA
        if (im == PINYIN) aside += CHAR_READINGS
        aside -= asideWarmedUp
        if (pinyinWarmedUp && aside.isEmpty()) return
        pinyinWarmedUp = true
        asideWarmedUp += aside
        warmUps.execute {
            try {
                assets.pinyinData.wordIndex
                assets.charSyllables
                assets.warmUpAside(EMOJI_DATA in aside, CHAIZI_DATA in aside, CHAR_READINGS in aside)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * The keys no letter [im], a table input method, reads now, as its session has them, for
     * [readsAsKey]: 二笔's `;',./` of its codes, 仓颉's `*` for any key, 二笔's `[` that starts a
     * pinyin lookup, the lookup's `'`. None for another input method, nor a table with no session
     * yet, whose table is not read to answer: the keyboard waits.
     */
    fun tableKeys(im: String): Set<Char> {
        val options = TABLES[im]?.options ?: return emptySet()
        val session = sessions[im] ?: return emptySet()
        // made with its session: read already
        val keys = tables.table(im).dictionary.keys + listOfNotNull(options.matchingKey, options.pinyinKey, SyllableGraph.SEPARATOR) + options.selectionKeys.asIterable()
        return keys.filterTo(HashSet()) { !it.isLetter() && session.reads(it) }
    }

    /**
     * What the handwriting pad read, for its session to show at its next [Keyboard.INK]:
     * [HandwritingSession.offer]. Called on the fcitx thread, just before that key is sent.
     */
    fun offerInk(reading: InkReading, piece: Int) {
        (session(HANDWRITING) as HandwritingSession).offer(reading, piece)
    }

    // read when first glided: most never glide pinyin
    private val pinyinGlide by lazy { PinyinGlide(assets.pinyinData, user = ::glideUser) }

    // the user's words in a glide's readings, as the sessions score them: added, learned, blocked
    private fun glideUser(): PinyinGlide.User {
        val user = lexicon.user()
        val scorer = lexicon.prior().scorer(UserScorer(assets.pinyinData.model, user))
        val context = scorer.context(NO_WORD, NO_WORD)
        return PinyinGlide.User(user to user.changes, { word -> scorer.scoreAfter(context, word) }, user, { word -> user.count(word) > 0f }, user::blocked)
    }

    // what a pinyin's glide after something typed goes on after
    private val syllableBoundary = Action.Key(PinyinGlide.SEPARATOR)

    /**
     * The keyboard's glide across the letters, for [im] to read at its next [Keyboard.GLIDE]:
     * English's session its words ([EnglishSession.offerGlide]), a pinyin's or a table's its
     * readings ([GlideSession]). False for one that does not glide ([glides]). On the fcitx thread,
     * just before that key.
     */
    fun offerGlide(im: String, glide: Glide): Boolean {
        if (!glides(im)) return false
        when (val s = session(im)) {
            is EnglishSession -> s.offerGlide(glide)
            is GlideSession -> s.offer(glide)
            else -> return false
        }
        return true
    }

    /** The handwriting pad's [piece] finished, after its readings: [HandwritingSession.finished]. On the fcitx thread, before an Ink key. */
    fun inkFinished(piece: Int) {
        (session(HANDWRITING) as HandwritingSession).finished(piece)
    }

    /** The latest piece of handwriting ended ([HandwritingSession.ended]), for the pad to wipe; on the fcitx thread. */
    val inkEnded: Int get() = (sessions[HANDWRITING] as? HandwritingSession)?.ended ?: inkEndedBefore

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    /** What a long press on the [index]th of [im]'s candidates offers; nothing if it has no session yet. */
    fun offers(im: String, index: Int): Set<Offer> = sessions[im]?.offers(index).orEmpty()

    // each of [glides] made a GlideSession or an EnglishSession, which offerGlide hands the glide to
    private fun session(im: String): Session = sessions.getOrPut(im) {
        val s = settings
        when (im) {
            PINYIN -> {
                val segmenter = PinyinSegmenter(s.fuzzy, s.typos, neighbours = s.typos, latin = if (s.latinWords) assets.latinWords else null)
                val pinyin = pinyinSession(segmenter, spell = false, chaizi = chaizi())
                val looked = ForgetsInTables(StrokeLookup(pinyin, assets::strokes, assets::charReading, s.pageSize, blocked = lexicon::isBlocked, follow = pinyin::follow, retype = pinyin::typeAll))
                GlideSession(looked, syllableBoundary, blocked = glideHeldBack()) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            ENGLISH -> {
                val data = assets.englishData
                // its keys and word index, some hundreds of milliseconds on a phone, built before the
                // first key wants them; a key that comes sooner waits for them, as it would anyway
                if (data != null && !englishWarmedUp) {
                    englishWarmedUp = true
                    warmUps.execute(data::warmUp)
                }
                EnglishSession(data, pageSize = s.pageSize, user = english.user(), nineKeys = s.nineKeys)
            }
            HANDWRITING -> {
                // read as pinyin reads a sentence: its words, the user's among them, and their counts
                val language = ChineseLanguage()
                val data = language.data
                val decoder = HandwritingDecoder(
                    data.dictionary, data.vocabulary, data.wordIndex, language.scorer,
                    assets::charSyllables, language.user, language.coarse,
                )
                HandwritingSession(
                    decoder, TextWords(data.model, data.wordIndex), s.pageSize, language.user, language.predictor, reranker(), refiner(),
                    block = lexicon::block, forgotten = { tables.forgetInTables(listOf(it)) }, ended = inkEndedBefore,
                )
            }
            // 简拼 only where the digits read nothing else: as common on the evaluation set, and faster
            // glided as full pinyin, over the digits' keys: each letter typed as itself, as one chosen from a key's long press
            T9 -> GlideSession(
                ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = s.nineKeys), spell = true)),
                syllableBoundary,
                blocked = glideHeldBack(),
                typed = { c -> if (c == PinyinGlide.SEPARATOR) Action.Key(c) else Action.Letter(c) },
                readings = GlideSession.NINE_KEYS_READINGS,
            ) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            SHUANGPIN -> {
                val spelling = PinyinGlide.Spelling.shuangpin(s.scheme)
                GlideSession(
                    ForgetsInTables(pinyinSession(ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true)),
                    syllableBoundary,
                    blocked = glideHeldBack(),
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n, spelling) }
            }
            else -> {
                val method = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
                val table = tables.table(im)
                val options = (s.tables[method.group]?.applyTo(method.options) ?: method.options).copy(pageSize = s.pageSize)
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(assets.pinyinData, PinyinSegmenter(), prediction = false)
                val language = tableLanguage()
                val glide = TableGlide(
                    table.dictionary, { text -> language.words.logProb(text) }, PinyinGlide.WEIGHTS,
                    version = { language.user?.let { it to it.changes } },
                    // the words counted, scored anew a glide: their scores move with every word learned
                    learned = { text -> language.user?.typed(text) == true },
                    // as the table's session blocks: not a character blocked under one pinyin reading
                    blocked = lexicon::isBlockedWord,
                )
                // a glide after a code typed picks its first candidate: the next code is the glide's
                GlideSession(
                    TableSession(table.dictionary, options, lookUp, table.user, tables.shared(table, im).also { it.prepare() }, language),
                    between = Action.Pick(0),
                    blocked = glideHeldBack(lexicon::isBlockedWord),
                ) { g, n -> glide.decode(g.path, g.layout, n) }
            }
        }
    }

    // what a glide's other readings leave out: the words blocked, and the coarse ones but those the
    // user added, as the decoders hold them back (CoarseWords); [blocked] as the session blocks
    private fun glideHeldBack(blocked: (String) -> Boolean = lexicon::isBlocked): (String) -> Boolean {
        val coarse = (CoarseWords.LIST - lexicon.addedTexts()).toHashSet()
        return { blocked(it) || it in coarse }
    }

    /**
     * What a table shares with the other Chinese input methods: the word model with the user's words,
     * the one user model, 联想 as the setting has it, the sentence models of the 性能档位.
     */
    private fun tableLanguage(): TableLanguage {
        val language = ChineseLanguage()
        val data = language.data
        return TableLanguage(
            TextWords(data.model, data.wordIndex, language.scorer),
            language.user,
            reading = { text -> data.wordIndex.find(text).takeIf { it != NO_WORD }?.let(language.readings::reading) },
            predictor = language.predictor,
            reranker = reranker(),
            refiner = refiner(),
            block = lexicon::block,
            forgotten = { tables.forgetInTables(listOf(it)) },
            emoji = emoji(),
            phrases = wordPhrases(),
        )
    }

    /**
     * What handwriting and a table read by, as pinyin does (specs/engine.md), made with each of their
     * sessions: pinyin's [data], the one [user] model, the word model with the user's words
     * ([scorer]), the coarse words held back ([coarse], one for them all), and 联想 ([predictor]).
     */
    private inner class ChineseLanguage {
        val data = assets.pinyinData
        val user = lexicon.user()
        val scorer = lexicon.prior().scorer(UserScorer(data.model, user))
        val coarse = coarseWords ?: CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - lexicon.addedTexts()).also { coarseWords = it }

        // a word's reading as it is learned: as the user typed it, else the dictionary's likeliest. The
        // coarse words hold back only what is predicted, not a reading: one predictor for both
        val readings = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = assets::charSyllables)

        // what may follow, as pinyin offers it: the same setting
        val predictor = readings.takeIf { settings.prediction }
    }

    /** A session of one of the pinyins, alike but for how [segmenter] reads the keys; the built-in phrases as set, kept from [spell] by the session. */
    private fun pinyinSession(segmenter: Segmenter, spell: Boolean, chaizi: (String) -> List<Pair<String, String>> = { emptyList() }): PinyinSession {
        val s = settings
        return PinyinSession(
            assets.pinyinData, segmenter, spell = spell,
            pageSize = s.pageSize, user = lexicon.user(), prediction = s.prediction, prior = lexicon.prior(), phraseBook = lexicon.phrases(), builtInPhrases = s.builtInPhrases,
            reranker = reranker(), refiner = refiner(), block = lexicon::block, emoji = emoji(), chaizi = chaizi,
            charReadings = assets::charSyllables, extensions = assets::decoderExtensions, addedWords = lexicon.addedTexts(),
        )
    }

    /**
     * [pinyin], what a long press forgets there forgotten in every table too, as [TableRegistry.shared] has it.
     * Offered only where pinyin learned something of it (PY-33): a character only a table learned
     * (picked there, not a phrase it saved) is forgotten from that table's own long press, not here.
     */
    private inner class ForgetsInTables(private val pinyin: Session) : Session by pinyin {
        override fun apply(action: Action): Snapshot {
            val forgotten = if (action is Action.Forget && Offer.FORGET in pinyin.offers(action.index)) pinyin.candidates(action.index, 1).firstOrNull() else null
            return pinyin.apply(action).also { if (forgotten != null) tables.forgetInTables(listOf(forgotten.text)) }
        }
    }

    /** The emoji a word stands for, as the Chinese input methods offer them: none, the setting off. */
    private fun emoji(): (String) -> List<String> = if (settings.emoji) assets::emojiOf else { _ -> emptyList() }

    /** The built-in phrases' texts a word stands for, as the tables offer them: none, the setting off. */
    private fun wordPhrases(): (String) -> List<String> =
        if (settings.builtInPhrases) { word -> lexicon.phrases().builtInsOf(word, Calendar::getInstance) } else { _ -> emptyList() }

    /** The characters by their parts' pinyin, as full pinyin offers them, but those blocked: none, the setting off. */
    private fun chaizi(): (String) -> List<Pair<String, String>> =
        if (settings.chaizi) { letters -> assets.chaiziOf(letters).filterNot { lexicon.isBlocked(it.first) } } else { _ -> emptyList() }

    /**
     * The one reranker over the model, for every session: they all run on the fcitx thread, one
     * input typed at a time, and what it ran for one input is started over for another's.
     */
    private fun reranker(): Reranker? = if (settings.modelLevel >= ModelLevel.MEDIUM) assets.sentenceModel.reranker(Reranker.LIMIT) else null

    /** As [reranker], over the larger model, which weighs the readings again while the user pauses. */
    private fun refiner(): SentenceRefiner? = if (settings.modelLevel == ModelLevel.HIGH) assets.lateRefiner else null

    /**
     * Reads [additions] again, for the user changed them: the sessions are made anew, what was
     * typed dropped. If the dictionaries changed, the user's words are read again from the log,
     * with them; with no log, what was learned is only in memory, so the new words are added to it
     * and the removed ones kept till the next start.
     */
    fun reload() {
        val before = lexicon.dropAdditions()
        dropSessions()
        lexicon.reloadDictionaries(before)
    }

    override fun close() {
        // each on its own: one that fails to close must not leave the others open
        (listOfNotNull(lexicon.store, english.store) + tables.stores()).forEach { closeQuietly(it, onError) }
        // used after all the same: each reads its log again, and appends to it
        lexicon.closed()
        english.closed()
        tables.closed()
        dropSessions()
    }

    companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        /** Pinyin on a phone's nine keys (九键). */
        const val T9 = "engine-t9"

        const val WUBI = "engine-wubi"

        /** Handwriting: characters written one at a time on a pad, read as a sentence. */
        const val HANDWRITING = "engine-handwriting"

        /**
         * The input methods but the tables ([TABLES], which read it too) whose sessions read pinyin's
         * data and word index as they are made: the pinyins, and handwriting, which reads its sentences by them.
         */
        private val READS_PINYIN_DATA = setOf(PINYIN, SHUANGPIN, T9, HANDWRITING)

        // what charReading reads off pinyin's dictionary, warmed up as the data files are
        private const val CHAR_READINGS = "char readings"

        /** Offered first to be added: the most asked for after pinyin, which is there already. */
        private val ADDED_FIRST = listOf(T9, WUBI)

        /** [methods] in the order they are offered to be added: [ADDED_FIRST], then the rest as they came. */
        fun <T> addOrder(methods: Collection<T>, name: (T) -> String): List<T> =
            methods.sortedBy { ADDED_FIRST.indexOf(name(it)).takeIf { i -> i >= 0 } ?: ADDED_FIRST.size }

        /**
         * Whether [im] types with [c], no letter, as with a letter: the nine keys' digits, a 双拼
         * [scheme]'s own keys (`;` is ing in 微软 and 紫光), and the pinyins' syllable separator `'`
         * while something is typed ([composing]), as PinyinSession.reads has them; and a table's
         * keys while something is typed, as its session reads them ([tableKeys], [Engines.tableKeys] as
         * read on fcitx's thread: asked only then). Any other an address
         * field takes as it is (PendingInputPolicy.typesAsIs); 微软's `;` taken so cut the syllable
         * being typed, `'` put `xi'an` in as `xi'` and `an`, and 二笔's `;` cut its code.
         */
        fun readsAsKey(
            im: String,
            scheme: ShuangpinScheme,
            c: Int,
            composing: Boolean = false,
            tableKeys: () -> Set<Char> = { emptySet() },
        ): Boolean = when (im) {
            T9 -> Character.isDigit(c) || separates(c, composing)
            SHUANGPIN -> c <= Char.MAX_VALUE.code && scheme.types(c.toChar()) || separates(c, composing)
            PINYIN -> separates(c, composing)
            // with nothing typed a table's is text, as ' is: through the table 二笔's / alone would be 、
            else -> composing && c <= Char.MAX_VALUE.code && c.toChar() in tableKeys()
        }

        // with nothing typed ' is the app's: through the engine it would be a mark, ‘
        private fun separates(c: Int, composing: Boolean) = composing && c == SyllableGraph.SEPARATOR.code

        /**
         * Whether input method [name] is typed with a keyboard's keys: all but those made for a
         * touch screen, the nine keys and handwriting. A hardware keyboard types with one that is.
         */
        fun takesKeyboard(name: String): Boolean = name != T9 && name != HANDWRITING

        /**
         * Whether input method [name] reads a glide across its keys (滑行输入): English, the pinyins and
         * the tables, not handwriting. Each is made a session that reads one (Engines.session): one
         * added here is added there.
         */
        fun glides(name: String): Boolean = name == ENGLISH || name == PINYIN || name == SHUANGPIN || name == T9 || name in TABLES

        /** English: fcitx's own name for it, which fcitx falls back to, so a user's lists keep it. */
        const val ENGLISH = "keyboard-us"

        /** Its words as written and a trigram model over them, counted from FineWeb's English pages, in the app's assets. */
        const val ENGLISH_DATA = "engine/english.data"

        const val PINYIN_DATA = "engine/pinyin.data"

        /** Characters by their strokes (rime-stroke), for pinyin's `u` lookup; read if the app has it. */
        const val STROKE_DATA = "engine/stroke.data"

        /** The emoji panel's groups and the words emoji stand for (EmojiData); read if the app has it. */
        const val EMOJI_DATA = "engine/emoji.data"

        /** Characters by the pinyin of their parts (Chaizi), for full pinyin; read if the app has it. */
        const val CHAIZI_DATA = "engine/chaizi.data"
        /** Read if the app has one; without it the engine types by the decoder alone. */
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
        /** Six times the work of [SENTENCE_MODEL], and right more often: see [PinyinSession]'s refiner. */
        const val REFINING_MODEL = "engine/sentence-model-large.safetensors"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"
        const val USER_ENGLISH = "english.user"

        /** Beside a table's log: its phrases saved before were told to pinyin (see shared). */
        const val TOLD = ".told"

        /** Where what the user taught the table input method [im] is kept: `wubi.user` and so on. */
        fun userTable(im: String) = im.removePrefix("engine-") + ".user"

        /** Whether [im] is a table input method, the only kind [tableKeys] has keys for. */
        fun hasTableKeys(im: String) = im in TABLES

        /** Each table input method. */
        val TABLES: Map<String, TableMethod> = mapOf(
            WUBI to TableMethod("wbx.data", "Wubi", TableOptions.WUBI, "wbx"),
            "engine-cangjie" to TableMethod("cj.data", "Cangjie", TableOptions.CANGJIE, "cangjie"),
            "engine-ziranma" to TableMethod("zrm.data", "Ziranma", TableOptions.ZIRANMA, "zrm"),
            "engine-erbi" to TableMethod("erbi.data", "Erbi", TableOptions.ERBI, "erbi"),
            "engine-wubipinyin" to TableMethod("wbpy.data", "WubiPinyin", TableOptions.WUBI_PINYIN, "wbpy"),
        )
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

/**
 * A table input method: its [file] under [Engines.TABLE_DIR], the [group] of its settings in the
 * addon's config (see [EngineSettings.tables]), and how it behaves unless those say otherwise.
 * [libime] the name libime's table input method had.
 */
class TableMethod(val file: String, val group: String, val options: TableOptions, val libime: String)
