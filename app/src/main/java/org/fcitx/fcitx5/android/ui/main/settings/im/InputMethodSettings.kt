/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import org.fcitx.fcitx5.android.input.keyboard.KeyboardLabels
import org.fcitx.fcitx5.android.pack.PackKind
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.ShuangpinNames
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.settings.LearningRestore
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute

/** Does an [InputMethodSettings.Action], then tells whether it was done, on the main thread. */
typealias ImAct = (InputMethodSettings.Action, done: (Boolean) -> Unit) -> Unit

/** Asks the user to confirm an [InputMethodSettings.Action], `then` done on yes. */
typealias ImConfirm = (Context, InputMethodSettings.Action, then: () -> Unit) -> Unit

/**
 * The pages of the app's own input methods, in its own words: fcitx stores the values (the keys
 * are androidengine's options), while what an option is called, which of
 * them a user sees and how they are grouped is decided here. fcitx's own descriptions came in
 * fcitx's language, not the app's, and named every knob the engine has.
 *
 * What every input method shares is on a page of its own, [SHARED], over pinyin's config: the
 * addon keeps one value of each for all (androidengine's config_), and pinyin's page has them
 * all. On each input method's page they were shown again and again, "the same as pinyin's"; each
 * page now has only its own and a way to the shared one. A page saves only what it shows
 * ([only]), so one left open behind another does not write back an older value of what the other changed.
 */
object InputMethodSettings {

    sealed interface Item

    /** An item titled and summed up by string resources (0 for no summary): the settings search finds it by its [key]. */
    sealed interface Titled : Item {
        val key: String
        @get:StringRes val title: Int
        @get:StringRes val summary: Int
        /** The input methods (unique names) it is no use to, said under it: on the shared page, which says it for all. */
        val notFor: List<String>
    }

    /** [key] (a/b for b under a) is "True" or "False", unless [read] and [write] say otherwise; [notFor] as [Titled.notFor]. */
    class Toggle(
        val key: String,
        val title: (Context) -> String,
        @StringRes val summary: Int = 0,
        val read: (String) -> Boolean = { it == "True" },
        val write: (Boolean) -> String = { if (it) "True" else "False" },
        val notFor: List<String> = emptyList(),
    ) : Item

    /** One of [values]; a value stored that is not one of them shows as the one [nearest] to it. */
    class Choice(
        override val key: String,
        @StringRes override val title: Int,
        val values: List<String>,
        @StringRes override val summary: Int = 0,
        val label: (Context, String) -> String,
        val nearest: (String) -> String = { it },
        override val notFor: List<String> = emptyList(),
    ) : Titled

    /**
     * Switches of the same kind, picked together in one dialog: [flags] of key and label, under
     * [path]; [notFor] says under a flag the input methods it is no use to, as [Titled.notFor].
     */
    class Flags(
        val path: String,
        @StringRes val title: Int,
        @StringRes val none: Int,
        val flags: List<Pair<String, String>>,
        val notFor: Map<String, List<String>> = emptyMap(),
    ) : Item

    /**
     * One of [values], kept by the app (the keyboard reads it), not by fcitx: the preference
     * [key], [pref] asked for only when the page is made. [offered], of the page's options, those
     * listed (双拼's keyboards by its scheme), a value set that is not one shown as [instead] has it
     * (the first by default); [shown], whether it is there at all. All read again as anything on the
     * page changes.
     */
    class AppChoice<T>(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val values: List<T>,
        override val notFor: List<String> = emptyList(),
        val offered: (Map<String, String>) -> List<T> = { values },
        val instead: (T, Map<String, String>) -> T? = { _, _ -> null },
        val shown: (Map<String, String>) -> Boolean = { true },
        val pref: () -> ManagedPreference.PStringLike<T>,
    ) : Titled where T : Enum<T>, T : ManagedPreferenceEnum

    /** A switch over the app's own preference [pref], as [AppChoice] is a list. */
    class AppToggle(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        override val notFor: List<String> = emptyList(),
        val pref: () -> ManagedPreference<Boolean>,
    ) : Titled

    /** A page of its own, [route], as the app's settings open one: not an option of fcitx's. [shown] as [AppChoice.shown]. */
    class Page(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val route: SettingsRoute,
        override val notFor: List<String> = emptyList(),
        val shown: (Map<String, String>) -> Boolean = { true },
    ) : Titled

    /**
     * A button that does [run] to the page's input method, the user asked to [confirm] it first: no option of fcitx's.
     * [run] says whether it was done: [done] is shown only then, a failure otherwise.
     */
    class Action(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        @StringRes val confirm: Int,
        @StringRes val done: Int,
        override val notFor: List<String> = emptyList(),
        val run: suspend HostApi.(im: String) -> Boolean,
    ) : Titled

    /**
     * A group of [items] under [title]; [shown] as [AppChoice.shown], the group and all in it. [via],
     * the key of the row that shows it when hidden by [shown] (a keyboard choice): settings search
     * opens that row for what is in the group, which may not be there to open.
     */
    class Section(
        @StringRes val title: Int,
        val items: List<Item>,
        val shown: (Map<String, String>) -> Boolean = { true },
        val via: String? = null,
    ) : Item

    private fun text(@StringRes id: Int): (Context) -> String = { it.getString(id) }

    private fun numbers(range: IntRange): (String) -> String = { v ->
        v.toIntOrNull()?.coerceIn(range)?.toString() ?: range.first.toString()
    }

    private val PAGE_SIZES = EngineSettings.PAGE_SIZES.map { it.toString() }

    private val READING_STYLES = mapOf(
        ReadingStyle.OFF to R.string.im_candidate_reading_off,
        ReadingStyle.PINYIN to R.string.im_candidate_reading_pinyin,
        ReadingStyle.ZHUYIN to R.string.im_candidate_reading_zhuyin,
    )

    private val MODEL_LEVELS = mapOf(
        ModelLevel.LOW to R.string.im_model_level_low,
        ModelLevel.MEDIUM to R.string.im_model_level_medium,
        ModelLevel.HIGH to R.string.im_model_level_high,
    )

    // keyed by Fuzzy's names, as the engine reads them (EngineOptions.toSettings)
    private val FUZZY = listOf(
        Fuzzy.V_U to "u ↔ v", Fuzzy.AN_ANG to "an ↔ ang", Fuzzy.EN_ENG to "en ↔ eng", Fuzzy.IAN_IANG to "ian ↔ iang",
        Fuzzy.IN_ING to "in ↔ ing", Fuzzy.U_OU to "u ↔ ou", Fuzzy.UAN_UANG to "uan ↔ uang", Fuzzy.C_CH to "c ↔ ch",
        Fuzzy.F_H to "f ↔ h", Fuzzy.L_N to "l ↔ n", Fuzzy.L_R to "l ↔ r", Fuzzy.S_SH to "s ↔ sh", Fuzzy.Z_ZH to "z ↔ zh",
    ).map { (fuzzy, label) -> fuzzy.name to label }

    /** What every input method shares: its page's key, the one row on each input method's page that opens it. */
    const val SHARED_LINK = "shared_input"

    /** The shared page's row of the address suggestions (AddressSettingsFragment): its key. */
    const val ADDRESSES = "addresses"

    /** The shared page's row of the Chinese punctuation: its key. */
    const val PUNCTUATION = "punctuation"

    /** What every input method shares, each with those it is no use to (Engines has which reads what). */
    val SHARED: List<Item> = listOf(
        Section(
            R.string.shared_section_all, listOf(
                // the keyboard's, one of: a word by gliding across its keys (InputMethodCatalog.glides), a stroke
                // filtering pinyin's and 双拼's candidates (InputMethodCatalog.filtersByStrokes), or off; which input
                // methods read each is the docs' (the summary is the choice alone). 键盘手写 always listed, said to need
                // the text pack while it is missing (keyboardLabel): a ListPreference cannot disable one entry
                AppChoice(AppPrefs.KEY_DRAWING, R.string.key_drawing, 0, KeyDrawing.entries) { AppPrefs.getInstance().keyboard.keyDrawing },
                AppToggle(AppPrefs.AUTO_PAIRS, R.string.auto_pairs, 0) { AppPrefs.getInstance().keyboard.autoPairs },
                AppToggle(AppPrefs.CALCULATION_REPLACES_SUM, R.string.calculation_replaces_sum, 0) {
                    AppPrefs.getInstance().keyboard.calculationReplacesSum
                },
                Page(ADDRESSES, R.string.address_suggestions, R.string.address_suggestions_summary, SettingsRoute.Addresses),
            )
        ),
        // the section's title says English reads none of these: only an exception within it is said under a row
        Section(
            R.string.shared_section_chinese, listOf(
                Toggle(EngineSettings.PREDICTION, text(R.string.im_prediction), R.string.im_prediction_summary),
                // handwriting writes no word an emoji stands for
                Toggle(EngineSettings.EMOJI, text(R.string.im_emoji), R.string.im_emoji_summary, notFor = listOf(InputMethodCatalog.HANDWRITING)),
                // 全拼's rq, sj, xq, and in each the words 日期, 时间, 星期 (PhraseBook.WORD_KEYS); handwriting writes none of them
                Toggle(EngineSettings.BUILT_IN_PHRASES, text(R.string.im_built_in_phrases), R.string.im_built_in_phrases_summary, notFor = listOf(InputMethodCatalog.HANDWRITING)),
                Choice(
                    EngineSettings.MODEL_LEVEL, R.string.im_model_level, ModelLevel.entries.map { it.key }, R.string.im_model_level_summary,
                    label = { ctx, v -> ctx.getString(MODEL_LEVELS.getValue(ModelLevel.entries.first { it.key == v })) },
                    // what the engine reads as the default (EngineOptions.toSettings)
                    nearest = { v -> ModelLevel.entries.firstOrNull { it.key == v }?.key ?: ModelLevel.HIGH.key },
                ),
                // 候选词注音: pinyin above each character or zhuyin beside it, on every Chinese input method's candidates
                Choice(
                    EngineSettings.CANDIDATE_READING, R.string.im_candidate_reading, ReadingStyle.entries.map { it.key }, R.string.im_candidate_reading_summary,
                    label = { ctx, v -> ctx.getString(READING_STYLES.getValue(ReadingStyle.of(v))) },
                    nearest = { v -> ReadingStyle.of(v).key },
                ),
                // the punctuation addon's own page went with the list of addons
                Page(PUNCTUATION, R.string.punctuation_map, R.string.punctuation_map_summary, SettingsRoute.Punctuation()),
            )
        ),
        Section(
            R.string.shared_section_pinyin, listOf(
                // pinyin's grouped keyboards type keys, not letters: no letter slips onto its neighbour. 双拼's
                // one slip read on them, ü typed v, is none on its nine keys: u and v share 8 (the 18 and 14 keys keep them apart)
                Toggle(
                    "${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", text(R.string.im_typos), R.string.im_typos_summary,
                    notFor = listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.SHUANGPIN_NINE, InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN),
                ),
                // u and v are both on 8 of pinyin's nine keys; zhuyin's keep ㄨ and ㄩ apart (ㄌㄨ for ㄌㄩ is a slip there)
                Flags(EngineSettings.FUZZY, R.string.im_fuzzy, R.string.im_fuzzy_none, FUZZY, notFor = mapOf(Fuzzy.V_U.name to listOf(InputMethodCatalog.T9, InputMethodCatalog.SHUANGPIN_NINE))),
            )
        ),
    )

    /**
     * The physical keyboard's page's own of fcitx's options: how many candidates its window shows,
     * which no touch keyboard's bar has (it shows as many as fit), every input method's alike.
     */
    val HARDWARE_KEYBOARD: List<Item> = listOf(
        Choice(EngineSettings.PAGE_SIZE, R.string.im_page_size, PAGE_SIZES, R.string.im_page_size_summary, label = { _, v -> v }, nearest = numbers(EngineSettings.PAGE_SIZES)),
    )

    private val sharedLink = Page(SHARED_LINK, R.string.shared_input_link, R.string.shared_input_link_summary, SettingsRoute.SharedInput)

    // the nine keys' own settings as a section 九键 on the page whose keyboard they are, there while the nine
    // keys are chosen (the user's 11-01: not a row opening a page of 按键); [items] what of them that keyboard reads
    private fun nineKeysSection(items: List<Item>, keyboard: String, chosen: () -> Boolean) =
        Section(R.string.keyboard_nine, items, shown = { chosen() }, via = keyboard)

    // how a digit pressed again types: pinyin's and English's nine keys (九键双拼's letters are its scheme's)
    private val multiTap = AppChoice(AppPrefs.T9_MULTI_TAP, R.string.t9_multi_tap, R.string.t9_multi_tap_summary, T9MultiTap.entries) {
        AppPrefs.getInstance().keyboard.t9MultiTap
    }
    private val nineKeyLetters = Page(T9_LETTERS, R.string.t9_letters, R.string.t9_letters_summary, SettingsRoute.T9Letters)

    // the long-press symbols: every nine keys', 九键双拼's too
    private val nineKeySymbols = Page(T9_SYMBOLS, R.string.t9_symbols, R.string.t9_symbols_summary, SettingsRoute.T9Symbols)

    /** The nine keys' settings pinyin's and English's share: one pref each, whichever page sets it. */
    val NINE_KEYS: List<Item> = listOf(multiTap, nineKeyLetters, nineKeySymbols)

    // which are no use on pinyin's grouped keyboards
    private val pinyinKeys = listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS, InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN)

    private val pinyin = listOf(
        sharedLink,
        // the keyboard's: the full keyboard, the 18 or 14 keys or the nine keys, the engine reading each (KeyboardPair). First,
        // the page's main choice, and in no section: a section 键盘 over a row 键盘 said it twice
        AppChoice(AppPrefs.PINYIN_KEYBOARD, R.string.pinyin_keyboard, R.string.pinyin_keyboard_summary, PinyinKeyboard.entries) {
            AppPrefs.getInstance().keyboard.pinyinKeyboard
        },
        nineKeysSection(NINE_KEYS, AppPrefs.PINYIN_KEYBOARD) { AppPrefs.getInstance().keyboard.pinyinKeyboard.getValue() == PinyinKeyboard.NineKeys },
        Section(
            R.string.im_section_typing, listOf(
                // full pinyin's alone: 双拼's u is a key of its own, the grouped keyboards' no letter. Not hidden
                // on them: a hardware keyboard still types full pinyin and reads both
                Toggle(EngineSettings.CHAIZI, text(R.string.im_chaizi), R.string.im_chaizi_summary, notFor = pinyinKeys),
                // full pinyin's alone: 双拼 types its letters otherwise, the grouped keyboards their keys
                Toggle(EngineSettings.LATIN_WORDS, text(R.string.im_latin_words), R.string.im_latin_words_summary, notFor = pinyinKeys),
            )
        ),
    )

    // 双拼's scheme as the page's options have it, as the engine reads it (EngineSettings)
    private fun schemeOf(options: Map<String, String>) =
        EngineSettings.SCHEMES[options[EngineSettings.SHUANGPIN_PROFILE]] ?: EngineSettings.SCHEMES.getValue(EngineSettings.XIAOHE)

    private val shuangpin = listOf(
        sharedLink,
        Section(
            R.string.im_section_scheme, listOf(
                Choice(
                    EngineSettings.SHUANGPIN_PROFILE, R.string.im_shuangpin_scheme, EngineSettings.SCHEMES.keys.toList(),
                    label = { ctx, v -> ShuangpinNames.of(ctx, v) ?: v },
                    nearest = { v -> if (v in EngineSettings.SCHEMES) v else EngineSettings.XIAOHE },
                ),
                // the keyboard's: the full keyboard, the 18 or 14 keys or the nine keys, the engine reading each
                // (KeyboardPair); the 14 keys not under 微软 (ImKeyboards.choices), listed as the scheme changes
                AppChoice(
                    AppPrefs.SHUANGPIN_KEYBOARD, R.string.shuangpin_keyboard, R.string.shuangpin_keyboard_summary, ShuangpinKeyboard.entries,
                    offered = { options ->
                        val offered = ImKeyboards.choices(InputMethodCatalog.SHUANGPIN, schemeOf(options))
                        ShuangpinKeyboard.entries.filter { it.keyboard in offered }
                    },
                    // the 14 keys under 微软 the 18, as the keyboard types and EngineBridge.configure stores them
                    instead = { chosen, options -> ShuangpinKeyboard.of(ImKeyboards.effective(InputMethodCatalog.SHUANGPIN, chosen.keyboard, schemeOf(options))) },
                ) {
                    AppPrefs.getInstance().keyboard.shuangpinKeyboard
                },
            )
        ),
        // 九键双拼's letters and taps are its scheme's: of the nine keys' own, the long-press symbols alone
        nineKeysSection(listOf(nineKeySymbols), AppPrefs.SHUANGPIN_KEYBOARD) { AppPrefs.getInstance().keyboard.shuangpinKeyboard.getValue() == ShuangpinKeyboard.NineKeys },
    )

    // each table's own, under its group of the addon's config; [keys] what its keys show of its roots, where it has them
    private fun table(keys: List<Item> = emptyList()) = listOf(
        sharedLink,
        Section(
            R.string.im_section_typing, keys + listOf(
                Toggle(TableSettings.AUTO_SELECT, text(R.string.im_auto_select), R.string.im_auto_select_summary),
                Toggle(TableSettings.HINT, text(R.string.im_hint), R.string.im_hint_summary),
                // 调频: Freq and Fast alike order by use; off on a new install (U-28 D7)
                Toggle(
                    TableSettings.ORDER_POLICY, text(R.string.im_order_by_use), R.string.im_order_by_use_summary,
                    read = { it != "No" }, write = { if (it) TableSettings.ORDER_BY_USE else "No" },
                ),
                // what 调频 learned, which clearing what is learned keeps (U-28, U-86)
                Action(
                    RESET_TABLE_ORDER, R.string.im_reset_table_order, R.string.im_reset_table_order_summary, R.string.im_reset_table_order_confirm,
                    R.string.im_reset_table_order_done,
                ) {
                    learning(LearningOp.ResetTableOrder(it)) is LearningResult.Done
                },
            )
        ),
    )

    private val table = table()

    // 仓颉's one root a key: on or off (its preset's Names)
    private val cangjie = table(
        listOf(
            Toggle(
                TableSettings.KEY_LABELS, text(R.string.im_cangjie_roots), R.string.im_cangjie_roots_summary,
                read = { it != KeyLabels.NONE.key }, write = { if (it) KeyLabels.NAMES.key else KeyLabels.NONE.key },
            ),
        ),
    )

    // 五笔's: letters, 键名, or all its roots (a long press shows them large)
    private val wubi = table(
        listOf(
            Choice(
                TableSettings.KEY_LABELS, R.string.im_wubi_key_labels, KeyLabels.entries.map { it.key }, R.string.im_wubi_key_labels_summary,
                label = { c, v ->
                    c.getString(
                        when (KeyLabels.of(v)) {
                            KeyLabels.NAMES -> R.string.im_wubi_key_labels_names
                            KeyLabels.ROOTS -> R.string.im_wubi_key_labels_roots
                            else -> R.string.im_wubi_key_labels_none
                        },
                    )
                },
                // one the engine does not know it reads as 五笔's preset has it: letters alone
                nearest = { v -> KeyLabels.of(v)?.key ?: KeyLabels.NONE.key },
            ),
        ),
    )
    private fun englishOnNineKeys() = AppPrefs.getInstance().keyboard.englishKeyboard.getValue() == EnglishKeyboard.NineKeys

    // androidengine's English page (EngineEnglishPage)
    private val english = listOf(
        sharedLink,
        // the keyboard's: the 26 keys or the nine keys, first as pinyin's
        AppChoice(AppPrefs.ENGLISH_KEYBOARD, R.string.english_keyboard, R.string.english_keyboard_summary, EnglishKeyboard.entries) {
            AppPrefs.getInstance().keyboard.englishKeyboard
        },
        // how English types on the nine keys first, then what pinyin's share
        nineKeysSection(
            listOf(
                AppChoice(AppPrefs.T9_ENGLISH_TYPING, R.string.t9_english_typing, R.string.t9_english_typing_summary, T9EnglishTyping.entries) {
                    AppPrefs.getInstance().keyboard.t9EnglishTyping
                },
            ) + NINE_KEYS,
            AppPrefs.ENGLISH_KEYBOARD,
            ::englishOnNineKeys,
        ),
        Section(
            R.string.im_section_typing, listOf(
                Toggle(EngineSettings.ENGLISH_SUGGEST, text(R.string.im_word_hint), R.string.im_word_hint_summary),
                // the keyboard's: Shift where a sentence starts
                AppToggle(AppPrefs.ENGLISH_AUTO_CAPS, R.string.im_auto_caps, R.string.im_auto_caps_summary) {
                    AppPrefs.getInstance().keyboard.englishAutoCaps
                },
            )
        ),
        Section(
            R.string.im_section_words, listOf(
                Page(
                    ENGLISH_WORDS, R.string.english_words, R.string.english_words_summary,
                    SettingsRoute.UserWordList(UserWord.Kind.LEARNED.name, english = true),
                ),
            )
        ),
    )

    private val handwriting = listOf(
        sharedLink,
        Section(
            R.string.im_section_typing, listOf(
                // the pad's: how soon the pen up ends a character
                AppChoice(AppPrefs.HANDWRITING_PAUSE, R.string.handwriting_pause, R.string.handwriting_pause_summary, HandwritingPause.entries) {
                    AppPrefs.getInstance().keyboard.handwritingPause
                },
            )
        ),
    )

    /** The English page's word list: its key. */
    const val ENGLISH_WORDS = "english_words"

    /** 九键's page of its keys' letters: its key. */
    const val T9_LETTERS = "t9_letters"

    /** 九键's page of its keys' long-press symbols: its key. */
    const val T9_SYMBOLS = "t9_symbols"

    /** A table's 恢复码表顺序: its key. */
    const val RESET_TABLE_ORDER = "reset_table_order"

    /** The app's own input methods, each with a page in [of]. */
    val BUILT_IN: List<String> = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN) + InputMethodCatalog.TABLES.keys + InputMethodCatalog.HANDWRITING + InputMethodNames.KEYBOARD

    /** The page of [uniqueName], or null for an input method the app does not know. */
    fun of(uniqueName: String): List<Item>? = when (uniqueName) {
        // 0.4's 九键拼音 (a route saved before NineKeysMerge made it pinyin's keyboard): pinyin's, not a table's
        InputMethodCatalog.PINYIN, InputMethodCatalog.T9 -> pinyin
        InputMethodCatalog.SHUANGPIN -> shuangpin
        InputMethodCatalog.HANDWRITING -> handwriting
        InputMethodNames.KEYBOARD -> english
        InputMethodCatalog.CANGJIE -> cangjie
        InputMethodCatalog.WUBI, InputMethodCatalog.WUBI_PINYIN -> wubi
        else -> if (uniqueName.startsWith("engine-")) table else null
    }

    /**
     * What [items] show of [options], as options of their own to save: the host takes what it is
     * given and keeps the rest (a partial load), so a page left open behind the shared one, which
     * read the shared options before they were changed there, does not write them back as they were.
     */
    fun only(items: List<Item>, options: Map<String, String>): Map<String, String> {
        val shown = LinkedHashMap<String, String>()
        fun copy(path: String) {
            shown[path] = options[path] ?: return
        }
        fun walk(item: Item) {
            when (item) {
                is Section -> item.items.forEach(::walk)
                is Toggle -> copy(item.key)
                is Choice -> copy(item.key)
                is Flags -> item.flags.forEach { copy("${item.path}/${it.first}") }
                is AppChoice<*>, is AppToggle, is Page, is Action -> Unit
            }
        }
        items.forEach(::walk)
        return shown
    }

    /** An item's option in a page's [options], by its path (a/b for b under a): made, as "", if it is not there. */
    private class Slot(private val options: MutableMap<String, String>, private val path: String) {
        init {
            options.getOrPut(path) { "" }
        }

        var value: String
            get() = options.getValue(path)
            set(value) {
                options[path] = value
            }
    }

    /** [summary] (0 for none), and on a line of its own the input methods [notFor] it is no use to; null for neither. */
    internal fun summaryOf(context: Context, @StringRes summary: Int, notFor: List<String>): String? =
        listOfNotNull(if (summary == 0) null else context.getString(summary), notForText(context, notFor))
            .joinToString("\n").ifEmpty { null }

    // "不适用：英文、手写。": the input methods by the names the app gives them
    private fun notForText(context: Context, notFor: List<String>): String? =
        if (notFor.isEmpty()) null
        else context.getString(R.string.im_not_for, notFor.joinToString(context.getString(R.string.im_not_for_separator)) { InputMethodNames.of(context, it, it) })

    private fun page(context: Context, item: Page, open: (SettingsRoute) -> Unit) = Preference(context).apply {
        key = item.key
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(item.title)
        summary = summaryOf(context, item.summary, item.notFor)
        setOnPreferenceClickListener {
            open(item.route)
            true
        }
    }

    private fun action(context: Context, item: Action, act: ImAct, confirm: ImConfirm) = Preference(context).apply {
        key = item.key
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(item.title)
        summary = summaryOf(context, item.summary, item.notFor)
        setOnPreferenceClickListener {
            confirm(context, item) {
                // told once it ran: a toast before it was "done" whether or not the engine did it
                act(item) { ok -> Toast.makeText(context, if (ok) item.done else R.string.dict_change_failed, Toast.LENGTH_SHORT).show() }
            }
            true
        }
    }

    /** The dialog [create] asks with unless told otherwise: the JVM tests (no app resources) cannot show it. */
    private val askFirst: ImConfirm = { context, item, then ->
        LearningRestore.confirmDialog(context, item.title, context.getString(item.confirm)) { then() }
    }

    /** [items] as a screen over [options], [save] called after each change, [open] opening a [Page]'s route. */
    fun create(
        manager: PreferenceManager,
        items: List<Item>,
        options: MutableMap<String, String>,
        save: () -> Unit,
        act: ImAct = { _, _ -> },
        confirm: ImConfirm = askFirst,
        open: (SettingsRoute) -> Unit,
    ): PreferenceScreen = manager.createPreferenceScreen(manager.context).also { addTo(it, items, options, save, act, confirm, open) }

    /** [items] added to [group], as [create] makes them: a page of the app's own preferences may show some of fcitx's too. */
    fun addTo(
        group: PreferenceGroup,
        items: List<Item>,
        options: MutableMap<String, String>,
        save: () -> Unit,
        act: ImAct = { _, _ -> },
        confirm: ImConfirm = askFirst,
        open: (SettingsRoute) -> Unit,
    ) {
        val context = group.context
        fun slotOf(path: String) = Slot(options, path)
        // what reads the page again as anything on it changes (AppChoice.offered, shown)
        val refreshers = mutableListOf<() -> Unit>()
        fun changed() = refreshers.forEach { it() }
        fun add(item: Item, into: (Preference) -> Unit) {
            when (item) {
                is Section -> {
                    val category = PreferenceCategory(context).apply {
                        setTitle(item.title)
                        isIconSpaceReserved = false
                    }
                    // the keyboard chosen above it changed: there or not at once
                    val refresh = { category.isVisible = item.shown(options) }
                    refresh()
                    refreshers += refresh
                    into(category)
                    item.items.forEach { add(it, category::addPreference) }
                }
                is Toggle -> {
                    val slot = slotOf(item.key)
                    into(SwitchPreferenceCompat(context).apply {
                        // the key a search result names it by (SettingsIndex.Target); still never stored
                        key = item.key
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        title = item.title(context)
                        summary = summaryOf(context, item.summary, item.notFor)
                        isChecked = item.read(slot.value)
                        setOnPreferenceChangeListener { _, value ->
                            slot.value = item.write(value as Boolean)
                            save()
                            true
                        }
                    })
                }
                is Choice -> {
                    val slot = slotOf(item.key)
                    into(ListPreference(context).apply {
                        // its dialog finds it by key (MyPreferenceFragment); still never stored
                        key = item.key
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        setTitle(item.title)
                        dialogTitle = title
                        entries = item.values.map { item.label(context, it) }.toTypedArray()
                        entryValues = item.values.toTypedArray()
                        value = item.nearest(slot.value)
                        // the explanation goes under the value: a dialog message would replace the list of choices
                        summaryProvider = choiceSummary(context, item)
                        setOnPreferenceChangeListener { _, value ->
                            slot.value = value as String
                            save()
                            changed()
                            true
                        }
                    })
                }
                is Flags -> into(flagsPreference(context, item, item.flags.map { slotOf("${item.path}/${it.first}") }, save))
                is AppChoice<*> -> into(appChoice(context, item, options, refreshers, ::changed))
                is Page -> into(page(context, item, open).also { p ->
                    val refresh = { p.isVisible = item.shown(options) }
                    refresh()
                    refreshers += refresh
                })
                is Action -> into(action(context, item, act, confirm))
                is AppToggle -> into(appToggle(context, item))
            }
        }
        items.forEach { add(it, group::addPreference) }
    }

    /** [item] as one preference over [slots], one for each of its flags, picked in a dialog; [save] called after each change. */
    private fun flagsPreference(context: Context, item: Flags, slots: List<Slot>, save: () -> Unit): Preference =
        object : Preference(context) {
            fun refresh() {
                val on = item.flags.filterIndexed { i, _ -> slots[i].value == "True" }
                summary = if (on.isEmpty()) context.getString(item.none) else on.joinToString("  ") { it.second }
            }

            override fun onClick() {
                val checked = BooleanArray(slots.size) { slots[it].value == "True" }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(flagList(context, item, checked))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        checked.forEachIndexed { i, on -> slots[i].value = if (on) "True" else "False" }
                        refresh()
                        save()
                    }
                    .show()
            }
        }.apply {
            key = item.path
            isPersistent = false
            isIconSpaceReserved = false
            setTitle(item.title)
            refresh()
        }

    private fun <T> appChoice(
        context: Context,
        item: AppChoice<T>,
        options: Map<String, String>,
        refreshers: MutableList<() -> Unit>,
        changed: () -> Unit,
    ) where T : Enum<T>, T : ManagedPreferenceEnum =
        ListPreference(context).apply {
            val pref = item.pref()
            key = item.key
            // the app's preference set by hand: this screen's store is fcitx's config
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(item.title)
            dialogTitle = title
            fun refresh() {
                val offered = item.offered(options)
                entries = offered.map { keyboardLabel(context, it) }.toTypedArray()
                entryValues = offered.map { it.name }.toTypedArray()
                // one set and not offered now (双拼's 14 keys under 微软) shows as the one the keyboard types
                val set = pref.getValue()
                value = (set.takeIf { it in offered } ?: item.instead(set, options)?.takeIf { it in offered } ?: offered.first()).name
                isVisible = item.shown(options)
            }
            refresh()
            refreshers += ::refresh
            val summary = summaryOf(context, item.summary, item.notFor)
            summaryProvider = Preference.SummaryProvider<ListPreference> { listOfNotNull(it.entry, summary).joinToString("\n") }
            setOnPreferenceChangeListener { _, value ->
                pref.setValue(item.values.first { it.name == value })
                changed()
                true
            }
        }

    // a keyboard's name, with the theme's layout's it is drawn with (KeyboardLabels.label); any other choice's own
    private fun keyboardLabel(context: Context, choice: ManagedPreferenceEnum): String {
        val own = context.getString(choice.stringRes)
        val (im, keyboard) = when (choice) {
            is PinyinKeyboard -> InputMethodCatalog.PINYIN to choice.keyboard
            is ShuangpinKeyboard -> InputMethodCatalog.SHUANGPIN to choice.keyboard
            is EnglishKeyboard -> InputMethodCatalog.ENGLISH to choice.keyboard
            // its recognizer is the text pack's
            KeyDrawing.Handwriting ->
                return if (PackStore.ready(PackKind.TEXT)) own else context.getString(R.string.key_drawing_needs_text_pack, own)
            else -> return own
        }
        return KeyboardLabels.label(context, im, keyboard, own)
    }

    private fun appToggle(context: Context, item: AppToggle) = SwitchPreferenceCompat(context).apply {
        val pref = item.pref()
        key = item.key
        // the app's preference set by hand: this screen's store is fcitx's config
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(item.title)
        summary = summaryOf(context, item.summary, item.notFor)
        isChecked = pref.getValue()
        setOnPreferenceChangeListener { _, value ->
            pref.setValue(value as Boolean)
            true
        }
    }

    private fun choiceSummary(context: Context, item: Choice): Preference.SummaryProvider<ListPreference> {
        val simple = ListPreference.SimpleSummaryProvider.getInstance()
        val summary = summaryOf(context, item.summary, item.notFor) ?: return simple
        // the value as the simple provider words it: "Not set", not "null", for one not among the entries
        return Preference.SummaryProvider<ListPreference> { "${simple.provideSummary(it)}\n$summary" }
    }

    /** [item]'s flags as a list of check boxes over [checked], each with the input methods it is no use to under it. */
    private fun flagList(context: Context, item: Flags, checked: BooleanArray): ListView {
        // the note under the label: brackets around it would be one language's
        val labels = item.flags.map { (key, label) -> summaryOf(context, 0, item.notFor[key].orEmpty())?.let { "$label\n$it" } ?: label }
        val list = ListView(context)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, labels)
        checked.forEachIndexed { i, on -> list.setItemChecked(i, on) }
        list.setOnItemClickListener { _, _, position, _ -> checked[position] = list.isItemChecked(position) }
        return list
    }

}
