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
    // off by default, as the app always set it (AddonDefaults till 0.4): a Chinese comma after 3 stays one
    val halfWidthAfterLatinOrNumber: Boolean = false,
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
    /**
     * The marks that now type as the key's own character ([typesAsItself]) changed on [InputContext]:
     * the keyboard shows them as they will be typed (060-render-p2 S7 #7).
     */
    private val asTyped: (InputContext, Set<String>) -> Unit = { _, _ -> },
) {
    /** `PunctuationState` (`:64-72`); the backups only serve the surrounding text rebuild, not ported. */
    class State {
        val lastPuncStack = HashMap<Int, String>()
        var lastIsEngOrDigit = 0
        var notConverted = 0
    }

    private val stateProperty = IcProperty { State() }

    // the marks last told through [asTyped] (bit i: AS_TYPED[i] types as itself) and the context they
    // were told for: another context's are told anew. One focused anew is told whatever was told
    // before (another context's state, or the keyboard's of an earlier host)
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
        override fun activate(ic: InputContext) = setEnabled(!enabled, ic)
    }.also(statusArea::register)

    val enabled: Boolean get() = config.enabled

    fun state(ic: InputContext): State = ic.propertyFor(stateProperty)

    /** A new table: the edit page saved one, or the user's file went away (`setSubConfig`, `reloadConfig`). */
    fun setProfile(profile: PunctuationProfile) {
        this.profile = profile
        // the keys' marks ([keys]) go out with the status: sent anew
        focused?.let(action::updateOn)
    }

    /** The options page's change (`setConfig`); the toggle's own state comes in with it. */
    fun setConfig(config: PunctuationConfig) {
        this.config = config
        save(config)
        // on or off: the status, and the keys' marks with it ([keys]), sent anew
        focused?.let(action::updateOn)
        // "half width after a letter or number" may have turned: the keyboard shown anew
        focused?.let(::tell)
    }

    /**
     * Whether the key [unicode] now types its own character, not its mark: after a letter or digit,
     * `.` and `,` while "half width after a letter or number" is on (`:444-448`). What [push] types
     * and what the keyboard shows ([asTyped]) both come from here.
     */
    fun typesAsItself(ic: InputContext, unicode: Int): Boolean =
        enabled && state(ic).lastIsEngOrDigit != 0 && config.halfWidthAfterLatinOrNumber && dontConvertWhenEn(unicode)

    // the marks [typesAsItself] now, told when they change
    private fun tell(ic: InputContext, always: Boolean = false) {
        // no set made a key: the marks' bits compared, the set made when they are told
        var now = 0
        for (i in AS_TYPED.indices) if (typesAsItself(ic, AS_TYPED[i].code)) now = now or (1 shl i)
        // nothing told yet: none is what the keyboard has, no need to tell it
        val sameContext = ic === toldFor || toldFor == null
        if (now == told && sameContext && !always) return
        told = now
        toldFor = ic
        asTyped(ic, AS_TYPED.filterIndexed { i, _ -> now and (1 shl i) != 0 }.mapTo(HashSet()) { it.toString() })
    }

    /** `setEnabled` (`punctuation.h:150-155`): saved, and the action shown anew. */
    fun setEnabled(enabled: Boolean, ic: InputContext) {
        if (enabled == config.enabled) return
        // the focused context's status and marks setConfig sends
        setConfig(config.copy(enabled = enabled))
        if (ic !== focused) {
            action.updateOn(ic)
            tell(ic)
        }
    }

    /**
     * What each key types now on [ic] (a key's first mark), none where punctuation is off or its input method
     * has not the toggle: what the keyboard draws on the keys, sent with the status (060-render-p2 S7a).
     */
    fun keys(ic: InputContext): Map<String, String> = keys(inWhiteList(ic))

    /** [keys] for an input method that has the toggle ([hasToggle]: a Chinese one) or not, before a context has one (the host's start). */
    fun keys(hasToggle: Boolean): Map<String, String> {
        if (!enabled || !hasToggle) return emptyMap()
        val map = LinkedHashMap<String, String>()
        profile.entries.forEach { map.putIfAbsent(it.key, it.mapping) }
        return map
    }

    /** `inWhiteList` (`:434-436`): the context's input method put the toggle in its status area. */
    fun inWhiteList(ic: InputContext) = statusArea.contains(ic, action)

    /** `CommitFilter` (`:217-228`): whether the last character committed is an ASCII letter or digit. */
    val commitFilter = CommitFilter { ic, text ->
        val last = text.lastOrNull()
        state(ic).lastIsEngOrDigit = if (last != null && isAsciiLetterOrDigit(last)) last.code else 0
        tell(ic)
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
            tell(ic)
        }

        // focused (an app's field, a context switched to): told whatever was told last
        override fun onFocusIn(ic: InputContext) {
            focused = ic
            tell(ic, always = true)
        }

        override fun onFocusOut(ic: InputContext) {
            if (focused === ic) focused = null
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
        tell(ic)
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
        if (typesAsItself(ic, unicode)) {
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

        // the keys left as typed after a letter or digit (`:444-448`), the one place they are written
        private const val AS_TYPED = ".,"

        private fun dontConvertWhenEn(c: Int) = AS_TYPED.any { it.code == c }
    }
}
