/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_UNLIMITED
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.LetterCaseChoice
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.input.keyboard.VoiceEndMark
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator
import timber.log.Timber

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val symbolPanelLocked = bool("symbol_panel_locked", false)

        /** The 候选词注音 style last used but off (ReadingStyle's key; empty, none yet): what the handwriting pad's 注 turns on. */
        val lastCandidateReading = string("last_candidate_reading", "")

        /**
         * U-93 「用我的输入习惯」, from the ⋯ menu or the lexicon page: off, the engine as installed but
         * for the blocks (Engines.useHabits), nothing learned, nothing shown of what was.
         */
        val useHabits = bool("learning.use_habits", true)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)

        /** [org.fcitx.fcitx5.android.ui.main.AppLanguage]'s tag, read where appcompat cannot know it yet */
        val appLanguage = string("app_language", "")

        /**
         * See [org.fcitx.fcitx5.android.input.popup.PopupOverrides] for the format. A letter's first
         * is also what swiping it types, drawn in its corner.
         */
        val popupOverrides = string("popup_overrides", "")

        /** See [org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts] for the format: the keys' swipe shortcuts the user set. */
        val keyShortcuts = string("key_shortcuts", "")

        /**
         * The bar's buttons, their order and which show (ToolbarConfig.serialize; empty, as
         * never set, is the bar as it always was): set on its own page from the 按键 page.
         */
        val toolbarButtons = string("toolbar_buttons", "")

        /** Whether [resetHiddenSettings] ran: on the first start of a version with it, and so once. */
        val hiddenSettingsReset = bool("hidden_settings_reset", false)

        /** Whether a zhuyin keyboard has said once that 繁体 is in ⋯ (KeyboardWindow). */
        val zhuyinTraditionalTold = bool("zhuyin_traditional_told", false)

        /**
         * Whether 0.4's 九键拼音 was merged into pinyin (KotlinHost.mergeNineKeys): pinyin's keyboard set
         * once, though the profile could not be written back without the old name. Not a 0.4 backup's,
         * so one restored is merged again.
         */
        val nineKeysMerged = bool("nine_keys_merged", false)

        /**
         * Whether the merge's notice ([nineKeysMerged], both pinyins were on) is still to be seen: kept till
         * it is said on the keyboard, so a process ended before any keyboard showed says it at the next start.
         */
        val nineKeysUnseen = bool("nine_keys_merged_unseen", false)

        /**
         * Whether the user saw that the 双拼 scheme they had is one 0.5 dropped, now 小鹤 (KotlinHost):
         * once, though the config could not be written back. Not a 0.4 backup's, so one restored is told again.
         */
        val schemeRetiredTold = bool("scheme_retired_told", false)

        /** The dropped scheme ([schemeRetiredTold]) still to be told, by its config name, till it is seen; empty, none. */
        val schemeRetiredUnseen = string("scheme_retired_unseen", "")

        /**
         * Whether 双拼's scheme was settled for 0.5 on this install (KotlinHost): a new install's at its
         * first start, an upgrade's once 自然码 is pinned. Not a 0.4 backup's, so one restored is pinned too.
         */
        val schemeSettled = bool("shuangpin_scheme_settled", false)

        /**
         * Where the lone floating keyboard button was dragged in an editor that tells no cursor, as
         * fractions of its window (so a rotation still finds it); negative for none, the top end then.
         */
        val keyboardButtonX = float("keyboard_button_x", -1f)
        val keyboardButtonY = float("keyboard_button_y", -1f)
        // where the floating keyboard was dragged to: FloatingKeyboard.Spot, its middle at the bottom first
        val floatingKeyboardX = float("floating_keyboard_x", FloatingKeyboard.DEFAULT.x)
        val floatingKeyboardY = float("floating_keyboard_y", FloatingKeyboard.DEFAULT.y)

        /** U-106's switch, at the lexicon page's top beside 用我的输入习惯 (UserWordsFragment): a backup of the learning every few typing days. */
        val learningAutoBackup = bool("learning.auto_backup", true)

        /**
         * What the address suggestions learned ([org.fcitx.fcitx5.android.input.address.AddressBook]'s two
         * strings): how often each suffix ended an address, and the email addresses remembered. In the
         * backup with the rest of this file; never in the device-protected copy (AddressStrip learns nothing before the first unlock).
         */
        val addressCounts = string("address.counts", "")
        val rememberedAddresses = string("address.remembered", "")
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = hidden { switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false) }
        val hideKeyConfig = hidden { switch(R.string.hide_key_config, "hide_key_config", true) }
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val keyboardHeightPercentBase = hidden {
            enumList(
                R.string.keyboard_height_percent_base,
                "keyboard_height_percent_base",
                KeyboardHeightPercentBase.DisplayMetrics
            )
        }
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        init {
            section(R.string.section_keys)
        }

        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = hidden {
            switch(
                R.string.keep_keyboard_letters_uppercase,
                "keep_keyboard_letters_uppercase",
                false
            )
        }
        /** 拼音键字母大小写: a Chinese input method's letter keys' faces ([LetterCaseChoice]); English's follow Shift whatever it is. */
        val letterCase = enumList(R.string.letter_case, "letter_case", LetterCaseChoice.Shift)
        /** What a key's swipe up and down reach ([SwipeLayers]); the old 划动输入标点符号及数字 moves over ([dropRetired]). */
        val swipeLayers = enumList(
            R.string.swipe_layers,
            SWIPE_LAYERS,
            SwipeLayersChoice.ShortcutUpSymbolDown
        )
        /**
         * Where every key draws its symbol ([KeyMarkPosition]), whichever way swipes to it; never where
         * [shortcutMarkPosition] is (greyed out there), but 无. Was the theme's, under the same key.
         */
        val punctuationPosition = enumList(
            R.string.punctuation_position,
            "punctuation_position",
            // small in the corner, as Sogou's: under the letter, it crowded the key
            KeyMarkPosition.TopRight,
            apart = ManagedPreferenceUi.Apart(
                taken = { shortcutMarkPosition.getValue().takeIf { it != KeyMarkPosition.None } },
                note = R.string.mark_at_taken_by_shortcut,
                read = { settledSymbol(it, shortcutMarkPosition.getValue()) },
            )
        )
        /** Where every key draws its shortcut's mark ([KeyMarkPosition]), whichever way swipes to it; apart from [punctuationPosition]. */
        val shortcutMarkPosition: ManagedPreference.PStringLike<KeyMarkPosition> = enumList(
            R.string.shortcut_mark_position,
            "shortcut_mark_position",
            KeyMarkPosition.TopLeft,
            apart = ManagedPreferenceUi.Apart(
                taken = { settledSymbol(punctuationPosition.getValue(), shortcutMarkPosition.getValue()).takeIf { it != KeyMarkPosition.None } },
                note = R.string.mark_at_taken_by_symbol,
            )
        )
        val longPressDelay = hidden(setByLevels = true) {
            int(
                R.string.keyboard_long_press_delay,
                "keyboard_long_press_delay",
                300,
                100,
                700,
                "ms",
                10
            )
        }

        init {
            levels(
                R.string.keyboard_long_press_delay, "keyboard_long_press_delay_level",
                listOf(longPressDelay.key to longPressDelay.defaultValue),
                listOf(Level(R.string.level_short, 200), Level(R.string.level_medium, 300), Level(R.string.level_long, 450))
            )
        }
        // voice input, as Sogou's long press on the space (without the voice pack, it asks for it)
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.Voice,
        )
        // what voice types
        val voiceEndMark = enumList(R.string.voice_end_mark, "voice_end_mark", VoiceEndMark.Auto)
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        // set on an input method's page or the shared one, with no UI here: not hidden { }, which would put them back to their defaults
        private fun bool(key: String, defaultValue: Boolean) =
            ManagedPreference.PBool(sharedPreferences, key, defaultValue).apply { register() }

        private fun string(key: String, defaultValue: String) =
            ManagedPreference.PString(sharedPreferences, key, defaultValue).apply { register() }

        private fun <T : Any> stringLike(key: String, codec: ManagedPreference.StringLikeCodec<T>, defaultValue: T) =
            ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec).apply { register() }

        /** Shown in the 九键 section of pinyin's and English's pages (InputMethodSettings), while the nine keys are their keyboard. */
        val t9MultiTap = stringLike(T9_MULTI_TAP, object : ManagedPreference.StringLikeCodec<T9MultiTap> {
            override fun decode(raw: String) = T9MultiTap.entries.firstOrNull { it.name == raw }
        }, T9MultiTap.Off)

        /** Shown in the 九键 section of English's page: how the nine keys type English. */
        val t9EnglishTyping = stringLike(T9_ENGLISH_TYPING, object : ManagedPreference.StringLikeCodec<T9EnglishTyping> {
            override fun decode(raw: String) = T9EnglishTyping.entries.firstOrNull { it.name == raw }
        }, T9EnglishTyping.Predictive)

        /**
         * What each of 九键's digit keys types ([NineKeyLetters.serialize], empty as on a phone):
         * set on its own page from the 九键 section of an input method's page, read by the keyboard and the engine alike.
         */
        val t9Letters = string("t9_letters", "")

        /**
         * Shown on the pinyin page and in ⋯'s 键盘: the on-screen keyboard pinyin is typed on, the
         * nine keys 0.4's 九键拼音 (NineKeysMerge). A hardware keyboard types 全拼 still.
         */
        val pinyinKeyboard = stringLike(PINYIN_KEYBOARD, object : ManagedPreference.StringLikeCodec<PinyinKeyboard> {
            override fun decode(raw: String) = PinyinKeyboard.entries.firstOrNull { it.name == raw }
        }, PinyinKeyboard.Full)

        /**
         * Shown on the 双拼 page and in ⋯'s 键盘: the on-screen keyboard 双拼 is typed on; the 14 keys
         * not under 微软 (ImKeyboards.choices): chosen before 微软 is, they give way to the 18 keys,
         * which are then stored (EngineBridge.configure). A hardware keyboard types the full one still.
         */
        val shuangpinKeyboard = stringLike(SHUANGPIN_KEYBOARD, object : ManagedPreference.StringLikeCodec<ShuangpinKeyboard> {
            override fun decode(raw: String) = ShuangpinKeyboard.entries.firstOrNull { it.name == raw }
        }, ShuangpinKeyboard.Full)

        /** Shown on the English page and in ⋯'s 键盘: the 26 keys for everyone, upgrades too (I1 Q3), or the nine keys. */
        val englishKeyboard = stringLike(ENGLISH_KEYBOARD, object : ManagedPreference.StringLikeCodec<EnglishKeyboard> {
            override fun decode(raw: String) = EnglishKeyboard.entries.firstOrNull { it.name == raw }
        }, EnglishKeyboard.Full)

        /** Each input method's keyboard as set, for ImKeyboards to decide the layout from. */
        fun imKeyboards() = ImKeyboards.Chosen(pinyinKeyboard.getValue().keyboard, shuangpinKeyboard.getValue().keyboard, englishKeyboard.getValue().keyboard)

        /** [im]'s keyboard set to [keyboard], one it offers (ImKeyboards.choices). */
        fun setImKeyboard(im: String, keyboard: ImKeyboard) {
            when (im) {
                InputMethodCatalog.PINYIN -> pinyinKeyboard.setValue(PinyinKeyboard.of(keyboard))
                InputMethodCatalog.SHUANGPIN -> shuangpinKeyboard.setValue(ShuangpinKeyboard.of(keyboard))
                InputMethodCatalog.ENGLISH -> englishKeyboard.setValue(EnglishKeyboard.of(keyboard))
            }
        }

        /**
         * What each of 九键's keys 1 to 9 offers on a long press besides its digit and letters
         * ([NineKeySymbols.serialize], empty as by default): set on its own page from the 九键 section of an input method's page.
         */
        val t9Symbols = string("t9_symbols", "")

        /** Shown on the English page (InputMethodSettings), not here: English's alone. */
        val englishAutoCaps = bool(ENGLISH_AUTO_CAPS, true)

        /**
         * Shown on the page of what every input method shares (InputMethodSettings.SHARED), two
         * switches for what a finger drawn across the keys does (GlideInput), one or the other, never
         * both (the page turns the other off): a word glided across its keys in every input method
         * that glides (InputMethodCatalog.glides), or a stroke drawn while pinyin is typed that
         * filters its candidates (笔画筛选, InputMethodCatalog.filtersByStrokes). Both off till
         * chosen: a fast tap may glide. The glide's key is 0.4's, so its switch carries over as it is.
         */
        val glideTyping = bool(GLIDE_TYPING, false)
        val strokeFilter = bool(STROKE_FILTER, false)

        /** Shown on the shared page too: every input method types a bracket or a quote. */
        val autoPairs = bool(AUTO_PAIRS, true)

        /** Shown on the shared page too: a sum is typed in any input method. */
        val calculationReplacesSum = bool(CALCULATION_REPLACES_SUM, false)

        /**
         * 「通用输入 → 网址和邮箱」's (AddressSettingsFragment): the bar's suggestions' two lists, one entry a
         * line ([AddressLists.serialize]), and whether the email addresses typed are remembered.
         */
        val emailSuffixes = string("address_email_suffixes", AddressLists.serialize(AddressLists.DEFAULT_EMAIL))
        val urlSuffixes = string("address_url_suffixes", AddressLists.serialize(AddressLists.DEFAULT_URL))
        val rememberAddresses = bool("address_remember", true)

        /** Shown on the handwriting page (InputMethodSettings): how long the pen stays up before a character is done. */
        val handwritingPause = stringLike(HANDWRITING_PAUSE, object : ManagedPreference.StringLikeCodec<HandwritingPause> {
            override fun decode(raw: String) = HandwritingPause.entries.firstOrNull { it.name == raw }
        }, HandwritingPause.Medium)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = hidden {
            enumList(
                R.string.lang_switch_key_behavior,
                "lang_switch_key_behavior",
                LangSwitchBehavior.Enumerate
            ) { showLangSwitchKey.getValue() }
        }

        val focusChangeResetKeyboard =
            hidden { switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true) }

        val expandToolbarByDefault =
            hidden { switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false) }
        val inlineSuggestions = hidden { switch(R.string.inline_suggestions, "inline_suggestions", true) }
        val toolbarNumRowOnPassword =
            hidden { switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true) }

        val horizontalCandidateStyle = hidden {
            enumList(
                R.string.horizontal_candidate_style,
                "horizontal_candidate_style",
                HorizontalCandidateMode.AutoFillWidth
            )
        }
        val expandedCandidateStyle = hidden {
            enumList(
                R.string.expanded_candidate_style,
                "expanded_candidate_style",
                ExpandedCandidateStyle.Grid
            )
        }

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden {
                twinInt(
                    R.string.expanded_candidate_grid_span_count,
                    R.string.portrait,
                    "expanded_candidate_grid_span_count_portrait",
                    6,
                    R.string.landscape,
                    "expanded_candidate_grid_span_count_landscape",
                    8,
                    4,
                    12,
                )
            }
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }


        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden(setByLevels = true) {
                twinInt(
                    R.string.keyboard_height,
                    R.string.portrait,
                    "keyboard_height_percent",
                    30,
                    R.string.landscape,
                    "keyboard_height_percent_landscape",
                    49,
                    10,
                    90,
                    "%"
                )
            }
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden(setByLevels = true) {
                twinInt(
                    R.string.keyboard_side_padding,
                    R.string.portrait,
                    "keyboard_side_padding",
                    0,
                    R.string.landscape,
                    "keyboard_side_padding_landscape",
                    0,
                    0,
                    300,
                    "dp"
                )
            }
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden(setByLevels = true) {
                twinInt(
                    R.string.keyboard_bottom_padding,
                    R.string.portrait,
                    "keyboard_bottom_padding",
                    0,
                    R.string.landscape,
                    "keyboard_bottom_padding_landscape",
                    0,
                    0,
                    100,
                    "dp"
                )
            }
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        // 1: docked, no room is left for the navigation bar (键盘底部边距 贴底), for ROMs that hide
        // the gesture line yet still report its insets, leaving a blank strip under the keys
        val keyboardBottomFlush = hidden(setByLevels = true) {
            int(R.string.keyboard_bottom_padding, "keyboard_bottom_flush", 0, 0, 1)
        }

        /** 贴底 chosen ([keyboardBottomFlush]). */
        val bottomFlush: Boolean get() = keyboardBottomFlush.getValue() == 1

        init {
            section(R.string.section_sound_and_vibration)
        }

        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = hidden {
            switch(
                R.string.button_up_haptic_feedback,
                "haptic_on_keyup",
                false
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        }
        val hapticOnRepeat = hidden { switch(R.string.haptic_on_repeat, "haptic_on_repeat", false) }

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden(setByLevels = true) {
                twinInt(
                    R.string.button_vibration_milliseconds,
                    R.string.button_press,
                    "button_vibration_press_milliseconds",
                    0,
                    R.string.button_long_press,
                    "button_vibration_long_press_milliseconds",
                    0,
                    0,
                    100,
                    "ms",
                    defaultLabel = R.string.system_default
                ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden(setByLevels = true) {
                twinInt(
                    R.string.button_vibration_amplitude,
                    R.string.button_press,
                    "button_vibration_press_amplitude",
                    0,
                    R.string.button_long_press,
                    "button_vibration_long_press_amplitude",
                    0,
                    0,
                    255,
                    defaultLabel = R.string.system_default
                ) {
                    (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                            // hide this if using default duration
                            && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                            && appContext.vibrator.hasAmplitudeControl()
                }
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
            // how long and how hard together: a felt strength, not milliseconds and an amplitude
            levels(
                R.string.vibration_strength, "vibration_strength_level",
                listOf(
                    buttonPressVibrationMilliseconds, buttonLongPressVibrationMilliseconds,
                    buttonPressVibrationAmplitude, buttonLongPressVibrationAmplitude,
                ).map { it.key to it.defaultValue },
                listOf(
                    Level(R.string.system_default, 0, 0, 0, 0),
                    Level(R.string.level_light, 10, 20, 60, 90),
                    Level(R.string.level_medium, 20, 30, 128, 160),
                    Level(R.string.level_strong, 35, 45, 220, 255),
                )
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = hidden(setByLevels = true) {
            int(
                R.string.button_sound_volume,
                "button_sound_volume",
                0,
                0,
                100,
                "%",
                defaultLabel = R.string.system_default
            ) {
                soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
            }
        }

        init {
            levels(
                R.string.button_sound_volume, "button_sound_volume_level",
                listOf(soundOnKeyPressVolume.key to soundOnKeyPressVolume.defaultValue),
                listOf(
                    Level(R.string.system_default, 0),
                    Level(R.string.level_quiet, 20),
                    Level(R.string.level_medium, 50),
                    Level(R.string.level_loud, 90),
                )
            ) { soundOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        }
    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        // the hardware keyboard's other settings are here: this is its page
        val switchShortcuts = switch(
            R.string.hardware_switch_shortcuts, "hardware_switch_shortcuts", true, R.string.hardware_switch_shortcuts_summary
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = hidden {
            int(
                R.string.candidates_window_min_width,
                "candidates_window_min_width",
                0,
                0,
                640,
                "dp",
                10
            )
        }

        val windowPadding =
            hidden { int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp") }

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            hidden { int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp") }

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden {
                twinInt(
                    R.string.candidates_padding,
                    R.string.vertical,
                    "candidates_item_padding_vertical",
                    2,
                    R.string.horizontal,
                    "candidates_item_padding_horizontal",
                    4,
                    0,
                    64,
                    "dp"
                )
            }
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        /** [CLIPBOARD_UNLIMITED] for 「无上限」, 0 keeping none. */
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            10,
            special = ManagedPreferenceUi.EditTextInt.Special(
                CLIPBOARD_UNLIMITED, R.string.clipboard_unlimited, R.string.clipboard_unlimited_summary
            ),
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = hidden {
            int(
                R.string.clipboard_suggestion_timeout,
                "clipboard_item_timeout",
                30,
                -1,
                Int.MAX_VALUE,
                "s"
            ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", true
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
        val clipboardClearUrls = switch(
            R.string.clipboard_clear_urls,
            "clipboard_clear_urls",
            true,
            R.string.clipboard_clear_urls_summary
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = hidden {
            switch(
                R.string.hide_unsupported_emojis,
                "hide_unsupported_emojis",
                true
            )
        }

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    // resetHiddenSettings ran in this process: a category registered after it is reset as it registers
    private var hiddenResetInProcess = false

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        if (hiddenResetInProcess && provider is ManagedPreferenceCategory) {
            sharedPreferences.edit(commit = true) { provider.resetHidden(this) }
        }
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                // the long-press and swipe characters: a password may be typed with them before the first unlock
                internal.popupOverrides,
                // a swipe's shortcut too
                internal.keyShortcuts,
                // the bar is there before the first unlock too
                internal.toolbarButtons,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    /**
     * The settings no version reads any more removed, as a 0.4 install or a backup of one has them,
     * and 标点位置 stored where 快捷键图标位置 is moved ([settleMarkPositions]): at every start, a
     * backup restored (an import, after [resetHiddenSettings] again) being read at the next.
     */
    fun dropRetired() {
        // gone with the old drawing (060-render-p2 S8): 0.6's betas' switch of the redraw, vivo's touch routing to
        // the key views (the scene's keys always take their touches so), and 扩大按键触摸区 (the engine always
        // gives a key its cell to the edge); a stored one, of any type, read by nothing
        RETIRED.filter { sharedPreferences.contains(it) }.takeIf { it.isNotEmpty() }?.let { keys -> sharedPreferences.edit { keys.forEach(::remove) } }
        migrateSwipe()
        settleMarkPositions()
    }

    /**
     * 标点位置 stored in 快捷键图标位置's place, which the settings page never stores (an import, a
     * backup), stored as it reads ([KeyMarks.settled]). Read so only ([ManagedPreferenceUi.Apart.read]),
     * it was not what the page's list held: 快捷键图标位置 moved away, the keyboard drew the symbol where
     * it was stored and the page's summary said where it had read.
     */
    private fun settleMarkPositions() {
        val symbol = keyboard.punctuationPosition.getValue()
        val settled = settledSymbol(symbol, keyboard.shortcutMarkPosition.getValue())
        if (settled != symbol) keyboard.punctuationPosition.setValue(settled)
    }

    /**
     * 0.5's 划动输入标点符号及数字 as 滑动 ([SwipeLayers.fromLegacy]): the symbol the way it was, the
     * shortcuts the other, off both off. A 滑动 set already is kept, the old one dropped all the same.
     */
    private fun migrateSwipe() {
        if (!sharedPreferences.contains(SWIPE_SYMBOL_BEHAVIOR)) return
        // stored as another type (a bad backup, edited by hand): read as unset, never a crash at every start
        val legacy = runCatching { sharedPreferences.getString(SWIPE_SYMBOL_BEHAVIOR, null) }.getOrNull()
        sharedPreferences.edit {
            if (!sharedPreferences.contains(SWIPE_LAYERS)) {
                putString(SWIPE_LAYERS, SwipeLayersChoice.of(SwipeLayers.fromLegacy(legacy)).name)
            }
            remove(SWIPE_SYMBOL_BEHAVIOR)
        }
    }

    /**
     * Puts the settings that have no UI back to their defaults ([ManagedPreferenceCategory.hidden]):
     * once, and [again] after an import of the user's data, for what an older version or a backup
     * set there could not be changed back. Every category registered here is reset, ThemePrefs too:
     * ThemeManager registers it after this has run at the start, and [registerProvider] resets it then.
     */
    fun resetHiddenSettings(again: Boolean = false) {
        if (!again && internal.hiddenSettingsReset.getValue()) return
        // on disk before an import's marker goes
        sharedPreferences.edit(commit = true) {
            providers.filterIsInstance<ManagedPreferenceCategory>().forEach { it.resetHidden(this@edit) }
            putBoolean(internal.hiddenSettingsReset.key, true)
        }
        hiddenResetInProcess = true
        Timber.i("settings without a UI reset to their defaults%s", if (again) " after an import" else "")
    }

    companion object {
        private var instance: AppPrefs? = null

        // keys an input method's page names its rows by (InputMethodSettings); the others are only this file's
        const val T9_MULTI_TAP = "t9_multi_tap"
        const val T9_ENGLISH_TYPING = "t9_english_typing"
        const val PINYIN_KEYBOARD = "pinyin_keyboard"

        const val SHUANGPIN_KEYBOARD = "shuangpin_keyboard"
        const val ENGLISH_KEYBOARD = "english_keyboard"

        // 0.4's: English on the nine keys while 九键's 中/英 had brought it there, gone with the key (I1)
        private const val ENGLISH_ON_NINE_KEYS = "english_on_nine_keys"
        private val RETIRED = listOf(ENGLISH_ON_NINE_KEYS, "canvas_keyboard", "vivo_keypress_workaround", "expand_keypress_area", "voice_polish")

        // 0.5's swipe setting, and 0.5.1's that took its place
        private const val SWIPE_SYMBOL_BEHAVIOR = "swipe_symbol_behavior"
        const val SWIPE_LAYERS = "swipe_layers"

        const val ENGLISH_AUTO_CAPS = "english_auto_caps"
        const val GLIDE_TYPING = "glide_typing"
        const val STROKE_FILTER = "stroke_filter"

        const val AUTO_PAIRS = "auto_pairs"
        const val CALCULATION_REPLACES_SUM = "calculation_replaces_sum"
        const val HANDWRITING_PAUSE = "handwriting_pause"

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        /**
         * Fails with an explanation rather than a bare NPE.
         *
         * Many classes read a preference in their initialiser, so a test that touches one of
         * them transitively blows up here. The message says what to do: `AppPrefs.init()` with
         * a `SharedPreferences` -- a relaxed mock is enough, nothing reads a real stored value.
         */
        fun getInstance(): AppPrefs = instance
            ?: error(
                "AppPrefs has not been initialised. FcitxApplication.onCreate does this on " +
                        "device; a unit test must call AppPrefs.init(sharedPreferences) itself."
            )
    }
}

/** Pinyin's on-screen keyboard ([ImKeyboard]): the full one, the 18 keys, the 14 keys (拼音双键), the nine keys. Kept by name: never rename one. */
enum class PinyinKeyboard(override val stringRes: Int, val keyboard: ImKeyboard) : ManagedPreferenceEnum {
    Full(R.string.pinyin_keyboard_full, ImKeyboard.FULL),
    Keys18(R.string.keyboard_18, ImKeyboard.KEYS18),
    Keys14(R.string.pinyin_keyboard_14, ImKeyboard.KEYS14),
    NineKeys(R.string.keyboard_nine, ImKeyboard.NINE_KEYS),
    ZhuyinNine(R.string.keyboard_zhuyin_nine, ImKeyboard.ZHUYIN_NINE),
    ZhuyinFull(R.string.keyboard_zhuyin_full, ImKeyboard.ZHUYIN_FULL);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

/** 双拼's on-screen keyboard ([ImKeyboard]): the full one, the 18 keys, the 14 keys, or the nine keys (many more alike). Kept by name: never rename one. */
enum class ShuangpinKeyboard(override val stringRes: Int, val keyboard: ImKeyboard) : ManagedPreferenceEnum {
    Full(R.string.pinyin_keyboard_full, ImKeyboard.FULL),
    Keys18(R.string.keyboard_18, ImKeyboard.KEYS18),
    Keys14(R.string.pinyin_keyboard_14, ImKeyboard.KEYS14),
    NineKeys(R.string.keyboard_nine, ImKeyboard.NINE_KEYS);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

/** English's on-screen keyboard ([ImKeyboard]): the 26 keys or the nine keys. Kept by name: never rename one. */
enum class EnglishKeyboard(override val stringRes: Int, val keyboard: ImKeyboard) : ManagedPreferenceEnum {
    Full(R.string.pinyin_keyboard_full, ImKeyboard.FULL),
    NineKeys(R.string.keyboard_nine, ImKeyboard.NINE_KEYS);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

// where the symbol's mark is drawn with the shortcut's at [shortcut]: moved off it if it is there (KeyMarks.settled)
private fun settledSymbol(symbol: KeyMarkPosition, shortcut: KeyMarkPosition) =
    KeyMarkPosition.of(KeyMarks.settled(symbol.at, shortcut.at).symbol)
