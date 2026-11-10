/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.LicensesFragment
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.PacksFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.HardwareKeyboardFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodListFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.SharedInputFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment

@Parcelize
sealed class SettingsRoute : Parcelable {

    /* ========== Index ========== */

    @Serializable
    data object Index : SettingsRoute()

    /* ========== Fcitx ========== */

    @Serializable
    data object InputMethodList : SettingsRoute()

    @Serializable
    data class InputMethodConfig(val name: String, val uniqueName: String) : SettingsRoute()

    /** What every input method shares (InputMethodSettings.SHARED). */
    @Serializable
    data object SharedInput : SettingsRoute()

    /* ========== Android ========== */

    @Serializable
    data object Theme : SettingsRoute()

    /** The keys (按键), one of the two pages of AppPrefs.Keyboard. */
    @Serializable
    data object Keys : SettingsRoute()

    /** The sound and vibration, the other. */
    @Serializable
    data object SoundAndVibration : SettingsRoute()

    /** The physical keyboard's page: its candidate window, its shortcuts. */
    @Serializable
    data object HardwareKeyboard : SettingsRoute()

    /** The clipboard and the emoji. */
    @Serializable
    data object Clipboard : SettingsRoute()

    @Serializable
    data object Backup : SettingsRoute()

    /** The resource packs: what is imported, and the import (PacksFragment). */
    @Serializable
    data object Packs : SettingsRoute()

    @Serializable
    data object Developer : SettingsRoute()

    @Serializable
    data object License : SettingsRoute()

    @Serializable
    data object About : SettingsRoute()

    /* ========== External ========== */

    @Serializable
    data class PinyinDict(val uri: String? = null) : SettingsRoute() {
        constructor(uri: Uri) : this(uri.toString())
    }

    /** The punctuation editor, titled [title] (the app's own name for it when null). */
    @Serializable
    data class Punctuation(val title: String? = null, val lang: String? = null) : SettingsRoute()

    @Serializable
    data object PinyinCustomPhrase : SettingsRoute()

    /**
     * 快速输入's page; [edit]: its editor open at once on [key], [content], [note] and [folder], an
     * entry the user has when [existing] (else a new one filled in so), as the keyboard opens it.
     */
    @Serializable
    data class QuickPhrase(
        val edit: Boolean = false,
        val key: String = "",
        val content: String = "",
        val existing: Boolean = false,
        val note: String = "",
        val folder: String = "",
    ) : SettingsRoute() {
        /** The entry the editor opens on. */
        val entry get() = QuickPhraseEntry(key, content, note, folder)
    }

    @Serializable
    data object UserWords : SettingsRoute()

    /** The learning's backups (LearningBackupsFragment). */
    @Serializable
    data object LearningBackups : SettingsRoute()

    @Serializable
    data class UserWordList(val kind: String, val english: Boolean = false) : SettingsRoute()

    /** The pins or the predictions turned off ([LearnedItemsFragment]), [what] a LearningOp.Listed's name. */
    @Serializable
    data class LearnedItems(val what: String) : SettingsRoute()

    @Serializable
    data object PopupOverrides : SettingsRoute()

    // not NineKeyLetters, the letters' class that NineKeyLettersFragment edits
    @Serializable
    data object T9Letters : SettingsRoute()

    @Serializable
    data object T9Symbols : SettingsRoute()

    /** 「通用输入 → 网址和邮箱」 (AddressSettingsFragment). */
    @Serializable
    data object Addresses : SettingsRoute()

    /** One of its two lists, [kind] AddressSuffixesFragment's EMAIL or URL. */
    @Serializable
    data class AddressSuffixes(val kind: String) : SettingsRoute()

    @Serializable
    data object ToolbarButtons : SettingsRoute()
    @Serializable
    data object Search : SettingsRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Index) {
            val ctx = controller.context

            /* ========== Index ========== */

            fragment<MainFragment, Index> {
                label = ctx.getString(R.string.app_name)
            }

            /* ========== Fcitx ========== */

            fragment<InputMethodListFragment, InputMethodList> {
                label = ctx.getString(R.string.input_methods)
            }
            fragment<InputMethodConfigFragment, InputMethodConfig>()
            fragment<SharedInputFragment, SharedInput>()

            /* ========== Android ========== */

            fragment<ThemeFragment, Theme> {
                label = ctx.getString(R.string.theme_and_size)
            }
            // titled as the home page's rows that open them (MainFragment.HOME)
            fragment<KeysSettingsFragment, Keys> {
                label = ctx.getString(R.string.virtual_keyboard)
            }
            fragment<SoundSettingsFragment, SoundAndVibration> {
                label = ctx.getString(R.string.section_sound_and_vibration)
            }
            fragment<HardwareKeyboardFragment, HardwareKeyboard> {
                label = ctx.getString(R.string.hardware_keyboard)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard_and_emoji)
            }
            fragment<BackupFragment, Backup> {
                label = ctx.getString(R.string.user_data)
            }
            fragment<PacksFragment, Packs> {
                label = ctx.getString(R.string.packs)
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<LicensesFragment, License> {
                label = ctx.getString(R.string.license)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }

            /* ========== External ========== */

            fragment<PinyinDictionaryFragment, PinyinDict> {
                label = ctx.getString(R.string.word_packs)
            }
            fragment<UserWordsFragment, UserWords> {
                label = ctx.getString(R.string.my_words)
            }
            fragment<UserWordListFragment, UserWordList>()
            fragment<LearnedItemsFragment, LearnedItems>()
            fragment<LearningBackupsFragment, LearningBackups> {
                label = ctx.getString(R.string.learning_backups)
            }
            fragment<PunctuationEditorFragment, Punctuation>()
            fragment<PinyinCustomPhraseFragment, PinyinCustomPhrase> {
                label = ctx.getString(R.string.custom_phrases)
            }
            fragment<QuickPhraseFragment, QuickPhrase> {
                label = ctx.getString(R.string.quick_phrases)
            }
            fragment<PopupOverridesFragment, PopupOverrides> {
                label = ctx.getString(R.string.long_press_characters)
            }
            fragment<NineKeyLettersFragment, T9Letters> {
                label = ctx.getString(R.string.t9_letters)
            }
            fragment<NineKeySymbolsFragment, T9Symbols> {
                label = ctx.getString(R.string.t9_symbols)
            }
            fragment<ToolbarButtonsFragment, ToolbarButtons> {
                label = ctx.getString(R.string.toolbar_buttons)
            }
            fragment<AddressSettingsFragment, Addresses> {
                label = ctx.getString(R.string.address_suggestions)
            }
            fragment<AddressSuffixesFragment, AddressSuffixes>()
            fragment<SettingsSearchFragment, Search> {
                label = ctx.getString(R.string.search_settings)
            }
        }
    }
}
