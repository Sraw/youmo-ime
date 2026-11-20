/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

interface QuickPhraseHooks {
    /** What the nine keys' digits are in a key ([QuickPhraseKeys]). */
    val nineKeys: NineKeyLetters get() = NineKeyLetters.STANDARD

    /** Not with the habits off (借手机): a notebook is private. */
    val withMine: Boolean get() = true

    /** Never in a password field. */
    fun used(entry: QuickPhraseEntry, at: Long) {}

    fun add() {}

    fun now(): Long = System.currentTimeMillis()

    companion object {
        val NONE = object : QuickPhraseHooks {}
    }
}

/**
 * Quick input, fcitx's quick phrase temp mode ported: only the built-in table's matches (no callback providers,
 * so every match commits, the digits select) and no compose (Android's fcitx has no xkb). Added: the user's
 * entries listed with nothing typed, multi-letter keys ([QuickPhraseKeys]), entries committed as is.
 * [book] is first asked at the first lookup, so the caller can load it off the host thread.
 */
@Suppress("LongParameterList")
class QuickPhrase(
    private val host: TempModeHost,
    @Volatile private var book: () -> QuickPhraseBook,
    private val auxLabel: String = "Quick Phrase: ",
    private val hooks: QuickPhraseHooks = QuickPhraseHooks.NONE,
    private val addLabel: String = "+ Add",
    private val emptyHint: String = "type a key, or tap + Add for your own",
) : TempMode {

    constructor(host: TempModeHost, table: QuickPhraseTable, auxLabel: String = "Quick Phrase: ") :
        this(host, fixed(QuickPhraseBook(table)), auxLabel)

    /** A key reads its code ([QuickPhraseKeys]), not the letter [TempModeManager] would make it. */
    override val readsCodes: Boolean get() = true

    private class State {
        var active = false
        var typed = false
        var hint = ""
        val buffer = TempInputBuffer(MAX_SIZE)

        // in step with buffer: edited through the methods below
        val keys = ArrayList<Set<Int>>()

        fun backspace(): Boolean = buffer.backspace().also { if (it) keys.removeAt(buffer.cursor) }

        fun delete(): Boolean = buffer.del().also { if (it) keys.removeAt(buffer.cursor) }

        fun type(shown: Int, can: Set<Int>): Boolean = buffer.type(String(Character.toChars(shown))).also { if (it) keys.add(buffer.cursor - 1, can) }
        var list: TempCandidateList? = null

        // one for each candidate; null for ＋添加
        var found: List<QuickPhraseBook.Found?> = emptyList()
    }

    private val state = IcProperty { State() }

    fun reload(book: QuickPhraseBook) {
        this.book = { book }
    }

    /** A lookup before it is read waits for it. */
    fun reload(book: () -> QuickPhraseBook) {
        this.book = book
    }

    /** If the mode is on in [ic]; what is typed, the page and the lit one stay. */
    fun refresh(ic: InputContext) {
        val s = ic.propertyFor(state)
        if (s.active) updateUI(ic, kept = s.list)
    }

    /** Null for ＋添加, or none. */
    fun entryAt(ic: InputContext, globalIndex: Int): QuickPhraseBook.Found? =
        ic.propertyFor(state).takeIf { it.active }?.found?.getOrNull(globalIndex)

    override fun isActive(ic: InputContext) = ic.propertyFor(state).active

    /** `triggerTempMode`. */
    override fun trigger(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release || TRIGGER_KEYS.none { TempKeys.check(e.key, it) }) return false
        trigger(ic)
        return true
    }

    /** `QuickPhraseTempMode::trigger`; fcitx's text/prefix/key arguments only come from pinyin's hand-off, never here. */
    fun trigger(ic: InputContext) {
        val s = ic.propertyFor(state)
        s.typed = false
        s.active = true
        s.buffer.clear()
        s.keys.clear()
        updateUI(ic)
    }

    private fun nothingTypedOrLit(s: State): Boolean = s.buffer.isEmpty() && (s.list?.cursorIndex ?: -1) < 0

    /** `keyEvent`: true when the key was used. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override fun keyEvent(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        val s = ic.propertyFor(state)
        val key = e.key
        // nothing typed or lit: Space leaves and types itself, never a whole entry by mistake
        if (key.sym == KeySyms.SPACE && key.states == 0 && nothingTypedOrLit(s)) {
            reset(ic)
            host.commit(ic, " ")
            return true
        }
        s.list?.let { list ->
            // nothing typed, a digit is a key's: the list is the whole notebook, not a page
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
                if (!s.buffer.isEmpty()) host.commit(ic, s.buffer.userInput)
                reset(ic)
                return true
            }
            KeySyms.BACKSPACE -> {
                if (s.buffer.isEmpty()) {
                    reset(ic)
                } else if (s.backspace()) {
                    if (s.buffer.isEmpty()) reset(ic) else updateUI(ic)
                }
                return true
            }
            KeySyms.DELETE -> {
                if (s.buffer.isEmpty()) {
                    reset(ic)
                } else if (s.delete()) {
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
        val chr = KeySyms.toUnicode(key.sym)
        if (chr == 0) {
            s.buffer.type("")
        } else {
            // a keyboard's own code that is no key's types nothing and leaves the mode untouched
            val typed = QuickPhraseKeys.of(chr, hooks.nineKeys) ?: return true
            s.type(typed.shown, typed.can)
        }
        s.typed = true
        updateUI(ic)
        return true
    }

    /** `invokeAction`: a preedit tap moves the buffer's cursor; else (or without client preedit) the mode leaves. */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent): Boolean {
        val s = ic.propertyFor(state)
        val cursor = e.cursor
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

    /** `reset`. */
    override fun reset(ic: InputContext) {
        val s = ic.propertyFor(state)
        s.active = false
        s.typed = false
        s.buffer.clear()
        s.keys.clear()
        s.found = emptyList()
        s.list = null
        host.showPanel(ic, TempPanel.EMPTY)
    }

    /** `updateUI`; [kept]'s page and lit candidate are kept where they still are. */
    private fun updateUI(ic: InputContext, kept: TempCandidateList? = null) {
        val s = ic.propertyFor(state)
        val book = book()
        val withMine = hooks.withMine
        val empty = s.buffer.isEmpty()
        val found: List<QuickPhraseBook.Found?> = if (empty) {
            // the built-in tables are thousands, not listed
            if (withMine) book.mine.map { QuickPhraseBook.Found.of(it) } + null else emptyList()
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
                if (kept != null) keep(it, kept)
            }
        }
        val noneYet = withMine && book.mine.isEmpty()
        s.hint = if (empty && noneYet) emptyHint else ""
        show(ic)
    }

    private fun keep(list: TempCandidateList, old: TempCandidateList) {
        while (list.currentPage < old.currentPage && list.hasNext) list.next()
        if (old.globalCursorIndex >= 0) list.setGlobalCursorIndex(minOf(old.globalCursorIndex, list.totalSize - 1))
    }

    private fun candidate(f: QuickPhraseBook.Found): TempCandidate {
        if (!f.mine) {
            return TempCandidate(f.content, f.key) { c ->
                host.commit(c, f.content)
                reset(c)
            }
        }
        // as is, so no traditional; its note tells `#wz` 百度 from `#wz` 新浪
        return TempCandidate(QuickPhraseExcerpt.of(f.content), { f.note.ifEmpty { f.key } }, asIs = true, select = { c ->
            host.commitAsIs(c, f.content)
            // a password field's use is not kept: nothing typed in one leaves a trace
            if (!c.has(Capability.PASSWORD_OR_SENSITIVE)) {
                val at = hooks.now()
                book().use(f.entry, at)
                hooks.used(f.entry, at)
            }
            reset(c)
        })
    }

    private fun addCandidate() = TempCandidate(addLabel, "") { c ->
        reset(c)
        hooks.add()
    }

    private fun show(ic: InputContext) {
        val s = ic.propertyFor(state)
        // capabilityFlags(), the preedit flag masked as setEnablePreedit left it
        val client = ic.has(Capability.PREEDIT)
        val format = if (client) TextFormatFlag.Underline.flag else TextFormatFlag.NoFlag.flag
        val segments = listOf(s.buffer.userInput).filter { it.isNotEmpty() }
        val preedit = FormattedText(
            segments.toTypedArray(),
            IntArray(segments.size) { format },
            s.buffer.cursorInString,
        )
        val aux = TempKeys.text(if (s.typed) auxLabel else auxLabel + s.hint)
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

        /** `TriggerKey` default. */
        val TRIGGER_KEYS = listOf(
            Key(TempKeys.GRAVE, KeyStates.SUPER),
            Key(TempKeys.SEMICOLON, KeyStates.SUPER),
        )

        /** `setSelectionKeys`. */
        private val SELECTION_LABELS = (1..10).map { TempCandidateList.keyToLabel(Key(KeySyms.DIGIT_0 + it % 10)) }
    }
}
