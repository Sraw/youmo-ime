/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.SwipeSlotsCache
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.firstShown
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.input.keyboard.swipeWays
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.scene.SceneLetters
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.scene.layout.Text as SceneText

/**
 * Keys drawn as keys in the settings that edit what a key does (long press, Chinese punctuation),
 * so a page reads as the keyboard it changes rather than as a list of strings.
 */
object KeyCaps {

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
     * A key's swipes, for the engine to draw its marks: a character key's [name] (`full/a`) or a function
     * key's (`space`, its up and down), as [slots] (the page's, being edited) has them; [layout] the board it is
     * drawn on, its form's ([board]) where null.
     */
    class Shortcut(val name: String, val slots: SwipeSlots, val charShortcuts: Boolean = true, val layout: String? = null)

    // the edited key outlined along the engine's face, in the keyboard theme's accent (as light or dark as the
    // face it is on, whatever the settings' own theme); dimmed (its long press off), its words faded alone, the
    // face and marks as they are, as the key views fade theirs (060-render-p2 S6 review)
    private fun sceneKey(ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean, shortcut: Shortcut?) =
        SceneKeyCell(ctx, ThemeManager.activeTheme).apply {
            // the item a move or delete acts on (selected) outlined in the settings' own colour: the keyboard's
            // styles draw no selected key of their own outside a popup, and it read as any other item; over the
            // marked outline where it is both (the first item), the one acted on being what the user looks for
            outline = when {
                selected -> Argb(ctx.styledColor(android.R.attr.colorPrimary))
                marked -> Argb(ThemeManager.activeTheme.accentKeyBackgroundColor)
                else -> null
            }
            labelAlpha = if (dimmed) DIMMED else 1.0
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // the key's one name, as the layouts' (a function key's `fn/comma`)
            val id = shortcut?.name?.let(KeyId::of)
            attrs = KeyAttrs(
                label = SceneText.Literal(text),
                alt = hint.takeIf { it.isNotEmpty() }?.let(SceneText::Literal),
                kind = if (id?.isFunction == true) "function" else "letter",
                shortcut = id?.name,
            )
            states = setOfNotNull(KeyState.Selected.takeIf { selected })
            // the label the keyboard's, shrunk as the cap is to its key, its marks where they are there (060-pending-decisions);
            // a shortcut's key the keyboard's very key, made on its board at the keys' size, every key of the board
            // with its marks, as a mark class is sized over them all (060-render-p2 S7a)
            keyboardKeyHeight = keyboardKeyHeight(ctx)
            board = shortcut?.let { if (it.layout != null) board(ctx, it.name, it.layout) else board(ctx, it.name) }
            val names = board?.let { b -> SceneLayouts.of(ctx, b.layout)?.let(SceneBoard::names) } ?: listOfNotNull(id?.name)
            // each slot where its way's 图标位置 puts it on the keyboard, a key with no shortcut too
            val spots = swipeSpots()
            marks = SceneMarks.settings(
                shortcut?.slots ?: SwipeSlotsCache.current, swipeWays(), spots.symbol, spots.shortcut,
                shortcut?.charShortcuts ?: true, names, SceneBoard.firsts(PopupOverridesCache.byName.current, names),
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
    fun board(ctx: Context, shortcut: String, layout: String = defaultBoard(shortcut)): SceneKeyCell.Board {
        val codeRow = CODE_ROWS[layout] ?: ImKeyboards.Layout.TEXT
        val code = CodeRowHeight.keyboardDp(codeRow, KawaiiBarComponent.codeRowFloats())
        // the letters' corners as the keyboard draws them (TextKeyboard.alts: the long presses' firsts, the user's
        // overrides; none where the first's way draws nothing): without them no symbol is drawn, nor does one take a mark's room
        val alts = if (!firstShown()) emptyMap() else
            SceneLetters.alts { c -> c.toString().let { PopupOverridesCache.user.current.resolve(it, PopupPreset[it])?.firstOrNull() } }
        return SceneKeyCell.Board(layout, KeyId.of(shortcut).name, KeyboardArea.keysWidth(ctx), KeyboardArea.keysHeight(ctx, code), alts,
            AppPrefs.getInstance().keyboard.letterCase.getValue().case)
    }

    private fun defaultBoard(shortcut: String) =
        BOARDS[shortcut.substringBefore('/', "")]?.first ?: FUNCTION_BOARDS[shortcut] ?: KeyNames.QWERTY

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

    // the function keys qwerty has not: the board each is on (九键's 重输 and 0, 14/18 键's 分词)
    private val FUNCTION_BOARDS = mapOf("retype" to KeyNames.NINE, "zero" to KeyNames.NINE, "separator" to KeyNames.K14)

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

        /** The key's symbol where the page labels the key by it (九键's digits): not drawn, its corner kept from the shortcut. */
        data object Unshown : Mark
    }

    /**
     * A key with [text] on it and [hint], the first long-press character, where the keyboard puts its
     * symbol, and [shortcut]'s marks, the engine placing both as on the keyboard (按键's 标点位置 and
     * 快捷键图标位置); [dimmed] for a key whose long press is turned off. [state] is what a screen
     * reader says of what the outline and the fading show, which only the caller knows; [said], what
     * a screen reader says of the shortcut. With a [shortcut] the cap is the keyboard's very key, drawn as
     * the board has it: [text] and [hint] are then what a screen reader says alone.
     */
    fun key(
        ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean = false,
        state: String? = null,
        said: String? = null,
        shortcut: Shortcut? = null,
    ): FrameLayout = KeyFrame(ctx).apply {
        isClickable = true
        isFocusable = true
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = listOfNotNull(text, hint, said).filter { it.isNotEmpty() }.joinToString(", ")
        ViewCompat.setStateDescription(this, state)
        isSelected = selected
        // drawn by the engine as the keyboard draws the key (060-render-p2 S6): its face in the keyboard's
        // theme, its symbol and shortcut's marks placed by the engine; edited, outlined along its face
        foreground = ripple
        add(sceneKey(ctx, text, hint, marked, dimmed, selected, shortcut), lParams(matchParent, matchParent))
    }

    // the engine's cell is a gesture view and takes every touch it gets, so a tap never reached this frame's
    // click on a device (Robolectric's performClick went straight to the frame): the frame keeps them all
    private class KeyFrame(ctx: Context) : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
    }
}
