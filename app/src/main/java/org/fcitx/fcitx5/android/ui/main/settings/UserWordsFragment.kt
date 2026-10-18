/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The user dictionary at a glance: how many words each of its lists holds, a page of its own
 * for each (see [UserWordListFragment]: a list may run to thousands), and the whole of it
 * exported as a text file; it is imported back where every dictionary is imported, the
 * dictionaries' page asking into which ([PinyinDictionaryFragment]).
 */
class UserWordsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val lists = mutableMapOf<UserWord.Kind, Preference>()

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::export) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        val words = category(R.string.words_section_manage)
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
        }
        val files = category(R.string.words_section_files)
        files.addPreference(R.string.export_words, R.string.export_words_summary) {
            exportLauncher.launch("youmo-words-${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.txt")
        }
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        setTitle(title)
        isIconSpaceReserved = false
        preferenceScreen.addPreference(this)
    }

    override fun onResume() {
        super.onResume()
        // back from a list: counted again
        refreshCounts()
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val counts = viewModel.fcitx.runOnReady { userWordCounts() }
            lists.forEach { (kind, pref) -> pref.summary = count(counts[kind] ?: 0) }
        }
    }

    private fun count(n: Int) = resources.getQuantityString(R.plurals.word_count, n, n)

    private fun export(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            ctx.showingFileErrors("user words export") {
                val lines = viewModel.fcitx.runOnReady { exportUserWords() }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStreamOrThrow(uri).bufferedWriter().use { out ->
                        out.write(WordListFiles.exportHead(getString(R.string.export_words_header)))
                        lines.forEach { out.write(it); out.write("\n") }
                    }
                }
                ctx.toast(count(lines.size))
            }
        }
    }

    companion object {
        fun title(kind: UserWord.Kind) = when (kind) {
            UserWord.Kind.ADDED -> R.string.my_words_added
            UserWord.Kind.LEARNED -> R.string.my_words_learned
            UserWord.Kind.BLOCKED -> R.string.my_words_blocked
        }
    }
}
