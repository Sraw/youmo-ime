/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView
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
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.ShuangpinNames
import org.fcitx.fcitx5.android.core.ThisDevice
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
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
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.TableSettings
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.host.ModelChoice
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLabels
import org.fcitx.fcitx5.android.input.keyboard.StrokeLift
import org.fcitx.fcitx5.android.input.keyboard.SwipeSlotsCache
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.levelText
import org.fcitx.fcitx5.android.ui.main.settings.LearningRestore
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.toast

typealias ImAct = (InputMethodSettings.Action, done: (Boolean) -> Unit) -> Unit

typealias ImConfirm = (Context, InputMethodSettings.Action, then: () -> Unit) -> Unit

/**
 * fcitx stores the values, but names, visibility and grouping are decided here: fcitx's descriptions named every
 * engine knob. A page saves only what it shows ([only]), so one left open behind another writes back nothing older.
 */
object InputMethodSettings {

    sealed interface Item

    /** 0 for no summary; settings search finds it by its [key]. */
    sealed interface Titled : Item {
        val key: String
        @get:StringRes val title: Int
        @get:StringRes val summary: Int
        val notFor: List<String>
        /** The row settings search points at while this one is hidden. */
        val via: String? get() = null
    }

    /** [key] a/b is b under a; "True" or "False" unless [read] and [write] say otherwise. */
    class Toggle(
        val key: String,
        val title: (Context) -> String,
        @StringRes val summary: Int = 0,
        val read: (String) -> Boolean = { it == "True" },
        val write: (Boolean) -> String = { if (it) "True" else "False" },
        val notFor: List<String> = emptyList(),
    ) : Item

    /** A stored value not among [values] shows as the one [nearest] to it. */
    class Choice(
        override val key: String,
        @StringRes override val title: Int,
        val values: List<String>,
        @StringRes override val summary: Int = 0,
        val label: (Context, String) -> String,
        val nearest: (String) -> String = { it },
        override val notFor: List<String> = emptyList(),
        val shown: (Map<String, String>) -> Boolean = { true },
        override val via: String? = null,
    ) : Titled

    class Flags(
        val path: String,
        @StringRes val title: Int,
        @StringRes val none: Int,
        val flags: List<Pair<String, String>>,
        val notFor: Map<String, List<String>> = emptyMap(),
    ) : Item

    /** Kept by the app, not fcitx; [pref] is asked only when the page is made. A value not [offered] shows as [instead] has it. */
    class AppChoice<T>(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val values: List<T>,
        override val notFor: List<String> = emptyList(),
        val offered: (Map<String, String>) -> List<T> = { values },
        val instead: (T, Map<String, String>) -> T? = { _, _ -> null },
        val shown: (Map<String, String>) -> Boolean = { true },
        override val via: String? = null,
        /** Where set, the user's layouts are listed after the app's, valued `Custom:<id>`. */
        val customIm: String? = null,
        val pref: () -> ManagedPreference.PStringLike<T>,
    ) : Titled where T : Enum<T>, T : ManagedPreferenceEnum

    /** One choice for every table; shown only while a user letter layout exists or one is chosen. */
    class TableKeyboard(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        override val notFor: List<String> = emptyList(),
    ) : Titled

    class AppToggle(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        override val notFor: List<String> = emptyList(),
        val pref: () -> ManagedPreference<Boolean>,
    ) : Titled

    /** Opened as the app's settings open one: not an fcitx option. */
    class Page(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val route: SettingsRoute,
        override val notFor: List<String> = emptyList(),
        val shown: (Map<String, String>) -> Boolean = { true },
    ) : Titled

    /** [done] is shown only when [run] says it was done, a failure otherwise. */
    class Action(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        @StringRes val confirm: Int,
        @StringRes val done: Int,
        override val notFor: List<String> = emptyList(),
        val run: suspend HostApi.(im: String) -> Boolean,
    ) : Titled

    /** [via]: the row that shows the group when [shown] hides it; settings search opens that row instead. */
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

    private val STROKE_SINGLES = StrokeFilter.SINGLES_RANGE.map { it.toString() }

    private fun strokesDrawn() = AppPrefs.getInstance().keyboard.keyDrawing.getValue() == KeyDrawing.StrokeFilter

    private val READING_STYLES = mapOf(
        ReadingStyle.OFF to R.string.im_candidate_reading_off,
        ReadingStyle.PINYIN to R.string.im_candidate_reading_pinyin,
        ReadingStyle.ZHUYIN to R.string.im_candidate_reading_zhuyin,
    )

    private val MODEL_LEVELS = mapOf(
        ModelChoice.LOW to R.string.im_model_level_low,
        ModelChoice.MEDIUM to R.string.im_model_level_medium,
        ModelChoice.HIGH to R.string.im_model_level_high,
    )

    internal fun modelLevelLabel(context: Context, value: String): String = when (val choice = ModelChoice.of(value)) {
        ModelChoice.AUTO -> context.getString(R.string.im_model_level_auto, levelText(context, ThisDevice.profile.autoLevel))
        else -> context.getString(MODEL_LEVELS.getValue(choice))
    }

    // keyed by Fuzzy's names, as the engine reads them
    private val FUZZY = listOf(
        Fuzzy.V_U to "u ↔ v", Fuzzy.AN_ANG to "an ↔ ang", Fuzzy.EN_ENG to "en ↔ eng", Fuzzy.IAN_IANG to "ian ↔ iang",
        Fuzzy.IN_ING to "in ↔ ing", Fuzzy.U_OU to "u ↔ ou", Fuzzy.UAN_UANG to "uan ↔ uang", Fuzzy.C_CH to "c ↔ ch",
        Fuzzy.F_H to "f ↔ h", Fuzzy.L_N to "l ↔ n", Fuzzy.L_R to "l ↔ r", Fuzzy.S_SH to "s ↔ sh", Fuzzy.Z_ZH to "z ↔ zh",
    ).map { (fuzzy, label) -> fuzzy.name to label }

    const val SHARED_LINK = "shared_input"

    // a keyboard list's values are the enums' names; `Custom` is a user layout, listed as `Custom:<id>`
    private const val CUSTOM = "Custom"
    private const val CUSTOM_VALUE = "Custom:"
    private const val FULL_VALUE = "Full"

    const val ADDRESSES = "addresses"

    val SHARED: List<Item> = listOf(
        Section(
            R.string.shared_section_all, listOf(
                AppChoice(AppPrefs.KEY_DRAWING, R.string.key_drawing, 0, KeyDrawing.entries) { AppPrefs.getInstance().keyboard.keyDrawing },
                // 首页单字 also shows where a swipe is set to a stroke, which the filter reorders the same
                AppChoice(AppPrefs.STROKE_LIFT, R.string.stroke_lift, R.string.stroke_lift_summary, StrokeLift.entries, shown = { strokesDrawn() },
                    via = AppPrefs.KEY_DRAWING) {
                    AppPrefs.getInstance().keyboard.strokeLift
                },
                Choice(
                    EngineSettings.STROKE_SINGLES, R.string.stroke_singles, STROKE_SINGLES, R.string.stroke_singles_summary,
                    label = { ctx, v -> if (v == "0") ctx.getString(R.string.swipe_off) else v },
                    nearest = numbers(StrokeFilter.SINGLES_RANGE),
                    shown = { strokesDrawn() || SwipeSlotsCache.current.strokes },
                    via = AppPrefs.KEY_DRAWING,
                ),
                AppToggle(AppPrefs.AUTO_PAIRS, R.string.auto_pairs, 0) { AppPrefs.getInstance().keyboard.autoPairs },
                AppToggle(AppPrefs.CALCULATION_REPLACES_SUM, R.string.calculation_replaces_sum, 0) {
                    AppPrefs.getInstance().keyboard.calculationReplacesSum
                },
                Page(ADDRESSES, R.string.address_suggestions, R.string.address_suggestions_summary, SettingsRoute.Addresses),
            )
        ),
        Section(
            R.string.handwriting, listOf(
                AppChoice(AppPrefs.HANDWRITING_PAUSE, R.string.handwriting_pause, R.string.handwriting_pause_summary, HandwritingPause.entries) {
                    AppPrefs.getInstance().keyboard.handwritingPause
                },
            )
        ),
        // English reads none of these: only exceptions are said under a row
        Section(
            R.string.shared_section_chinese, listOf(
                Toggle(EngineSettings.PREDICTION, text(R.string.im_prediction), R.string.im_prediction_summary),
                Toggle(EngineSettings.SPACE_PICKS_PREDICTION, text(R.string.im_space_picks_prediction), R.string.im_space_picks_prediction_summary),
                // HandwritingSession offers no emoji: its summary says so
                Toggle(EngineSettings.EMOJI, text(R.string.im_emoji), R.string.im_emoji_summary),
                Toggle(EngineSettings.BUILT_IN_PHRASES, text(R.string.im_built_in_phrases), R.string.im_built_in_phrases_summary),
                Choice(
                    EngineSettings.MODEL_LEVEL, R.string.im_model_level, ModelChoice.entries.map { it.key }, R.string.im_model_level_summary,
                    label = ::modelLevelLabel,
                    // what the engine reads as the default
                    nearest = { v -> ModelChoice.of(v).key },
                ),
                Choice(
                    EngineSettings.CANDIDATE_READING, R.string.im_candidate_reading, ReadingStyle.entries.map { it.key }, R.string.im_candidate_reading_summary,
                    label = { ctx, v -> ctx.getString(READING_STYLES.getValue(ReadingStyle.of(v))) },
                    nearest = { v -> ReadingStyle.of(v).key },
                ),
            )
        ),
        Section(
            R.string.shared_section_pinyin, listOf(
                Toggle("${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", text(R.string.im_typos), R.string.im_typos_summary),
                // u and v share pinyin's nine-key 8; zhuyin keeps ㄨ and ㄩ apart
                Flags(EngineSettings.FUZZY, R.string.im_fuzzy, R.string.im_fuzzy_none, FUZZY, notFor = mapOf(Fuzzy.V_U.name to listOf(InputMethodCatalog.T9, InputMethodCatalog.SHUANGPIN_NINE))),
            )
        ),
    )

    /** No touch keyboard's bar has it (it shows as many as fit). */
    val HARDWARE_KEYBOARD: List<Item> = listOf(
        Choice(EngineSettings.PAGE_SIZE, R.string.im_page_size, PAGE_SIZES, R.string.im_page_size_summary, label = { _, v -> v }, nearest = numbers(EngineSettings.PAGE_SIZES)),
    )

    private val sharedLink = Page(SHARED_LINK, R.string.shared_input_link, R.string.shared_input_link_summary, SettingsRoute.SharedInput)

    private fun nineKeysSection(items: List<Item>, keyboard: String, chosen: () -> Boolean) =
        Section(R.string.keyboard_nine, items, shown = { chosen() }, via = keyboard)

    private val multiTap = AppChoice(AppPrefs.T9_MULTI_TAP, R.string.t9_multi_tap, R.string.t9_multi_tap_summary, T9MultiTap.entries) {
        AppPrefs.getInstance().keyboard.t9MultiTap
    }
    private val nineKeyLetters = Page(T9_LETTERS, R.string.t9_letters, R.string.t9_letters_summary, SettingsRoute.T9Letters)

    val NINE_KEYS: List<Item> = listOf(multiTap, nineKeyLetters)

    private val zhuyin = listOf(InputMethodCatalog.ZHUYIN_NINE, InputMethodCatalog.ZHUYIN)
    private val pinyinKeys = listOf(InputMethodCatalog.T9, InputMethodCatalog.PINYIN_KEYS18, InputMethodCatalog.PINYIN_KEYS) + zhuyin

    private val pinyin = listOf(
        sharedLink,
        // in no section: a section 键盘 over a row 键盘 said it twice
        AppChoice(AppPrefs.PINYIN_KEYBOARD, R.string.pinyin_keyboard, R.string.pinyin_keyboard_summary, PinyinKeyboard.entries, customIm = InputMethodCatalog.PINYIN) {
            AppPrefs.getInstance().keyboard.pinyinKeyboard
        },
        nineKeysSection(NINE_KEYS, AppPrefs.PINYIN_KEYBOARD) { drawn(InputMethodCatalog.PINYIN) == ImKeyboards.Layout.PINYIN_NINE },
        Section(
            R.string.im_section_typing, listOf(
                // not hidden on the others: a hardware keyboard still types full pinyin
                Toggle(EngineSettings.CHAIZI, text(R.string.im_chaizi), R.string.im_chaizi_summary, notFor = pinyinKeys),
                Toggle(EngineSettings.LATIN_WORDS, text(R.string.im_latin_words), R.string.im_latin_words_summary, notFor = zhuyin),
            )
        ),
    )

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
                // the 14 keys are not offered under 微软, so the list changes with the scheme
                AppChoice(
                    AppPrefs.SHUANGPIN_KEYBOARD, R.string.shuangpin_keyboard, R.string.shuangpin_keyboard_summary, ShuangpinKeyboard.entries,
                    offered = { options ->
                        val offered = ImKeyboards.choices(InputMethodCatalog.SHUANGPIN, schemeOf(options))
                        ShuangpinKeyboard.entries.filter { it.keyboard in offered }
                    },
                    // 14 keys under 微软 show as 18, as the keyboard types and stores them
                    instead = { chosen, options -> ShuangpinKeyboard.of(ImKeyboards.effective(InputMethodCatalog.SHUANGPIN, chosen.keyboard, schemeOf(options))) },
                    customIm = InputMethodCatalog.SHUANGPIN,
                ) {
                    AppPrefs.getInstance().keyboard.shuangpinKeyboard
                },
            )
        ),
        Section(
            R.string.im_section_typing, listOf(
                Toggle(EngineSettings.SHUANGPIN_KEYS_SHOWN, text(R.string.im_shuangpin_keys_shown), R.string.im_shuangpin_keys_shown_summary),
            )
        ),
    )

    private fun table(keys: List<Item> = emptyList()) = listOf(
        sharedLink,
        TableKeyboard(AppPrefs.TABLE_KEYBOARD_LAYOUT, R.string.table_keyboard, R.string.table_keyboard_summary),
        Section(
            R.string.im_section_typing, keys + listOf(
                Toggle(TableSettings.AUTO_SELECT, text(R.string.im_auto_select), R.string.im_auto_select_summary),
                Toggle(TableSettings.HINT, text(R.string.im_hint), R.string.im_hint_summary),
                // Freq and Fast alike order by use; off on a new install
                Toggle(
                    TableSettings.ORDER_POLICY, text(R.string.im_order_by_use), R.string.im_order_by_use_summary,
                    read = { it != "No" }, write = { if (it) TableSettings.ORDER_BY_USE else "No" },
                ),
                Toggle(TableSettings.OFFER_WORDS, text(R.string.im_offer_words), R.string.im_offer_words_summary),
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

    private val cangjie = table(
        listOf(
            Toggle(
                TableSettings.KEY_LABELS, text(R.string.im_cangjie_roots), R.string.im_cangjie_roots_summary,
                read = { it != KeyLabels.NONE.key }, write = { if (it) KeyLabels.NAMES.key else KeyLabels.NONE.key },
            ),
        ),
    )

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
                // an unknown value reads as 五笔's preset: letters alone
                nearest = { v -> KeyLabels.of(v)?.key ?: KeyLabels.NONE.key },
            ),
        ),
    )
    private fun englishOnNineKeys() = drawn(InputMethodCatalog.ENGLISH) == ImKeyboards.Layout.ENGLISH_NINE

    // a user layout of the nine-key family reads the nine keys' settings too
    private fun drawn(im: String) = ImKeyboards.layoutOf(im, AppPrefs.getInstance().keyboard.imKeyboards(), EngineState.shuangpinScheme)

    private val english = listOf(
        sharedLink,
        AppChoice(AppPrefs.ENGLISH_KEYBOARD, R.string.english_keyboard, R.string.english_keyboard_summary, EnglishKeyboard.entries, customIm = InputMethodCatalog.ENGLISH) {
            AppPrefs.getInstance().keyboard.englishKeyboard
        },
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

    const val ENGLISH_WORDS = "english_words"

    const val T9_LETTERS = "t9_letters"

    const val RESET_TABLE_ORDER = "reset_table_order"

    val BUILT_IN: List<String> = listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN) + InputMethodCatalog.TABLES.keys + InputMethodNames.KEYBOARD

    fun of(uniqueName: String): List<Item>? = when (uniqueName) {
        // 0.4's 九键拼音 (a saved route): pinyin's, not a table's
        InputMethodCatalog.PINYIN, InputMethodCatalog.T9 -> pinyin
        InputMethodCatalog.SHUANGPIN -> shuangpin
        InputMethodNames.KEYBOARD -> english
        InputMethodCatalog.CANGJIE -> cangjie
        InputMethodCatalog.WUBI, InputMethodCatalog.WUBI_PINYIN -> wubi
        // a retired route: no input method now, so no page
        InputMethodCatalog.HANDWRITING_RETIRED -> null
        else -> if (uniqueName.startsWith("engine-")) table else null
    }

    /** The host keeps what it is not given, so a page left open behind the shared one writes back nothing it read before. */
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
                is AppChoice<*>, is TableKeyboard, is AppToggle, is Page, is Action -> Unit
            }
        }
        items.forEach(::walk)
        return shown
    }

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

    internal fun summaryOf(context: Context, @StringRes summary: Int, notFor: List<String>): String? =
        listOfNotNull(if (summary == 0) null else context.getString(summary), notForText(context, notFor))
            .joinToString("\n").ifEmpty { null }

    private fun notForText(context: Context, notFor: List<String>): String? =
        if (notFor.isEmpty()) null
        else context.getString(R.string.im_not_for, notFor.joinToString(context.getString(R.string.list_separator)) { InputMethodNames.of(context, it, it) })

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
                // a toast before it ran said "done" whether or not the engine did it
                act(item) { ok -> context.toast(if (ok) item.done else R.string.dict_change_failed) }
            }
            true
        }
    }

    /** JVM tests (no app resources) cannot show it. */
    private val askFirst: ImConfirm = { context, item, then ->
        LearningRestore.confirmDialog(context, item.title, context.getString(item.confirm)) { then() }
    }

    fun create(
        manager: PreferenceManager,
        items: List<Item>,
        options: MutableMap<String, String>,
        save: () -> Unit,
        act: ImAct = { _, _ -> },
        confirm: ImConfirm = askFirst,
        open: (SettingsRoute) -> Unit,
    ): PreferenceScreen = manager.createPreferenceScreen(manager.context).also { addTo(it, items, options, save, act, confirm, open) }

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
        val refreshers = mutableListOf<() -> Unit>()
        fun changed() = refreshers.forEach { it() }
        fun add(item: Item, into: (Preference) -> Unit) {
            when (item) {
                is Section -> {
                    val category = PreferenceCategory(context).apply {
                        setTitle(item.title)
                        isIconSpaceReserved = false
                    }
                    val refresh = { category.isVisible = item.shown(options) }
                    refresh()
                    refreshers += refresh
                    into(category)
                    item.items.forEach { add(it, category::addPreference) }
                }
                is Toggle -> {
                    val slot = slotOf(item.key)
                    into(SwitchPreferenceCompat(context).apply {
                        // the key a search result names it by; never stored
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
                        val refresh = { isVisible = item.shown(options) }
                        refresh()
                        refreshers += refresh
                        // its dialog finds it by key; never stored
                        key = item.key
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        setTitle(item.title)
                        dialogTitle = title
                        entries = item.values.map { item.label(context, it) }.toTypedArray()
                        entryValues = item.values.toTypedArray()
                        value = item.nearest(slot.value)
                        // a dialog message would replace the list of choices
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
                is TableKeyboard -> into(tableKeyboard(context, item, refreshers))
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
            // set by hand: this screen's store is fcitx's config
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(item.title)
            dialogTitle = title
            val im = item.customIm
            fun refresh() {
                val offered = item.offered(options).filter { it.name != CUSTOM }
                val set = pref.getValue()
                val customs = im?.let { customEntries(context, it, schemeOf(options)) }
                entries = (offered.map { keyboardLabel(context, it) } + customs?.entries.orEmpty().map { it.second }).toTypedArray()
                entryValues = (offered.map { it.name } + customs?.entries.orEmpty().map { it.first }).toTypedArray()
                // one set but not offered now shows as the one the keyboard types
                value = customs?.chosen?.takeIf { set.name == CUSTOM }
                    ?: (set.takeIf { it in offered } ?: item.instead(set, options)?.takeIf { it in offered } ?: offered.first()).name
                isVisible = item.shown(options)
            }
            refresh()
            refreshers += ::refresh
            val summary = summaryOf(context, item.summary, item.notFor)
            summaryProvider = Preference.SummaryProvider<ListPreference> { listOfNotNull(it.entry, summary).joinToString("\n") }
            setOnPreferenceChangeListener { _, value ->
                val id = (value as String).removePrefix(CUSTOM_VALUE).takeIf { im != null && value.startsWith(CUSTOM_VALUE) }
                if (id != null) {
                    AppPrefs.getInstance().keyboard.setImKeyboard(im!!, ImKeyboard.CUSTOM, id)
                } else {
                    pref.setValue(item.values.first { it.name == value })
                }
                changed()
                refresh()
                true
            }
        }

    /** Reads the layouts: off the main thread where it may read many. */
    fun tableKeyboardShown(context: Context): Boolean =
        customEntries(context, InputMethodCatalog.TABLES.keys.first(), EngineState.shuangpinScheme).entries.isNotEmpty()

    private fun tableKeyboard(context: Context, item: TableKeyboard, refreshers: MutableList<() -> Unit>) =
        ListPreference(context).apply {
            key = item.key
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(item.title)
            dialogTitle = title
            val im = InputMethodCatalog.TABLES.keys.first()
            val pref = AppPrefs.getInstance().keyboard.tableKeyboardLayout
            fun refresh() {
                val customs = customEntries(context, im, EngineState.shuangpinScheme)
                entries = (listOf(context.getString(R.string.pinyin_keyboard_full)) + customs.entries.map { it.second }).toTypedArray()
                entryValues = (listOf(FULL_VALUE) + customs.entries.map { it.first }).toTypedArray()
                value = customs.chosen?.takeIf { pref.getValue().isNotEmpty() } ?: FULL_VALUE
                isVisible = customs.entries.isNotEmpty()
            }
            refresh()
            refreshers += ::refresh
            val summary = summaryOf(context, item.summary, item.notFor)
            summaryProvider = Preference.SummaryProvider<ListPreference> { listOfNotNull(it.entry, summary).joinToString("\n") }
            setOnPreferenceChangeListener { _, value ->
                val id = (value as String).removePrefix(CUSTOM_VALUE).takeIf { value.startsWith(CUSTOM_VALUE) }
                AppPrefs.getInstance().keyboard.setImKeyboard(im, if (id != null) ImKeyboard.CUSTOM else ImKeyboard.FULL, id)
                refresh()
                true
            }
        }

    private class CustomEntries(val entries: List<Pair<String, String>>, val chosen: String?)

    /** The chosen one too where not among them, said with what is drawn instead: 「17 键（读不出，暂用 18 键）」. */
    private fun customEntries(context: Context, im: String, scheme: ShuangpinScheme): CustomEntries {
        val usable = ImKeyboards.customs(im, scheme, CustomKeyboards.all())
        val entries = usable.map { CUSTOM_VALUE + it.id to KeyboardLabels.custom(context, it) }.toMutableList()
        val chosen = AppPrefs.getInstance().keyboard.imKeyboards()
        val id = chosen.layoutOf(im) ?: return CustomEntries(entries, null)
        if (usable.none { it.id == id }) {
            val layout = chosen.customOf(im)
            val base = ImKeyboards.effective(im, ImKeyboard.CUSTOM, scheme, layout)
            val name = layout ?: CustomChoice(id, id, "", null, drawn = false)
            val instead = KeyboardLabels.builtInName(context, im, base)
            // not of this input method now (its file replaced by another of the same id): said so, not unreadable
            entries += CUSTOM_VALUE + id to if (layout?.drawn == true) {
                context.getString(R.string.keyboard_custom_unusable, KeyboardLabels.shortName(layout.name), instead)
            } else {
                KeyboardLabels.custom(context, name.copy(drawn = false), instead)
            }
        }
        return CustomEntries(entries, CUSTOM_VALUE + id)
    }

    private fun keyboardLabel(context: Context, choice: ManagedPreferenceEnum): String {
        val own = context.getString(choice.stringRes)
        val (im, keyboard) = when (choice) {
            is PinyinKeyboard -> InputMethodCatalog.PINYIN to choice.keyboard
            is ShuangpinKeyboard -> InputMethodCatalog.SHUANGPIN to choice.keyboard
            is EnglishKeyboard -> InputMethodCatalog.ENGLISH to choice.keyboard
            else -> return own
        }
        return KeyboardLabels.label(context, im, keyboard, own)
    }

    private fun appToggle(context: Context, item: AppToggle) = SwitchPreferenceCompat(context).apply {
        val pref = item.pref()
        key = item.key
        // set by hand: this screen's store is fcitx's config
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
        // the simple provider says "Not set", not "null", for one not among the entries
        return Preference.SummaryProvider<ListPreference> { "${simple.provideSummary(it)}\n$summary" }
    }

    private fun flagList(context: Context, item: Flags, checked: BooleanArray): ListView {
        // on its own line: brackets around it would be one language's
        val labels = item.flags.map { (key, label) -> summaryOf(context, 0, item.notFor[key].orEmpty())?.let { "$label\n$it" } ?: label }
        val list = ListView(context)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, labels)
        checked.forEachIndexed { i, on -> list.setItemChecked(i, on) }
        list.setOnItemClickListener { _, _, position, _ -> checked[position] = list.isItemChecked(position) }
        return list
    }

}
