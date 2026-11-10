/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING
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
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.pinyin.UserLatinWords
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
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordLists

/**
 * Makes the session of each input method as [settings] and the user's habits have it ([Engines]
 * keeps them, made at first use, made anew after [invalidate]): from [assets], the user's words
 * ([lexicon]), the tables, what was learned ([learner]) and English's words. What handwriting and the
 * tables read by is one [ChineseLanguage] for a user model, and the coarse words one for the words
 * added by hand, shared by every session made with them. On the engines' thread.
 */
internal class SessionFactory(
    private val assets: EngineAssets,
    private val lexicon: UserLexicon,
    private val tables: TableRegistry,
    private val learner: LearningFacade,
    private val english: EnglishLexicon,
    private val settings: () -> EngineSettings,
    // the English words learned, as 中英混输 spells them before a key of 全拼's (LatinSync)
    private val latinWords: UserLatinWords,
    // English's data, its warm-up started if it was not (FieldContext)
    private val englishData: () -> EnglishData?,
    // the latest piece of handwriting a session dropped ended ([HandwritingSession.latestPiece])
    private val inkEndedBefore: () -> Int,
    private val uptimeMillis: () -> Long,
    private val learnClock: LearnClock,
    simplifier: () -> Simplifier,
) {
    private val simplified by lazy(LazyThreadSafetyMode.NONE) { simplifier() }

    // the coarse words of pinyin's vocabulary but those the user added, one for handwriting and every
    // table: made again once the sessions are dropped (a word added drops them; a long press's delete
    // of one does not, and these stay as they were, as they always did)
    private var coarseWords: CoarseWords? = null

    // the coarse words made last, and the added words they leave out: a pinyin session made with the
    // same added words takes them, the very words it would make itself (a long press's delete of a
    // word added by hand keeps the sessions: one made after makes its own, as it always did)
    internal var lastCoarse: CoarseWords? = null
        private set
    private var lastCoarseAdded: Set<String>? = null

    // handwriting's and the tables' language, for the user model it was made with
    private var language: ChineseLanguage? = null

    /**
     * How many [ChineseLanguage] and [CoarseWords] were made since these engines were: between two
     * [invalidate]s, one language per user model and one list per set of words added by hand. Read
     * by the tests alone (EnginesSessionFactoryTest), which count what is made, not only what is kept.
     */
    internal var languagesMade = 0
    internal var coarseMade = 0
    internal var lookUpsMade = 0

    // each table's 拼 key, on or off: kept while the engine is, over the sessions made anew
    private val lookUpModes = HashMap<String, TableSession.LookUpMode>()

    /** Whether table [im]'s 拼 key is on ([TableSession.LookUpMode]). */
    fun lookUpMode(im: String): TableSession.LookUpMode = lookUpModes.getOrPut(im) { TableSession.LookUpMode() }

    /** What the sessions share made anew at the next need: the user's words or the settings changed. */
    fun invalidate() {
        coarseWords = null
        lastCoarse = null
        lastCoarseAdded = null
        language = null
    }

    // each of [glides] made a GlideSession or an EnglishSession, which Engines.glide reads the glide for;
    // each that [filtersByStrokes] wrapped in a StrokeFilter, which filters what is shown; each that
    // [writesOnKeys] wrapped, last, in an InkOver, whose handwriting's candidates are none to filter
    fun make(im: String): Session = made(im)
        .let { if (filtersByStrokes(im)) StrokeFilter(it, assets::strokes, settings().pageSize) else it }
        .let { if (writesOnKeys(im)) InkOver(it, ::handwritingSession) else it }

    /** Handwriting's session, as the input method has it and as an [InkOver] writes with. */
    fun handwritingSession(): HandwritingSession {
        val s = settings()
        // read as pinyin reads a sentence: its words, the user's among them, and their counts
        val language = language()
        val data = language.data
        val decoder = HandwritingDecoder(
            data.dictionary, data.vocabulary, data.wordIndex, language.scorer,
            assets::charSyllables, language.user, language.coarse,
        )
        return HandwritingSession(
            decoder, TextWords(data.model, data.wordIndex), s.pageSize, language.user, language.predictor, reranker(), refiner(),
            block = lexicon::block, forgotten = { learner.forgottenEverywhere(it) }, ended = inkEndedBefore(), habits = learner.habitsNow(), simplify = simplified::simplify,
        )
    }

    private fun made(im: String): Session {
        val s = settings()
        return when (im) {
            PINYIN -> {
                val segmenter = PinyinSegmenter(
                    s.fuzzy, s.typos, neighbours = s.typos,
                    latin = if (s.latinWords) assets.latinWords else null, userLatin = if (s.latinWords) latinWords else null,
                )
                val pinyin = pinyinSession(segmenter, spell = false, chaizi = chaizi())
                val looked = ForgetsInTables(
                    StrokeLookup(
                        pinyin, assets::strokes, assets::charReading, s.pageSize, blocked = lexicon::isBlocked, follow = pinyin::follow, retype = pinyin::typeAll,
                        blockedUnder = { strokes -> learner.openHabits().blockedUnder(strokeCode(strokes)) },
                        blockUnder = { strokes, text -> learner.openHabits().blockUnder(strokeCode(strokes), text, on = true) },
                    ),
                )
                GlideSession(looked, syllableBoundary, blocked = glideHeldBack(), heldBack = heldBack(pinyin), blockOther = ::blockOtherReading) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            // the 14 keys: no glide, nor 笔画查字 (u is on UI, no stroke key of its own). 简拼 only where nothing
            // else is read, as on the nine keys. Top-1, both models on, the same samples (one exact reading each, so
            // no 简拼 among them; 2026-10-25): full pinyin / 14 keys / nine keys 97.0 / 94.6 / 88.8% on pinyin.tsv's
            // 429, 73.4 / 61.3 / 53.6% on chat's held-out 983. g14 and g14-strict alike there; strict is faster
            PINYIN_KEYS -> ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = NineKeyLetters.G14), spell = true))
            // the 18 keys: 双拼's groups (I1 Q4: G18 first, measured; another grouping if it falls short), ; no
            // letter of pinyin's; as the 14 keys otherwise, learned under qk:<G18's tag>
            PINYIN_KEYS18 -> ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = NineKeyLetters.G18), spell = true))
            // a layout's own groups (a theme's 17 keys, 060-render-p3 T9b): as the 14 keys, learned under qk:<their tag>;
            // its keyboard the one in use, its codes read by it (typedLetter). None told (Engines swallows their codes
            // then): G14's, which reads none of them
            PINYIN_KEYS_OWN -> {
                val own = s.ownKeyboard?.also(NineKeyLetters::inUse) ?: NineKeyLetters.G14
                ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = own), spell = true))
            }
            // pinyin in zhuyin (design 051 §3.3): the nine keys' way with symbols for letters, a tone a separator
            // the decoder weighs; learned under zy:nine and zy:dachen, apart from pinyin's keyboards
            ZHUYIN_NINE -> ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = ZhuyinKeys.NINE, toneAllows = assets.pinyinData.tones::allows), spell = true, kind = Kinds.ZHUYIN_NINE))
            ZHUYIN -> ForgetsInTables(pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = ZhuyinKeys.DACHEN, toneAllows = assets.pinyinData.tones::allows), spell = true, kind = Kinds.ZHUYIN_DACHEN))
            ENGLISH -> {
                val data = englishData()
                EnglishSession(
                    data, pageSize = s.pageSize, user = if (learner.useHabits) english.user() else EnglishUser.OFF, nineKeys = s.nineKeys,
                    phrases = EnglishPhrases(phraseBook(), learnClock::calendar), blocks = lexicon.englishBlocks,
                )
            }
            HANDWRITING -> handwritingSession()
            // 简拼 only where the digits read nothing else: as common on the evaluation set, and faster
            // glided as full pinyin, over the digits' keys: each letter typed as itself, as one chosen from a key's long press
            T9 -> {
                val pinyin = pinyinSession(T9Segmenter(s.fuzzy, abbreviations = false, letters = s.nineKeys), spell = true)
                GlideSession(
                    ForgetsInTables(pinyin),
                    syllableBoundary,
                    blocked = glideHeldBack(),
                    typed = { c -> if (c == PinyinGlide.SEPARATOR) Action.Key(c, glided = true) else Action.Letter(c, glided = true) },
                    readings = GlideSession.NINE_KEYS_READINGS,
                    heldBack = heldBack(pinyin),
                    blockOther = ::blockOtherReading,
                ) { g, n -> pinyinGlide.decode(g.path, g.layout, n) }
            }
            // the 18, 14 and nine keys: as pinyin's 14 keys, no glide nor 笔画查字; learned under spk: (CommitRecorder)
            SHUANGPIN_KEYS18, SHUANGPIN_KEYS14, SHUANGPIN_NINE -> ForgetsInTables(pinyinSession(GroupedShuangpinSegmenter(s.scheme, KeyboardPair.lettersOf(im), s.fuzzy, s.typos), spell = true, kind = Kinds.shuangpin(s.shuangpin)))
            SHUANGPIN -> {
                val spelling = PinyinGlide.Spelling.shuangpin(s.scheme)
                val pinyin = pinyinSession(ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true, kind = Kinds.shuangpin(s.shuangpin))
                GlideSession(
                    ForgetsInTables(pinyin),
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
        val options = tableOptions(method, s).copy(pageSize = s.pageSize)
        // looking a character up by pinyin learns as full pinyin does (U-28), the table's codes untouched;
        // no phrases, emoji nor 联想 there: what it shows is to be typed by its code. Habits off, as
        // pinyin's own session: nothing learned, nor read of what was (U-93). Every table's: its 拼 key
        // looks up where no key of its codes does; made as it first looks up, most tables never do
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
            // the table's blocks too: a word blocked under a code scores -inf from then on (U-28);
            // keyed on which state as well: a replay or clear makes a new one counting from 0 (by its id, not
            // holding it: F3216)
            version = { language.user?.let { it to it.changes } to learner.learnLog().state.let { it.id to it.tableBlocksVersion } },
            // the words counted, scored anew a glide: their scores move with every word learned
            learned = { text -> language.user?.typed(text) == true },
            // the table's own blocks, under a code, alone: a block in pinyin hides none of them (U-28 C12)
            blocked = { code, text -> text in learner.openHabits().blockedUnder(Code(Kinds.table(im), code)) },
        )
        // a glide after a code typed picks its first candidate: the next code is the glide's
        return GlideSession(
            TableSession(
                table.dictionary, options, MadeOnUse { ForgetsInTables(lookUp()) }, if (learner.useHabits) table.saved else SavedPhrases(table.dictionary, im, { null }), sharedWords(table, im), language, uptimeMillis,
                phrases = phraseBook(), clock = learnClock::calendar, lookUpMode = lookUpMode(im),
            ),
            between = Action.Pick(0),
            blocked = glideHeldBack { false },
            // under its code, as the table blocks its own (U-28)
            blockOther = { r -> { learner.openHabits().blockUnder(Code(Kinds.table(im), r.keys), r.word, on = true) } },
        ) { g, n -> glide.decode(g.path, g.layout, n) }
    }

    // read when first glided: most never glide pinyin
    private val pinyinGlide by lazy { PinyinGlide(assets.pinyinData, user = ::glideUser) }

    // a pinyin glide's other reading's word blocked as it reads, as a long press on it in the list would
    // (the session kept: the host fetches its candidates next); none where it does not read so: not offered (review L3)
    private fun blockOtherReading(r: GlideReading): (() -> Unit)? =
        WordLists.entry(r.word, r.pinyin)?.let { entry -> { lexicon.block(entry) } }

    // a character blocked under strokes is filed as a table's block under its code (U-28), the record's whatever the habits
    private fun strokeCode(strokes: String) = Code(Kinds.table(StrokeLookup.BLOCK_TABLE), strokes)

    // the user's words in a glide's readings, as the sessions score them: added, learned, blocked
    private fun glideUser(): PinyinGlide.User {
        // as installed while habits are off (U-93), as the sessions
        val user = sessionUser()
        val scorer = lexicon.prior().scorer(UserScorer(assets.pinyinData.model, user))
        val context = scorer.context(NO_WORD, NO_WORD)
        return PinyinGlide.User(user to user.changes, { word -> scorer.scoreAfter(context, word) }, user, { word -> user.count(word) > 0f }, user::blocked)
    }

    // what a pinyin's glide after something typed goes on after
    private val syllableBoundary = Action.Key(PinyinGlide.SEPARATOR)

    // a glide's word the session held back, committed all the same: recorded as learning records a pick
    private fun heldBack(pinyin: PinyinSession): (GlideReading, () -> String) -> Unit = { r, first -> pinyin.heldBack(r.word, r.pinyin, first) }

    // what a glide's other readings leave out: the words blocked, and the coarse ones but those the
    // user added, as the decoders hold them back (CoarseWords); [blocked] as the session blocks
    private fun glideHeldBack(blocked: (String) -> Boolean = lexicon::isBlocked): (String) -> Boolean {
        val coarse = (CoarseWords.LIST - addedNow()).toHashSet()
        return { blocked(it) || it in coarse }
    }

    /**
     * What a table shares with the other Chinese input methods: the word model with the user's words,
     * the one user model, 联想 as the setting has it, the sentence models of the 性能档位.
     */
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
            // the record's own, not the habits a session is made with (U-93 keeps the blocks)
            blockedUnder = learner.openHabits()::blockedUnder,
        )
    }

    /**
     * The one [ChineseLanguage] for the user model the sessions read now ([sessionUser]): made with the
     * first of handwriting's and the tables' sessions after [invalidate], or after the model was made
     * anew, and shared by the rest.
     */
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

    // the coarse words but those the user added now, made once for the same added words
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

    /**
     * A session of one of the pinyins, alike but for how [segmenter] reads the keys; the built-in
     * phrases as set, under their keys on full pinyin's keyboards ([kind] full: the 26, 14 and nine keys) alone.
     */
    private fun pinyinSession(
        segmenter: Segmenter,
        spell: Boolean,
        chaizi: (String) -> List<Pair<String, String>> = { emptyList() },
        kind: String = Kinds.FULL,
    ): PinyinSession {
        val s = settings()
        val user = sessionUser()
        return PinyinSession(
            assets.pinyinData, segmenter, spell = spell,
            pageSize = s.pageSize, user = user, prediction = s.prediction, prior = lexicon.prior(), phraseBook = phraseBook(),
            builtInPhrases = s.builtInPhrases,
            reranker = reranker(), refiner = refiner(), block = lexicon::block, emoji = emoji(), chaizi = chaizi, now = learnClock::calendar,
            charReadings = assets::charSyllables, extensions = { assets.decoderExtensions(user) }, addedWords = ::addedNow, coarse = coarse(),
            learnTo = LearnTo(learner.habitsNow(), kind, simplified::simplify),
        )
    }

    /**
     * [pinyin], what a long press forgets there forgotten in every table too, as [TableRegistry.shared] has it:
     * a phrase an older build saved there (U-84). Offered only where something was learned of it (PY-33);
     * what the record has of it, a table's picks too, the pinyin forgets itself. A table's lookup is
     * pinyin wrapped so (review C N3).
     */
    private inner class ForgetsInTables(private val pinyin: Session) : Session by pinyin {
        override fun apply(action: Action): Snapshot {
            // the rest of a whole phrase (U-73) forgets its count alone: a table's word of its text stays
            val forgotten = if (action is Action.Forget && Offer.FORGET in pinyin.offers(action.index) && pinyin.why(action.index)?.whole.isNullOrEmpty()) {
                pinyin.candidates(action.index, 1).firstOrNull()
            } else {
                null
            }
            return pinyin.apply(action).also { if (forgotten != null) tables.forgetInTables(listOf(forgotten.text)) }
        }
    }

    /**
     * The session [make] makes, made when first told a key or a context: a table's lookup, which most
     * tables never use. Till then it has nothing to show, and keeps its learning and field's flags for it.
     */
    private class MadeOnUse(make: () -> Session) : Session {
        private var flags: Int? = null
        private val lazySession = lazy {
            make().also { s ->
                s.learning = learning
                flags?.let(s::fieldFlags)
            }
        }
        private val session: Session by lazySession

        // the session once it is made, null till then
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

    // the user's words a table offers, and the blocks it keeps; while habits are off (U-93) the blocks
    // and the dictionaries turned on alone, as pinyin's fresh model keeps them (review F3412)
    private fun sharedWords(table: TableRegistry.Table, im: String): SharedWords {
        val shared = if (learner.useHabits) tables.shared(table, im) else tables.dictionariesOnly(table, im)
        shared.prepare()
        return shared
    }

    // the custom phrases every session but handwriting offers: the built-in ones alone while habits are off (U-93)
    private fun phraseBook() = if (learner.useHabits) lexicon.added.phrases() else lexicon.added.freshPhrases()

    // the words added by hand, as the sessions offer them: none while habits are off (U-93)
    private fun addedNow(): Set<String> = if (learner.useHabits) lexicon.addedTexts() else emptySet()

    // the user's words the sessions read: as installed, but for the blocks and the predictions turned off, while habits are off (U-93)
    private fun sessionUser(): UserModel = if (learner.useHabits) {
        lexicon.user()
    } else {
        lexicon.freshUser().also { it.predictionsOff = learner::predictionOff }
    }

    /** The emoji a word stands for, as the Chinese input methods offer them: none, the setting off. */
    private fun emoji(): (String) -> List<String> = if (settings().emoji) assets::emojiOf else { _ -> emptyList() }

    /** The built-in phrases' texts a word stands for, as the tables offer them, at learning's time: none, the setting off. */
    private fun wordPhrases(): (String) -> List<String> =
        if (settings().builtInPhrases) { word -> lexicon.added.phrases().builtInsOf(word, learnClock::calendar) } else { _ -> emptyList() }

    /** The characters by their parts' pinyin, as full pinyin offers them, but those blocked: none, the setting off. */
    private fun chaizi(): (String) -> List<Pair<String, String>> =
        if (settings().chaizi) { letters -> assets.chaiziOf(letters).filterNot { lexicon.isBlocked(it.first) } } else { _ -> emptyList() }

    /**
     * The one reranker over the model, for every session: they all run on the fcitx thread, one
     * input typed at a time, and what it ran for one input is started over for another's.
     */
    private fun reranker(): Reranker? = if (settings().modelLevel >= ModelLevel.MEDIUM) assets.sentenceModel.reranker(Reranker.LIMIT) else null

    /** As [reranker], over the larger model, which weighs the readings again while the user pauses. */
    private fun refiner(): SentenceRefiner? = if (settings().modelLevel == ModelLevel.HIGH) assets.lateRefiner else null
}

/**
 * What handwriting and a table read by, as pinyin does (specs/engine.md), one for the sessions made
 * with the same user model: pinyin's [data], the one [user] model, the word model with the user's
 * words ([scorer]), the coarse words held back ([coarse], one for them all), and 联想 ([predictor]).
 * What changes in it as it is read is a cache of the same answers (CoarseWords' merge, keyed by the
 * user model's words) or read at once by the asker (Predictor's `after`), so the sessions share it.
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

    // a word's reading as it is learned: as the user typed it, else the dictionary's likeliest. The
    // coarse words hold back only what is predicted, not a reading: one predictor for both
    val readings = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse, charReadings = charReadings)

    // what may follow, as pinyin offers it: the same setting
    val predictor = readings.takeIf { prediction }
}

// [method]'s options as [s] sets them: what 与 X 同码 checks is what the session offers
internal fun tableOptions(method: TableMethod, s: EngineSettings): TableOptions = s.tables[method.group]?.applyTo(method.options) ?: method.options
