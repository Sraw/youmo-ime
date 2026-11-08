/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.input.scene.SceneLetters
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Text as SceneText
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.keyboard.markSpots
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * Keys drawn as keys in the settings that edit what a key does (long press, Chinese punctuation),
 * so a page reads as the keyboard it changes rather than as a list of strings.
 */
object KeyCaps {

    // as the keyboard's marks (KeyView): an icon's side, and a text's share of the key's width
    private const val MARK_ICON_DP = 11
    private const val MARK_TEXT_WIDTH = 0.45f

    // the letters' rows (qwerty without its number row): a key's height the keys' over these
    private const val KEY_ROWS = 4

    // a dimmed cap's label, as the views' (0.4)
    private const val DIMMED = 0.4

    /** A key's face: [marked] outlined in the accent colour, [selected] filled with it */
    fun background(ctx: Context, marked: Boolean = false, radius: Int = 8, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = ctx.dp(radius).toFloat()
        setColor(ctx.styledColor(if (selected) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHighest))
        if (marked) setStroke(ctx.dp(2), ctx.styledColor(android.R.attr.colorPrimary))
    }

    /**
     * A key's shortcut, for the engine to draw its marks: a character key's [name] (`full/a`) or a function
     * key's (`space`, its up and down), as [shortcuts] (the page's, being edited) has them; [layout] the board it is
     * drawn on, its form's ([board]) where null.
     */
    class Shortcut(val name: String, val shortcuts: KeyShortcuts, val charShortcuts: Boolean = true, val layout: String? = null)

    // the edited key outlined along the engine's face, in the keyboard theme's accent (as light or dark as the
    // face it is on, whatever the settings' own theme); dimmed (its long press off), its words faded alone, the
    // face and marks as they are, as the key views fade theirs (060-render-p2 S6 review)
    private fun sceneKey(ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean, shortcut: Shortcut?) =
        SceneKeyCell(ctx, ThemeManager.activeTheme).apply {
            if (marked) outline = Argb(ThemeManager.activeTheme.accentKeyBackgroundColor)
            labelAlpha = if (dimmed) DIMMED else 1.0
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val function = shortcut != null && '/' !in shortcut.name
            attrs = KeyAttrs(
                label = SceneText.Literal(text),
                alt = hint.takeIf { it.isNotEmpty() }?.let(SceneText::Literal),
                kind = if (function) "function" else "letter",
                shortcut = shortcut?.name,
            )
            states = setOfNotNull(KeyState.Selected.takeIf { selected })
            // the label the keyboard's, shrunk as the cap is to its key, its marks where they are there (060-pending-decisions);
            // a shortcut's key the keyboard's very key, made on its board at the keys' size, every key of the board
            // with its marks, as a mark class is sized over them all (060-render-p2 S7a)
            keyboardKeyHeight = keyboardKeyHeight(ctx)
            board = shortcut?.let { if (it.layout != null) board(ctx, it.name, it.layout) else board(ctx, it.name) }
            val names = board?.let { b -> SceneLayouts.of(ctx, b.layout)?.let(SceneBoard::names) } ?: listOfNotNull(shortcut?.name)
            // the symbol where 标点位置 puts it on the keyboard, a key with no shortcut too
            marks = SceneMarks.settings(
                shortcut?.shortcuts ?: KeyShortcuts.Empty, AppPrefs.getInstance().keyboard.swipeLayers.getValue().layers, markSpots(),
                shortcut?.charShortcuts ?: true, names,
            )
        }

    /** A qwerty key's height on the keyboard as the height settings make it now, px (InputView's keys' area, [KeyboardArea]). */
    fun keyboardKeyHeight(ctx: Context): Int =
        KeyboardArea.keysHeight(ctx, CodeRowHeight.keyboardDp(ImKeyboards.Layout.TEXT, KawaiiBarComponent.codeRowFloats())) / KEY_ROWS

    /**
     * The keyboard the key of [shortcut] (a shortcut page's name: `full/a`, `zyfull/1`, a function key's `space`) is on,
     * at its keys' size as InputView makes it (KeyboardArea: the height settings, the bar, the board's code row, the
     * paddings, the cap): [layout] the board, a function key's qwerty's unless given.
     */
    fun board(ctx: Context, shortcut: String, layout: String = BOARDS[shortcut.substringBefore('/', "")]?.first ?: KeyNames.QWERTY): SceneKeyCell.Board {
        val codeRow = CODE_ROWS[layout] ?: ImKeyboards.Layout.TEXT
        val code = CodeRowHeight.keyboardDp(codeRow, KawaiiBarComponent.codeRowFloats())
        // the letters' corners as the keyboard draws them (TextKeyboard.alts: the long presses' firsts, the user's
        // overrides; none with 标点位置 无): without them no symbol is drawn, nor does one take a mark's room
        val alts = if (markSpots().symbol == KeyMarks.At.None) emptyMap() else
            SceneLetters.alts { c -> c.toString().let { PopupOverridesCache.user.current.resolve(it, PopupPreset[it])?.firstOrNull() } }
        return SceneKeyCell.Board(layout, shortcut, KeyboardArea.keysWidth(ctx), KeyboardArea.keysHeight(ctx, code), alts)
    }

    // a shortcut page's form: its board and the code row that board has
    private val BOARDS = mapOf(
        KeyShortcutPreset.FULL to (KeyNames.QWERTY to ImKeyboards.Layout.TEXT),
        KeyShortcutPreset.NINE to (KeyNames.NINE to ImKeyboards.Layout.PINYIN_NINE),
        KeyShortcutPreset.GROUPED14 to (KeyNames.K14 to ImKeyboards.Layout.PINYIN14),
        KeyShortcutPreset.GROUPED18 to (KeyNames.K18 to ImKeyboards.Layout.PINYIN18),
        KeyShortcutPreset.ZHUYIN_NINE to (KeyNames.ZHUYIN_NINE to ImKeyboards.Layout.ZHUYIN_NINE),
        KeyShortcutPreset.ZHUYIN_FULL to (KeyNames.ZHUYIN to ImKeyboards.Layout.ZHUYIN_FULL),
        KeyShortcutPreset.NUMBER to (KeyNames.NUMBER to ImKeyboards.Layout.TEXT),
    )

    // each board's code row (handwriting's too, a function key's board of its own)
    private val CODE_ROWS = BOARDS.values.toMap() + (KeyNames.HANDWRITING to ImKeyboards.Layout.HANDWRITING)

    fun label(ctx: Context, text: String, size: Float = 18f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
    }

    /** What a key draws beside its label, as the keyboard draws its swipes' marks ([KeyMarks]). */
    sealed interface Mark {
        /** A character: the corner symbol, or a text shortcut's whole, shrunk sideways to fit as on the key. */
        data class Text(val text: String) : Mark

        /** A command's icon. */
        data class Icon(@DrawableRes val res: Int) : Mark

        /** The key's symbol where the page labels the key by it (九键's digits): not drawn, its corner kept from the shortcut. */
        data object Unshown : Mark
    }

    /**
     * A key with [text] on it and [hint] small where the keyboard puts its symbol, the first
     * long-press character (按键's 标点位置, kept from 快捷键图标位置's place, as on the keyboard's
     * height and orientation: [KeyMarks.symbolSlot], as [KeyView][org.fcitx.fcitx5.android.input.keyboard.KeyView]
     * places it); [dimmed] for a key whose long press is turned off. [state] is what a screen
     * reader says of what the outline and the fading show, which only the caller knows.
     * [marks], placed as the keyboard places them, in the hint's stead; [said], what a screen reader
     * says of the shortcut among them.
     */
    fun key(
        ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean = false,
        state: String? = null,
        marks: KeyMarks.Placed<Mark>? = null,
        said: String? = null,
        shortcut: Shortcut? = null,
    ) = FrameLayout(ctx).apply {
        isClickable = true
        isFocusable = true
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = listOfNotNull(text, hint, said).filter { it.isNotEmpty() }.joinToString(", ")
        ViewCompat.setStateDescription(this, state)
        isSelected = selected
        if (SceneKeyCell.on) {
            // drawn by the engine as the keyboard draws the key (060-render-p2 S6): its face in the keyboard's
            // theme, its symbol and shortcut's marks placed by the engine; edited, outlined along its face
            foreground = ripple
            add(sceneKey(ctx, text, hint, marked, dimmed, selected, shortcut), lParams(matchParent, matchParent))
            return@apply
        }
        background = background(ctx, marked, selected = selected)
        foreground = ripple
        val label = label(ctx, text, 20f).apply { alpha = if (dimmed) 0.4f else 1f }
        if (marks != null) return@apply addMarked(ctx, label, marks)
        val small = TextView(ctx).apply {
            this.text = hint
            textSize = 10f
            maxLines = 1
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        when (val slot = hintSlot(ctx)) {
            KeyMarks.Slot.TopRight, KeyMarks.Slot.BottomRight, KeyMarks.Slot.TopLeft, KeyMarks.Slot.BottomLeft -> {
                add(label, lParams(matchParent, matchParent))
                val top = !slot.bottom
                val side = if (slot.left) Gravity.START else Gravity.END
                add(small, lParams(wrapContent, wrapContent, (if (top) Gravity.TOP else Gravity.BOTTOM) or side) {
                    val (start, end) = if (slot.left) ctx.dp(4) to 0 else 0 to ctx.dp(4)
                    setMargins(start, if (top) ctx.dp(2) else 0, end, if (top) 0 else ctx.dp(2))
                })
            }
            KeyMarks.Slot.Below -> add(ctx.verticalLayout {
                gravity = Gravity.CENTER_HORIZONTAL
                add(label, lParams(wrapContent, wrapContent))
                if (hint.isNotEmpty()) add(small, lParams(wrapContent, wrapContent))
            }, lParams(wrapContent, wrapContent, Gravity.CENTER))
            // no hint drawn there (a slot over the key's middle, or none): the label alone
            KeyMarks.Slot.Top, KeyMarks.Slot.Bottom, null -> add(label, lParams(matchParent, matchParent))
        }
    }

    // [label] with [marks]: under it, and in the slots over the key, sized as the keyboard's
    private fun FrameLayout.addMarked(ctx: Context, label: TextView, marks: KeyMarks.Placed<Mark>) {
        val below = marks[KeyMarks.Slot.Below]?.let { markView(ctx, it, KeyMarks.Slot.Below) }
        if (below == null) {
            add(label, lParams(matchParent, matchParent))
        } else {
            add(ctx.verticalLayout {
                gravity = Gravity.CENTER_HORIZONTAL
                add(label, lParams(wrapContent, wrapContent))
                add(below, lParams(wrapContent, wrapContent))
            }, lParams(wrapContent, wrapContent, Gravity.CENTER))
        }
        val corners = ConstraintLayout(ctx)
        marks.at.forEach { (slot, mark) ->
            if (slot == KeyMarks.Slot.Below) return@forEach
            val v = markView(ctx, mark, slot) ?: return@forEach
            val text = mark is Mark.Text
            val icon = ctx.dp(MARK_ICON_DP)
            corners.addView(v, ConstraintLayout.LayoutParams(if (text) 0 else icon, if (text) wrapContent else icon).apply {
                if (text) matchConstraintPercentWidth = MARK_TEXT_WIDTH
                if (!slot.right) {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    if (slot.left) marginStart = ctx.dp(4)
                }
                if (!slot.left) {
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    if (slot.right) marginEnd = ctx.dp(4)
                }
                if (slot.bottom) {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomMargin = ctx.dp(2)
                } else {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin = ctx.dp(2)
                }
            })
        }
        add(corners, lParams(matchParent, matchParent))
    }

    // a text from its slot's edge in: the right one's from the right
    private fun markView(ctx: Context, mark: Mark, slot: KeyMarks.Slot): View? {
        val color = ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant)
        return when (mark) {
            is Mark.Text -> AutoScaleTextView(ctx).apply {
                text = mark.text
                textSize = 10f
                setSingleLine()
                gravity = if (slot.left) Gravity.START else if (slot.right) Gravity.END else Gravity.CENTER_HORIZONTAL
                scaleMode = AutoScaleTextView.Mode.Horizontal
                setTextColor(color)
            }
            is Mark.Icon -> ImageView(ctx).apply {
                setImageResource(mark.res)
                imageTintList = ColorStateList.valueOf(color)
            }
            Mark.Unshown -> null
        }?.apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    // where the keyboard puts a key's symbol (KeyMarks.symbolSlot): where 标点位置 says, under the letter
    // only in portrait and not 紧凑 (the key too short for two lines else)
    private fun hintSlot(ctx: Context): KeyMarks.Slot? {
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val spots = markSpots()
        return KeyMarks.symbolSlot(spots.symbol, spots.shortcut, landscape, keyboardCompact())
    }
}
