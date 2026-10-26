/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag

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
 * [table] is read once by the caller ([QuickPhraseTable.load]) and replaced with [reload]; it is
 * asked for at the first lookup, not before, so the caller can load it off the host thread meanwhile.
 */
class QuickPhrase(
    private val host: TempModeHost,
    @Volatile private var table: () -> QuickPhraseTable,
    /** `_("Quick Phrase: ")`, translated by the app. */
    private val auxLabel: String = "Quick Phrase: ",
) : PanelTempMode {

    constructor(host: TempModeHost, table: QuickPhraseTable, auxLabel: String = "Quick Phrase: ") :
        this(host, { table }, auxLabel)

    private class State {
        var active = false
        var typed = false
        var text = ""
        var prefix = ""
        var str = ""
        var alt = ""
        var key = Key(TempKeys.NONE)
        val buffer = TempInputBuffer(MAX_SIZE)
        var list: TempCandidateList? = null
    }

    private val state = IcProperty { State() }

    fun reload(table: QuickPhraseTable) {
        this.table = { table }
    }

    /** As [reload], with a table read off the host thread: a lookup before it is read waits for it. */
    fun reload(table: () -> QuickPhraseTable) {
        this.table = table
    }

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
        updateUI(ic)
    }

    /** `keyEvent` (`:96-276`): true when the key was used. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override fun keyEvent(ic: InputContext, e: KeyEvent): Boolean {
        if (e.release) return false
        val s = ic.propertyFor(state)
        val key = e.key
        s.list?.let { list ->
            val idx = TempKeys.digitSelection(key, 0)
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
                    if (s.buffer.isEmpty()) reset(ic) else updateUI(ic)
                }
                return true
            }
            KeySyms.DELETE -> {
                if (s.buffer.isEmpty()) {
                    reset(ic)
                } else if (s.buffer.del()) {
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
        // a keyboard's own code types nothing and leaves the mode untouched (Enter still commits s.alt)
        if (KeySyms.isPrivateUse(chr)) return true
        s.buffer.type(if (chr == 0) "" else String(Character.toChars(chr)))
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
        s.list = if (s.buffer.isEmpty()) {
            null
        } else {
            val words = table().populate(s.buffer.userInput).map { (key, value) ->
                TempCandidate(value, key) { c ->
                    host.commit(c, value)
                    reset(c)
                }
            }
            TempCandidateList(words, TempKeys.PAGE_SIZE, SELECTION_LABELS, resetCursorAfterPaging = true).also {
                if (it.totalSize > 0) it.setGlobalCursorIndex(0)
            }
        }
        show(ic)
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
        val aux = TempKeys.text(if (s.typed) auxLabel else auxLabel + s.text)
        host.showPanel(
            ic,
            if (client) {
                TempPanel(clientPreedit = preedit, auxUp = aux, candidates = s.list)
            } else {
                TempPanel(preedit = preedit, auxUp = aux, candidates = s.list)
            },
        )
    }

    companion object {
        private const val MAX_SIZE = 30

        /** `TriggerKey` default (`quickphrase.h:35-39`). */
        val TRIGGER_KEYS = listOf(
            Key(TempKeys.GRAVE, KeyStates.SUPER),
            Key(TempKeys.SEMICOLON, KeyStates.SUPER),
        )

        /** Digits 1–9, 0 with no modifier (`setSelectionKeys`, `:430-465`). */
        private val SELECTION_LABELS = (1..10).map { TempCandidateList.keyToLabel(Key(KeySyms.DIGIT_0 + it % 10)) }
    }
}
