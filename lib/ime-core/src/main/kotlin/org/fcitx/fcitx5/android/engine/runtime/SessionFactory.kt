/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS18
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN_KEYS_OWN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ZHUYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ZHUYIN_NINE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN_KEYS14
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN_KEYS18
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN_NINE
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.T9
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.filtersByStrokes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.writesOnKeys
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.TableMethod
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishPhrases
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.glide.GlideReading
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkOver
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Simplifier
import org.fcitx.fcitx5.android.engine.phrase.CommonWordBook
import org.fcitx.fcitx5.android.engine.phrase.PartsLookup
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.pinyin.UserLatinWords
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.table.SavedPhrases
import org.fcitx.fcitx5.android.engine.table.SharedWords
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableSlips
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordLists

/** Makes each input method's session from [settings] and the user's habits, from [assets], [lexicon], tables, [learner] and English. Handwriting and tables share one [ChineseLanguage] per user model and one coarse-word list per set of added words. On the engines' thread. */
internal class SessionFactory(
    private val assets: EngineAssets,
    private val lexicon: UserLexicon,
    private val tables: TableRegistry,
    private val learner: LearningFacade,
    private val english: EnglishLexicon,
    private val settings: () -> EngineSettings,
    // English's learned words as 中英混输 spells them (LatinSync)
    private val latinWords: UserLatinWords,
    // English's data, warming up if not yet (FieldContext)
    private val englishData: () -> EnglishData?,
    // the latest handwriting piece a dropped session ended ([HandwritingSession.latestPiece])
    private val inkEndedBefore: () -> Int,
    private val uptimeMillis: () -> Long,
    private val learnClock: LearnClock,
    simplifier: () -> Simplifier,
) {
    private val simplified by lazy(LazyThreadSafetyMode.NONE) { simplifier() }

    /** 候选词注音's readings, made on first ask; pinyin blocks a polyphone by its parts in the reading shown. */
    val candidateReadings by lazy(LazyThreadSafetyMode.NONE) {
        CandidateReadings(assets.pinyinData, assets.pinyinData.tones, { simplified.simplify(it) }) { assets.charSyllables }
    }

    // pinyin's coarse words minus the user's added words, one for handwriting and every table; remade when sessions
    // drop (adding a word drops them; a long-press delete does not)
    private var coarseWords: CoarseWords? = null

    // the last coarse words and the added words they exclude: a pinyin session with the same added words reuses them
    internal var lastCoarse: CoarseWords? = null
        private set
    private var lastCoarseAdded: Set<String>? = null

    // handwriting's and tables' language, for the user model it was made with
    private var language: ChineseLanguage? = null

    /** [ChineseLanguage]s and [CoarseWords] made: one language per user model and one list per added-words set between [invalidate]s. For EnginesSessionFactoryTest. */
    internal var languagesMade = 0
    internal var coarseMade = 0
    internal var lookUpsMade = 0

    // each table's 拼 key state, kept across remade sessions
    private val lookUpModes = HashMap<String, TableSession.LookUpMode>()

    fun lookUpMode(im: String): TableSession.LookUpMode = lookUpModes.getOrPut(im) { TableSession.LookUpMode() }

    /** Remakes shared state at next need: user words or settings changed. */
    fun invalidate() {
        coarseWords = null
        lastCoarse = null
        lastCoarseAdded = null
        language = null
    }

    // [glides] get a GlideSession or EnglishSession (Engines.glide reads them); [filtersByStrokes] a StrokeFilter;
    // [writesOnKeys], outermost, an InkOver, whose handwriting candidates are not filtered
    fun make(im: String): Session = made(im)
        .let { if (filtersByStrokes(im)) StrokeFilter(it, assets::strokes, settings().pageSize, singles = settings().strokeSingles) { cp, drawn -> assets.charInfo.orderStartsWith(cp, drawn) } else it }
        .let { if (writesOnKeys(im)) InkOver(it, ::handwritingSession) else it }

    /** Handwriting's session as an [InkOver] writes with (and ime-eval measures alone). */
    fun handwritingSession(): HandwritingSession {
        val s = settings()
        // read as pinyin reads a sentence: its words, the user's among them, and their counts
        val language = language()
        val data = language.data
        val decoder = HandwritingDecoder(
            data.dictionary, data.vocabulary, data.wordIndex, language.scorer,
            assets::charSyllables, language.user, language.coarse, held = lexicon.gate()::holds,
        )
        return HandwritingSession(
            decoder, TextWords(data.model, data.wordIndex), s.pageSize, language.user, language.predictor, reranker(), refiner(),
            block = lexicon::block, forgotten = { learner.forgottenEverywhere(it) }, ended = inkEndedBefore(), habits = learner.habitsNow(), simplify = simplified::simplify,
        )
    }

    private fun made(im: String): Session {
        val s = settings()
        // 全拼 on keys: 中英混输 as on the full keyboard
        fun keys(letters: NineKeyLetters) = T9Segmenter(
            s.fuzzy, abbreviations = false, letters = letters, neighbours = s.typos,
            latin = if (s.latinWords) assets.latinWords else null, userLatin = if (s.latinWords) latinWords else null,
        )
        return when (im) {
            PINYIN -> {
                val segmenter = PinyinSegmenter(
                    s.fuzzy, s.typos, neighbours = s.typos,
                    latin = if (s.latinWords) assets.latinWords else null, userLatin = if (s.latinWords) latinWords else null,
                )
                val pinyin = pinyinSession(segmenter, spell = false, chaizi = chaizi())
                val looked = StrokeLookup(
                    pinyin, assets::strokes, assets::charReading, s.pageSize, blocked = lexicon::withheld, follow = pinyin::follow, retype = pinyin::typeAll,
                    blockedUnder = { strokes -> learner.openHabits().blockedUnder(strokeCode(strokes)) },
                    blockUnder = { strokes, text -> learner.openHabits().blockUnder(strokeCode(strokes), text, on = true) },
                )
                GlideSession(looked, syllableBoundary, blocked = glideHeldBack(), heldBack = heldBack(pinyin), blockOther = ::blockOtherReading) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            // the 14 keys: no glide nor 笔画查字 (u shares UI, no stroke key); 简拼 only where nothing else reads, as on nine keys
            PINYIN_KEYS -> pinyinSession(keys(NineKeyLetters.G14), spell = true)
            // the 18 keys: 双拼's G18 groups (; is no pinyin letter); otherwise as the 14 keys, learned under qk:<G18's tag>
            PINYIN_KEYS18 -> pinyinSession(keys(NineKeyLetters.G18), spell = true)
            // a layout's own groups: as the 14 keys, learned under qk:<their tag>, its keyboard the one in use; none told
            // (Engines swallows their codes then): G14's, which reads none of them
            PINYIN_KEYS_OWN -> {
                val own = s.ownKeyboard?.also(NineKeyLetters::inUse) ?: NineKeyLetters.G14
                pinyinSession(keys(own), spell = true)
            }
            // zhuyin: the nine keys' way with symbols for letters and a tone as separator; learned under zy:nine and
            // zy:dachen, apart from pinyin's keyboards; both read slipped keys
            ZHUYIN_NINE -> pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = ZhuyinKeys.NINE, toneAllows = assets.pinyinData.tones::allows, neighbours = s.typos), spell = true, kind = Kinds.ZHUYIN_NINE)
            ZHUYIN -> pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = ZhuyinKeys.DACHEN, toneAllows = assets.pinyinData.tones::allows, neighbours = s.typos), spell = true, kind = Kinds.ZHUYIN_DACHEN)
            ENGLISH -> {
                val data = englishData()
                EnglishSession(
                    data, pageSize = s.pageSize, user = if (learner.useHabits) english.user() else EnglishUser.OFF, nineKeys = s.nineKeys,
                    phrases = EnglishPhrases(phraseBook(), learnClock::calendar), blocks = lexicon.englishBlocks,
                )
            }
            // 简拼 only where the digits read nothing else: as accurate and faster; glided as full pinyin over the digits'
            // keys, each letter typed as itself
            T9 -> {
                val pinyin = pinyinSession(keys(s.nineKeys), spell = true)
                GlideSession(
                    pinyin,
                    syllableBoundary,
                    blocked = glideHeldBack(),
                    typed = { c -> if (c == PinyinGlide.SEPARATOR) Action.Key(c, glided = true) else Action.Letter(c, glided = true) },
                    readings = GlideSession.NINE_KEYS_READINGS,
                    heldBack = heldBack(pinyin),
                    blockOther = ::blockOtherReading,
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            // the 18, 14 and nine keys: as pinyin's 14 keys, no glide nor 笔画查字; slips read by tap, no other typo;
            // learned under spk: (CommitRecorder)
            SHUANGPIN_KEYS18, SHUANGPIN_KEYS14, SHUANGPIN_NINE ->
                pinyinSession(GroupedShuangpinSegmenter(s.scheme, KeyboardPair.lettersOf(im), s.fuzzy, neighbours = s.typos), spell = true, keysShown = s.shuangpinKeysShown, kind = Kinds.shuangpin(s.shuangpin))
            SHUANGPIN -> {
                val spelling = PinyinGlide.Spelling.shuangpin(s.scheme)
                val pinyin = pinyinSession(ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos, neighbours = s.typos), spell = true, keysShown = s.shuangpinKeysShown, kind = Kinds.shuangpin(s.shuangpin))
                GlideSession(
                    pinyin,
                    syllableBoundary,
                    blocked = glideHeldBack(),
                    heldBack = heldBack(pinyin),
                    blockOther = ::blockOtherReading,
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n, spelling) }
            }
            else -> table(im, s)
        }
    }

    private fun table(im: String, s: EngineSettings): Session {
        val method = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
        val table = tables.table(im)
        // nothing offered to remember where nothing is learned
        val options = tableOptions(method, s).let { o ->
            o.copy(pageSize = s.pageSize, spacePicksPrediction = s.spacePicksPrediction, offerWords = o.offerWords && learner.useHabits)
        }
        // pinyin lookup in a table learns as full pinyin does, leaving codes alone; no phrases, emoji or 联想 (the
        // result is to be typed by code); habits off as pinyin's own. Made on first lookup: most tables never do
        val lookUp = {
            lookUpsMade++
            val user = sessionUser()
            PinyinSession(
                assets.pinyinData, PinyinSegmenter(), user = user, prediction = false, prior = lexicon.prior(),
                builtInPhrases = false, now = learnClock::calendar, block = lexicon::block, charReadings = assets::charSyllables,
                extensions = { assets.decoderExtensions(user) }, addedWords = ::addedNow, coarse = coarse(),
                learnTo = LearnTo(learner.habitsNow(), Kinds.FULL, simplified::simplify),
            )
        }
        val language = tableLanguage(im)
        val glide = TableGlide(
            table.dictionary, { text -> language.words.logProb(text) }, PinyinGlide.WEIGHTS,
            // the table's blocks too (a word blocked under a code scores -inf); keyed by state id too, since a replay or
            // clear makes a new state counting from 0
            version = { language.user?.let { it to it.changes } to learner.learnLog().state.let { it.id to it.tableBlocksVersion } },
            // learned words rescored per glide: scores move with every word learned
            learned = { text -> language.user?.typed(text) == true },
            // only the table's own code blocks (a pinyin block hides none of them), plus 生僻字屏蔽 everywhere
            blocked = { code, text -> text in learner.openHabits().blockedUnder(Code(Kinds.table(im), code)) || language.held(text) },
        )
        // a glide after a typed code picks its first candidate: the next code is the glide's
        return GlideSession(
            TableSession(
                // 五笔拼音 mixes pinyin in, no lookup
                table.dictionary, options, if (options.pinyinKey == null && !options.lookUpKey) null else MadeOnUse { lookUp() }, if (learner.useHabits) table.saved else SavedPhrases(table.dictionary, im, { null }), sharedWords(table, im), language, uptimeMillis,
                phrases = phraseBook(), clock = learnClock::calendar, lookUpMode = lookUpMode(im),
                // a slip read where the key would lead nowhere (empty code, 顶字 at the key's edge): the shared typo setting
                slips = if (s.typos) TableSlips.SLIPS else null,
            ),
            between = Action.Pick(0),
            blocked = glideHeldBack { false },
            // under its code, as the table blocks its own
            blockOther = { r -> { learner.openHabits().blockUnder(Code(Kinds.table(im), r.keys), r.word, on = true) } },
        ) { g, n -> glide.decode(g.path, g.layout, n) }
    }

    // read when first glided: most never glide pinyin
    private val pinyinGlide by lazy { PinyinGlide(assets.pinyinData, user = ::glideUser) }

    // blocks a pinyin glide's other reading as a long press would (the session kept: the host fetches candidates
    // next); null where it does not read so: not offered
    private fun blockOtherReading(r: GlideReading): (() -> Unit)? =
        WordLists.entry(r.word, r.pinyin)?.let { entry -> { lexicon.block(entry) } }

    // a stroke-lookup block is filed as a table block under its code, recorded whatever the habits
    private fun strokeCode(strokes: String) = Code(Kinds.table(StrokeLookup.BLOCK_TABLE), strokes)

    // the user's words in a glide's readings, scored as the sessions score them
    private fun glideUser(): PinyinGlide.User {
        // as installed while habits are off, as the sessions
        val user = sessionUser()
        val scorer = lexicon.prior().scorer(UserScorer(assets.pinyinData.model, user))
        val context = scorer.context(NO_WORD, NO_WORD)
        return PinyinGlide.User(user to user.changes, { word -> scorer.scoreAfter(context, word) }, user, { word -> user.count(word) > 0f }, user::blocked)
    }

    // what a pinyin glide after typed input continues after
    private val syllableBoundary = Action.Key(PinyinGlide.SEPARATOR)

    // a glide's word the session held back, committed anyway: recorded as a pick
    private fun heldBack(pinyin: PinyinSession): (GlideReading, () -> String) -> Unit = { r, first -> pinyin.heldBack(r.word, r.pinyin, first) }

    // what glide alternatives leave out: blocked words and coarse ones not added by the user, as the decoders do
    private fun glideHeldBack(blocked: (String) -> Boolean = lexicon::withheld): (String) -> Boolean {
        val coarse = (CoarseWords.LIST - addedNow()).toHashSet()
        return { blocked(it) || it in coarse }
    }

    /** What a table shares with the other Chinese input methods: word model with the user's words, the user model, 联想 and the 性能档位's sentence models. */
    private fun tableLanguage(im: String): TableLanguage {
        val language = language()
        val data = language.data
        return TableLanguage(
            TextWords(data.model, data.wordIndex, language.scorer),
            language.user,
            reading = { text -> data.wordIndex.find(text).takeIf { it != NO_WORD }?.let(language.readings::reading) },
            predictor = language.predictor,
            reranker = reranker(),
            refiner = refiner(),
            block = lexicon::block,
            forgotten = { learner.forgottenEverywhere(it) },
            emoji = emoji(),
            phrases = wordPhrases(),
            habits = learner.habitsNow(),
            kind = Kinds.table(im),
            simplify = { simplified.simplify(it) },
            // the record's own, not the session's habits (habits off keeps the blocks)
            blockedUnder = learner.openHabits()::blockedUnder,
            held = lexicon.gate()::holds,
        )
    }

    /** The one [ChineseLanguage] for the current user model ([sessionUser]), made by the first handwriting or table session after [invalidate] or a model change, shared by the rest. */
    internal fun language(): ChineseLanguage {
        val data = assets.pinyinData
        val user = sessionUser()
        language?.takeIf { it.data === data && it.user === user }?.let { return it }
        val coarse = coarseWords ?: coarse().also { coarseWords = it }
        return ChineseLanguage(data, user, lexicon.prior(), coarse, assets::charSyllables, settings().prediction).also {
            language = it
            languagesMade++
        }
    }

    // coarse words minus the current added words, made once per added-words set
    private fun coarse(): CoarseWords {
        val added = addedNow()
        lastCoarse?.takeIf { lastCoarseAdded == added }?.let { return it }
        val data = assets.pinyinData
        return CoarseWords.of({ data.wordIndex.find(it) }, CoarseWords.LIST - added).also {
            lastCoarse = it
            lastCoarseAdded = added
            coarseMade++
        }
    }

    /** A pinyin-family session differing only in [segmenter]; built-in phrases only on full pinyin's keyboards ([kind] full: 26, 14 and nine keys). */
    private fun pinyinSession(
        segmenter: Segmenter,
        spell: Boolean,
        keysShown: Boolean = false,
        chaizi: PartsLookup = PartsLookup { emptyList() },
        kind: String = Kinds.FULL,
    ): PinyinSession {
        val s = settings()
        val user = sessionUser()
        return PinyinSession(
            assets.pinyinData, segmenter, spell = spell, keysShown = keysShown,
            pageSize = s.pageSize, user = user, prediction = s.prediction, spacePicksPrediction = s.spacePicksPrediction, uptime = uptimeMillis,
            prior = lexicon.prior(), phraseBook = phraseBook(),
            builtInPhrases = s.builtInPhrases, commonWords = commonWords(kind),
            reranker = reranker(), refiner = refiner(), block = lexicon::block, emoji = emoji(), chaizi = chaizi, now = learnClock::calendar,
            charReadings = assets::charSyllables, extensions = { assets.decoderExtensions(user) }, addedWords = ::addedNow, coarse = coarse(),
            learnTo = LearnTo(learner.habitsNow(), kind, simplified::simplify),
        )
    }

    /** A session made when first told a key or context (a table's lookup, which most never use); till then it shows nothing and keeps its learning and field flags. */
    private class MadeOnUse(make: () -> Session) : Session {
        private var flags: Int? = null
        private val lazySession = lazy {
            make().also { s ->
                s.learning = learning
                flags?.let(s::fieldFlags)
            }
        }
        private val session: Session by lazySession

        private val made: Session? get() = if (lazySession.isInitialized()) lazySession.value else null

        override var learning = true
            set(value) {
                field = value
                made?.learning = value
            }

        override fun apply(action: Action) = session.apply(action)
        override fun reads(c: Char) = session.reads(c)
        override fun endsInput(c: Char) = session.endsInput(c)
        override fun handBack() = session.handBack()

        override fun fieldFlags(flags: Int) {
            this.flags = flags
            made?.fieldFlags(flags)
        }

        override fun candidates(from: Int, count: Int) = made?.candidates(from, count).orEmpty()
        override fun offers(index: Int) = made?.offers(index).orEmpty()
        override fun why(index: Int) = made?.why(index)
        override fun lookedUp(index: Int) = made?.lookedUp(index)
        override fun readingOf(index: Int) = made?.readingOf(index)
        override fun reread() = made?.reread()
        override fun passedOn() = made?.passedOn() ?: Unit
        override fun typed(text: String) = made?.typed(text) ?: Unit
        override val lastCommit get() = made?.lastCommit
        override val rereads get() = made?.rereads ?: false
    }

    // the user's words and blocks a table offers; habits off: only blocks and enabled dictionaries, as pinyin's fresh model
    private fun sharedWords(table: TableRegistry.Table, im: String): SharedWords {
        val shared = if (learner.useHabits) tables.shared(table, im) else tables.dictionariesOnly(table, im)
        shared.prepare()
        return shared
    }

    // custom phrases for every session but handwriting: built-ins alone while habits are off
    private fun phraseBook() = if (learner.useHabits) lexicon.added.phrases() else lexicon.added.freshPhrases()

    // none with habits off (借手机), nor in zhuyin, whose keys are not the pinyin the user keys words by
    private fun commonWords(kind: String) =
        if (learner.useHabits && kind != Kinds.ZHUYIN_NINE && kind != Kinds.ZHUYIN_DACHEN) lexicon.added.commonWords() else CommonWordBook.none()

    // none while habits are off
    private fun addedNow(): Set<String> = if (learner.useHabits) lexicon.addedTexts() else emptySet()

    // as installed except blocks and turned-off predictions while habits are off
    private fun sessionUser(): UserModel = if (learner.useHabits) {
        lexicon.user()
    } else {
        lexicon.freshUser().also { it.predictionsOff = learner::predictionOff }
    }

    /** None with the setting off. */
    private fun emoji(): (String) -> List<String> = if (settings().emoji) assets::emojiOf else { _ -> emptyList() }

    /** Built-in phrase texts for tables at learning time; none with the setting off; user edits left out while habits are off. */
    private fun wordPhrases(): (String) -> List<String> {
        if (!settings().builtInPhrases) return { _ -> emptyList() }
        // the book the session was made with (switching habits or rereading phrases drops sessions): picked at its first word
        val book by lazy(LazyThreadSafetyMode.NONE) { phraseBook() }
        return { word -> book.builtInsOf(word, learnClock::calendar) }
    }

    /** Characters by parts' pinyin, minus blocked or held-back ones; none with the setting off. */
    private fun chaizi(): PartsLookup = if (!settings().chaizi) PartsLookup { emptyList() } else object : PartsLookup {
        override fun of(letters: String) = assets.chaiziOf(letters).filterNot { lexicon.withheld(it.first) }

        // a polyphone blocked in the reading 候选词注音 draws
        override fun shownIn(text: String, before: String, hint: String?) = candidateReadings.oneOf(text, before, hint)?.let { Syllables.spelling(it.syllable) }
    }

    /** One reranker for all sessions: all run on the fcitx thread, one input at a time, restarting for another input. */
    private fun reranker(): Reranker? = if (settings().modelLevel >= ModelLevel.MEDIUM) assets.sentenceModel.reranker(Reranker.LIMIT) else null

    /** Over the larger model, reweighing while the user pauses. */
    private fun refiner(): SentenceRefiner? = if (settings().modelLevel == ModelLevel.HIGH) assets.lateRefiner else null
}

/**
 * What handwriting and tables read by, as pinyin does: [data], the [user] model, the word model with the user's
 * words ([scorer]), the shared [coarse] words and 联想 ([predictor]); one per user model. Its mutable parts are
 * caches of the same answers or read at once by the asker, so sessions can share it.
 */
internal class ChineseLanguage(
    val data: PinyinData,
    val user: UserModel,
    prior: LayerPrior,
    val coarse: CoarseWords,
    charReadings: () -> Map<Int, IntArray>,
    prediction: Boolean,
) {
    val scorer = prior.scorer(UserScorer(data.model, user))

    // a learned word's reading: as typed, else the dictionary's likeliest; coarse words hold back predictions only,
    // so one predictor serves both
    val readings = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = charReadings)

    // what may follow, as pinyin offers it: the same setting
    val predictor = readings.takeIf { prediction }
}

// as [s] sets them: 与 X 同码 checks what the session offers
internal fun tableOptions(method: TableMethod, s: EngineSettings): TableOptions = s.tables[method.group]?.applyTo(method.options) ?: method.options
