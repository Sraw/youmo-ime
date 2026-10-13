/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
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
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import kotlin.math.abs

/**
 * The pages of the app's own input methods, in its own words: fcitx stores the values (the keys
 * are androidengine's options), while what an option is called, which of
 * them a user sees and how they are grouped is decided here. fcitx's own descriptions came in
 * fcitx's language, not the app's, and named every knob the engine has.
 */
object InputMethodSettings {

    sealed interface Item

    /** An item titled and summed up by string resources (0 for no summary): the settings search finds it by its [key]. */
    sealed interface Titled : Item {
        val key: String
        @get:StringRes val title: Int
        @get:StringRes val summary: Int
    }

    /**
     * [key] (a/b for b under a) is "True" or "False", unless [read] and [write] say otherwise.
     * With [unused], shown greyed with that as its summary: an option of the engine this input
     * method has no use for, kept for the others that share it.
     */
    class Toggle(
        val key: String,
        val title: (Context) -> String,
        @StringRes val summary: Int = 0,
        val read: (String) -> Boolean = { it == "True" },
        val write: (Boolean) -> String = { if (it) "True" else "False" },
        @StringRes val unused: Int = 0,
    ) : Item

    /** One of [values]; a value stored that is not one of them shows as the one [nearest] to it. */
    class Choice(
        override val key: String,
        @StringRes override val title: Int,
        val values: List<String>,
        @StringRes override val summary: Int = 0,
        val label: (Context, String) -> String,
        val nearest: (String) -> String = { it },
    ) : Titled

    /**
     * Switches of the same kind, picked together in one dialog: [flags] of key and label, under
     * [path]; those [unused] by this input method greyed there, as [Toggle.unused], with
     * [unusedNote] under each.
     */
    class Flags(
        val path: String,
        @StringRes val title: Int,
        @StringRes val none: Int,
        val flags: List<Pair<String, String>>,
        val unused: Set<String> = emptySet(),
        @StringRes val unusedNote: Int = 0,
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
    ) : Titled where T : Enum<T>, T : ManagedPreferenceEnum

    /** A switch over the app's own preference [pref], as [AppChoice] is a list. */
    class AppToggle(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val pref: () -> ManagedPreference<Boolean>,
    ) : Titled

    /** A page of its own, [route], as the app's settings open one: not an option of fcitx's. */
    class Page(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val route: SettingsRoute,
    ) : Titled

    class Section(@StringRes val title: Int, val items: List<Item>) : Item

    private fun text(@StringRes id: Int): (Context) -> String = { it.getString(id) }

    private fun numbers(range: IntRange): (String) -> String = { v ->
        v.toIntOrNull()?.coerceIn(range)?.toString() ?: range.first.toString()
    }

    /** The value of [values] (numbers) nearest to a number, the first for what is not one. */
    private fun nearestOf(values: List<String>): (String) -> String = { v ->
        val n = v.toIntOrNull()
        if (n == null) values.first() else values.minBy { abs(it.toInt() - n) }
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

    /** [nineKeys]: the options 九键 has no use for greyed: slips onto a neighbouring key, u ↔ v (both on 8). */
    private fun pinyin(shuangpin: Boolean, nineKeys: Boolean = false) = buildList {
        if (shuangpin) {
            add(
                Section(
                    R.string.im_section_scheme, listOf(
                        Choice(
                            EngineSettings.SHUANGPIN_PROFILE, R.string.im_shuangpin_scheme, EngineSettings.SCHEMES.keys.toList(),
                            label = { ctx, v -> ctx.getString(SHUANGPIN.getValue(EngineSettings.SCHEMES.getValue(v))) },
                            nearest = { v -> if (v in EngineSettings.SCHEMES) v else EngineSettings.ZIRANMA },
                        )
                    )
                )
            )
        }
        add(
            Section(
                R.string.im_section_typing, listOfNotNull(
                    // the keyboard's: how it sends a key pressed again
                    AppChoice(AppPrefs.T9_MULTI_TAP, R.string.t9_multi_tap, R.string.t9_multi_tap_summary, T9MultiTap.entries) {
                        AppPrefs.getInstance().keyboard.t9MultiTap
                    }.takeIf { nineKeys },
                    Page(T9_LETTERS, R.string.t9_letters, R.string.t9_letters_summary, SettingsRoute.T9Letters).takeIf { nineKeys },
                    Choice(EngineSettings.PAGE_SIZE, R.string.im_page_size, PAGE_SIZES, label = { _, v -> v }, nearest = numbers(EngineSettings.PAGE_SIZES)),
                    Toggle(EngineSettings.PREDICTION, text(R.string.im_prediction), R.string.im_prediction_summary),
                    Choice(
                        EngineSettings.MODEL_LEVEL, R.string.im_model_level, ModelLevel.entries.map { it.key }, R.string.im_model_level_summary,
                        label = { ctx, v -> ctx.getString(MODEL_LEVELS.getValue(ModelLevel.entries.first { it.key == v })) },
                        // what the engine reads as the default (EngineSettings.parse)
                        nearest = { v -> ModelLevel.entries.firstOrNull { it.key == v }?.key ?: ModelLevel.HIGH.key },
                    ),
                    Toggle("${EngineSettings.FUZZY}/${EngineSettings.TYPOS}", text(R.string.im_typos), R.string.im_typos_summary, unused = if (nineKeys) R.string.im_not_for_t9 else 0),
                    // full pinyin's alone: 双拼 types its letters otherwise, the nine keys digits
                    Toggle(EngineSettings.LATIN_WORDS, text(R.string.im_latin_words), R.string.im_latin_words_summary, unused = if (nineKeys) R.string.im_not_for_t9 else 0)
                        .takeUnless { shuangpin },
                    Flags(
                        EngineSettings.FUZZY, R.string.im_fuzzy, R.string.im_fuzzy_none, FUZZY,
                        unused = if (nineKeys) setOf(Fuzzy.V_U.name) else emptySet(), unusedNote = R.string.im_not_for_t9,
                    ),
                )
            )
        )
    }

    private val PHRASE_LENGTHS = listOf("0", "2", "3", "4", "6", "-1")
    private val SAVE_AFTER = listOf("0", "1", "2", "3", "5", "10")

    private val table = listOf(
        Section(
            R.string.im_section_typing, listOf(
                Toggle(TableSettings.AUTO_SELECT, text(R.string.im_auto_select), R.string.im_auto_select_summary),
                Toggle(TableSettings.HINT, text(R.string.im_hint), R.string.im_hint_summary),
                // Freq and Fast alike order by use
                Toggle(TableSettings.ORDER_POLICY, text(R.string.im_order_by_use), read = { it != "No" }, write = { if (it) "Freq" else "No" }),
            )
        ),
        Section(
            R.string.im_section_phrases, listOf(
                Choice(
                    TableSettings.AUTO_PHRASE_LENGTH, R.string.im_auto_phrase_length, PHRASE_LENGTHS, R.string.im_auto_phrase_summary,
                    label = { ctx, v ->
                        when (v) {
                            "0" -> ctx.getString(R.string.im_auto_phrase_off)
                            "-1" -> ctx.getString(R.string.im_auto_phrase_longest)
                            else -> ctx.getString(R.string.im_auto_phrase_up_to, v.toInt())
                        }
                    },
                    nearest = { v ->
                        val n = v.toIntOrNull()
                        when {
                            // 1 makes no phrase, a phrase being two characters at least (TableSession): off
                            n == null || n in 0..1 -> "0"
                            n < 0 -> "-1"
                            else -> nearestOf(PHRASE_LENGTHS)(v)
                        }
                    },
                ),
                Choice(
                    TableSettings.SAVE_AUTO_PHRASE_AFTER, R.string.im_save_phrase_after, SAVE_AFTER,
                    label = { ctx, v ->
                        val n = v.toInt()
                        if (n <= 0) ctx.getString(R.string.im_save_phrase_picked)
                        else ctx.resources.getQuantityString(R.plurals.im_save_phrase_typed, n, n)
                    },
                    nearest = { v -> if ((v.toIntOrNull() ?: 0) <= 0) "0" else nearestOf(SAVE_AFTER)(v) },
                ),
            )
        ),
    )

    // androidengine's English page (EngineEnglishPage)
    private val english = listOf(
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

    /** The English page's word list: its key. */
    const val ENGLISH_WORDS = "english_words"

    /** 九键's page of its keys' letters: its key. */
    const val T9_LETTERS = "t9_letters"

    /** The app's own input methods, each with a page in [of]. */
    val BUILT_IN: List<String> = listOf(Engines.PINYIN, Engines.T9, Engines.SHUANGPIN) + Engines.TABLES.keys + InputMethodNames.KEYBOARD

    /** The page of [uniqueName], or null for an input method the app does not know. */
    fun of(uniqueName: String): List<Item>? = when (uniqueName) {
        Engines.PINYIN -> pinyin(shuangpin = false)
        Engines.T9 -> pinyin(shuangpin = false, nineKeys = true)
        Engines.SHUANGPIN -> pinyin(shuangpin = true)
        InputMethodNames.KEYBOARD -> english
        else -> if (uniqueName.startsWith("engine-")) table else null
    }

    private fun page(context: Context, item: Page, open: (SettingsRoute) -> Unit) = Preference(context).apply {
        key = item.key
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(item.title)
        setSummary(item.summary)
        setOnPreferenceClickListener {
            open(item.route)
            true
        }
    }

    /** [items] as a screen over [cfg], [save] called after each change, [open] opening a [Page]'s route. */
    fun create(
        manager: PreferenceManager,
        items: List<Item>,
        cfg: RawConfig,
        save: () -> Unit,
        open: (SettingsRoute) -> Unit,
    ): PreferenceScreen {
        val context = manager.context
        val screen = manager.createPreferenceScreen(context)
        fun raw(path: String) = path.split('/').fold(cfg) { at, name -> at.getOrCreate(name) }
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
                        if (item.summary != 0) setSummary(item.summary)
                        if (item.unused != 0) {
                            isEnabled = false
                            setSummary(item.unused)
                        }
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
                is AppToggle -> into(appToggle(context, item))
            }
        }
        items.forEach { add(it, screen::addPreference) }
        return screen
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
        setSummary(item.summary)
        isChecked = pref.getValue()
        setOnPreferenceChangeListener { _, value ->
            pref.setValue(value as Boolean)
            true
        }
    }

    private fun choiceSummary(context: Context, item: Choice): Preference.SummaryProvider<ListPreference> {
        val simple = ListPreference.SimpleSummaryProvider.getInstance()
        return if (item.summary == 0) {
            simple
        } else {
            val summary = context.getString(item.summary)
            // the value as the simple provider words it: "Not set", not "null", for one not among the entries
            Preference.SummaryProvider<ListPreference> { "${simple.provideSummary(it)}\n$summary" }
        }
    }

    /**
     * [item]'s flags as a list of check boxes over [checked], those it has no use for greyed and
     * marked so: a dialog's own multi-choice list cannot grey one.
     */
    private fun flagList(context: Context, item: Flags, checked: BooleanArray): ListView {
        val note = if (item.unusedNote == 0) null else context.getString(item.unusedNote)
        // the note under the label: brackets around it would be one language's
        val labels = item.flags.map { (key, label) -> if (key in item.unused && note != null) "$label\n$note" else label }
        val list = ListView(context)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_multiple_choice, labels) {
            override fun isEnabled(position: Int) = item.flags[position].first !in item.unused
            override fun areAllItemsEnabled() = item.unused.isEmpty()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { it.isEnabled = isEnabled(position) }
        }
        checked.forEachIndexed { i, on -> list.setItemChecked(i, on) }
        list.setOnItemClickListener { _, _, position, _ -> checked[position] = list.isItemChecked(position) }
        return list
    }

}
