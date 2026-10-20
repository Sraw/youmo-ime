/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import androidx.savedstate.SavedState
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.AboutFragment
import org.fcitx.fcitx5.android.ui.main.DeveloperFragment
import org.fcitx.fcitx5.android.ui.main.LicensesFragment
import org.fcitx.fcitx5.android.ui.main.MainFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.BackupFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.HardwareKeyboardFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.ClipboardSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeysSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SoundSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodListFragment
import org.fcitx.fcitx5.android.ui.main.settings.im.SharedInputFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsSearchFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.parcelable
import kotlin.reflect.typeOf

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

    @Serializable
    data object Developer : SettingsRoute()

    @Serializable
    data object License : SettingsRoute()

    @Serializable
    data object About : SettingsRoute()

    /* ========== External ========== */

    @Serializable
    data class ListConfig(val params: Params) : SettingsRoute() {
        @Parcelize
        @Serializable
        data class Params(val cfg: RawConfig, val desc: ConfigDescriptor<*, *>) : Parcelable {
            companion object {
                // https://developer.android.com/guide/navigation/design/kotlin-dsl#custom-types
                val NavType = object : NavType<Params>(isNullableAllowed = false) {
                    override fun put(bundle: SavedState, key: String, value: Params) {
                        bundle.putParcelable(key, value)
                    }

                    override fun get(bundle: SavedState, key: String): Params? {
                        return bundle.parcelable<Params>(key)
                    }

                    override fun serializeAsValue(value: Params): String {
                        // Serialized values must always be Uri encoded
                        return Uri.encode(Json.encodeToString(value))
                    }

                    override fun parseValue(value: String): Params {
                        // Navigation decodes the string before passing it to parseValue()
                        return Json.decodeFromString(value)
                    }
                }
            }
        }

        constructor(cfg: RawConfig, desc: ConfigDescriptor<*, *>) : this(Params(cfg, desc))

        val desc: ConfigDescriptor<*, *>
            get() = params.desc
        val cfg: RawConfig
            get() = params.cfg
    }

    @Serializable
    data class PinyinDict(val uri: String? = null) : SettingsRoute() {
        constructor(uri: Uri) : this(uri.toString())
    }

    /** The punctuation editor, titled [title] (the app's own name for it when null). */
    @Serializable
    data class Punctuation(val title: String? = null, val lang: String? = null) : SettingsRoute()

    @Serializable
    data object PinyinCustomPhrase : SettingsRoute()

    @Serializable
    data object UserWords : SettingsRoute()

    @Serializable
    data class UserWordList(val kind: String, val english: Boolean = false) : SettingsRoute()

    @Serializable
    data object PopupOverrides : SettingsRoute()

    // not NineKeyLetters, the letters' class that NineKeyLettersFragment edits
    @Serializable
    data object T9Letters : SettingsRoute()

    @Serializable
    data object T9Symbols : SettingsRoute()

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

            fragment<ListFragment, ListConfig>(
                typeMap = mapOf(typeOf<ListConfig.Params>() to ListConfig.Params.NavType)
            )
            fragment<PinyinDictionaryFragment, PinyinDict> {
                label = ctx.getString(R.string.word_packs)
            }
            fragment<UserWordsFragment, UserWords> {
                label = ctx.getString(R.string.my_words)
            }
            fragment<UserWordListFragment, UserWordList>()
            fragment<PunctuationEditorFragment, Punctuation>()
            fragment<PinyinCustomPhraseFragment, PinyinCustomPhrase> {
                label = ctx.getString(R.string.custom_phrases)
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
            fragment<SettingsSearchFragment, Search> {
                label = ctx.getString(R.string.search_settings)
            }
        }
    }
}
