/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * fcitx's `Chttrans`, OpenCC backend only ([ChineseConverter], s2tw), per input method ([enabledIm], `EnabledIM`).
 * Every input method here is zh_CN and no traditional-to-simplified data ships, so a context converts when its input
 * method is listed and the toggle is in its status area. [outputFilter] covers preedit, aux and candidates, not tips.
 * A [converter] that would not load leaves text as is.
 */
@Suppress("LongParameterList")
class Chttrans(
    enabledIm: Set<String>,
    private val statusArea: StatusArea,
    private val inputMethodOf: (InputContext) -> String?,
    private val languageOf: (String) -> String?,
    converter: () -> ChineseConverter?,
    private val tip: (Tip) -> Unit,
    private val panelChanged: (InputContext) -> Unit,
    private val save: (Set<String>) -> Unit = {},
) {
    private val converter by lazy(LazyThreadSafetyMode.NONE, converter)

    var enabledIm: Set<String> = enabledIm
        private set

    /** `Chttrans::reloadConfig`: nothing saved. */
    fun reload(enabled: Set<String>) {
        enabledIm = enabled
    }

    val action: StatusAction = object : StatusAction("chttrans") {
        override fun icon(ic: InputContext) = if (isTraditional(ic)) "fcitx-chttrans-active" else "fcitx-chttrans-inactive"
        override fun shortText(ic: InputContext) = if (isTraditional(ic)) "Traditional Chinese" else "Simplified Chinese"
        override fun active(ic: InputContext) = isTraditional(ic)
        override fun activate(ic: InputContext) = toggle(ic)
    }.also(statusArea::register)

    /** `inputMethodType` for this app: zh_CN or nothing. */
    private fun isSimplifiedIm(ic: InputContext) = inputMethodOf(ic)?.let(languageOf) == "zh_CN"

    /** `currentType` == Trad. */
    fun isTraditional(ic: InputContext): Boolean =
        isSimplifiedIm(ic) && inputMethodOf(ic) in enabledIm

    private fun converts(ic: InputContext) = statusArea.contains(ic, action) && isTraditional(ic)

    /** [panelChanged] sends the panel and composing text (handwriting's too), converted the new way at once. */
    fun toggle(ic: InputContext) {
        if (!statusArea.contains(ic, action) || !isSimplifiedIm(ic)) return
        val im = inputMethodOf(ic) ?: return
        enabledIm = if (im in enabledIm) enabledIm - im else enabledIm + im
        save(enabledIm)
        action.updateOn(ic)
        panelChanged(ic)
    }

    /** `convert`. */
    fun convert(text: String): String = converter?.convert(text) ?: text

    val hotkey = PostKeyHook { ic, e ->
        if (e.release || e.key != HOTKEY) return@PostKeyHook
        if (!statusArea.contains(ic, action) || !isSimplifiedIm(ic)) return@PostKeyHook
        // what it was before the toggle decides the tip
        val toTraditional = !isTraditional(ic)
        toggle(ic)
        tip(if (toTraditional) Tip.TraditionalEnabled else Tip.SimplifiedEnabled)
        e.filterAndAccept()
    }

    val commitFilter = CommitFilter { ic, text -> if (converts(ic)) convert(text) else text }

    val outputFilter: OutputFilter = WholeTextFilter(::converts, ::convert)

    companion object {
        /** As `Key::normalize` leaves Ctrl+Shift+f. */
        val HOTKEY = Key('F'.code, KeyStates.CTRL or KeyStates.SHIFT)
    }
}
