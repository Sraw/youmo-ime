/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.popup.PopupEditor
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent
import java.io.IOException
import java.util.Calendar
import com.google.android.material.R as MaterialR

/**
 * The long press editor's item marks (使用变量, 不转中文标点): toggles on the button row, the line over it, and the
 * check boxes by [field]. [convertible]: the marks a Chinese punctuation table converts; [changed] redraws the editor.
 */
class PopupItemMarks(
    private val ctx: Context,
    private val editor: PopupEditor,
    private val field: EditText,
    private val convertible: Set<String>,
    private val changed: () -> Unit,
) {
    /** Always one line so nothing under it moves: a hint with nothing selected, what a variable item types now, else blank. */
    val line: TextView = small().apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private val toggles = MARKS.map { (mark, title) ->
        val button = textButton(ctx, title).apply {
            isCheckable = true
            // a text button has no border: a toggle on is filled, so on and off tell apart
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ctx.styledColor(MaterialR.attr.colorSecondaryContainer), Color.TRANSPARENT),
            )
            setOnClickListener {
                editor.toggle(mark)
                changed()
            }
        }
        Triple(button, mark, title)
    }

    private val help = TextVariableHelp(ctx, field, PopupEditor::template)
    private val asIsHint = small().apply {
        setText(R.string.text_as_is_hint)
        isVisible = false
    }
    private val addVariables = CheckBox(ctx).apply { setText(R.string.text_variables_use) }
    private val addAsIs = CheckBox(ctx).apply { setText(R.string.text_as_is) }

    init {
        addVariables.setOnCheckedChangeListener { _, on ->
            if (on) addAsIs.isChecked = false
            help.on = on
            field.hint = ctx.getString(if (on) R.string.text_variables_item_hint else R.string.long_press_add_hint)
        }
        addAsIs.setOnCheckedChangeListener { _, on ->
            if (on) addVariables.isChecked = false
            asIsHint.isVisible = on
        }
    }

    /** As the check boxes by the field are. */
    val adding: ItemMark?
        get() = when {
            addVariables.isChecked -> ItemMark.Variables
            addAsIs.isChecked -> ItemMark.AsIs
            else -> null
        }

    fun addTo(actions: FlexboxLayout) = toggles.forEach { (button, _, _) ->
        with(actions) { add(button, FlexboxLayout.LayoutParams(wrapContent, wrapContent)) }
    }

    /** Null for none. */
    fun tag(item: String): String? = editor.marks[item]?.let { ctx.getString(tagOf(it)) }

    fun render() {
        toggles.forEach { (button, mark, title) ->
            val on = editor.selectedMark == mark
            button.isChecked = on
            button.isEnabled = editor.canMark(mark, convertible)
            button.contentDescription = ctx.getString(title)
            ViewCompat.setStateDescription(button, ctx.getString(if (on) R.string.text_mark_on else R.string.text_mark_off))
        }
        val template = editor.items.getOrNull(editor.selected)?.takeIf { editor.selectedMark == ItemMark.Variables }
        line.text = when {
            template != null -> TextVariableHelp.typedNow(ctx, template, Calendar.getInstance())
            editor.selected !in editor.items.indices -> ctx.getString(R.string.long_press_card_line)
            else -> ""
        }
    }

    /** Each with its top margin in dp: check boxes, the as-is hint, the variables' help. */
    fun underField(): List<Pair<View, Int>> {
        val row = FlexboxLayout(ctx).apply {
            flexWrap = FlexWrap.WRAP
            add(addVariables, FlexboxLayout.LayoutParams(wrapContent, wrapContent).apply { marginEnd = ctx.dp(16) })
            add(addAsIs, FlexboxLayout.LayoutParams(wrapContent, wrapContent))
        }
        return listOf(row to 0, asIsHint to 0, help.view to 0)
    }

    private fun small() = TextView(ctx).apply {
        textSize = 12f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
    }

    companion object {
        /** Compact: no border, 40 dp high, disabled only fades the text. */
        fun textButton(ctx: Context, @StringRes title: Int) = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(title)
            insetTop = 0
            insetBottom = 0
            minHeight = ctx.dp(TEXT_BUTTON_DP)
            minimumHeight = ctx.dp(TEXT_BUTTON_DP)
            // as wide as its words: four actions on one phone line, not Material's 88 dp each
            minWidth = 0
            minimumWidth = 0
            setPaddingRelative(ctx.dp(TEXT_BUTTON_PAD_DP), paddingTop, ctx.dp(TEXT_BUTTON_PAD_DP), paddingBottom)
        }

        private const val TEXT_BUTTON_DP = 40
        private const val TEXT_BUTTON_PAD_DP = 12

        private val MARKS = listOf(ItemMark.Variables to R.string.text_variables_use, ItemMark.AsIs to R.string.text_as_is)

        @StringRes
        private fun tagOf(mark: ItemMark): Int = when (mark) {
            ItemMark.Variables -> R.string.text_mark_variables
            ItemMark.AsIs -> R.string.text_mark_as_is
        }

        /** The built-in table's and the user's: the items 不转中文标点 is for. */
        fun convertible(ctx: Context): Set<String> {
            val app = ctx.applicationContext
            val maps = PunctuationMaps(HostConfigDir.inUse(ConfigHome.DATA).dir) { lang ->
                try {
                    app.assets.open("pkgdata/punctuation/punc.mb.$lang")
                } catch (_: IOException) {
                    null
                }
            }
            // the one language with a punctuation table
            val lang = KotlinHost.ZH_CN
            return (maps.builtIn(lang) + maps.load(lang)).mapTo(HashSet()) { it.key }
        }
    }
}
