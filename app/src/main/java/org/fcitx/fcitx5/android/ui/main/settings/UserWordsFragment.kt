/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.user.WordLists
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp
import kotlin.math.roundToInt

/**
 * The user's words, in three lists: those they added here, those the engine learned as they put
 * a reading together from pieces, and those they blocked (here, or by a long press on a
 * candidate). A word is added, edited, forgotten, blocked or shown again from here.
 */
class UserWordsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var words = emptyList<UserWord>()
    private var filter = ""

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                val tint = requireContext().styledColor(android.R.attr.colorControlNormal)
                menu.item(R.string.search, R.drawable.ic_baseline_search_24, tint, showAsAction = true) { search() }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    private fun refresh() {
        lifecycleScope.launch {
            words = viewModel.fcitx.runOnReady { userWords() }
            show()
        }
    }

    private fun show() {
        val screen = preferenceScreen
        screen.removeAll()
        if (filter.isNotEmpty()) {
            screen.addPreference(getString(R.string.search_clear, filter)) {
                filter = ""
                show()
            }
        }
        screen.addPreference(R.string.add_word, R.string.add_word_summary, R.drawable.ic_baseline_plus_24) {
            edit(R.string.add_word, null) { text, pinyin -> addUserWord(text, pinyin) }
        }
        val shown = words.filter { filter.isEmpty() || filter in it.text || filter in it.pinyin.replace(" ", "") }
        section(R.string.my_words_added, shown, UserWord.Kind.ADDED)
        section(R.string.my_words_learned, shown, UserWord.Kind.LEARNED)
        section(R.string.my_words_blocked, shown, UserWord.Kind.BLOCKED) {
            addPreference(R.string.block_word, R.string.block_word_summary) {
                edit(R.string.block_word, null) { text, pinyin -> blockUserWord(text, pinyin) }
            }
        }
    }

    private fun section(title: Int, shown: List<UserWord>, kind: UserWord.Kind, head: PreferenceCategory.() -> Unit = {}) {
        val category = PreferenceCategory(requireContext()).apply {
            setTitle(title)
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(category)
        category.head()
        val ofKind = shown.filter { it.kind == kind }
        if (ofKind.isEmpty()) {
            category.addPreference(R.string.my_words_none)
            return
        }
        for (word in ofKind) {
            val summary = if (kind == UserWord.Kind.LEARNED) {
                val times = word.count.roundToInt().coerceAtLeast(1)
                resources.getQuantityString(R.plurals.word_typed_times, times, word.pinyin, times)
            } else {
                word.pinyin
            }
            category.addPreference(word.text, summary) { act(word) }
        }
    }

    /** What can be done to [word]: edited (as an added word), taken off its list. */
    private fun act(word: UserWord) {
        val ctx = requireContext()
        val remove = when (word.kind) {
            UserWord.Kind.ADDED -> R.string.delete
            UserWord.Kind.LEARNED -> R.string.forget_word
            UserWord.Kind.BLOCKED -> R.string.unblock_word
        }
        val actions = buildList {
            if (word.kind != UserWord.Kind.BLOCKED) add(R.string.edit)
            add(remove)
        }
        AlertDialog.Builder(ctx)
            .setTitle("${word.text}  ${word.pinyin}")
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, which ->
                when (actions[which]) {
                    R.string.edit -> edit(R.string.edit, word) { text, pinyin ->
                        // added before the old is taken off: a word that does not read so leaves both be
                        addUserWord(text, pinyin).also { if (it) removeUserWord(word) }
                    }
                    else -> lifecycleScope.launch {
                        viewModel.fcitx.runOnReady { removeUserWord(word) }
                        refresh()
                    }
                }
            }
            .show()
    }

    /**
     * Asks for a word and its pinyin, prefilled with [word]'s, and hands them to [save]; kept
     * open, with an error under the pinyin, while they do not read together. Left blank, the
     * pinyin is filled in as the dictionary reads the word, for the user to check and save.
     */
    private fun edit(title: Int, word: UserWord?, save: suspend FcitxAPI.(String, String) -> Boolean) {
        val ctx = requireContext()
        val (textLayout, textField) = ctx.materialTextInput { hint = getString(R.string.word_text) }
        textField.apply {
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val (pinyinLayout, pinyinField) = ctx.materialTextInput { hint = getString(R.string.word_pinyin) }
        pinyinField.apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        word?.let {
            textField.setText(it.text)
            pinyinField.setText(it.pinyin)
        }
        val layout = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(textLayout, lParams(matchParent))
            add(pinyinLayout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick onClick@{
                val text = textField.str.trim()
                val pinyin = pinyinField.str
                if (text.isEmpty()) {
                    textField.error = getString(R.string._cannot_be_empty, getString(R.string.word_text))
                    return@onClick false
                }
                val dialog = this
                if (pinyin.isBlank()) {
                    // read as the dictionary reads it, shown for the user to check before saving
                    lifecycleScope.launch {
                        val guessed = viewModel.fcitx.runOnReady { pinyinOf(text) }
                        if (guessed == null) {
                            pinyinField.error = getString(R.string.word_pinyin_unknown)
                        } else {
                            pinyinField.setText(guessed)
                        }
                    }
                    return@onClick false
                }
                val entry = WordLists.entry(text, pinyin)
                if (entry == null) {
                    pinyinField.error = getString(R.string.word_pinyin_invalid)
                    return@onClick false
                }
                // unchanged: nothing to do
                if (word != null && WordLists.entry(word.text, word.pinyin) == entry) return@onClick true
                lifecycleScope.launch {
                    if (viewModel.fcitx.runOnReady { save(text, pinyin) }) {
                        dialog.dismiss()
                        refresh()
                    } else {
                        pinyinField.error = getString(R.string.word_pinyin_invalid)
                    }
                }
                false
            }
    }

    private fun search() {
        val ctx = requireContext()
        val (layout, field) = ctx.materialTextInput { hint = getString(R.string.search_words_hint) }
        field.isSingleLine = true
        field.setText(filter)
        val padded = ctx.verticalLayout {
            setPaddingDp(20, 10, 20, 0)
            add(layout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.search)
            .setView(padded)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                filter = field.str.trim().replace(" ", "")
                show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
