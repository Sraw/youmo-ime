/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.utils.lazyRoute

/**
 * What the user did on purpose that the learning record keeps (U-80): the words pinned first
 * under keys (U-83), or the predictions turned off after a word (U-72). Tapped, one is taken off:
 * unpinned, or predicted again. A few dozen at most, so a page of preferences.
 */
class LearnedItemsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.LearnedItems>()

    private val what by lazy { LearningOp.Listed.valueOf(args.what) }

    // the input methods' names, a table's pin said under its own (review-E-ui N10)
    private var names = emptyMap<String, String>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(title(what)))
    }

    private fun load() {
        lifecycleScope.launch {
            val listed = viewModel.fcitx.runOnReady { learning(LearningOp.ListItems(what)) } as? LearningResult.Listed ?: return@launch
            val ctx = requireContext()
            // as the list page names them (UserWordListFragment): translated, not fcitx's own
            names = viewModel.fcitx.runOnReady { availableIme() }.associate { it.uniqueName to InputMethodNames.of(ctx, it) }
            val screen = preferenceScreen
            screen.removeAll()
            if (listed.items.isEmpty()) {
                screen.addPreference(Preference(requireContext()).apply {
                    setTitle(R.string.word_list_empty)
                    isIconSpaceReserved = false
                    isSelectable = false
                })
            }
            for (item in listed.items) {
                screen.addPreference(Preference(requireContext()).apply {
                    title = item.text
                    summary = detail(item)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        confirmRemove(item)
                        true
                    }
                })
            }
        }
    }

    private fun detail(item: ListedItem): String {
        val code = item.code ?: return getString(R.string.learning_no_predict_detail, item.prev)
        val table = code.kind.takeIf { it.startsWith(TABLE) }?.let { names[it.removePrefix(TABLE)] }
        return getString(if (item.blocked) R.string.learning_table_blocked_detail else R.string.learning_pinned_detail, keys(code), table ?: label(requireContext(), code))
    }

    private fun confirmRemove(item: ListedItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(item.text)
            .setMessage(detail(item))
            .setPositiveButton(
                when {
                    item.code == null -> R.string.learning_suggest_again
                    item.blocked -> R.string.unblock_word
                    else -> R.string.learning_unpin
                },
            ) { _, _ ->
                lifecycleScope.launch {
                    viewModel.fcitx.runOnReady { learning(LearningOp.RemoveItems(listOf(item))) }
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private val TABLE = Kinds.table("")
        private val KEYS = Kinds.keys("")

        /**
         * A pin's keys as the user reads them: a grouped keyboard's codes (private use, most fonts
         * draw them blank, TalkBack reads nothing) as their letters, `bn ui`;
         * any other kind's as stored.
         */
        fun keys(code: Code): String =
            Kinds.keysTag(code.kind)?.let { NineKeyLetters.grouped(it) }?.shown(code.keys) ?: code.keys

        fun title(what: LearningOp.Listed) = when (what) {
            LearningOp.Listed.PINS -> R.string.my_words_pinned
            LearningOp.Listed.NO_PREDICT -> R.string.my_words_no_predict
            LearningOp.Listed.TABLE_BLOCKS -> R.string.my_words_table_blocked
        }

        /**
         * [kind]'s name, and a 双拼 pin's scheme after it (`双拼 18 键 · 自然码`), on any keyboard: its
         * keys read other syllables under each scheme, so two pins of the same keys are told apart.
         */
        fun label(context: Context, code: Code): String {
            val kind = context.getString(kind(code))
            val scheme = Kinds.shuangpinScheme(code.kind)?.let { InputMethodSettings.schemeName(context, it) }
            return if (scheme == null) kind else context.getString(R.string.learning_kind_with_scheme, kind, scheme)
        }

        /** The keyboard a pin's [code] is of, as the user knows it. */
        fun kind(code: Code) = when {
            code.kind == Kinds.FULL -> R.string.learning_kind_full
            code.kind == Kinds.HANDWRITING -> R.string.learning_kind_handwriting
            code.kind.startsWith(Kinds.shuangpin("")) -> R.string.learning_kind_shuangpin
            // the keyboard from the kind's tag; its scheme said by [label]
            Kinds.isShuangpin(code.kind) ->
                if (Kinds.keysTag(code.kind) == NineKeyLetters.NINE_SP.tag()) R.string.learning_kind_shuangpin_nine
                else R.string.learning_kind_shuangpin_keys18
            code.kind.startsWith(Kinds.nineKeys("")) || code.kind.startsWith(Kinds.nineKeysMultiTap("")) -> R.string.learning_kind_nine_keys
            code.kind.startsWith(KEYS) -> R.string.learning_kind_keys14
            else -> R.string.learning_kind_table
        }
    }
}
