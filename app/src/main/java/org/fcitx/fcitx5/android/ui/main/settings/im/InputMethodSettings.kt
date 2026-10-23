/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

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
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.host.ModelLevel
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.handwriting.HandwritingPause
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
     * [key], [pref] asked for only when the page is made.
     */
    class AppChoice<T>(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val values: List<T>,
        val pref: () -> ManagedPreference.PStringLike<T>,
    ) : Titled where T : Enum<T>, T : ManagedPreferenceEnum {
        override val notFor: List<String> get() = emptyList()
    }

    /** A switch over the app's own preference [pref], as [AppChoice] is a list. */
    class AppToggle(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        override val notFor: List<String> = emptyList(),
        val pref: () -> ManagedPreference<Boolean>,
    ) : Titled

    /** A page of its own, [route], as the app's settings open one: not an option of fcitx's. */
    class Page(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val route: SettingsRoute,
        override val notFor: List<String> = emptyList(),
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
        val run: suspend FcitxAPI.(im: String) -> Boolean,
    ) : Titled

    class Section(@StringRes val title: Int, val items: List<Item>) : Item

    private fun text(@StringRes id: Int): (Context) -> String = { it.getString(id) }

    private fun numbers(range: IntRange): (String) -> String = { v ->
        v.toIntOrNull()?.coerceIn(range)?.toString() ?: range.first.toString()
    }

    private val PAGE_SIZES = EngineSettings.PAGE_SIZES.map { it.toString() }

    // a label for each of EngineSettings.SCHEMES
    private val SHUANGPIN = mapOf(
        ShuangpinScheme.ZIRANMA to R.string.shuangpin_ziranma,
        ShuangpinScheme.MICROSOFT to R.string.shuangpin_ms,
        ShuangpinScheme.ZIGUANG to R.string.shuangpin_ziguang,
        ShuangpinScheme.ABC to R.string.shuangpin_abc,
        ShuangpinScheme.ZHONGWENZHIXING to R.string.shuangpin_zhongwenzhixing,
        ShuangpinScheme.PINYINJIAJIA to R.string.shuangpin_pinyinjiajia,
        ShuangpinScheme.XIAOHE to R.string.shuangpin_xiaohe,
        ShuangpinScheme.GB to R.string.shuangpin_gb,
    )

    private val MODEL_LEVELS = mapOf(
        ModelLevel.LOW to R.string.im_model_level_low,
        ModelLevel.MEDIUM to R.string.im_model_level_medium,
        ModelLevel.HIGH to R.string.im_model_level_high,
    )

    // keyed by Fuzzy's names, as the engine reads them (EngineSettings.parse)
    private val FUZZY = listOf(
        Fuzzy.V_U to "u ↔ v", Fuzzy.AN_ANG to "an ↔ ang", Fuzzy.EN_ENG to "en ↔ eng", Fuzzy.IAN_IANG to "ian ↔ iang",
        Fuzzy.IN_ING to "in ↔ ing", Fuzzy.U_OU to "u ↔ ou", Fuzzy.UAN_UANG to "uan ↔ uang", Fuzzy.C_CH to "c ↔ ch",
        Fuzzy.F_H to "f ↔ h", Fuzzy.L_N to "l ↔ n", Fuzzy.L_R to "l ↔ r", Fuzzy.S_SH to "s ↔ sh", Fuzzy.Z_ZH to "z ↔ zh",
    ).map { (fuzzy, label) -> fuzzy.name to label }

    /** What every input method shares: its page's key, the one row on each input method's page that opens it. */
    const val SHARED_LINK = "shared_input"

    /** The shared page's row of the Chinese punctuation: its key. */
    const val PUNCTUATION = "punctuation"

    /** What every input method shares, each with those it is no use to (Engines has which reads what). */
    val SHARED: List<Item> = listOf(
        Section(
            R.string.shared_section_all, listOf(
                // the keyboard's: a word by gliding across its keys, every input method's but handwriting's (Engines.glides)
                AppToggle(AppPrefs.GLIDE_TYPING, R.string.im_glide, R.string.im_glide_summary, notFor = listOf(Engines.HANDWRITING)) {
                    AppPrefs.getInstance().keyboard.glideTyping
                },
                AppToggle(AppPrefs.AUTO_PAIRS, R.string.auto_pairs, 0) { AppPrefs.getInstance().keyboard.autoPairs },
                AppToggle(AppPrefs.CALCULATION_REPLACES_SUM, R.string.calculation_replaces_sum, 0) {
                    AppPrefs.getInstance().keyboard.calculationReplacesSum
                },
            )
        ),
        // the section's title says English reads none of these: only an exception within it is said under a row
        Section(
            R.string.shared_section_chinese, listOf(
                Toggle(EngineSettings.PREDICTION, text(R.string.im_prediction), R.string.im_prediction_summary),
                // handwriting writes no word an emoji stands for
                Toggle(EngineSettings.EMOJI, text(R.string.im_emoji), R.string.im_emoji_summary, notFor = listOf(Engines.HANDWRITING)),
                // 全拼's rq, sj, xq, and in each the words 日期, 时间, 星期 (PhraseBook.WORD_KEYS); handwriting writes none of them
                Toggle(EngineSettings.BUILT_IN_PHRASES, text(R.string.im_built_in_phrases), R.string.im_built_in_phrases_summary, notFor = listOf(Engines.HANDWRITING)),
                Choice(
                    EngineSettings.MODEL_LEVEL, R.string.im_model_level, ModelLevel.entries.map { it.key }, R.string.im_model_level_summary,
                    label = { ctx, v -> ctx.getString(MODEL_LEVELS.getValue(ModelLevel.entries.first { it.key == v })) },
                    // what the engine reads as the default (EngineSettings.parse)
                    nearest = { v -> ModelLevel.entries.firstOrNull { it.key == v }?.key ?: ModelLevel.HIGH.key },
                ),
                // the punctuation addon's own page went with the list of addons
                Page(PUNCTUATION, R.string.punctuation_map, R.string.punctuation_map_summary, SettingsRoute.Punctuation()),
            )
        ),
        Section(
            R.string.shared_section_pinyin, listOf(
                // the nine keys type digits: no letter slips onto its neighbour
                Toggle("${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", text(R.string.im_typos), R.string.im_typos_summary, notFor = listOf(Engines.T9)),
                // u and v are both on 8
                Flags(EngineSettings.FUZZY, R.string.im_fuzzy, R.string.im_fuzzy_none, FUZZY, notFor = mapOf(Fuzzy.V_U.name to listOf(Engines.T9))),
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

    private val pinyin = listOf(
        sharedLink,
        Section(
            R.string.im_section_typing, listOf(
                // full pinyin's alone: 双拼's u is a key of its own, 九键's no letter
                Toggle(EngineSettings.CHAIZI, text(R.string.im_chaizi), R.string.im_chaizi_summary),
                // full pinyin's alone: 双拼 types its letters otherwise, the nine keys digits
                Toggle(EngineSettings.LATIN_WORDS, text(R.string.im_latin_words), R.string.im_latin_words_summary),
            )
        ),
    )

    private val shuangpin = listOf(
        sharedLink,
        Section(
            R.string.im_section_scheme, listOf(
                Choice(
                    EngineSettings.SHUANGPIN_PROFILE, R.string.im_shuangpin_scheme, EngineSettings.SCHEMES.keys.toList(),
                    label = { ctx, v -> ctx.getString(SHUANGPIN.getValue(EngineSettings.SCHEMES.getValue(v))) },
                    nearest = { v -> if (v in EngineSettings.SCHEMES) v else EngineSettings.ZIRANMA },
                )
            )
        ),
    )

    private val nineKeys = listOf(
        sharedLink,
        Section(
            R.string.im_section_typing, listOf(
                // the keyboard's: how it sends a key pressed again
                AppChoice(AppPrefs.T9_MULTI_TAP, R.string.t9_multi_tap, R.string.t9_multi_tap_summary, T9MultiTap.entries) {
                    AppPrefs.getInstance().keyboard.t9MultiTap
                },
                // how 九键英文 (the 中/英 key's English) types
                AppChoice(AppPrefs.T9_ENGLISH_TYPING, R.string.t9_english_typing, R.string.t9_english_typing_summary, T9EnglishTyping.entries) {
                    AppPrefs.getInstance().keyboard.t9EnglishTyping
                },
                Page(T9_LETTERS, R.string.t9_letters, R.string.t9_letters_summary, SettingsRoute.T9Letters),
                Page(T9_SYMBOLS, R.string.t9_symbols, R.string.t9_symbols_summary, SettingsRoute.T9Symbols),
            )
        ),
    )

    // each table's own, under its group of the addon's config
    private val table = listOf(
        sharedLink,
        Section(
            R.string.im_section_typing, listOf(
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

    // androidengine's English page (EngineEnglishPage)
    private val english = listOf(
        sharedLink,
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
    val BUILT_IN: List<String> = listOf(Engines.PINYIN, Engines.T9, Engines.SHUANGPIN) + Engines.TABLES.keys + Engines.HANDWRITING + InputMethodNames.KEYBOARD

    /** The page of [uniqueName], or null for an input method the app does not know. */
    fun of(uniqueName: String): List<Item>? = when (uniqueName) {
        Engines.PINYIN -> pinyin
        Engines.T9 -> nineKeys
        Engines.SHUANGPIN -> shuangpin
        Engines.HANDWRITING -> handwriting
        InputMethodNames.KEYBOARD -> english
        else -> if (uniqueName.startsWith("engine-")) table else null
    }

    /**
     * What [items] show of [cfg], as a config of its own to save: the addon takes what it is given
     * and keeps the rest (a partial load), so a page left open behind the shared one, which read
     * the shared options before they were changed there, does not write them back as they were.
     */
    fun only(items: List<Item>, cfg: RawConfig): RawConfig {
        val shown = RawConfig()
        fun copy(path: String) {
            val from = cfg.find(path) ?: return
            shown.at(path).value = from.value
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

    // an item's path, a/b for b under a: where it is, made if it is not there
    private fun RawConfig.at(path: String): RawConfig = path.split('/').fold(this) { at, name -> at.getOrCreate(name) }

    // an item's path: where it is, or null if it is not there
    private fun RawConfig.find(path: String): RawConfig? = path.split('/').fold<String, RawConfig?>(this) { at, name -> at?.findByName(name) }

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

    /** [items] as a screen over [cfg], [save] called after each change, [open] opening a [Page]'s route. */
    fun create(
        manager: PreferenceManager,
        items: List<Item>,
        cfg: RawConfig,
        save: () -> Unit,
        act: ImAct = { _, _ -> },
        confirm: ImConfirm = askFirst,
        open: (SettingsRoute) -> Unit,
    ): PreferenceScreen = manager.createPreferenceScreen(manager.context).also { addTo(it, items, cfg, save, act, confirm, open) }

    /** [items] added to [group], as [create] makes them: a page of the app's own preferences may show some of fcitx's too. */
    fun addTo(
        group: PreferenceGroup,
        items: List<Item>,
        cfg: RawConfig,
        save: () -> Unit,
        act: ImAct = { _, _ -> },
        confirm: ImConfirm = askFirst,
        open: (SettingsRoute) -> Unit,
    ) {
        val context = group.context
        fun raw(path: String) = cfg.at(path)
        fun add(item: Item, into: (Preference) -> Unit) {
            when (item) {
                is Section -> {
                    val category = PreferenceCategory(context).apply {
                        setTitle(item.title)
                        isIconSpaceReserved = false
                    }
                    into(category)
                    item.items.forEach { add(it, category::addPreference) }
                }
                is Toggle -> {
                    val raw = raw(item.key)
                    into(SwitchPreferenceCompat(context).apply {
                        // the key a search result names it by (SettingsIndex.Target); still never stored
                        key = item.key
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        title = item.title(context)
                        summary = summaryOf(context, item.summary, item.notFor)
                        isChecked = item.read(raw.value)
                        setOnPreferenceChangeListener { _, value ->
                            raw.value = item.write(value as Boolean)
                            save()
                            true
                        }
                    })
                }
                is Choice -> {
                    val raw = raw(item.key)
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
                        value = item.nearest(raw.value)
                        // the explanation goes under the value: a dialog message would replace the list of choices
                        summaryProvider = choiceSummary(context, item)
                        setOnPreferenceChangeListener { _, value ->
                            raw.value = value as String
                            save()
                            true
                        }
                    })
                }
                is Flags -> into(flagsPreference(context, item, item.flags.map { raw("${item.path}/${it.first}") }, save))
                is AppChoice<*> -> into(appChoice(context, item))
                is Page -> into(page(context, item, open))
                is Action -> into(action(context, item, act, confirm))
                is AppToggle -> into(appToggle(context, item))
            }
        }
        items.forEach { add(it, group::addPreference) }
    }

    /** [item] as one preference over [raws], one for each of its flags, picked in a dialog; [save] called after each change. */
    private fun flagsPreference(context: Context, item: Flags, raws: List<RawConfig>, save: () -> Unit): Preference =
        object : Preference(context) {
            fun refresh() {
                val on = item.flags.filterIndexed { i, _ -> raws[i].value == "True" }
                summary = if (on.isEmpty()) context.getString(item.none) else on.joinToString("  ") { it.second }
            }

            override fun onClick() {
                val checked = BooleanArray(raws.size) { raws[it].value == "True" }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(flagList(context, item, checked))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        checked.forEachIndexed { i, on -> raws[i].value = if (on) "True" else "False" }
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

    private fun <T> appChoice(context: Context, item: AppChoice<T>) where T : Enum<T>, T : ManagedPreferenceEnum =
        ListPreference(context).apply {
            val pref = item.pref()
            key = item.key
            // the app's preference set by hand: this screen's store is fcitx's config
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(item.title)
            dialogTitle = title
            entries = item.values.map { context.getString(it.stringRes) }.toTypedArray()
            entryValues = item.values.map { it.name }.toTypedArray()
            value = pref.getValue().name
            val summary = context.getString(item.summary)
            summaryProvider = Preference.SummaryProvider<ListPreference> { "${it.entry}\n$summary" }
            setOnPreferenceChangeListener { _, value ->
                pref.setValue(item.values.first { it.name == value })
                true
            }
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
