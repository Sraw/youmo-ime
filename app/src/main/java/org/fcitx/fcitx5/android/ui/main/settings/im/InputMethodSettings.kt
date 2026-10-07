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
import org.fcitx.fcitx5.android.core.RawConfig
import kotlin.math.abs

/**
 * The pages of the app's own input methods, in its own words: fcitx stores the values (the keys
 * are androidengine's and androidkeyboard's options), while what an option is called, which of
 * them a user sees and how they are grouped is decided here. fcitx's own descriptions came in
 * fcitx's language, not the app's, and named every knob the engine has.
 */
object InputMethodSettings {

    sealed interface Item

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
        val key: String,
        @StringRes val title: Int,
        val values: List<String>,
        @StringRes val message: Int = 0,
        val label: (Context, String) -> String,
        val nearest: (String) -> String = { it },
    ) : Item

    /**
     * Switches of the same kind, picked together in one dialog: [flags] of key and label, under
     * [path]; those [unused] by this input method greyed there, as [Toggle.unused].
     */
    class Flags(
        val path: String,
        @StringRes val title: Int,
        @StringRes val none: Int,
        val flags: List<Pair<String, String>>,
        val unused: Set<String> = emptySet(),
    ) : Item

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

    private val PAGE_SIZES = (3..10).map { it.toString() }

    private val SHUANGPIN = listOf(
        "Ziranma" to R.string.shuangpin_ziranma,
        "MS" to R.string.shuangpin_ms,
        "Ziguang" to R.string.shuangpin_ziguang,
        "ABC" to R.string.shuangpin_abc,
        "Zhongwenzhixing" to R.string.shuangpin_zhongwenzhixing,
        "PinyinJiajia" to R.string.shuangpin_pinyinjiajia,
        "Xiaohe" to R.string.shuangpin_xiaohe,
        "GB Standard" to R.string.shuangpin_gb,
    )

    // keys as fcitx's Fuzzy options have them (androidengine.h)
    private val FUZZY = listOf(
        "V_U" to "u ↔ v", "AN_ANG" to "an ↔ ang", "EN_ENG" to "en ↔ eng", "IAN_IANG" to "ian ↔ iang",
        "IN_ING" to "in ↔ ing", "U_OU" to "u ↔ ou", "UAN_UANG" to "uan ↔ uang", "C_CH" to "c ↔ ch",
        "F_H" to "f ↔ h", "L_N" to "l ↔ n", "L_R" to "l ↔ r", "S_SH" to "s ↔ sh", "Z_ZH" to "z ↔ zh",
    )

    /** [nineKeys]: the options 九键 has no use for greyed: slips onto a neighbouring key, u ↔ v (both on 8). */
    private fun pinyin(shuangpin: Boolean, nineKeys: Boolean = false) = buildList {
        if (shuangpin) {
            add(
                Section(
                    R.string.im_section_scheme, listOf(
                        Choice(
                            "ShuangpinProfile", R.string.im_shuangpin_scheme, SHUANGPIN.map { it.first },
                            label = { ctx, v -> ctx.getString(SHUANGPIN.first { it.first == v }.second) },
                            nearest = { v -> if (SHUANGPIN.any { it.first == v }) v else SHUANGPIN.first().first },
                        )
                    )
                )
            )
        }
        add(
            Section(
                R.string.im_section_typing, listOf(
                    Choice("PageSize", R.string.im_page_size, PAGE_SIZES, label = { _, v -> v }, nearest = numbers(3..10)),
                    Toggle("Prediction", text(R.string.im_prediction), R.string.im_prediction_summary),
                    Toggle("SentenceModel", text(R.string.im_sentence_model), R.string.im_sentence_model_summary),
                    Toggle("Fuzzy/NG_GN", text(R.string.im_typos), R.string.im_typos_summary, unused = if (nineKeys) R.string.im_not_for_t9 else 0),
                    Flags("Fuzzy", R.string.im_fuzzy, R.string.im_fuzzy_none, FUZZY, unused = if (nineKeys) setOf("V_U") else emptySet()),
                )
            )
        )
    }

    private val PHRASE_LENGTHS = listOf("0", "2", "3", "4", "6", "-1")
    private val SAVE_AFTER = listOf("0", "1", "2", "3", "5", "10")

    private val table = listOf(
        Section(
            R.string.im_section_typing, listOf(
                Toggle("AutoSelect", text(R.string.im_auto_select), R.string.im_auto_select_summary),
                Toggle("Hint", text(R.string.im_hint), R.string.im_hint_summary),
                // Freq and Fast alike order by use (TableConf)
                Toggle("OrderPolicy", text(R.string.im_order_by_use), read = { it != "No" }, write = { if (it) "Freq" else "No" }),
            )
        ),
        Section(
            R.string.im_section_phrases, listOf(
                Choice(
                    "AutoPhraseLength", R.string.im_auto_phrase_length, PHRASE_LENGTHS, R.string.im_auto_phrase_summary,
                    label = { ctx, v ->
                        when (v) {
                            "0" -> ctx.getString(R.string.im_auto_phrase_off)
                            "-1" -> ctx.getString(R.string.im_auto_phrase_longest)
                            else -> ctx.getString(R.string.im_auto_phrase_up_to, v.toInt())
                        }
                    },
                    nearest = { v -> if (v.toIntOrNull()?.let { it < 0 } == true) "-1" else nearestOf(PHRASE_LENGTHS.dropLast(1))(v) },
                ),
                Choice(
                    "SaveAutoPhraseAfter", R.string.im_save_phrase_after, SAVE_AFTER,
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

    private val MODIFIERS = listOf(
        "None" to R.string.im_modifier_none, "Alt" to R.string.im_modifier_alt,
        "Control" to R.string.im_modifier_control, "Super" to R.string.im_modifier_super,
    )

    private val english = listOf(
        Section(
            R.string.im_section_typing, listOf(
                Toggle("EnableWordHint", text(R.string.im_word_hint), R.string.im_word_hint_summary),
                Choice("PageSize", R.string.im_word_hint_size, PAGE_SIZES, label = { _, v -> v }, nearest = numbers(3..10)),
                Toggle("EditorControlledWordHint", text(R.string.im_word_hint_editor), R.string.im_word_hint_editor_summary),
                Toggle("InsertSpace", text(R.string.im_insert_space)),
            )
        ),
        Section(
            R.string.im_section_physical, listOf(
                Toggle("WordHintOnPhysicalKeyboard", text(R.string.im_word_hint_physical)),
                Choice(
                    "ChooseModifier", R.string.im_choose_modifier, MODIFIERS.map { it.first },
                    label = { ctx, v -> ctx.getString(MODIFIERS.first { it.first == v }.second) },
                    nearest = { v -> if (MODIFIERS.any { it.first == v }) v else "Alt" },
                ),
            )
        ),
    )

    /** The page of [uniqueName], or null for an input method the app does not know. */
    fun of(uniqueName: String): List<Item>? = when (uniqueName) {
        "engine-pinyin" -> pinyin(shuangpin = false)
        "engine-t9" -> pinyin(shuangpin = false, nineKeys = true)
        "engine-shuangpin" -> pinyin(shuangpin = true)
        "keyboard-us" -> english
        else -> if (uniqueName.startsWith("engine-")) table else null
    }

    /** [items] as a screen over [cfg], [save] called after each change. */
    fun create(manager: PreferenceManager, items: List<Item>, cfg: RawConfig, save: () -> Unit): PreferenceScreen {
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
                    screen.addPreference(category)
                    item.items.forEach { add(it, category::addPreference) }
                }
                is Toggle -> {
                    val raw = raw(item.key)
                    into(SwitchPreferenceCompat(context).apply {
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
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = false
                        setTitle(item.title)
                        dialogTitle = title
                        if (item.message != 0) setDialogMessage(item.message)
                        entries = item.values.map { item.label(context, it) }.toTypedArray()
                        entryValues = item.values.toTypedArray()
                        value = item.nearest(raw.value)
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setOnPreferenceChangeListener { _, value ->
                            raw.value = value as String
                            save()
                            true
                        }
                    })
                }
                is Flags -> {
                    val raws = item.flags.map { raw("${item.path}/${it.first}") }
                    into(object : Preference(context) {
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
                        isPersistent = false
                        isIconSpaceReserved = false
                        setTitle(item.title)
                        refresh()
                    })
                }
            }
        }
        items.forEach { add(it, screen::addPreference) }
        return screen
    }

    /**
     * [item]'s flags as a list of check boxes over [checked], those it has no use for greyed and
     * marked so: a dialog's own multi-choice list cannot grey one.
     */
    private fun flagList(context: Context, item: Flags, checked: BooleanArray): ListView {
        val note = context.getString(R.string.im_not_for_t9)
        val labels = item.flags.map { (key, label) -> if (key in item.unused) "$label（$note）" else label }
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
