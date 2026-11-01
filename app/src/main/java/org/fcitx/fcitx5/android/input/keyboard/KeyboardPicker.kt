/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/**
 * ⋯'s 键盘 (I1 Q1): the keyboards the input method now on offers ([ImKeyboards.choices]), the one
 * it is typed on ticked, one picked set as its page sets it (the input view is made anew for it,
 * FcitxInputMethodService). [im] is asked as it opens: the input method as the keyboard last heard.
 * Public for a toolbar button of its own too: [show] beside whatever view opens it.
 */
class KeyboardPicker(private val context: Context, private val im: () -> String) {

    /** The keyboards [im] offers, the one in use first ticked ([ImKeyboards.effective]): none where it has no choice. */
    fun offered(): Pair<List<ImKeyboard>, ImKeyboard>? {
        val name = im()
        val scheme = EngineState.shuangpinScheme
        val choices = ImKeyboards.choices(name, scheme).ifEmpty { return null }
        val chosen = AppPrefs.getInstance().keyboard.imKeyboards().of(name)
        return choices to ImKeyboards.effective(name, chosen, scheme)
    }

    /** The list beside [anchor]; null, nothing shown, where the input method has no choice. The caller dismisses it. */
    fun show(anchor: View): PopupMenu? {
        val (choices, current) = offered() ?: return null
        val name = im()
        return PopupMenu(context, anchor).apply {
            for ((i, keyboard) in choices.withIndex()) {
                // the keyboards' names as each page lists them (全键盘, 18 键, 14 键, 九键)
                menu.add(GROUP, i, i, labelOf(name, keyboard)).apply {
                    isCheckable = true
                    isChecked = keyboard == current
                }
            }
            menu.setGroupCheckable(GROUP, true, true)
            setOnMenuItemClickListener { item ->
                val picked = choices[item.itemId]
                if (picked != current) AppPrefs.getInstance().keyboard.setImKeyboard(name, picked)
                true
            }
            show()
        }
    }

    private companion object {
        const val GROUP = 1

        // the name of [keyboard] on [im]'s own page: each input method's preference names its own
        fun labelOf(im: String, keyboard: ImKeyboard): Int = when (im) {
            InputMethodCatalog.SHUANGPIN -> ShuangpinKeyboard.of(keyboard).stringRes
            InputMethodCatalog.ENGLISH -> EnglishKeyboard.of(keyboard).stringRes
            InputMethodCatalog.PINYIN -> PinyinKeyboard.of(keyboard).stringRes
            // [offered] lists none for any other
            else -> error("no keyboards of $im")
        }
    }
}
