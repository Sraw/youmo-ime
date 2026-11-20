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
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.InputFeedbacks.KeySoundSource
import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_UNLIMITED
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi.Levels.Level
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.PanelLayouts
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.StrokeLift
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.LetterCaseChoice
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.input.keyboard.VoiceEndMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.KeyListsMigration
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeMigration
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator
import timber.log.Timber

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val symbolPanelLocked = bool("symbol_panel_locked", false)
        /** A [org.fcitx.fcitx5.android.input.clipboard.ClipboardPage]'s name. */
        val clipboardPage = string("clipboard_page", "Recent")
        // a restored 0.6.2 backup sets it back: its pins are favourites again
        val clipboardPinsMovedTold = bool("clipboard_pins_moved_told", false)

        /** The symbol panel's category picked last, by its [org.fcitx.fcitx5.android.input.picker.PickerData.Category.id] (`arrows`); empty, none yet (PAN-29). */
        val symbolPanelCategory = string("symbol_panel_category", "")

        /**
         * U-93 「用我的输入习惯」, from the ⋯ menu or the lexicon page: off, the engine as installed but
         * for the blocks (Engines.useHabits), nothing learned, nothing shown of what was.
         */
        val useHabits = bool("learning.use_habits", true)
        val verboseLog = bool("verbose_log", false)
        val pid = int(PID, 0)
        val editorInfoInspector = bool("editor_info_inspector", false)

        /** [org.fcitx.fcitx5.android.ui.main.AppLanguage]'s tag, read where appcompat cannot know it yet */
        val appLanguage = string("app_language", "")

        /**
         * See [org.fcitx.fcitx5.android.input.shortcut.SwipeSlots] for the format: every key's 上滑 and 下滑
         * the user set (060-swipe-slots); 0.5's `key_shortcuts` and 滑动 move here ([dropRetired]).
         */
        val keySwipes = string(KEY_SWIPES, "")

        /**
         * Every key's long press the user set, of every keyboard ([org.fcitx.fcitx5.android.input.keys.KeyLists],
         * `@2` first; 060-keys-unify §2, 060-row-unlock §1.2): 0.5's and the betas' [POPUP_OVERRIDES], [KEY_POPUPS] and [T9_SYMBOLS] moved here
         * once ([dropRetired]), those kept as they were for a downgrade (§4.4), no longer settings of this version. A plain setting, never `hidden`: a backup's
         * would be reset after its import and the old ones migrated again (m2). 全部恢复默认 writes [KeyLists.HEADER], the
         * default, never removes it: the old ones would come back at the next start. Whether to migrate is read from what
         * is stored ([storedKeyLists]), never through this, which reads the default where nothing is.
         */
        val keyLists = string(KEY_LISTS, KeyLists.HEADER)

        /**
         * The bar's buttons, their order and which show (ToolbarConfig.serialize; empty, as
         * never set, is the bar as it always was): set on its own page from the 按键 page.
         */
        val toolbarButtons = string("toolbar_buttons", "")

        /** Whether [offerHandwriting] ran: its first look at [toolbarButtons], once. */
        val toolbarHandwritingLooked = bool("toolbar_handwriting_looked", false)

        /** Whether the punctuation table the user edited before 0.6.1 was deleted (HostConfigDir.resetPunctuationOnce). */
        val punctuationReset061 = bool("punctuation_reset_061", false)

        /**
         * Whether the toolbar's 手写 is on (061-handwriting-toolbar §3.4, the user's Q1): the letters written on
         * in every input method that writes (HandwritingMode), till the button is tapped again; kept across
         * keyboards, input methods and restarts. On for a user whose first input method was 0.6.0's 手写
         * (KotlinHost.retireHandwriting).
         */
        val handwritingOpen = bool("handwriting_open", false)

        /**
         * Whether 0.6.0's 手写 input method was moved onto the toolbar (KotlinHost.retireHandwriting): the toolbar's
         * 手写 pinned and turned on once, though the profile could not be written back. Not a 0.6.0 backup's, so one
         * restored is moved again.
         */
        val handwritingRetired = bool("handwriting_retired", false)

        /**
         * The notice of that still to be seen ([handwritingRetired]): what it says besides, KotlinHost's markers
         * comma-separated, empty for none; kept till it is said on the keyboard.
         */
        val handwritingRetiredUnseen = string("handwriting_retired_unseen", "")

        /** How often 展开候选 said its strokes are drawn there and its pages turned by ↑↓ (PageStrokeTouch.Hint). */
        val strokePageHintShown = int("stroke_page_hint_shown", 0)

        /** Whether 0.6.0's 键盘手写 (在键上划, now 关) is still to be told it is the toolbar's 手写 ([migrateKeyHandwriting]). */
        val keyHandwritingUnseen = bool("key_handwriting_unseen", false)

        /** Whether a zhuyin keyboard has said once that 繁体 is in ⋯ (KeyboardWindow). */
        val zhuyinTraditionalTold = bool("zhuyin_traditional_told", false)

        /**
         * What the theme packs' boards made layouts of the user's at an upgrade or a restore tell (ThemeLayoutsToList,
         * 061-custom-layout §11: which are chosen now), as said; empty, nothing to say. Kept till it is said on the keyboard.
         */
        val themeLayoutsUnseen = string("theme_layouts_unseen", "")

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
        // where the floating keyboard was dragged to before 0.6.1, one place for 竖屏 and 横屏: FloatingKeyboard.Spot, its
        // middle at the bottom first. Read, never written: each shape's own falls back to it (FloatingStore)
        val floatingKeyboardX = float("floating_keyboard_x", FloatingKeyboard.DEFAULT.x)
        val floatingKeyboardY = float("floating_keyboard_y", FloatingKeyboard.DEFAULT.y)

        /**
         * The floating keyboard's place and width (dp) the user dragged and resized it to, 竖屏 and 横屏 each its own
         * (061-floating-resize §2.6; FloatingStore). Negative: never kept, the old place and the default width then.
         */
        val floatingPortraitX = float("floating_portrait_x", -1f)
        val floatingPortraitY = float("floating_portrait_y", -1f)
        val floatingLandscapeX = float("floating_landscape_x", -1f)
        val floatingLandscapeY = float("floating_landscape_y", -1f)
        val floatingPortraitWidthDp = float("floating_portrait_width_dp", -1f)
        val floatingLandscapeWidthDp = float("floating_landscape_width_dp", -1f)

        /** U-106's switch, at the lexicon page's top beside 用我的输入习惯 (UserWordsFragment): a backup of the learning every few typing days. */
        val learningAutoBackup = bool("learning.auto_backup", true)

        /**
         * 语音先问再记's switch, the lexicon page's third (061-voice-learn-from-edits.md, Q4): a word of
         * voice's deleted and typed again, read the same, asked about (「让语音记住『徐婧』？」).
         */
        val voiceAskRemember = bool("learning.voice_ask_remember", true)

        /** How often it was asked, and the word remembered (an undo taking one back): the developer page's line. */
        val voiceAskShown = int("learning.voice_ask_shown", 0)
        val voiceAskTaken = int("learning.voice_ask_taken", 0)

        /** 「把整个词删掉重打，语音才能记住」 said, once (Q3). */
        val voiceAskHintShown = bool("learning.voice_ask_hint_shown", false)

        /**
         * What the address suggestions learned ([org.fcitx.fcitx5.android.input.address.AddressBook]'s two
         * strings): how often each suffix ended an address, and the email addresses remembered. In the
         * backup with the rest of this file; never in the device-protected copy (AddressStrip learns nothing before the first unlock).
         */
        val addressCounts = string("address.counts", "")
        val rememberedAddresses = string("address.remembered", "")
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)

        /** 键盘动效 (060-render-p3 A1): a theme's keys move as it says (KeyMotionSwitch); off, they are drawn as a theme without motion. */
        val keyboardMotion = switch(R.string.keyboard_motion, "keyboard_motion", true, R.string.keyboard_motion_summary)
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        init {
            section(R.string.section_keys)
        }

        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        /** 拼音键字母大小写: a Chinese input method's letter keys' faces ([LetterCaseChoice]); English's follow Shift whatever it is. */
        val letterCase = enumList(R.string.letter_case, "letter_case", LetterCaseChoice.Shift)
        /** 上滑 on every key (060-swipe-slots): off, a swipe up is a character key's tap, a function key as it was. */
        val swipeUp = switch(R.string.swipe_up, SWIPE_UP, true)
        /**
         * Where every key draws what its swipe up reaches ([KeyMarkPosition]); never where
         * [swipeDownPosition] is (greyed out there), but 无. 0.5's 标点位置 or 快捷键图标位置 move here.
         */
        val swipeUpPosition: ManagedPreference.PStringLike<KeyMarkPosition> = enumList(
            R.string.swipe_up_position,
            SWIPE_UP_POSITION,
            // small in the corner, as Sogou's: under the letter, it crowded the key
            KeyMarkPosition.TopRight,
            apart = ManagedPreferenceUi.Apart(
                taken = { swipeDownPosition.getValue().takeIf { it != KeyMarkPosition.None } },
                note = R.string.mark_at_taken_by_down,
                read = { settledUp(it, swipeDownPosition.getValue()) },
            )
        )
        /** 下滑 on every key. */
        val swipeDown = switch(R.string.swipe_down, SWIPE_DOWN, true)
        /** Where every key draws what its swipe down reaches; apart from [swipeUpPosition]. */
        val swipeDownPosition: ManagedPreference.PStringLike<KeyMarkPosition> = enumList(
            R.string.swipe_down_position,
            SWIPE_DOWN_POSITION,
            KeyMarkPosition.TopLeft,
            apart = ManagedPreferenceUi.Apart(
                taken = { settledUp(swipeUpPosition.getValue(), swipeDownPosition.getValue()).takeIf { it != KeyMarkPosition.None } },
                note = R.string.mark_at_taken_by_up,
            )
        )
        val longPressDelay = hidden {
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
        // voice input, as Sogou's long press on the space (without the voice pack holding the space types a space, as 0.5's
        // text build; the toolbar's microphone is what asks for the pack)
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.Voice,
        )
        // what voice types
        val voiceEndMark = enumList(R.string.voice_end_mark, "voice_end_mark", VoiceEndMark.Auto)
        val voicePausePlayback = switch(R.string.voice_pause_playback, "voice_pause_playback", true, R.string.voice_pause_playback_summary)
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        // set on an input method's page or the shared one, with no UI here
        private fun bool(key: String, defaultValue: Boolean) =
            ManagedPreference.PBool(sharedPreferences, key, defaultValue).apply { register() }

        private fun string(key: String, defaultValue: String) =
            ManagedPreference.PString(sharedPreferences, key, defaultValue).apply { register() }

        private fun <T : Any> stringLike(key: String, codec: ManagedPreference.StringLikeCodec<T>, defaultValue: T) =
            ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec).apply { register() }

        /** Shown in the 九键 section of pinyin's and English's pages (InputMethodSettings), while the nine keys are their keyboard. */
        val t9MultiTap = stringLike(T9_MULTI_TAP, ManagedPreference.enumCodec<T9MultiTap>(), T9MultiTap.Off)

        /** Shown in the 九键 section of English's page: how the nine keys type English. */
        val t9EnglishTyping = stringLike(T9_ENGLISH_TYPING, ManagedPreference.enumCodec<T9EnglishTyping>(), T9EnglishTyping.Predictive)

        /**
         * What each of 九键's digit keys types ([NineKeyLetters.serialize], empty as on a phone):
         * set on its own page from the 九键 section of an input method's page, read by the keyboard and the engine alike.
         */
        val t9Letters = string(T9_LETTERS, "")

        /**
         * Shown on the pinyin page and in ⋯'s 键盘: the on-screen keyboard pinyin is typed on, the
         * nine keys 0.4's 九键拼音. A hardware keyboard types 全拼 still.
         */
        val pinyinKeyboard = stringLike(PINYIN_KEYBOARD, ManagedPreference.enumCodec<PinyinKeyboard>(), PinyinKeyboard.Full)

        /**
         * Shown on the 双拼 page and in ⋯'s 键盘: the on-screen keyboard 双拼 is typed on; the 14 keys
         * not under 微软 (ImKeyboards.choices): chosen before 微软 is, they give way to the 18 keys,
         * which are then stored (EngineBridge.configure). A hardware keyboard types the full one still.
         */
        val shuangpinKeyboard = stringLike(SHUANGPIN_KEYBOARD, ManagedPreference.enumCodec<ShuangpinKeyboard>(), ShuangpinKeyboard.Full)

        /** Shown on the English page and in ⋯'s 键盘: the 26 keys for everyone, upgrades too (I1 Q3), or the nine keys. */
        val englishKeyboard = stringLike(ENGLISH_KEYBOARD, ManagedPreference.enumCodec<EnglishKeyboard>(), EnglishKeyboard.Full)

        /**
         * The layout of the user's each input method chose where its keyboard is [ImKeyboard.CUSTOM], by its id
         * (061-custom-layout §5.2): pinyin's, 双拼's, English's; and the tables' one ("": the full keyboard, Q6). Left
         * as they are where the file stops reading (§4.4: its base drawn meanwhile); set anew as it is deleted.
         */
        val pinyinKeyboardLayout = string(PINYIN_KEYBOARD_LAYOUT, "")
        val shuangpinKeyboardLayout = string(SHUANGPIN_KEYBOARD_LAYOUT, "")
        val englishKeyboardLayout = string(ENGLISH_KEYBOARD_LAYOUT, "")
        val tableKeyboardLayout = string(TABLE_KEYBOARD_LAYOUT, "")

        /**
         * The number keyboard's and the symbol panel's layout of the user's, by its id ("": the app's), one for every input
         * method (061-custom-layout §7, §8, L5: 外观 → 布局). Left as they are where the file stops reading (the app's drawn
         * meanwhile); set back to the app's as it is deleted.
         */
        val numberKeyboardLayout = string(NUMBER_KEYBOARD_LAYOUT, "")
        val symbolsPanelLayout = string(SYMBOLS_PANEL_LAYOUT, "")

        /** [panel]'s layout of the user's ([numberKeyboardLayout], [symbolsPanelLayout]). */
        fun panelLayout(panel: PanelLayouts.Panel) = when (panel) {
            PanelLayouts.Panel.NUMBER -> numberKeyboardLayout
            PanelLayouts.Panel.SYMBOLS -> symbolsPanelLayout
        }

        /** Each panel's id as set ("": the app's). */
        fun panelLayouts(): Map<PanelLayouts.Panel, String> = PanelLayouts.Panel.entries.associateWith { panelLayout(it).getValue() }

        /** The layout of the user's [panel] draws as its file reads now (read by [custom]); null where the app's is drawn. */
        fun drawnPanelLayout(panel: PanelLayouts.Panel, custom: (String) -> CustomChoice? = CustomKeyboards::choice): CustomChoice? =
            panelLayout(panel).getValue().takeIf { it.isNotEmpty() }?.let { id -> PanelLayouts.drawn(panel, runCatching { custom(id) }.getOrNull()) }

        // [slot]'s ([ImKeyboards.slot]) layout of the user's
        private fun layoutPref(slot: String) = when (slot) {
            InputMethodCatalog.PINYIN -> pinyinKeyboardLayout
            InputMethodCatalog.SHUANGPIN -> shuangpinKeyboardLayout
            InputMethodCatalog.ENGLISH -> englishKeyboardLayout
            else -> tableKeyboardLayout
        }

        /**
         * Each input method's keyboard as set, for ImKeyboards to decide the layout from; the layouts of the user's chosen
         * read by [custom] (null: one that does not read), the app's own by default (CustomKeyboards: a file stat each,
         * read again where it changed).
         */
        fun imKeyboards(custom: (String) -> CustomChoice? = CustomKeyboards::choice): ImKeyboards.Chosen {
            val layouts = ImKeyboards.SLOTS.associateWith { layoutPref(it).getValue() }.filterValues { it.isNotEmpty() }
            val chosen = ImKeyboards.Chosen(
                pinyinKeyboard.getValue().keyboard,
                shuangpinKeyboard.getValue().keyboard,
                englishKeyboard.getValue().keyboard,
                if (ImKeyboards.TABLE in layouts) ImKeyboard.CUSTOM else ImKeyboard.FULL,
                layouts,
            )
            val used = ImKeyboards.SLOTS.mapNotNull { slot -> layouts[slot]?.takeIf { slot == ImKeyboards.TABLE || chosen.of(slot) == ImKeyboard.CUSTOM } }.toSet()
            // one that throws as it is read is one that does not read: its base drawn, never the keyboard (and the IME) gone
            return chosen.copy(customs = used.mapNotNull { id -> runCatching { custom(id) }.getOrNull()?.let { id to it } }.toMap())
        }

        /** [im]'s keyboard set to [keyboard], one it offers (ImKeyboards.choices); [layout] the id of the user's layout where it is [ImKeyboard.CUSTOM]. */
        fun setImKeyboard(im: String, keyboard: ImKeyboard, layout: String? = null) {
            if (keyboard == ImKeyboard.CUSTOM) layoutPref(ImKeyboards.slot(im)).setValue(requireNotNull(layout))
            when {
                im == InputMethodCatalog.PINYIN -> pinyinKeyboard.setValue(PinyinKeyboard.of(keyboard))
                im == InputMethodCatalog.SHUANGPIN -> shuangpinKeyboard.setValue(ShuangpinKeyboard.of(keyboard))
                im == InputMethodCatalog.ENGLISH -> englishKeyboard.setValue(EnglishKeyboard.of(keyboard))
                // the tables' one choice: the full keyboard, or the layout of the user's set above
                InputMethodCatalog.hasTableKeys(im) && keyboard != ImKeyboard.CUSTOM -> tableKeyboardLayout.setValue("")
            }
        }

        /** Shown on the English page (InputMethodSettings), not here: English's alone. */
        val englishAutoCaps = bool(ENGLISH_AUTO_CAPS, true)

        /**
         * Shown on the page of what every input method shares (InputMethodSettings.SHARED), one list
         * for what a finger drawn across the keys does ([KeyDrawing], GlideInput): a word glided across
         * its keys in every input method that glides (InputMethodCatalog.glides), a stroke drawn while
         * pinyin is typed that filters its candidates (笔画筛选, InputMethodCatalog.filtersByStrokes),
         * or nothing. Off till chosen: a fast tap may glide. 0.5's two switches move over ([dropRetired]);
         * 0.6.0's 键盘手写 is the toolbar's 手写 now ([migrateKeyHandwriting]).
         */
        val keyDrawing = stringLike(KEY_DRAWING, ManagedPreference.enumCodec<KeyDrawing>(), KeyDrawing.Off)

        /** Shown under it while 笔画筛选 is chosen: how far a stroke drawn goes before it is no tap ([StrokeLift]). */
        val strokeLift = stringLike(STROKE_LIFT, ManagedPreference.enumCodec<StrokeLift>(), StrokeLift.Medium)

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

        /** Shown on the shared page (InputMethodSettings, under 在键上划): how long the pen stays up before a character written on the keys is done. */
        val handwritingPause = stringLike(HANDWRITING_PAUSE, ManagedPreference.enumCodec<HandwritingPause>(), HandwritingPause.Medium)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden {
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
            val (primary, secondary) = hidden {
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
            val (primary, secondary) = hidden {
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
        val keyboardBottomFlush = hidden {
            int(R.string.keyboard_bottom_padding, "keyboard_bottom_flush", 0, 0, 1)
        }

        /** 贴底 chosen ([keyboardBottomFlush]). */
        val bottomFlush: Boolean get() = keyboardBottomFlush.getValue() == 1

        // 跟随主题 of each of the three (060-render-p3 T5): the theme's size where it gives one, else
        // the numbers above, which 跟随主题 puts back to their defaults. The default, for 0.6.0's own;
        // an upgrade's are ThemePrefs.migrateSizeFollow's (a size set kept, not following)
        val keyboardHeightFollowTheme = hidden {
            switch(R.string.follow_theme, "keyboard_height_follow_theme", true)
        }
        val keyboardSidePaddingFollowTheme = hidden {
            switch(R.string.follow_theme, "keyboard_side_padding_follow_theme", true)
        }
        val keyboardBottomPaddingFollowTheme = hidden {
            switch(R.string.follow_theme, "keyboard_bottom_padding_follow_theme", true)
        }

        init {
            section(R.string.section_sound_and_vibration)
        }

        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = hidden {
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
            val (primary, secondary) = hidden {
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
        // 按键音音色 (060-theme-sounds-icons §1.4): which sound, apart from whether one plays; the theme's
        // where it has one, so a theme without sounds sounds as before
        val keySoundSource = enumList(
            R.string.key_sound_source,
            "key_sound_source",
            KeySoundSource.FollowTheme
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        // a slider (owner, 2026-11-11): 0 the system's volume, shown 系统默认; a sound plays as it is let go
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default,
            preview = { InputFeedbacks.previewSound(it) }
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
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

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")
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
        // not tied to the history: they rule the favourites too, which stay usable with it off
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", true
        )
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        )
        val clipboardClearUrls = switch(
            R.string.clipboard_clear_urls,
            "clipboard_clear_urls",
            true,
            R.string.clipboard_clear_urls_summary
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    /**
     * Waits for the settings written so far to be on disk: a setting's write (apply) is written later on another thread,
     * and a commit, even of nothing, is written after them. Not on the main thread. For what copies the settings' files
     * over (a restore) after a write of another thread (R26 F8423).
     */
    fun awaitWritten() {
        sharedPreferences.edit(commit = true) {}
    }

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
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
                // every key's swipe: a password may be typed with it before the first unlock (its long press below)
                internal.keySwipes,
                // the bar is there before the first unlock too
                internal.toolbarButtons,
                advanced.disableAnimation,
                advanced.keyboardMotion,
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
            // every key's long press as stored, never the `@2` the setting reads where none is, which would hide the old
            // ones there for good. None stored (a 0.5 or beta backup imported, or 撤销 putting that back, not migrated till
            // the next start): the three old ones copied as they are, so a direct boot migrates them in memory
            // (KeyListsCache) as this side does, never left with neither (an earlier sync dropped the old ones there).
            // Stored: the old ones dropped there (060-s6-review L5: no direct boot reads them past that). Not synced at a
            // backup's import, whose settings file is copied over AppPrefs' (UserDataManager): migrated and synced at the start after
            val lists = storedKeyLists()
            if (lists == null) {
                remove(KEY_LISTS)
                listOf(POPUP_OVERRIDES, KEY_POPUPS, T9_SYMBOLS).forEach { key ->
                    // one this side has not: the copy's own kept (it is all a direct boot has)
                    storedOldLongPress(key)?.let { putString(key, it) }
                }
            } else {
                putString(KEY_LISTS, lists)
                listOf(POPUP_OVERRIDES, KEY_POPUPS, T9_SYMBOLS).forEach(::remove)
            }
        }
    }

    /**
     * The settings no version reads any more removed, as a 0.4 install or a backup of one has them,
     * and 标点位置 stored where 快捷键图标位置 is moved ([settleMarkPositions]): at every start, a
     * backup restored (an import) being read at the next.
     */
    fun dropRetired() {
        // gone with the old drawing (060-render-p2 S8): 0.6's betas' switch of the redraw, vivo's touch routing to
        // the key views (the scene's keys always take their touches so), and 扩大按键触摸区 (the engine always
        // gives a key its cell to the edge); voice_polish, the voice's 整理 switch, gone with it always on; and
        // last_candidate_reading, the 注音 style 0.6.0's handwriting pad's 注 key turned back on, gone with the pad;
        // a stored one, of any type, read by nothing
        val stale = RETIRED.filter(sharedPreferences::contains)
        if (stale.isNotEmpty()) sharedPreferences.edit { stale.forEach(::remove) }
        migrateSwipe()
        migrateSwipeSlots()
        migrateKeyDrawing()
        migrateKeyHandwriting()
        offerHandwriting()
        settleMarkPositions()
        migrateKeyLists()
    }

    /** `key_lists` as stored, null where it is not (or is another type): read so, nothing written back. */
    fun storedKeyLists(): String? = runCatching { sharedPreferences.getString(KEY_LISTS, null) }.getOrNull()

    /** `key_lists` [text] as stored, or removed where null (not stored yet: migrated at the next start, read from the old ones till then). */
    fun setStoredKeyLists(text: String?) = sharedPreferences.edit { if (text == null) remove(KEY_LISTS) else putString(KEY_LISTS, text) }

    /** An old long press as stored, null where it is not (or is another type): read so, nothing written back. */
    fun storedOldLongPress(key: String): String? = runCatching { sharedPreferences.getString(key, null) }.getOrNull()

    /**
     * 0.5's long presses ([POPUP_OVERRIDES], [T9_SYMBOLS]; beta2's [KEY_POPUPS] no longer read) as [Internal.keyLists]
     * (KeyListsMigration, 060-keys-unify §4.3), to `@1` then `@2` (060-row-unlock §5, its rows by the user's 按键字母):
     * run while it is neither (never stored, a 0.5, beta or `@1` backup restored), once. A later version's (`@4`) left
     * as it is. The old ones kept as they are, for a downgrade (§4.4).
     */
    private fun migrateKeyLists() {
        val sp = sharedPreferences
        // stored as another type (a bad backup): read as unset, never a crash at every start
        fun storedString(key: String) = runCatching { sp.getString(key, null) }.getOrNull()
        if (!KeyLists.needsMigration(storedKeyLists())) return
        val moved = KeyListsMigration.migrate(storedString(POPUP_OVERRIDES), storedString(T9_SYMBOLS))
        moved.lost.forEach { Timber.i("long press not kept: %s", it) }
        sp.edit { putString(KEY_LISTS, KeyListsMigration.upgrade(moved.text, KeyListsCache.nineLettersOf(storedString(T9_LETTERS)))) }
    }

    /**
     * 上滑图标位置 stored in 下滑图标位置's place, which the settings page never stores (an import, a
     * backup), stored as it reads ([KeyMarks.settled]). Read so only ([ManagedPreferenceUi.Apart.read]),
     * it was not what the page's list held: 下滑图标位置 moved away, the keyboard drew the up mark where
     * it was stored and the page's summary said where it had read.
     */
    private fun settleMarkPositions() {
        val up = keyboard.swipeUpPosition.getValue()
        val settled = settledUp(up, keyboard.swipeDownPosition.getValue())
        if (settled != up) keyboard.swipeUpPosition.setValue(settled)
    }

    /**
     * 0.5's 滑动 ([SWIPE_LAYERS]), the shortcuts set (`key_shortcuts`), 标点位置 and 快捷键图标位置 as
     * 0.6's slots, 上滑 and 下滑 and their places ([SwipeMigration]): what a swipe did on every key it
     * does after. Run while any of the old ones is stored (a 0.5 backup restored too) or the slots never
     * were; the old ones removed. 滑动 never set: 0.5's default on an upgrade ([PID] stored: a start ran
     * before), the new one on a new install.
     */
    private fun migrateSwipeSlots() {
        val sp = sharedPreferences
        if (OLD_SWIPE.none(sp::contains) && sp.contains(KEY_SWIPES)) return
        // each read as unset where it is stored as another type (a bad backup): never a crash at every start
        fun storedString(key: String) = runCatching { sp.getString(key, null) }.getOrNull()
        fun storedPosition(key: String, default: KeyMarkPosition) =
            storedString(key)?.let { v -> KeyMarkPosition.entries.firstOrNull { it.name == v } }?.at ?: default.at
        val layers = storedString(SWIPE_LAYERS)?.let { v -> SwipeLayersChoice.entries.firstOrNull { it.name == v } }?.layers
        val moved = SwipeMigration.of(
            layers,
            upgraded = sp.contains(PID),
            shortcuts = KeyShortcuts.parse(storedString(KEY_SHORTCUTS).orEmpty()),
            symbolAt = storedPosition(PUNCTUATION_POSITION, KeyMarkPosition.TopRight),
            shortcutAt = storedPosition(SHORTCUT_MARK_POSITION, KeyMarkPosition.TopLeft),
        )
        // a 0.6 setting already there (set, then a 0.5 backup's old ones merged in by hand) is the user's newer
        val keep = sp.contains(KEY_SWIPES)
        sp.edit {
            if (!keep) {
                putString(KEY_SWIPES, moved.slots.serialize())
                if (!moved.up) putBoolean(SWIPE_UP, false)
                if (!moved.down) putBoolean(SWIPE_DOWN, false)
                if (moved.upAt != KeyMarks.At.TopRight) putString(SWIPE_UP_POSITION, KeyMarkPosition.of(moved.upAt).name)
                if (moved.downAt != KeyMarks.At.TopLeft) putString(SWIPE_DOWN_POSITION, KeyMarkPosition.of(moved.downAt).name)
            }
            OLD_SWIPE.forEach(::remove)
        }
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
     * 0.5's two switches 滑行输入 and 笔画筛选 as 在键上划: glide if it was on, else the stroke filter if
     * that was, else off. Both on (the page never stored that: a backup, edited by hand) reads as glide, the
     * older and wider of the two. One set already is kept, the old two dropped all the same.
     */
    private fun migrateKeyDrawing() {
        if (!sharedPreferences.contains(GLIDE_TYPING) && !sharedPreferences.contains(STROKE_FILTER)) return
        // stored as another type: read as off, never a crash at every start
        fun on(key: String) = runCatching { sharedPreferences.getBoolean(key, false) }.getOrDefault(false)
        sharedPreferences.edit {
            if (!sharedPreferences.contains(KEY_DRAWING)) {
                val drawing = when {
                    on(GLIDE_TYPING) -> KeyDrawing.Glide
                    on(STROKE_FILTER) -> KeyDrawing.StrokeFilter
                    else -> KeyDrawing.Off
                }
                putString(KEY_DRAWING, drawing.name)
            }
            remove(GLIDE_TYPING)
            remove(STROKE_FILTER)
        }
    }

    /**
     * 0.6.0's 在键上划 = 键盘手写 as 关, the toolbar's 手写 pinned for it (061-handwriting-toolbar §6.2): not turned
     * on, as one that wrote on the keys only now and then would find the letters not typing. The stored name is
     * read as it is, not through [KeyDrawing], which no longer has it. Told once ([Internal.keyHandwritingUnseen]).
     */
    private fun migrateKeyHandwriting() {
        val stored = runCatching { sharedPreferences.getString(KEY_DRAWING, null) }.getOrNull()
        if (stored != OLD_KEY_HANDWRITING) return
        sharedPreferences.edit {
            putString(KEY_DRAWING, KeyDrawing.Off.name)
            putBoolean(internal.keyHandwritingUnseen.key, true)
        }
        pinHandwriting()
    }

    /**
     * The toolbar's 手写 shown first at the bar's right, where the user arranged the bar ([Internal.toolbarButtons]
     * stored); one never arranged has it so already (ToolbarConfig.DEFAULT).
     */
    fun pinHandwriting() {
        val stored = internal.toolbarButtons.getValue()
        if (stored.isEmpty()) return
        internal.toolbarButtons.setValue(ToolbarConfig.parse(stored).withHandwritingPinned().serialize())
    }

    /**
     * The toolbar's 手写 shown at the bar's right after 表情 where the user arranged the bar before it was there to
     * choose (ToolbarConfig.handwritingNeverOffered): at every start, a 0.6.0 backup restored too; one the user took
     * off since is left so.
     */
    private fun offerHandwriting() {
        val stored = internal.toolbarButtons.getValue()
        val firstLook = !internal.toolbarHandwritingLooked.getValue()
        if (ToolbarConfig.handwritingNeverOffered(stored, firstLook)) {
            internal.toolbarButtons.setValue(ToolbarConfig.parse(stored).withHandwritingOffered().serialize())
        }
        if (firstLook) internal.toolbarHandwritingLooked.setValue(true)
    }

    /**
     * At the start after an import of the user's data: the punctuation mark it brings is the backup's, saying nothing of
     * this phone's data; cleared, the table a restore could not delete is tried again (HostConfigDir.resetPunctuationOnce).
     */
    fun afterImport() {
        // on disk before the import's marker goes
        sharedPreferences.edit(commit = true) { putBoolean(internal.punctuationReset061.key, false) }
        Timber.i("punctuation mark cleared after an import")
    }

    companion object {
        private var instance: AppPrefs? = null

        // keys an input method's page names its rows by (InputMethodSettings); the others are only this file's
        const val T9_MULTI_TAP = "t9_multi_tap"
        const val T9_ENGLISH_TYPING = "t9_english_typing"
        const val PINYIN_KEYBOARD = "pinyin_keyboard"

        const val SHUANGPIN_KEYBOARD = "shuangpin_keyboard"
        const val ENGLISH_KEYBOARD = "english_keyboard"
        const val PINYIN_KEYBOARD_LAYOUT = "pinyin_keyboard_layout"
        const val SHUANGPIN_KEYBOARD_LAYOUT = "shuangpin_keyboard_layout"
        const val ENGLISH_KEYBOARD_LAYOUT = "english_keyboard_layout"
        const val TABLE_KEYBOARD_LAYOUT = "table_keyboard_layout"
        const val NUMBER_KEYBOARD_LAYOUT = "number_keyboard_layout"
        const val SYMBOLS_PANEL_LAYOUT = "symbols_panel_layout"

        // 0.4's: English on the nine keys while 九键's 中/英 had brought it there, gone with the key (I1)
        private const val ENGLISH_ON_NINE_KEYS = "english_on_nine_keys"
        private val RETIRED = listOf(
            ENGLISH_ON_NINE_KEYS, "canvas_keyboard", "vivo_keypress_workaround", "expand_keypress_area", "voice_polish", "last_candidate_reading",
        )

        // 0.5's swipe setting, and 0.5.1's that took its place
        private const val SWIPE_SYMBOL_BEHAVIOR = "swipe_symbol_behavior"
        const val SWIPE_LAYERS = "swipe_layers"

        // 0.6's swipe slots (060-swipe-slots), and the 0.5 settings they took the place of
        const val KEY_SWIPES = "key_swipes"
        const val SWIPE_UP = "swipe_up"
        const val SWIPE_DOWN = "swipe_down"
        const val SWIPE_UP_POSITION = "swipe_up_position"
        const val SWIPE_DOWN_POSITION = "swipe_down_position"
        private const val KEY_SHORTCUTS = "key_shortcuts"
        private const val PUNCTUATION_POSITION = "punctuation_position"
        private const val SHORTCUT_MARK_POSITION = "shortcut_mark_position"
        private val OLD_SWIPE = listOf(SWIPE_LAYERS, KEY_SHORTCUTS, PUNCTUATION_POSITION, SHORTCUT_MARK_POSITION)
        private const val PID = "pid"

        // 0.6's long presses of every key (060-keys-unify), and 0.5's and the betas' they are made of
        const val KEY_LISTS = "key_lists"
        const val POPUP_OVERRIDES = "popup_overrides"
        const val KEY_POPUPS = "key_popups"
        const val T9_SYMBOLS = "t9_symbols"

        /** 九键's letters as the user set them (按键字母): what `@1`'s rows upgrade by. */
        const val T9_LETTERS = "t9_letters"

        const val ENGLISH_AUTO_CAPS = "english_auto_caps"
        // 0.5's two switches, and 0.6's one list that took their place
        private const val GLIDE_TYPING = "glide_typing"
        private const val STROKE_FILTER = "stroke_filter"
        const val KEY_DRAWING = "key_drawing"
        const val STROKE_LIFT = "stroke_lift"

        // 0.6.0's 在键上划 = 键盘手写, now the toolbar's 手写 (migrateKeyHandwriting)
        private const val OLD_KEY_HANDWRITING = "Handwriting"

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
    ZhuyinFull(R.string.keyboard_zhuyin_full, ImKeyboard.ZHUYIN_FULL),

    // a layout of the user's: which one, pinyinKeyboardLayout (061-custom-layout §5.2)
    Custom(R.string.keyboard_custom, ImKeyboard.CUSTOM);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

/** 双拼's on-screen keyboard ([ImKeyboard]): the full one, the 18 keys, the 14 keys, or the nine keys (many more alike). Kept by name: never rename one. */
enum class ShuangpinKeyboard(override val stringRes: Int, val keyboard: ImKeyboard) : ManagedPreferenceEnum {
    Full(R.string.pinyin_keyboard_full, ImKeyboard.FULL),
    Keys18(R.string.keyboard_18, ImKeyboard.KEYS18),
    Keys14(R.string.pinyin_keyboard_14, ImKeyboard.KEYS14),
    NineKeys(R.string.keyboard_nine, ImKeyboard.NINE_KEYS),

    // a layout of the user's: which one, shuangpinKeyboardLayout
    Custom(R.string.keyboard_custom, ImKeyboard.CUSTOM);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

/** English's on-screen keyboard ([ImKeyboard]): the 26 keys or the nine keys. Kept by name: never rename one. */
enum class EnglishKeyboard(override val stringRes: Int, val keyboard: ImKeyboard) : ManagedPreferenceEnum {
    Full(R.string.pinyin_keyboard_full, ImKeyboard.FULL),
    NineKeys(R.string.keyboard_nine, ImKeyboard.NINE_KEYS),

    // a layout of the user's: which one, englishKeyboardLayout
    Custom(R.string.keyboard_custom, ImKeyboard.CUSTOM);

    companion object {
        fun of(keyboard: ImKeyboard) = entries.first { it.keyboard == keyboard }
    }
}

// where the up mark is drawn with the down one's at [down]: moved off it if it is there (KeyMarks.settled)
private fun settledUp(up: KeyMarkPosition, down: KeyMarkPosition) =
    KeyMarkPosition.of(KeyMarks.settled(up.at, down.at).symbol)
