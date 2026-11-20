/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import androidx.annotation.ArrayRes
import org.fcitx.fcitx5.android.R

/** Kept in one table, not by each kind of setting: a setting renamed or removed leaves an id here that SettingsIndexTest finds. */
object SearchKeywords {

    internal val TABLE: List<Pair<List<String>, Int>> = listOf(
        listOf("value:key_drawing=StrokeFilter") to R.array.kw_stroke_filter,
        listOf("cmd:stroke") to R.array.kw_stroke_commands,
        listOf("pref:StrokeSingles") to R.array.kw_stroke_singles,
        listOf("pref:ShuangpinKeysShown") to R.array.kw_shuangpin_keys_shown,
        listOf("value:key_drawing=Glide") to R.array.kw_glide,
        listOf("page:RareChars") to R.array.kw_rare_chars,
        listOf("pref:Fuzzy") to R.array.kw_fuzzy,
        listOf("pref:haptic_on_keypress") to R.array.kw_haptic,
        listOf("pref:sound_on_keypress") to R.array.kw_key_sound,
        listOf("pref:keyboard_height_level") to R.array.kw_keyboard_height,
        listOf("pref:follow_system_dark_mode") to R.array.kw_dark_mode,
        listOf("page:Theme") to R.array.kw_theme,
        listOf("page:Clipboard") to R.array.kw_clipboard,
        listOf("pref:Prediction") to R.array.kw_prediction,
        listOf("cmd:trad_simp") to R.array.kw_trad_simp,
        listOf("value:pinyin_keyboard=NineKeys", "value:pinyin_keyboard=ZhuyinNine", "value:shuangpin_keyboard=NineKeys", "value:english_keyboard=NineKeys") to R.array.kw_nine_keys,
        listOf("value:pinyin_keyboard=Full") to R.array.kw_full_keys,
        listOf("value:space_long_press_behavior=Voice", "cmd:voice") to R.array.kw_voice,
        listOf("page:Backup") to R.array.kw_backup,
        listOf("page:PinyinDict") to R.array.kw_dictionaries,
        listOf("page:UserWords") to R.array.kw_user_words,
        listOf("page:PopupOverrides") to R.array.kw_popup_overrides,
        listOf("pref:space_swipe_move_cursor") to R.array.kw_space_cursor,
        listOf("pref:english_auto_caps") to R.array.kw_auto_caps,
        listOf("pref:app_language") to R.array.kw_app_language,
        listOf("page:ToolbarButtons") to R.array.kw_toolbar,
        listOf("pref:candidate_text_size") to R.array.kw_candidate_size,
        listOf("pref:Fuzzy/NG_GN") to R.array.kw_typos,
        listOf("pref:ModelLevel") to R.array.kw_model_level,
        listOf("pref:code_row_style") to R.array.kw_code_row,
        listOf("pref:disable_animation", "pref:keyboard_motion") to R.array.kw_motion,
        listOf("pref:key_h_spacing_choice", "pref:key_v_spacing_choice") to R.array.kw_key_spacing,
        listOf("page:PinyinCustomPhrase") to R.array.kw_custom_phrases,
        listOf("page:QuickPhrase") to R.array.kw_quick_phrases,
        listOf("pref:clipboard_limit") to R.array.kw_clipboard_limit,
        listOf("pref:floating_handle") to R.array.kw_floating_handle,
        listOf("value:keyboard_style_portrait_list=3", "value:keyboard_style_landscape_list=3") to R.array.kw_one_handed,
        listOf("value:keyboard_style_portrait_list=1", "value:keyboard_style_landscape_list=1") to R.array.kw_split,
        listOf("pref:CandidateReading") to R.array.kw_candidate_reading,
        listOf("pref:hardware_switch_shortcuts") to R.array.kw_hardware_shortcuts,
    )

    fun of(context: Context): Map<String, List<String>> = buildMap<String, List<String>> {
        for ((ids, @ArrayRes array) in TABLE) {
            val words = context.resources.getStringArray(array).filter { it.isNotBlank() }
            if (words.isNotEmpty()) for (id in ids) put(id, get(id).orEmpty() + words)
        }
    }
}
