/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

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
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp

/**
 * The user dictionary at a glance: how many words each of its lists holds, a page of its own
 * for each (see [UserWordListFragment]: a list may run to thousands), and the whole of it
 * exported as a learning backup (U-100: the words, imported ones, pins, blocks and the order
 * learned, as 另存一份 saves it); it is imported back where every dictionary is imported, the
 * dictionaries' page merging it as a backup restored ([PinyinDictionaryFragment]).
 *
 * At its top the switch of U-93 (the same as ⋯'s); the pins and the predictions turned off have
 * pages of their own ([LearnedItemsFragment]), and 清空学到的 (U-86) asks with what goes and
 * what stays.
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

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(::export) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        // not kept by this screen: the app's preference is, which ⋯ sets too
        habits = SwitchPreferenceCompat(ctx).apply {
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
        // U-106, beside U-93 (review-DE-design D-S7): the engine told, which makes the backups
        backups = SwitchPreferenceCompat(ctx).apply {
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
        val words = category(R.string.words_section_manage)
        fun addListed(what: LearningOp.Listed) {
            listed[what] = Preference(ctx).apply {
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
                setTitle(title(kind))
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.UserWordList(kind.name))
                    true
                }
                words.addPreference(this)
            }
            // U-72: the predictions turned off right after the blocked words, as they go with them
            if (kind == UserWord.Kind.BLOCKED) addListed(LearningOp.Listed.NO_PREDICT)
        }
        addListed(LearningOp.Listed.PINS)
        // English's learned words, a page of their own: the one the English settings open
        words.addPreference(R.string.english_words) {
            navigateWithAnim(SettingsRoute.UserWordList(UserWord.Kind.LEARNED.name, english = true))
        }
        words.addPreference(R.string.learning_clear_learned, R.string.learning_clear_learned_summary) { confirmClear() }
        val files = category(R.string.words_section_files)
        files.addPreference(R.string.learning_backups, R.string.learning_backups_summary) {
            navigateWithAnim(SettingsRoute.LearningBackups)
        }
        files.addPreference(R.string.export_words, R.string.export_words_summary) {
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
        // turned off or on in ⋯ meanwhile
        habits.isChecked = useHabits.getValue()
        backups.isChecked = autoBackup.getValue()
        // back from a list: counted again
        refreshCounts()
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val counts = viewModel.fcitx.runOnReady { userWordCounts() }
            lists.forEach { (kind, pref) -> pref.summary = count(counts[kind] ?: 0) }
            val overview = viewModel.fcitx.runOnReady { learning(LearningOp.Overview) } as? LearningResult.Overview ?: return@launch
            listed[LearningOp.Listed.PINS]?.summary = count(overview.pinned)
            listed[LearningOp.Listed.NO_PREDICT]?.summary = count(overview.noPredict)
        }
    }

    /**
     * U-86: what goes and what stays, and 「全部清空」 unticked, which says the blocked words come
     * back: counted first, never a 0 that is only not counted yet (review-E-ui N4).
     */
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
        // ticked, what stays is listed no more as kept: it goes too (the user, 2026-10-22)
        all.setOnCheckedChangeListener { _, ticked -> dialog.setMessage(getString(clearMessage(ticked))) }
    }

    private fun clear(all: Boolean) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            val cleared = viewModel.fcitx.runOnReady { learning(LearningOp.ClearLearned(all)) }
            // works locked too (the record in memory then): what fails is the disk (review-E-ui S6)
            // not on disk (locked since boot) is not now, as ⋯'s 清掉最近 says it (StatusAreaWindow)
            ctx.toast(clearedMessage(cleared))
            refreshCounts()
        }
    }

    private fun count(n: Int) = resources.getQuantityString(R.plurals.word_count, n, n)

    // all of it, as a backup (WD-10): a word list would carry neither the imports nor the order learned
    private fun export(uri: Uri) = restore.saveCopy(uri, R.string.export_words_done)

    companion object {
        fun title(kind: UserWord.Kind) = when (kind) {
            UserWord.Kind.ADDED -> R.string.my_words_added
            UserWord.Kind.LEARNED -> R.string.my_words_learned
            UserWord.Kind.BLOCKED -> R.string.my_words_blocked
        }
    }
}

/** The words 「全部清空」 unticked brings back (F2730): the blocked words, and the tables' blocks [tableBlocks] listed. */
internal fun blockedThatReturn(counts: Map<UserWord.Kind, Int>, tableBlocks: LearningResult?): Int =
    (counts[UserWord.Kind.BLOCKED] ?: 0) + ((tableBlocks as? LearningResult.Listed)?.items?.size ?: 0)

/** What 清空学到的 says it clears and keeps: [all] (全部清空 ticked) keeps the custom phrases alone. */
@StringRes
internal fun clearMessage(all: Boolean): Int =
    if (all) R.string.learning_clear_all_message else R.string.learning_clear_learned_message

/** What 清掉学到的 tells: works locked too (the record in memory then), what fails is the disk; anything else is not now. */
@StringRes
internal fun clearedMessage(cleared: LearningResult?): Int = when (cleared) {
    is LearningResult.Cleared -> R.string.learning_cleared
    LearningResult.Failed -> R.string.learning_clear_failed
    else -> R.string.learning_not_now
}
