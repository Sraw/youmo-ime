/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/**
 * ⋯'s 键盘 (I1 Q1): the keyboards the input method now on offers ([ImKeyboards.choices]) and the layouts of the user's
 * it may be typed on (061-custom-layout §5), the one
 * it is typed on ticked, one picked set as its page sets it (the input view is made anew for it,
 * FcitxInputMethodService). [im] is asked as it opens: the input method as the keyboard last heard.
 * Public for a toolbar button of its own too: [show] beside whatever view opens it.
 */
class KeyboardPicker(private val context: Context, private val im: () -> String) {

    /** One keyboard of the list: a built-in one, or ([layout]) a layout of the user's ([ImKeyboard.CUSTOM]). */
    data class Offered(val keyboard: ImKeyboard, val layout: CustomChoice? = null)

    /**
     * The keyboards [im] offers, then the layouts of the user's it may be typed on (061-custom-layout §5.1; a table: the
     * full keyboard and those), the one in use ([ImKeyboards.effective]) second: none where it has no choice.
     */
    fun offered(): Pair<List<Offered>, Offered>? {
        val name = im()
        val scheme = EngineState.shuangpinScheme
        // as ⋯ opens: listed now, a file or two read where changed
        val all = CustomKeyboards.all()
        if (ImKeyboards.choices(name, scheme).isEmpty() && !(InputMethodCatalog.hasTableKeys(name) && ImKeyboards.offers(name, scheme, all))) return null
        val customs = ImKeyboards.customs(name, scheme, all)
        val builtIn = ImKeyboards.choices(name, scheme).ifEmpty { listOf(ImKeyboard.FULL) }
        val chosen = AppPrefs.getInstance().keyboard.imKeyboards()
        val custom = chosen.customOf(name)
        val current = when (val k = ImKeyboards.effective(name, chosen.of(name), scheme, custom)) {
            ImKeyboard.CUSTOM -> Offered(k, custom)
            else -> Offered(k)
        }
        return builtIn.map { Offered(it) } + customs.map { Offered(ImKeyboard.CUSTOM, it) } to current
    }

    /**
     * Whether [im] has a choice ([offered] not null), asked as the toolbar is arranged on each start: the layouts of the
     * user's asked only for a table, whose keyboard is otherwise none to choose, as last listed (no file read on the main
     * thread as an input starts: CustomKeyboards.listed).
     */
    fun offers(): Boolean {
        val name = im()
        val scheme = EngineState.shuangpinScheme
        return ImKeyboards.choices(name, scheme).isNotEmpty() ||
            (InputMethodCatalog.hasTableKeys(name) && ImKeyboards.offers(name, scheme, CustomKeyboards.listed()))
    }

    /** The list beside [anchor]; null, nothing shown, where the input method has no choice. The caller dismisses it. */
    fun show(anchor: View): PopupMenu? {
        val (choices, current) = offered() ?: return null
        val name = im()
        return PopupMenu(context, anchor).apply {
            for ((i, offered) in choices.withIndex()) {
                // the keyboards' names as each page lists them (全键盘, 18 键, 14 键, 九键); a layout of the user's, 「17 键（自定义）」
                val label = offered.layout?.let { KeyboardLabels.custom(context, it) }
                    ?: KeyboardLabels.label(context, name, offered.keyboard, context.getString(labelOf(name, offered.keyboard)))
                menu.add(GROUP, i, i, label).apply {
                    isCheckable = true
                    isChecked = offered.keyboard == current.keyboard && offered.layout?.id == current.layout?.id
                }
            }
            menu.setGroupCheckable(GROUP, true, true)
            setOnMenuItemClickListener { item ->
                val picked = choices[item.itemId]
                // the base ticked in place of a layout of the user's that does not read: picking it stores it
                val stored = AppPrefs.getInstance().keyboard.imKeyboards { null }.of(name)
                if (picked != current || (picked.layout == null && stored == ImKeyboard.CUSTOM)) {
                    AppPrefs.getInstance().keyboard.setImKeyboard(name, picked.keyboard, picked.layout?.id)
                }
                true
            }
            show()
        }
    }

    private companion object {
        const val GROUP = 1

        // the name of [keyboard] on [im]'s own page: each input method's preference names its own; a table's full keyboard
        fun labelOf(im: String, keyboard: ImKeyboard): Int = when (im) {
            InputMethodCatalog.SHUANGPIN -> ShuangpinKeyboard.of(keyboard).stringRes
            InputMethodCatalog.ENGLISH -> EnglishKeyboard.of(keyboard).stringRes
            InputMethodCatalog.PINYIN -> PinyinKeyboard.of(keyboard).stringRes
            else -> R.string.pinyin_keyboard_full
        }
    }
}
