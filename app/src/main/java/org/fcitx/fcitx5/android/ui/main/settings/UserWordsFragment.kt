/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import java.util.Locale
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.input.learning.clearAndTell
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp

/**
 * The user dictionary at a glance: counts per list, a page per list ([UserWordListFragment]), and an export as a
 * learning backup (imported back on the dictionaries' page). 清空学到的 says what goes and what stays.
 */
class UserWordsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val restore by lazy { LearningRestore(this, { viewModel.fcitx }) }

    private val lists = mutableMapOf<UserWord.Kind, Preference>()

    private val listed = mutableMapOf<LearningOp.Listed, Preference>()

    private val useHabits = AppPrefs.getInstance().internal.useHabits

    private lateinit var habits: SwitchPreferenceCompat

    private val autoBackup = AppPrefs.getInstance().internal.learningAutoBackup

    private lateinit var backups: SwitchPreferenceCompat

    private val voiceAsk = AppPrefs.getInstance().internal.voiceAskRemember

    private lateinit var voiceAsks: SwitchPreferenceCompat

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    // learning not reaching the disk now (LearnLog.saving): said at the top while it lasts
    private lateinit var notSaving: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(::export) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        notSaving = Preference(ctx).apply {
            isIconSpaceReserved = false
            isSelectable = false
            isVisible = false
            setSummary(R.string.learning_not_saving)
        }
        screen.addPreference(notSaving)
        // not kept by this screen: the app's preference is, which ⋯ sets too
        habits = SwitchPreferenceCompat(ctx).apply {
            key = HABITS_KEY
            isPersistent = false
            isIconSpaceReserved = false
            setTitle(R.string.learning_use_habits)
            setSummary(R.string.learning_use_habits_summary)
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                useHabits.setValue(on)
                viewModel.fcitx.launchOnReady { it.learning(LearningOp.UseHabits(on)) }
                true
            }
        }
        screen.addPreference(habits)
        // the engine makes the backups, so it is told
        backups = SwitchPreferenceCompat(ctx).apply {
            key = AUTO_BACKUP_KEY
            isPersistent = false
            isIconSpaceReserved = false
            setTitle(R.string.learning_auto_backup)
            setSummary(R.string.learning_auto_backup_summary)
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                autoBackup.setValue(on)
                viewModel.fcitx.launchOnReady { it.learning(BackupOp.SetAuto(on)) }
                true
            }
        }
        screen.addPreference(backups)
        // read by the keyboard as a talk's text goes in: nothing to tell the engine
        voiceAsks = SwitchPreferenceCompat(ctx).apply {
            key = VOICE_ASK_KEY
            isPersistent = false
            isIconSpaceReserved = false
            setTitle(R.string.learning_voice_ask)
            setSummary(R.string.learning_voice_ask_summary)
            setOnPreferenceChangeListener { _, value ->
                voiceAsk.setValue(value as Boolean)
                true
            }
        }
        screen.addPreference(voiceAsks)
        val words = category(R.string.words_section_manage)
        fun addListed(what: LearningOp.Listed) {
            listed[what] = Preference(ctx).apply {
                key = listedKey(what)
                setTitle(LearnedItemsFragment.title(what))
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.LearnedItems(what.name))
                    true
                }
                words.addPreference(this)
            }
        }
        for (kind in UserWord.Kind.entries) {
            lists[kind] = Preference(ctx).apply {
                key = listKey(kind)
                setTitle(title(kind))
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.UserWordList(kind.name))
                    true
                }
                words.addPreference(this)
            }
            // the turned-off predictions right after the blocked words, as they go with them
            if (kind == UserWord.Kind.BLOCKED) addListed(LearningOp.Listed.NO_PREDICT)
        }
        addListed(LearningOp.Listed.PINS)
        // the page the English settings open
        words.addPreference(R.string.english_words, key = ENGLISH_KEY) {
            navigateWithAnim(SettingsRoute.UserWordList(UserWord.Kind.LEARNED.name, english = true))
        }
        words.addPreference(R.string.learning_clear_learned, R.string.learning_clear_learned_summary, key = CLEAR_KEY) { confirmClear() }
        val files = category(R.string.words_section_files)
        files.addPreference(R.string.learning_backups, R.string.learning_backups_summary, key = BACKUPS_KEY) {
            navigateWithAnim(SettingsRoute.LearningBackups)
        }
        files.addPreference(R.string.export_words, R.string.export_words_summary, key = EXPORT_KEY) {
            LearningBackupsFragment.askSaveCopy(ctx, R.string.export_words, "youmo-words", exportLauncher::launch)
        }
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(title)
        isIconSpaceReserved = false
        preferenceScreen.addPreference(this)
    }

    override fun onResume() {
        super.onResume()
        // changed in ⋯ meanwhile
        habits.isChecked = useHabits.getValue()
        backups.isChecked = autoBackup.getValue()
        voiceAsks.isChecked = voiceAsk.getValue()
        // back from a list: recounted
        refreshCounts()
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val counts = viewModel.fcitx.runOnReady { userWordCounts() }
            lists.forEach { (kind, pref) -> pref.summary = count(counts[kind] ?: 0) }
            val overview = viewModel.fcitx.runOnReady { learning(LearningOp.Overview) } as? LearningResult.Overview ?: return@launch
            notSaving.isVisible = !overview.saving
            listed[LearningOp.Listed.PINS]?.summary = count(overview.pinned)
            listed[LearningOp.Listed.NO_PREDICT]?.summary = count(overview.noPredict)
        }
    }

    /** Says what goes and stays, and that unticked 「全部清空」 brings blocked words back: counted first, never a not-yet 0. */
    private fun confirmClear() {
        lifecycleScope.launch {
            val counts = viewModel.fcitx.runOnReady { userWordCounts() }
            // the tables' blocks under a code go too: they come back as the blocked words do
            val tableBlocks = viewModel.fcitx.runOnReady { learning(LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) }
            showClearDialog(blockedThatReturn(counts, tableBlocks))
        }
    }

    private fun showClearDialog(blocked: Int) {
        val ctx = context ?: return
        val all = CheckBox(ctx).apply {
            text = getString(R.string.learning_clear_all) +
                if (blocked == 0) "" else "\n" + resources.getQuantityString(R.plurals.learning_clear_all_blocked, blocked, blocked)
        }
        val padded = ctx.verticalLayout {
            setPaddingDp(20, 8, 20, 0)
            add(all, lParams(matchParent))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.learning_clear_learned)
            .setMessage(clearMessage(all = false))
            .setView(padded)
            .setPositiveButton(R.string.learning_clear_do) { _, _ -> clear(all.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // ticked, what stays goes too
        all.setOnCheckedChangeListener { _, ticked -> dialog.setMessage(getString(clearMessage(ticked))) }
    }

    private fun clear(all: Boolean) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            // works locked too (the record in memory then): only the disk can fail
            // not on disk (locked since boot) is not now, as ⋯'s 清掉最近 says it (PanelActions)
            ctx.toast(viewModel.fcitx.runOnReady { clearAndTell(LearningOp.ClearLearned(all)) })
            refreshCounts()
        }
    }

    private fun count(n: Int) = resources.getQuantityString(R.plurals.word_count, n, n)

    // a backup: a word list would carry neither imports nor learning order
    private fun export(uri: Uri) = restore.saveCopy(uri, R.string.export_words_done)

    companion object : SearchablePage {
        fun title(kind: UserWord.Kind) = when (kind) {
            UserWord.Kind.ADDED -> R.string.my_words_added
            UserWord.Kind.LEARNED -> R.string.my_words_learned
            UserWord.Kind.BLOCKED -> R.string.my_words_blocked
        }

        // the rows' ids: where the settings search points
        const val HABITS_KEY = "learning_use_habits"
        private const val AUTO_BACKUP_KEY = "learning_auto_backup"
        private const val VOICE_ASK_KEY = "learning_voice_ask"
        private const val ENGLISH_KEY = "english_words"
        private const val CLEAR_KEY = "learning_clear_learned"
        private const val BACKUPS_KEY = "learning_backups"
        private const val EXPORT_KEY = "export_words"

        private fun listKey(kind: UserWord.Kind) = "user_words_${kind.name.lowercase(Locale.ROOT)}"
        private fun listedKey(what: LearningOp.Listed) = "learned_${what.name.lowercase(Locale.ROOT)}"

        override fun searchEntries(context: Context): List<PageEntry> {
            fun row(@StringRes title: Int, key: String, @StringRes summary: Int = 0) =
                PageEntry(context.getString(title), SearchHighlight.PREF + key, summary = if (summary == 0) "" else context.getString(summary))
            return listOf(
                row(R.string.learning_use_habits, HABITS_KEY, R.string.learning_use_habits_summary),
                row(R.string.learning_auto_backup, AUTO_BACKUP_KEY, R.string.learning_auto_backup_summary),
                row(R.string.learning_voice_ask, VOICE_ASK_KEY, R.string.learning_voice_ask_summary),
            ) + UserWord.Kind.entries.map { row(title(it), listKey(it)) } +
                listOf(LearningOp.Listed.NO_PREDICT, LearningOp.Listed.PINS).map { row(LearnedItemsFragment.title(it), listedKey(it)) } +
                listOf(
                    row(R.string.english_words, ENGLISH_KEY),
                    row(R.string.learning_clear_learned, CLEAR_KEY, R.string.learning_clear_learned_summary),
                    row(R.string.learning_backups, BACKUPS_KEY, R.string.learning_backups_summary),
                    row(R.string.export_words, EXPORT_KEY, R.string.export_words_summary),
                )
        }
    }
}

/** The blocked words and the listed table blocks. */
internal fun blockedThatReturn(counts: Map<UserWord.Kind, Int>, tableBlocks: LearningResult?): Int =
    (counts[UserWord.Kind.BLOCKED] ?: 0) + ((tableBlocks as? LearningResult.Listed)?.items?.size ?: 0)

/** [all] (全部清空 ticked) keeps the custom phrases alone. */
@StringRes
internal fun clearMessage(all: Boolean): Int =
    if (all) R.string.learning_clear_all_message else R.string.learning_clear_learned_message
