/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * `conf/punctuation.conf` (`ca/punctuation/punctuation.h:36-46`): [enabled] is the toggle (saved,
 * hidden), the other two the options page's. The hotkey is fixed at Ctrl+. (Q6).
 */
data class PunctuationConfig(
    val enabled: Boolean = true,
    val halfWidthAfterLatinOrNumber: Boolean = true,
    val typePairedTogether: Boolean = false,
)

/**
 * The punctuation module, fcitx's `Punctuation` (`ca/punctuation/punctuation.cpp`), for the one
 * table there is (zh_CN; fcitx keys its profiles by language and every input method asks for zh_CN,
 * `androidengine.cpp:518-533`).
 *
 * Plugs into the [KeyPipeline] as its [commitFilter], [keyResult], [forwardKey], [contextListener]
 * and [hotkey]; the engine's key router asks it for marks ([pushPunctuationV2] and the rest), as
 * `androidengine` calls `IPunctuation`. Per-context state ([State]) is an [IcProperty]. The toggle
 * is in the [statusArea] as [action]; [save] is told of each change to [config] to write it out.
 *
 * Not ported: rebuilding the state from the surrounding text (`:277-286,304-360`): the frontend
 * never sets the capability nor sends the text, so fcitx never gets there either.
 */
class Punctuation(
    profile: PunctuationProfile,
    config: PunctuationConfig,
    private val statusArea: StatusArea,
    private val tip: (Tip) -> Unit,
    private val save: (PunctuationConfig) -> Unit = {},
) {
    /** `PunctuationState` (`:64-72`); the backups only serve the surrounding text rebuild, not ported. */
    class State {
        val lastPuncStack = HashMap<Int, String>()
        var lastIsEngOrDigit = 0
        var notConverted = 0
    }

    private val stateProperty = IcProperty { State() }

    var profile: PunctuationProfile = profile
        private set

    var config: PunctuationConfig = config
        private set

    val action: StatusAction = object : StatusAction("punctuation") {
        override fun icon(ic: InputContext) = if (enabled) "fcitx-punc-active" else "fcitx-punc-inactive"
        override fun shortText(ic: InputContext) = if (enabled) "Full width punctuation" else "Half width punctuation"
        override fun activate(ic: InputContext) = setEnabled(!enabled, ic)
    }.also(statusArea::register)

    val enabled: Boolean get() = config.enabled

    fun state(ic: InputContext): State = ic.propertyFor(stateProperty)

    /** A new table: the edit page saved one, or the user's file went away (`setSubConfig`, `reloadConfig`). */
    fun setProfile(profile: PunctuationProfile) {
        this.profile = profile
    }

    /** The options page's change (`setConfig`); the toggle's own state comes in with it. */
    fun setConfig(config: PunctuationConfig) {
        this.config = config
        save(config)
    }

    /** `setEnabled` (`punctuation.h:150-155`): saved, and the action shown anew. */
    fun setEnabled(enabled: Boolean, ic: InputContext) {
        if (enabled == config.enabled) return
        setConfig(config.copy(enabled = enabled))
        action.updateOn(ic)
    }

    /** `inWhiteList` (`:434-436`): the context's input method put the toggle in its status area. */
    fun inWhiteList(ic: InputContext) = statusArea.contains(ic, action)

    /** `CommitFilter` (`:217-228`): whether the last character committed is an ASCII letter or digit. */
    val commitFilter = CommitFilter { ic, text ->
        val last = text.lastOrNull()
        state(ic).lastIsEngOrDigit = if (last != null && isAsciiLetterOrDigit(last)) last.code else 0
        text
    }

    /** `KeyEventResult` (`:229-248`): an unaccepted press of a letter or digit is the last character, any other clears it. */
    val keyResult = KeyResultListener { ic, e -> onKey(ic, e.key, e.release, e.accepted) }

    /** A key the engine forwarded, the same rule (`:249-254`). */
    val forwardKey = ForwardKeyListener { ic, e -> onKey(ic, e.key, e.release, false) }

    /** `InputContextReset` (`:287-302`): the state goes. */
    val contextListener = object : ContextListener {
        override fun onReset(ic: InputContext) {
            val s = state(ic)
            s.lastIsEngOrDigit = 0
            s.notConverted = 0
            s.lastPuncStack.clear()
        }
    }

    /** Ctrl+. at PostInputMethod (`:255-276`): toggles, says so, and takes the key; presses only, in the white list only. */
    val hotkey = PostKeyHook { ic, e ->
        if (e.release || !inWhiteList(ic) || e.key != HOTKEY) return@PostKeyHook
        setEnabled(!enabled, ic)
        tip(if (enabled) Tip.PunctuationEnabled else Tip.PunctuationDisabled)
        e.filterAndAccept()
    }

    private fun onKey(ic: InputContext, key: Key, release: Boolean, accepted: Boolean) {
        if (release || accepted) return
        // Key.isDigit takes the keypad's digits too, as fcitx's (fcitx-utils/key.cpp:412-415)
        val letterOrDigit = key.isUAZ || key.isLAZ || key.isDigit
        state(ic).lastIsEngOrDigit = if (letterOrDigit) KeySyms.toUnicode(key.sym) else 0
    }

    /** `getPunctuation` (`:531-543`): the key's first entry, whatever the context; empty when off. */
    fun getPunctuation(unicode: Int): Pair<String, String> = if (enabled) profile.punctuation(unicode) else EMPTY_PAIR

    /** `getPunctuationCandidates` (`:518-529`): every mark of the key; none when off. */
    fun getPunctuationCandidates(unicode: Int): List<String> = if (enabled) profile.punctuations(unicode) else emptyList()

    /**
     * `pushPunctuation` (`:438-468`): the mark [unicode] types on [ic], a pair's halves in turn. After
     * a letter or digit, `.` and `,` stay as typed when the option says so (empty, remembered for [cancelLast]).
     */
    fun pushPunctuation(ic: InputContext, unicode: Int): String {
        val (text, after) = push(ic, unicode, paired = false) ?: return ""
        check(after.isEmpty())
        return text
    }

    /** `pushPunctuationV2` (`:469-502`): as [pushPunctuation], but both halves at once when "type paired together" is on. */
    fun pushPunctuationV2(ic: InputContext, unicode: Int): Pair<String, String> =
        push(ic, unicode, config.typePairedTogether) ?: EMPTY_PAIR

    private fun push(ic: InputContext, unicode: Int, paired: Boolean): Pair<String, String>? {
        if (!enabled) return null
        val state = state(ic)
        if (state.lastIsEngOrDigit != 0 && config.halfWidthAfterLatinOrNumber && dontConvertWhenEn(unicode)) {
            state.notConverted = unicode
            return null
        }
        val result = getPunctuation(unicode)
        state.notConverted = 0
        if (result.second.isEmpty()) return result.first to ""
        if (paired) return result
        if (state.lastPuncStack.remove(unicode) != null) return result.second to ""
        state.lastPuncStack[unicode] = result.first
        return result.first to ""
    }

    /** `cancelLast` (`:504-516`): the mark a `.` or `,` left as typed would have been; empty otherwise. */
    fun cancelLast(ic: InputContext): String {
        if (!enabled) return ""
        val state = state(ic)
        if (!dontConvertWhenEn(state.notConverted)) return ""
        val result = getPunctuation(state.notConverted)
        state.notConverted = 0
        return result.first
    }

    companion object {
        /** `Control+period` (`punctuation.h:38-39`). */
        val HOTKEY = Key('.'.code, KeyStates.CTRL)
        private val EMPTY_PAIR = "" to ""

        private fun isAsciiLetterOrDigit(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

        private fun dontConvertWhenEn(c: Int) = c == '.'.code || c == ','.code
    }
}
