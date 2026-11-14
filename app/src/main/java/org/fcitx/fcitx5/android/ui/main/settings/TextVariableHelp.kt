/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import java.util.Calendar

/**
 * Under a field whose text fills in variables while [on] (061-text-variables §2.5): 插入变量 (each name, what it
 * is and its value now; picked, `${name}` at the cursor), what the text types now, and what of it is not filled in.
 * [shape] makes the field's text what is stored (an item of a long press: one line, trimmed, 64 at most).
 */
class TextVariableHelp(
    private val ctx: Context,
    private val field: EditText,
    private val shape: (String) -> String = { it },
    private val now: () -> Calendar = Calendar::getInstance,
) {
    private val insert = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(R.string.text_variables_insert)
        setOnClickListener { choose() }
    }

    private val preview = small()

    private val problems = small().apply { setTextColor(ctx.styledColor(android.R.attr.colorError)) }

    val view: LinearLayout = ctx.verticalLayout {
        add(insert, lParams())
        add(preview, lParams(matchParent))
        add(problems, lParams(matchParent))
        isVisible = false
    }

    /** Whether the text fills in variables: the help shown. */
    var on: Boolean = false
        set(value) {
            field = value
            view.isVisible = value
            update()
        }

    /** What the preview says now: a test's. */
    val previewed: String get() = preview.text.toString()

    /** What the problems line says now, empty for none: a test's. */
    val told: String get() = problems.text.toString().takeIf { problems.isVisible }.orEmpty()

    init {
        field.doAfterTextChanged { update() }
    }

    private fun small() = TextView(ctx).apply {
        textSize = 13f
        setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
    }

    private fun update() {
        if (!on) return
        val template = shape(field.text.toString())
        preview.text = typedNow(ctx, template, now())
        val said = problemsOf(ctx, template)
        problems.text = said
        problems.isVisible = said.isNotEmpty()
    }

    private fun choose() {
        val at = now()
        val words = ctx.resources.getStringArray(R.array.text_variable_descriptions)
        val names = TextVariables.NAMES
        val rows = names.mapIndexed { i, name -> "\${$name}  ${words.getOrElse(i) { "" }}  ${TextVariables.expand("\${$name}", at)}" }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.text_variables_insert)
            .setItems(rows.toTypedArray()) { _, i ->
                val text = field.text
                val start = minOf(field.selectionStart, field.selectionEnd).takeIf { it >= 0 } ?: text.length
                val end = maxOf(field.selectionStart, field.selectionEnd).takeIf { it >= 0 } ?: text.length
                text.replace(start, end, "\${${names[i]}}")
                field.requestFocus()
            }
            .show()
    }

    companion object {
        /** 「现在打出：…」 of [template] at [now]: what it types, or that it types nothing. */
        fun typedNow(ctx: Context, template: String, now: Calendar): String =
            TextVariables.expand(template, now).let { if (it.isEmpty()) ctx.getString(R.string.text_variables_nothing) else ctx.getString(R.string.text_variables_now, it) }

        /** What of [template] is not filled in, a line each ([TextVariables.problems]); empty for none. */
        fun problemsOf(ctx: Context, template: String): String = TextVariables.problems(template).joinToString("\n") {
            ctx.getString(if (it.dropped) R.string.text_variables_dropped else R.string.text_variables_kept, it.written)
        }
    }
}
