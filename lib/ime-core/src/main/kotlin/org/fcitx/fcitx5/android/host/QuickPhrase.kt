/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/** What quick input asks the app and tells it, for the user's own entries ([QuickPhraseBook.mine]). */
interface QuickPhraseHooks {
    /** The user's nine keys' letters, what the nine keys' digits are in a key ([QuickPhraseKeys]). */
    val nineKeys: NineKeyLetters get() = NineKeyLetters.STANDARD

    /** Whether the user's entries are offered: not with the habits off (借手机), a notebook being private. */
    val withMine: Boolean get() = true

    /** [entry] committed at [at] (never in a password field): kept in `mine.used`. */
    fun used(entry: QuickPhraseEntry, at: Long) {}

    /** ＋添加 tapped: the settings page's new entry. */
    fun add() {}

    fun now(): Long = System.currentTimeMillis()

    companion object {
        val NONE = object : QuickPhraseHooks {}
    }
}

/**
 * Quick input, fcitx's quick phrase temp mode (`modules/quickphrase/quickphrasetempmode.cpp`, the
 * module `quickphrase.cpp`/`quickphrase.h`): type a key (`:smile:`, `\alpha`, `yawn`), pick one of the
 * table's matches.
 *
 * What it ports and what it leaves out, the latter because nothing here reaches it:
 * - the trigger keys Super+grave and Super+semicolon (`quickphrase.h:35-39`, no setting, design Q6),
 *   and [trigger] for the keyboard's button (`native-lib.cpp:286-296`);
 * - only the built-in table's matches (`updateUI`, `:354-389`): no module registers a callback
 *   provider, the spell provider's English hints were removed, so every match commits
 *   (`QuickPhraseAction::Commit`) and the selection keys are always the digits, with no modifier
 *   (`Choose Modifier` is `None` and `conf/quickphrase.conf` is not read, design §2);
 * - no `setBuffer`/`setBufferWithRestoreCallback` (`:306-329`, only pinyin's quick phrase hand-off calls
 *   them, and this app's pinyin is not fcitx's), so no restore on Backspace (`:211-219`);
 * - no compose sequences: fcitx on Android is built without xkb (`ENABLE_KEYBOARD` off), so
 *   `processComposeString` is always the empty string (`fcitx/instance.cpp:1925-1929`) and a key
 *   types its own character.
 *
 * This app's own besides (design 051-quickphrase): the user's entries ([QuickPhraseBook.mine]) listed
 * with nothing typed, the last used first, then ＋添加; a key of several letters (the nine keys', the
 * 14 and 18 keys') any of them ([QuickPhraseKeys]); an entry shown as its first line's start
 * ([QuickPhraseExcerpt]) with its key as the comment, committed whole and as it is (no traditional,
 * no full width: [TempModeHost.commitAsIs]); its use kept, but not in a password field.
 *
 * [book] is read once by the caller ([QuickPhraseBook.load]) and replaced with [reload]; it is
 * asked for at the first lookup, not before, so the caller can load it off the host thread meanwhile.
 */
@Suppress("LongParameterList")
class QuickPhrase(
    private val host: TempModeHost,
    @Volatile private var book: () -> QuickPhraseBook,
    /** `_("Quick Phrase: ")`, translated by the app. */
    private val auxLabel: String = "Quick Phrase: ",
    private val hooks: QuickPhraseHooks = QuickPhraseHooks.NONE,
    /** The last candidate with nothing typed, the way to a new entry. */
    private val addLabel: String = "+ Add",
    /** After [auxLabel] with nothing typed and none of the user's entries. */
    private val emptyHint: String = "type a key, or tap + Add for your own",
) : PanelTempMode {

    constructor(host: TempModeHost, table: QuickPhraseTable, auxLabel: String = "Quick Phrase: ") :
        this(host, fixed(QuickPhraseBook(table)), auxLabel)

    /** A key typed reads its code ([QuickPhraseKeys]), not the letter [TempModeManager] would make it. */
    override val readsCodes: Boolean get() = true

    private class State {
        var active = false
        var typed = false
        var text = ""
        var hint = ""
        var prefix = ""
        var str = ""
        var alt = ""
        var key = Key(TempKeys.NONE)
        val buffer = TempInputBuffer(MAX_SIZE)

        // what each character of buffer may be in a key, in step with it
        val keys = ArrayList<Set<Int>>()
        var list: TempCandidateList? = null

        // what list shows, the user's or built-in, one for each candidate; null for ＋添加
        var found: List<QuickPhraseBook.Found?> = emptyList()
    }

    private val state = IcProperty { State() }

    fun reload(book: QuickPhraseBook) {
        this.book = { book }
    }

    /** As [reload], with a book read off the host thread: a lookup before it is read waits for it. */
    fun reload(book: () -> QuickPhraseBook) {
        this.book = book
    }

    /** What the candidate at [globalIndex] of the list shown is: null for ＋添加, or none. */
    fun entryAt(ic: InputContext, globalIndex: Int): QuickPhraseBook.Found? =
        ic.propertyFor(state).takeIf { it.active }?.found?.getOrNull(globalIndex)

    override fun isActive(ic: InputContext) = ic.propertyFor(state).active

    /** `triggerTempMode` (`quickphrasetempmode.cpp:86-94`). */
    override fun trigger(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release || TRIGGER_KEYS.none { TempKeys.check(e.key, it) }) return false
        trigger(ic)
        return true
    }

    /**
     * `QuickPhraseTempMode::trigger` (`:289-304`): active with an empty buffer. [text] follows the aux
     * label until something is typed; [prefix] goes before the buffer in the preedit and the commit;
     * [str] is committed by [key] while nothing is typed, [alt] by Return.
     */
    fun trigger(
        ic: InputContext,
        text: String = "",
        prefix: String = "",
        str: String = "",
        alt: String = "",
        key: Key = Key(TempKeys.NONE),
    ) {
        val s = ic.propertyFor(state)
        s.typed = false
        s.active = true
        s.text = text
        s.prefix = prefix
        s.str = str
        s.alt = alt
        s.key = key
        s.buffer.clear()
        s.keys.clear()
        updateUI(ic)
    }

    /** `keyEvent` (`:96-276`): true when the key was used. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override fun keyEvent(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        val s = ic.propertyFor(state)
        val key = e.key
        // nothing typed, nothing is lit: Space leaves and types itself, never a whole entry by mistake
        if (key.sym == KeySyms.SPACE && key.states == 0 && s.buffer.isEmpty()) {
            reset(ic)
            host.commit(ic, " ")
            return true
        }
        s.list?.let { list ->
            // nothing typed, a digit is a key's: the list is the user's whole notebook, not a page to pick from
            val idx = if (s.buffer.isEmpty()) -1 else TempKeys.digitSelection(key, 0)
            if (idx >= 0 && idx < list.size) {
                list.candidate(idx).select?.invoke(ic)
                return true
            }
            if (key.sym == KeySyms.SPACE && key.states == 0 && list.totalSize > 0) {
                if (list.cursorIndex >= 0) list.candidate(list.cursorIndex).select?.invoke(ic)
                return true
            }
            if (TempKeys.check(key, TempKeys.PREV_PAGE)) {
                if (list.hasPrev) {
                    list.prev()
                    show(ic)
                    return true
                }
                if (list.usedNextBefore) return true
            }
            if (TempKeys.check(key, TempKeys.NEXT_PAGE)) {
                if (list.hasNext) {
                    list.next()
                    show(ic)
                    return true
                }
                if (list.usedNextBefore) return true
            }
            if (list.totalSize > 0 && TempKeys.check(key, TempKeys.PREV_CANDIDATE)) {
                list.prevCandidate()
                show(ic)
                return true
            }
            if (list.totalSize > 0 && TempKeys.check(key, TempKeys.NEXT_CANDIDATE)) {
                list.nextCandidate()
                show(ic)
                return true
            }
        }
        if (TempKeys.isModifier(key) || key.hasModifier) return false
        when (key.sym) {
            KeySyms.ESCAPE -> {
                reset(ic)
                return true
            }
            KeySyms.RETURN, KeySyms.KP_ENTER -> {
                val untouched = !s.typed && s.buffer.isEmpty()
                if (untouched && s.str.isNotEmpty() && s.alt.isNotEmpty()) {
                    host.commit(ic, s.alt)
                } else if (s.buffer.size + s.prefix.toByteArray().size != 0) {
                    host.commit(ic, s.prefix + s.buffer.userInput)
                }
                reset(ic)
                return true
            }
            KeySyms.BACKSPACE -> {
                if (s.buffer.isEmpty()) {
                    reset(ic)
                } else if (s.buffer.backspace()) {
                    s.keys.removeAt(s.buffer.cursor)
                    if (s.buffer.isEmpty()) reset(ic) else updateUI(ic)
                }
                return true
            }
            KeySyms.DELETE -> {
                if (s.buffer.isEmpty()) {
                    reset(ic)
                } else if (s.buffer.del()) {
                    s.keys.removeAt(s.buffer.cursor)
                    if (s.buffer.isEmpty()) reset(ic) else updateUI(ic)
                }
                return true
            }
        }
        if (!s.buffer.isEmpty()) {
            val moveTo = when (key.sym) {
                TempKeys.HOME, TempKeys.KP_HOME -> 0
                TempKeys.END, TempKeys.KP_END -> s.buffer.size
                TempKeys.LEFT, TempKeys.KP_LEFT -> maxOf(s.buffer.cursor - 1, 0)
                TempKeys.RIGHT, TempKeys.KP_RIGHT -> minOf(s.buffer.cursor + 1, s.buffer.size)
                else -> -1
            }
            if (moveTo >= 0) {
                s.buffer.setCursor(moveTo)
                updateUI(ic)
                return true
            }
        }
        val untouched = !s.typed && s.buffer.isEmpty()
        if (untouched && s.str.isNotEmpty() && TempKeys.check(key, s.key)) {
            host.commit(ic, s.str)
            reset(ic)
            return true
        }
        val chr = KeySyms.toUnicode(key.sym)
        if (chr == 0) {
            s.buffer.type("")
        } else {
            // a keyboard's own code that is no key's types nothing and leaves the mode untouched (Enter still commits s.alt)
            val typed = QuickPhraseKeys.of(chr, hooks.nineKeys) ?: return true
            if (s.buffer.type(String(Character.toChars(typed.shown)))) s.keys.add(s.buffer.cursor - 1, typed.can)
        }
        s.typed = true
        updateUI(ic)
        return true
    }

    /**
     * `invokeAction` (`:331-343`): a tap in the preedit moves the buffer's cursor there; any other
     * action, a tap in the prefix, or a context without client preedit leaves the mode.
     */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent): Boolean {
        val s = ic.propertyFor(state)
        val cursor = e.cursor - s.prefix.codePointCount(0, s.prefix.length)
        if (cursor < 0 || e.action != InvokeActionEvent.Action.LeftClick ||
            !ic.has(Capability.PREEDIT)
        ) {
            reset(ic)
            return true
        }
        s.buffer.setCursor(cursor)
        updateUI(ic)
        return true
    }

    /** `reset` (`:278-283`, `QuickPhraseState::reset`, `:67-83`). */
    override fun reset(ic: InputContext) {
        val s = ic.propertyFor(state)
        s.active = false
        s.typed = false
        s.text = ""
        s.buffer.clear()
        s.keys.clear()
        s.found = emptyList()
        s.prefix = ""
        s.str = ""
        s.alt = ""
        s.key = Key(TempKeys.NONE)
        s.list = null
        host.showPanel(ic, TempPanel.EMPTY)
    }

    override fun selectCandidate(ic: InputContext, globalIndex: Int): Boolean {
        val list = ic.propertyFor(state).list ?: return false
        val c = list.candidates.getOrNull(globalIndex) ?: return false
        c.select?.invoke(ic)
        return true
    }

    /** `updateUI` (`:345-428`). */
    private fun updateUI(ic: InputContext) {
        val s = ic.propertyFor(state)
        val book = book()
        val withMine = hooks.withMine
        val empty = s.buffer.isEmpty()
        val found: List<QuickPhraseBook.Found?> = if (empty) {
            // the whole notebook, then the way to add to it: the built-in tables are thousands, not listed
            if (withMine) book.mine.map { QuickPhraseBook.Found(it.key, it.content, true) } + null else emptyList()
        } else {
            book.find(s.keys, withMine)
        }
        s.found = found
        s.list = if (found.isEmpty()) {
            null
        } else {
            val words = found.map { f -> if (f == null) addCandidate() else candidate(f) }
            TempCandidateList(words, TempKeys.PAGE_SIZE, SELECTION_LABELS, resetCursorAfterPaging = true).also {
                if (it.totalSize > 0 && !empty) it.setGlobalCursorIndex(0)
            }
        }
        // a trigger's own text (none of the app's) has the line to itself
        val noneYet = withMine && book.mine.isEmpty()
        s.hint = if (empty && noneYet && s.text.isEmpty()) emptyHint else ""
        show(ic)
    }

    private fun candidate(f: QuickPhraseBook.Found): TempCandidate {
        if (!f.mine) {
            return TempCandidate(f.content, f.key) { c ->
                host.commit(c, f.content)
                reset(c)
            }
        }
        return TempCandidate(QuickPhraseExcerpt.of(f.content), f.key) { c ->
            host.commitAsIs(c, f.content)
            // a password field's use is not kept: nothing typed in one leaves a trace
            if (!c.has(Capability.PASSWORD_OR_SENSITIVE)) {
                val at = hooks.now()
                book().use(f.entry, at)
                hooks.used(f.entry, at)
            }
            reset(c)
        }
    }

    private fun addCandidate() = TempCandidate(addLabel, "") { c ->
        reset(c)
        hooks.add()
    }

    private fun show(ic: InputContext) {
        val s = ic.propertyFor(state)
        // capabilityFlags(), the preedit flag masked as setEnablePreedit left it (inputcontext.cpp:69-75)
        val client = ic.has(Capability.PREEDIT)
        val format = if (client) TextFormatFlag.Underline.flag else TextFormatFlag.NoFlag.flag
        val segments = listOf(s.prefix, s.buffer.userInput).filter { it.isNotEmpty() }
        val preedit = FormattedText(
            segments.toTypedArray(),
            IntArray(segments.size) { format },
            s.prefix.length + s.buffer.cursorInString,
        )
        val aux = TempKeys.text(if (s.typed) auxLabel else auxLabel + s.text + s.hint)
        host.showPanel(
            ic,
            if (client) {
                TempPanel(clientPreedit = preedit, auxUp = aux, candidates = s.list, quickPhrase = true)
            } else {
                TempPanel(preedit = preedit, auxUp = aux, candidates = s.list, quickPhrase = true)
            },
        )
    }

    companion object {
        private const val MAX_SIZE = 30

        private fun fixed(book: QuickPhraseBook): () -> QuickPhraseBook = { book }

        /** `TriggerKey` default (`quickphrase.h:35-39`). */
        val TRIGGER_KEYS = listOf(
            Key(TempKeys.GRAVE, KeyStates.SUPER),
            Key(TempKeys.SEMICOLON, KeyStates.SUPER),
        )

        /** Digits 1–9, 0 with no modifier (`setSelectionKeys`, `:430-465`). */
        private val SELECTION_LABELS = (1..10).map { TempCandidateList.keyToLabel(Key(KeySyms.DIGIT_0 + it % 10)) }
    }
}
