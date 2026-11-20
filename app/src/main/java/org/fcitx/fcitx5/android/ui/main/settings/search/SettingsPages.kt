/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.settings.AddressSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.CommonWordsFragment
import org.fcitx.fcitx5.android.ui.main.settings.LearningBackupsFragment
import org.fcitx.fcitx5.android.ui.main.settings.NineKeyLettersFragment
import org.fcitx.fcitx5.android.ui.main.settings.PinyinCustomPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.PinyinDictionaryFragment
import org.fcitx.fcitx5.android.ui.main.settings.PopupOverridesFragment
import org.fcitx.fcitx5.android.ui.main.settings.QuickPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.RareCharsFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.ToolbarButtonsFragment
import org.fcitx.fcitx5.android.ui.main.settings.UserWordsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.HardwareKeyboardFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PacksFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeSettingsFragment
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Entry

/** Every [SettingsRoute] page and how search finds its contents, in home-page order: a page missing here fails SettingsPagesTest. */
object SettingsPages {

    /** Found with a page by the index itself: a key swipe's commands, fcitx's own options. */
    fun interface More {
        fun add(context: Context, path: List<String>, into: MutableList<Entry<SettingsIndex.Target>>)
    }

    sealed interface Source {
        /** Its language row, which opens no page. */
        data object Index : Source

        /**
         * Built as its fragment builds it, never shown, read for its preferences; [also]: hand-drawn extras (外观's built-in
         * themes), held by their own test. The rest drawn there imports or opens the user's own, not searched.
         */
        class Managed(
            val open: SettingsRoute, @StringRes val title: Int, val build: (PreferenceScreen) -> Unit,
            val also: SearchablePage? = null, val more: More? = null,
        ) : Source

        /** [listed] false: its row is a setting on another page (网址和邮箱 on 通用输入). [demoted]: all a step lower (开发者). */
        class Hand(
            val open: SettingsRoute, @StringRes val title: Int, val page: SearchablePage,
            val under: List<Int> = emptyList(), @StringRes val summary: Int = 0,
            val listed: Boolean = true, val demoted: Boolean = false, val more: More? = null,
        ) : Source

        /** Read from what the page is built of: InputMethodSettings. */
        class Listed(val more: More) : Source

        class Elsewhere(val why: String) : Source

        class NotSearched(val why: String) : Source
    }

    val PAGES: List<Pair<Class<out SettingsRoute>, Source>> = listOf(
        SettingsRoute.InputMethodList::class.java to Source.Listed { ctx, _, into -> SettingsIndex.inputMethods(ctx, into) },
        SettingsRoute.InputMethodConfig::class.java to Source.Elsewhere("each built-in input method's, with the input method list"),
        // its row is a setting on each input method's page (InputMethodSettings.NINE_KEYS), found there
        SettingsRoute.T9Letters::class.java to Source.Hand(
            SettingsRoute.T9Letters, R.string.t9_letters, NineKeyLettersFragment, under = listOf(R.string.input_methods), listed = false,
        ),
        SettingsRoute.SharedInput::class.java to Source.Listed { ctx, _, into -> SettingsIndex.sharedInput(ctx, into) },
        SettingsRoute.Addresses::class.java to Source.Hand(
            SettingsRoute.Addresses, R.string.address_suggestions, AddressSettingsFragment, under = listOf(R.string.shared_input), listed = false,
        ),
        SettingsRoute.AddressSuffixes::class.java to Source.NotSearched("the endings themselves, the user's to add; each list's row is on 网址和邮箱"),
        SettingsRoute.PinyinDict::class.java to Source.Hand(SettingsRoute.PinyinDict(""), R.string.word_packs, PinyinDictionaryFragment),
        SettingsRoute.UserWords::class.java to Source.Hand(SettingsRoute.UserWords, R.string.my_words, UserWordsFragment, under = listOf(R.string.word_packs)),
        SettingsRoute.LearningBackups::class.java to Source.Hand(
            SettingsRoute.LearningBackups, R.string.learning_backups, LearningBackupsFragment, under = listOf(R.string.word_packs, R.string.my_words),
        ),
        SettingsRoute.UserWordList::class.java to Source.NotSearched("the user's words: the page has its own list"),
        SettingsRoute.LearnedItems::class.java to Source.NotSearched("what was learned: the page has its own list"),
        SettingsRoute.PinyinCustomPhrase::class.java to Source.Hand(
            SettingsRoute.PinyinCustomPhrase, R.string.custom_phrases, PinyinCustomPhraseFragment, under = listOf(R.string.word_packs),
        ),
        SettingsRoute.CommonWords::class.java to Source.Hand(SettingsRoute.CommonWords, R.string.common_words, CommonWordsFragment, under = listOf(R.string.word_packs)),
        SettingsRoute.RareChars::class.java to Source.Hand(SettingsRoute.RareChars, R.string.rare_chars, RareCharsFragment, under = listOf(R.string.word_packs)),
        SettingsRoute.QuickPhrase::class.java to Source.Hand(SettingsRoute.QuickPhrase(), R.string.quick_phrases, QuickPhraseFragment, under = listOf(R.string.word_packs)),
        SettingsRoute.Theme::class.java to Source.Managed(SettingsRoute.Theme, R.string.theme_and_size, { ThemeSettingsFragment.build(it) }, also = ThemeFragment),
        SettingsRoute.Keys::class.java to Source.Managed(SettingsRoute.Keys, R.string.virtual_keyboard, { KeysSettingsFragment.build(it) }),
        SettingsRoute.PopupOverrides::class.java to Source.Hand(
            SettingsRoute.PopupOverrides, R.string.long_press_characters, PopupOverridesFragment, under = listOf(R.string.virtual_keyboard),
            summary = R.string.long_press_characters_summary, more = { ctx, path, into -> SettingsIndex.commands(ctx, path, into) },
        ),
        SettingsRoute.ToolbarButtons::class.java to Source.Hand(
            SettingsRoute.ToolbarButtons, R.string.toolbar_buttons, ToolbarButtonsFragment, under = listOf(R.string.virtual_keyboard),
            summary = R.string.toolbar_buttons_summary,
        ),
        SettingsRoute.SoundAndVibration::class.java to Source.Managed(SettingsRoute.SoundAndVibration, R.string.section_sound_and_vibration, { SoundSettingsFragment.build(it) }),
        SettingsRoute.Clipboard::class.java to Source.Managed(SettingsRoute.Clipboard, R.string.clipboard_and_emoji, { ClipboardSettingsFragment.build(it) }),
        // and fcitx's own, added once fcitx answers
        SettingsRoute.HardwareKeyboard::class.java to Source.Managed(
            SettingsRoute.HardwareKeyboard, R.string.hardware_keyboard, { HardwareKeyboardFragment.build(it) },
            more = { ctx, path, into -> SettingsIndex.hardwareItems(ctx, path, into) },
        ),
        SettingsRoute.Backup::class.java to Source.Hand(SettingsRoute.Backup, R.string.user_data, BackupFragment),
        SettingsRoute.Packs::class.java to Source.Hand(SettingsRoute.Packs, R.string.packs, PacksFragment),
        SettingsRoute.Index::class.java to Source.Index,
        SettingsRoute.Developer::class.java to Source.Hand(SettingsRoute.Developer, R.string.developer, DeveloperFragment, demoted = true),
        SettingsRoute.About::class.java to Source.Hand(SettingsRoute.About, R.string.about, AboutFragment),
        SettingsRoute.License::class.java to Source.NotSearched("the libraries' licenses, a list of their names; its row is on 关于"),
        SettingsRoute.Search::class.java to Source.NotSearched("the search itself"),
    )
}
