/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import kotlin.coroutines.CoroutineContext

/**
 * Sets which letters each of 九键's digit keys types, on its keys drawn as the keyboard has them:
 * each with its letters and its digit small, one set otherwise than on a phone outlined. Tapping
 * one asks for its letters; [NineKeyLetters] keeps them to what a key can type.
 */
class NineKeyLettersFragment : KeyCapsPageFragment() {

    private val pref get() = AppPrefs.getInstance().keyboard.t9Letters

    private val letters: NineKeyLetters get() = NineKeyLetters.parse(pref.getValue())

    /** Keeps [value] for the keyboard and has the engine read it. */
    private fun save(value: NineKeyLetters) =
        saveLetters(value, letters, { pref.setValue(it.serialize()) }, FcitxDaemon.getFirstConnectionOrNull())

    override fun rebuild() {
        val ctx = requireContext()
        val current = letters
        content.apply {
            removeAllViews()
            add(text(ctx, getString(R.string.t9_letters_hint), body = true), lParams(matchParent))
            Rows.forEachIndexed { i, row ->
                add(ctx.horizontalLayout {
                    row.forEach { add(key(ctx, it, current), lParams(0, ctx.dp(KeyHeight)) {
                        weight = 1f
                        marginStart = ctx.dp(3)
                        marginEnd = ctx.dp(3)
                    }) }
                }, lParams(matchParent) { topMargin = ctx.dp(if (i == 0) 16 else 6) })
            }
            val missing = current.missing
            if (missing.isNotEmpty()) {
                add(text(ctx, getString(R.string.t9_letters_missing, missing.uppercase().toList().joinToString(" ")), body = true).apply {
                    setTextColor(ctx.styledColor(androidx.appcompat.R.attr.colorError))
                }, lParams(matchParent) { topMargin = ctx.dp(16) })
            }
            if (!current.isStandard) {
                add(MaterialButton(ctx, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.reset_all)
                    setOnClickListener { confirmResetAll() }
                }, lParams { topMargin = ctx.dp(12) })
            }
        }
    }

    private fun key(ctx: Context, digit: Char, current: NineKeyLetters): View {
        // 1 types punctuation, not letters: drawn as on the keyboard, not set here
        if (digit == '1') return KeyCaps.key(ctx, T9Keyboard.ONE, "1", marked = false, dimmed = true).apply { isClickable = false }
        val shown = current.letters(digit)
        val edited = shown != NineKeyLetters.STANDARD.letters(digit)
        val state = getString(R.string.edited_x, digit.toString()).takeIf { edited }
        return KeyCaps.key(ctx, shown.uppercase(), digit.toString(), marked = edited, dimmed = shown.isEmpty(), state = state).apply {
            setOnClickListener { edit(digit) }
        }
    }

    private fun edit(digit: Char) {
        val ctx = requireContext()
        val current = letters
        val default = NineKeyLetters.STANDARD.letters(digit)
        val field = EditText(ctx).apply {
            setText(current.letters(digit))
            hint = getString(R.string.t9_letters_field_hint, default)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                // letters alone, as they are kept; how many is NineKeyLetters' to cut
                val kept = source.subSequence(start, end).filter { it.lowercaseChar() in 'a'..'z' }.toString().lowercase()
                if (kept == source.subSequence(start, end).toString()) null else kept
            }, InputFilter.LengthFilter(NineKeyLetters.MAX))
            isSingleLine = true
        }
        val box = ctx.verticalLayout {
            val pad = ctx.dp(24)
            setPadding(pad, ctx.dp(8), pad, 0)
            add(text(ctx, getString(R.string.t9_letters_editor_hint), body = false), lParams(matchParent))
            add(field, lParams(matchParent) { topMargin = ctx.dp(8) })
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.t9_letters_of, digit.toString()))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save(current.withEdit(digit, field.text.toString()))
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (current.letters(digit) != default) {
            builder.setNeutralButton(R.string.reset) { _, _ ->
                save(current.withEdit(digit, default))
                rebuild()
            }
        }
        builder.show()
        // after the dialog focused it, which put the cursor at the start
        field.post { field.setSelection(field.text.length) }
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_all)
            .setMessage(R.string.t9_letters_reset_all_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save(NineKeyLetters.STANDARD)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun text(ctx: Context, s: String, body: Boolean) = TextView(ctx).apply {
        text = s
        textSize = if (body) 14f else 12f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
    }

    private companion object {
        val Rows = listOf("123", "456", "789")
        const val KeyHeight = 60
    }
}

/**
 * These letters with [digit]'s [typed] as it may be kept; typed with no letter, [digit] is as on a
 * phone, as its field's hint says, not a key typing nothing.
 */
internal fun NineKeyLetters.withEdit(digit: Char, typed: String): NineKeyLetters {
    val kept = NineKeyLetters.normalize(typed).ifEmpty { NineKeyLetters.STANDARD.letters(digit) }
    return NineKeyLetters.of(('2'..'9').map { if (it == digit) kept else letters(it) })
}

/**
 * Keeps [value] in place of the letters [kept], as [keep] writes it, and has the engine of [fcitx]
 * read it once fcitx is ready, waiting on [context]; without [fcitx], fcitx is not running and
 * reads them as it starts.
 */
internal fun saveLetters(
    value: NineKeyLetters,
    kept: NineKeyLetters,
    keep: (NineKeyLetters) -> Unit,
    fcitx: FcitxConnection?,
    context: CoroutineContext = Dispatchers.Main.immediate,
) {
    // as they were: the engine's reload, which reads the user's words again too, is for nothing
    if (value == kept) return
    keep(value)
    if (fcitx == null) return
    // the engine reads them again as it reads the user's words (EngineBridge.reload): they are the
    // app's, not fcitx's config. Once ready: fcitx still starting may have read the old ones, and
    // runIfReady would drop the reload
    fcitx.lifecycleScope.launch(context) { fcitx.runOnReady { reloadEngine() } }
}
