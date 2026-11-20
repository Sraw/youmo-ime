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
import org.fcitx.fcitx5.android.core.ShuangpinNames
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.stroke.StrokeLookup
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.lazyRoute

/** Pins and turned-off predictions the learning record keeps; tapping one undoes it. A few dozen at most, so a preference page. */
class LearnedItemsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val args by lazyRoute<SettingsRoute.LearnedItems>()

    private val what by lazy { LearningOp.Listed.valueOf(args.what) }

    // a table's pin is noted under its input method's name
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
            // as the list page names them: translated, not fcitx's own
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
        // a whole phrase is off everywhere, after no word
        val code = item.code ?: return if (item.prev.isEmpty()) getString(R.string.learning_no_predict_phrase_detail) else getString(R.string.learning_no_predict_detail, item.prev)
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
         * As the user reads them: a grouped keyboard's private-use codes (blank in most fonts, unread by TalkBack) as letters,
         * a strokes block's as strokes; anything else as stored.
         */
        fun keys(code: Code): String = if (code.kind == STROKES && code.keys.all { it in Strokes.KEYS }) Strokes.shown(code.keys)
            else Kinds.keysOf(code.kind)?.shown(code.keys) ?: code.keys

        // a character blocked under strokes typed (笔画查字), filed as a table's block
        private val STROKES = Kinds.table(StrokeLookup.BLOCK_TABLE)

        fun title(what: LearningOp.Listed) = when (what) {
            LearningOp.Listed.PINS -> R.string.my_words_pinned
            LearningOp.Listed.NO_PREDICT -> R.string.my_words_no_predict
            LearningOp.Listed.TABLE_BLOCKS -> R.string.my_words_table_blocked
        }

        /** A 双拼 pin's scheme follows (`双拼 18 键 · 自然码`): the same keys read other syllables under each scheme. */
        fun label(context: Context, code: Code): String {
            val kind = context.getString(kind(code))
            val scheme = Kinds.shuangpinScheme(code.kind)?.let { ShuangpinNames.of(context, it) }
            return if (scheme == null) kind else context.getString(R.string.learning_kind_with_scheme, kind, scheme)
        }

        fun kind(code: Code) = when {
            code.kind == Kinds.FULL -> R.string.learning_kind_full
            code.kind == Kinds.HANDWRITING -> R.string.learning_kind_handwriting
            code.kind == STROKES -> R.string.learning_kind_strokes
            code.kind.startsWith(Kinds.shuangpin("")) -> R.string.learning_kind_shuangpin
            // the scheme is said by [label]
            Kinds.isShuangpin(code.kind) -> when (Kinds.keysTag(code.kind)) {
                NineKeyLetters.NINE_SP.tag() -> R.string.learning_kind_shuangpin_nine
                NineKeyLetters.G14.tag() -> R.string.learning_kind_shuangpin_keys14
                else -> R.string.learning_kind_shuangpin_keys18
            }
            code.kind.startsWith(Kinds.nineKeys("")) || code.kind.startsWith(Kinds.nineKeysMultiTap("")) -> R.string.learning_kind_nine_keys
            // pinyin's 18 and 14 keys by tag; a theme's own groups
            code.kind.startsWith(KEYS) -> when (Kinds.keysOf(code.kind)) {
                NineKeyLetters.G18 -> R.string.learning_kind_keys18
                NineKeyLetters.G14 -> R.string.learning_kind_keys14
                else -> R.string.learning_kind_keys_own
            }
            else -> R.string.learning_kind_table
        }
    }
}
