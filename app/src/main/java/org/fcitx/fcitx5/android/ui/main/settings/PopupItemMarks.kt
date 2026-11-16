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
 * The long press editor's item marks (061-text-variables §2.5: 使用变量; 061-no-cn-punct §2.3: 不转中文标点): the
 * selected item's toggles on the button row, the line over it, and beside [field] the two check boxes of what is
 * added (one or the other or none) with the help of each. [convertible]:
 * the English marks a Chinese punctuation table converts, the ones 不转中文标点 is for; [changed] redraws the editor.
 */
class PopupItemMarks(
    private val ctx: Context,
    private val editor: PopupEditor,
    private val field: EditText,
    private val convertible: Set<String>,
    private val changed: () -> Unit,
) {
    /**
     * The line over the buttons, one line always there so nothing under it moves (061-settings-cards §5.2): with
     * nothing selected how to start, with an item of variables selected what it types now, else blank.
     */
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

    /** The mark of what is added now: as the check boxes by the field are. */
    val adding: ItemMark?
        get() = when {
            addVariables.isChecked -> ItemMark.Variables
            addAsIs.isChecked -> ItemMark.AsIs
            else -> null
        }

    /** The toggles put on the button row [actions], after its buttons. */
    fun addTo(actions: FlexboxLayout) = toggles.forEach { (button, _, _) ->
        with(actions) { add(button, FlexboxLayout.LayoutParams(wrapContent, wrapContent)) }
    }

    /** What [item]'s cap says of its mark, null for none. */
    fun tag(item: String): String? = editor.marks[item]?.let { ctx.getString(tagOf(it)) }

    /** The toggles and the line over them as the selected item is. */
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

    /** Under the field, each with its top margin in dp: the check boxes, the as-is hint, the variables' help. */
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
        /** The editor's compact text button (§5.2): no border, 40 dp high, a disabled one only faded text. */
        fun textButton(ctx: Context, @StringRes title: Int) = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(title)
            insetTop = 0
            insetBottom = 0
            minHeight = ctx.dp(TEXT_BUTTON_DP)
            minimumHeight = ctx.dp(TEXT_BUTTON_DP)
            // as wide as its words: the four actions on one line of a phone, not Material's 88 dp each
            minWidth = 0
            minimumWidth = 0
            setPaddingRelative(ctx.dp(TEXT_BUTTON_PAD_DP), paddingTop, ctx.dp(TEXT_BUTTON_PAD_DP), paddingBottom)
        }

        private const val TEXT_BUTTON_DP = 40
        private const val TEXT_BUTTON_PAD_DP = 12

        // the marks an item can have, each its toggle's words
        private val MARKS = listOf(ItemMark.Variables to R.string.text_variables_use, ItemMark.AsIs to R.string.text_as_is)

        @StringRes
        private fun tagOf(mark: ItemMark): Int = when (mark) {
            ItemMark.Variables -> R.string.text_mark_variables
            ItemMark.AsIs -> R.string.text_mark_as_is
        }

        /**
         * The keys a Chinese punctuation table converts, the built-in table's and the user's (061-no-cn-punct Q1):
         * the items 不转中文标点 is for.
         */
        fun convertible(ctx: Context): Set<String> {
            val app = ctx.applicationContext
            val maps = PunctuationMaps(HostConfigDir.inUse(ConfigHome.DATA).dir, writable = false) { lang ->
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
