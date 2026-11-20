/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** `conf/punctuation.conf`: [enabled] is the toggle (saved, hidden); the hotkey is fixed at Ctrl+. */
data class PunctuationConfig(
    val enabled: Boolean = true,
    // off by default, as the app always set it: a Chinese comma after 3 stays one
    val halfWidthAfterLatinOrNumber: Boolean = false,
    val typePairedTogether: Boolean = false,
)

/**
 * fcitx's `Punctuation` for its one table (zh_CN, which every input method asks for), plugged into the
 * [KeyPipeline]; the key router asks it for marks as `androidengine` calls `IPunctuation`. Rebuilding state
 * from surrounding text is not ported: the frontend never sends it.
 */
class Punctuation(
    profile: PunctuationProfile,
    config: PunctuationConfig,
    private val statusArea: StatusArea,
    private val tip: (Tip) -> Unit,
    private val save: (PunctuationConfig) -> Unit = {},
    /** The marks now typed as the key's own character changed: the keyboard shows them as they will be typed. */
    private val asTyped: (InputContext, Set<String>) -> Unit = { _, _ -> },
) {
    /** `PunctuationState`; the backups only served the surrounding-text rebuild, not ported. */
    class State {
        val lastPuncStack = HashMap<Int, String>()
        var lastIsEngOrDigit = 0
    }

    private val stateProperty = IcProperty { State() }

    // the marks last told (bit i: AS_TYPED[i] types as itself) and their context; another context is told anew
    private var told = 0
    private var toldFor: InputContext? = null
    private var focused: InputContext? = null

    var profile: PunctuationProfile = profile
        private set

    var config: PunctuationConfig = config
        private set

    val action: StatusAction = object : StatusAction("punctuation") {
        override fun icon(ic: InputContext) = if (enabled) "fcitx-punc-active" else "fcitx-punc-inactive"
        override fun shortText(ic: InputContext) = if (enabled) "Full width punctuation" else "Half width punctuation"
        override fun active(ic: InputContext) = enabled
        override fun activate(ic: InputContext) = setEnabled(!enabled, ic)
    }.also(statusArea::register)

    val enabled: Boolean get() = config.enabled

    fun state(ic: InputContext): State = ic.propertyFor(stateProperty)

    /** `setSubConfig`, `reloadConfig`. */
    fun setProfile(profile: PunctuationProfile) {
        this.profile = profile
        // the keys' marks ([keys]) go out with the status: sent anew
        focused?.let(action::updateOn)
    }

    /** `setConfig`; the toggle's own state comes in with it. */
    fun setConfig(config: PunctuationConfig) {
        this.config = config
        save(config)
        focused?.let(action::updateOn)
        // "half width after a letter or number" may have turned: the keyboard shown anew
        focused?.let(::tell)
    }

    /** After a letter or digit, `.` and `,` while that option is on; [push] and the keyboard ([asTyped]) both read this. */
    fun typesAsItself(ic: InputContext, unicode: Int): Boolean =
        enabled && state(ic).lastIsEngOrDigit != 0 && config.halfWidthAfterLatinOrNumber && dontConvertWhenEn(unicode)

    private fun tell(ic: InputContext, always: Boolean = false) {
        // the marks' bits compared; the set made only when told
        var now = 0
        for (i in AS_TYPED.indices) if (typesAsItself(ic, AS_TYPED[i].code)) now = now or (1 shl i)
        // nothing told yet: none is what the keyboard has, no need to tell it
        val sameContext = ic === toldFor || toldFor == null
        if (now == told && sameContext && !always) return
        told = now
        toldFor = ic
        asTyped(ic, AS_TYPED.filterIndexed { i, _ -> now and (1 shl i) != 0 }.mapTo(HashSet()) { it.toString() })
    }

    /** `setEnabled`: saved, and the action shown anew. */
    fun setEnabled(enabled: Boolean, ic: InputContext) {
        if (enabled == config.enabled) return
        setConfig(config.copy(enabled = enabled))
        if (ic !== focused) {
            action.updateOn(ic)
            tell(ic)
        }
    }

    /** A key's first mark, none where off or without the toggle: drawn on the keys, sent with the status. */
    fun keys(ic: InputContext): Map<String, String> = keys(inWhiteList(ic))

    /** Before a context exists (the host's start). */
    fun keys(hasToggle: Boolean): Map<String, String> {
        if (!enabled || !hasToggle) return emptyMap()
        val map = LinkedHashMap<String, String>()
        profile.entries.forEach { map.putIfAbsent(it.key, it.mapping) }
        return map
    }

    /** `inWhiteList`: the input method put the toggle in its status area. */
    fun inWhiteList(ic: InputContext) = statusArea.contains(ic, action)

    /** `CommitFilter`: whether the last character committed is an ASCII letter or digit. */
    val commitFilter = CommitFilter { ic, text ->
        val last = text.lastOrNull()
        state(ic).lastIsEngOrDigit = if (last != null && isAsciiLetterOrDigit(last)) last.code else 0
        tell(ic)
        text
    }

    /** `KeyEventResult`: an unaccepted letter or digit press is the last character, any other clears it. */
    val keyResult = KeyResultListener { ic, e -> onKey(ic, e.key, e.release, e.accepted) }

    val forwardKey = ForwardKeyListener { ic, e -> onKey(ic, e.key, e.release, false) }

    /** `InputContextReset`. */
    val contextListener = object : ContextListener {
        override fun onReset(ic: InputContext) {
            val s = state(ic)
            s.lastIsEngOrDigit = 0
            s.lastPuncStack.clear()
            tell(ic)
        }

        override fun onFocusIn(ic: InputContext) {
            focused = ic
            tell(ic, always = true)
        }

        override fun onFocusOut(ic: InputContext) {
            if (focused === ic) focused = null
        }
    }

    /** Ctrl+. at PostInputMethod: toggles, says so, takes the key; presses only, in the white list only. */
    val hotkey = PostKeyHook { ic, e ->
        if (e.release || !inWhiteList(ic) || e.key != HOTKEY) return@PostKeyHook
        setEnabled(!enabled, ic)
        tip(if (enabled) Tip.PunctuationEnabled else Tip.PunctuationDisabled)
        e.filterAndAccept()
    }

    private fun onKey(ic: InputContext, key: Key, release: Boolean, accepted: Boolean) {
        if (release || accepted) return
        // Key.isDigit takes the keypad's digits too, as fcitx's
        val letterOrDigit = key.isUAZ || key.isLAZ || key.isDigit
        state(ic).lastIsEngOrDigit = if (letterOrDigit) KeySyms.toUnicode(key.sym) else 0
        tell(ic)
    }

    /** `getPunctuation`: the key's first entry, whatever the context; empty when off. */
    fun getPunctuation(unicode: Int): Pair<String, String> = if (enabled) profile.punctuation(unicode) else EMPTY_PAIR

    /** `getPunctuationCandidates`. */
    fun getPunctuationCandidates(unicode: Int): List<String> = if (enabled) profile.punctuations(unicode) else emptyList()

    /** `pushPunctuationV2`: a pair's halves in turn, or both with "type paired together". V1 and `cancelLast` are not ported (unused). */
    fun pushPunctuationV2(ic: InputContext, unicode: Int): Pair<String, String> {
        if (!enabled || typesAsItself(ic, unicode)) return EMPTY_PAIR
        val state = state(ic)
        val result = getPunctuation(unicode)
        if (result.second.isEmpty() || config.typePairedTogether) return result.first to result.second
        if (state.lastPuncStack.remove(unicode) != null) return result.second to ""
        state.lastPuncStack[unicode] = result.first
        return result.first to ""
    }

    companion object {
        /** `Control+period`. */
        val HOTKEY = Key('.'.code, KeyStates.CTRL)
        private val EMPTY_PAIR = "" to ""

        private fun isAsciiLetterOrDigit(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

        // the one place they are written
        private const val AS_TYPED = ".,"

        private fun dontConvertWhenEn(c: Int) = AS_TYPED.any { it.code == c }
    }
}
