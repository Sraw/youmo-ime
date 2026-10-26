/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * Simplified to traditional, fcitx's `Chttrans` (`ca/chttrans/chttrans.cpp`) with its OpenCC backend
 * only, as [ChineseConverter] (s2tw). Traditional is chosen per input method: [enabledIm] is
 * `chttrans.conf`'s `EnabledIM`, [save] told of each change.
 *
 * An input method is simplified Chinese when its language is zh_CN ([languageOf]); fcitx would also
 * convert a zh_TW/zh_HK one back to simplified, but this app has none (every Chinese input method is
 * zh_CN, `androidengine.cpp:347-355`), and the traditional-to-simplified data is not shipped.
 * So a context converts when its input method is zh_CN, listed, and the toggle is in its status area.
 *
 * Plugs into the [KeyPipeline] as [hotkey] (Ctrl+Shift+F, PostInputMethod, `:97-131`),
 * [commitFilter] (`:185-194`) and [outputFilter] (`:133-183`: the [WholeTextFilter], applied by the
 * frontend to preedit, aux and every candidate fetched, not tips, as `showTip`; a selected candidate is checked against
 * the filtered text, `androidfrontend.cpp:302-305`). [converter] is loaded once, the first time it is
 * needed; null (data that would not load) leaves text as it is, as a backend that failed to load does
 * (`:302-305`).
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

    /** The file read again (`Chttrans::reloadConfig`, `chttrans.cpp:218`): [enabled] in use, nothing saved. */
    fun reload(enabled: Set<String>) {
        enabledIm = enabled
    }

    val action: StatusAction = object : StatusAction("chttrans") {
        override fun icon(ic: InputContext) = if (isTraditional(ic)) "fcitx-chttrans-active" else "fcitx-chttrans-inactive"
        override fun shortText(ic: InputContext) = if (isTraditional(ic)) "Traditional Chinese" else "Simplified Chinese"
        override fun activate(ic: InputContext) = toggle(ic)
    }.also(statusArea::register)

    /** `inputMethodType` (`:313-321`, `:76-84`) for this app: zh_CN or nothing. */
    private fun isSimplifiedIm(ic: InputContext) = inputMethodOf(ic)?.let(languageOf) == "zh_CN"

    /** `currentType` == Trad (`:339-353`), the same as `convertType` != Other here: converting. */
    fun isTraditional(ic: InputContext): Boolean =
        isSimplifiedIm(ic) && inputMethodOf(ic) in enabledIm

    private fun converts(ic: InputContext) = statusArea.contains(ic, action) && isTraditional(ic)

    /**
     * `toggle` (`:198-213`): the current input method in or out of the list, saved, shown anew:
     * [panelChanged] sends the panel and the app's composing text (`:214-215`), handwriting's among
     * it, converted the new way at once.
     */
    fun toggle(ic: InputContext) {
        if (!statusArea.contains(ic, action) || !isSimplifiedIm(ic)) return
        val im = inputMethodOf(ic) ?: return
        enabledIm = if (im in enabledIm) enabledIm - im else enabledIm + im
        save(enabledIm)
        action.updateOn(ic)
        panelChanged(ic)
    }

    /** `convert` (`:302-311`), simplified to traditional. */
    fun convert(text: String): String = converter?.convert(text) ?: text

    val hotkey = PostKeyHook { ic, e ->
        if (e.release || e.key != HOTKEY) return@PostKeyHook
        if (!statusArea.contains(ic, action) || !isSimplifiedIm(ic)) return@PostKeyHook
        // what it was before the toggle decides the tip (:105-107)
        val toTraditional = !isTraditional(ic)
        toggle(ic)
        tip(if (toTraditional) Tip.TraditionalEnabled else Tip.SimplifiedEnabled)
        e.filterAndAccept()
    }

    val commitFilter = CommitFilter { ic, text -> if (converts(ic)) convert(text) else text }

    val outputFilter: OutputFilter = WholeTextFilter(::converts, ::convert)

    companion object {
        /** `Control+Shift+F` (`chttrans.h:52-53`), as `Key::normalize` leaves Ctrl+Shift+f. */
        val HOTKEY = Key('F'.code, KeyStates.CTRL or KeyStates.SHIFT)
    }
}
