/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyClock
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.SwipeSlotsCache
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.firstShown
import org.fcitx.fcitx5.android.input.keyboard.swipeSpots
import org.fcitx.fcitx5.android.input.keyboard.swipeWays
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.input.keys.KeyState as KeysState
import org.fcitx.fcitx5.android.scene.layout.Text as SceneText

/** Settings that edit a key draw it as a key, so the page reads as the keyboard it changes. */
object KeyCaps {

    private const val DIMMED = 0.4

    fun background(ctx: Context, marked: Boolean = false, radius: Int = 8, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = ctx.dp(radius).toFloat()
        setColor(ctx.styledColor(if (selected) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHighest))
        if (marked) setStroke(ctx.dp(2), ctx.styledColor(android.R.attr.colorPrimary))
    }

    /** [name]: `full/a`, or a function key's (`space`); [layout] null means its form's ([board]). */
    class Shortcut(val name: String, val slots: SwipeSlots, val charShortcuts: Boolean = true, val layout: String? = null)

    // outlined in the keyboard theme's accent, whatever the settings theme; dimmed fades only its words, as the key views do
    private fun sceneKey(ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean, shortcut: Shortcut?) =
        SceneKeyCell(ctx, ThemeManager.activeTheme).apply {
            // the item acted on is outlined in the settings' own colour: keyboard styles draw no selected key outside a popup
            outline = when {
                selected -> Argb(ctx.styledColor(android.R.attr.colorPrimary))
                marked -> Argb(ThemeManager.activeTheme.accentKeyBackgroundColor)
                else -> null
            }
            labelAlpha = if (dimmed) DIMMED else 1.0
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val id = shortcut?.name?.let(KeyId::of)
            // a non-key cap with a hint (九键's letters' digits): the hint drawn as a key's first mark
            val cap = HINT_CAP.takeIf { id == null && hint.isNotEmpty() }
            attrs = KeyAttrs(
                label = SceneText.Literal(text),
                alt = hint.takeIf { it.isNotEmpty() }?.let(SceneText::Literal),
                kind = if (id?.isFunction == true) "function" else "letter",
                shortcut = id?.name ?: cap,
            )
            states = setOfNotNull(KeyState.Selected.takeIf { selected })
            // made on its board at key size with every key's marks, as a mark class is sized over them all
            onBoard(ctx, shortcut, shortcut?.let { if (it.layout != null) board(ctx, it.name, it.layout) else board(ctx, it.name) })
            if (cap != null) marks = marks.copy(shortcuts = marks.shortcuts + ("$cap:${if (marks.firstUp) "up" else "down"}" to MarkContent.Symbol(hint)))
        }

    // no key's name (KeyId's forms), drawn as a character key's
    private const val HINT_CAP = "cap/hint"

    /** Also what the page tells of a way drawn otherwise (SwipeFates). */
    internal fun SceneKeyCell.onBoard(ctx: Context, shortcut: Shortcut?, board: SceneKeyCell.Board?) {
        keyboardKeyHeight = keyboardKeyHeight(ctx, board?.layout)
        this.board = board
        val layout = board?.let { SceneLayouts.board(ctx, it.layout) }
        val slots = shortcut?.slots ?: SwipeSlotsCache.current
        val charShortcuts = shortcut?.charShortcuts ?: true
        marks = if (layout != null) {
            SceneBoard.resolver(layout, NO_IM, nineLetters(), slots).marks(keysState()).let { if (it.charShortcuts == charShortcuts) it else it.copy(charShortcuts = charShortcuts) }
        } else {
            val spots = swipeSpots()
            // the keyboard's clock (KeyClock), not the system's: a variable shows as the keyboard would
            SceneMarks.settings(
                slots, swipeWays(), spots.symbol, spots.shortcut, charShortcuts, listOfNotNull(shortcut?.name?.let { KeyId.of(it).name }), emptyMap(),
                now = KeyClock::calendar,
            )
        }
    }

    private fun keysState() = KeysState(ways = swipeWays(), spots = swipeSpots())

    /** With the user's 按键字母 and 18 键's L with its `:` (the item every other keyboard skips). */
    fun resolver(ctx: Context, layout: String): KeyResolver? = SceneLayouts.board(ctx, layout)?.let {
        SceneBoard.resolver(it, KeyboardVariant(DefaultMarks.variantOf(it.layout, it.fallback).form, nineLetters(), semicolon = true))
    }

    fun nineLetters(): Map<Char, String> = KeyListsCache.nineLettersOf(AppPrefs.getInstance().keyboard.t9Letters.getValue())

    /** In px as InputView's keys' area makes it, over [layout]'s rows. */
    fun keyboardKeyHeight(ctx: Context, layout: String? = null): Int {
        val rows = layout?.let { rowsOf(ctx, it) } ?: KeyboardHeightCap.DEFAULT_ROWS
        return (SwipeFates.size(upright(ctx), layout ?: KeyNames.QWERTY, ImKeyboards.Layout.TEXT, rows).height / rows).toInt()
    }

    // built-in boards' row counts do not depend on size; once a turn, not per cap
    private fun rowsOf(ctx: Context, layout: String): Double = thisTurn(listOf(ROWS, layout)) {
        SceneLayouts.board(ctx, layout)?.let {
            KeyboardHeightCap.rowUnits(it.layout, it.fragments, Env(1.0, 1.0, ctx.resources.displayMetrics.density.toDouble()))
        } ?: KeyboardHeightCap.DEFAULT_ROWS
    }

    // upright whatever way the page is turned, as the page's notes have it
    private fun upright(ctx: Context) = thisTurn(UPRIGHT to Configuration(ctx.resources.configuration)) { KeyboardArea.screens(ctx).first }

    const val NO_IM = ""

    private const val UPRIGHT = "upright"

    private const val ROWS = "rows"

    // once per main-looper turn for all caps (the upright screen is a binder call); dropped before any setting can change
    private var turn: HashMap<Any, Any?>? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> thisTurn(key: Any, make: () -> T): T {
        if (Looper.myLooper() != Looper.getMainLooper()) return make()
        val cache = turn ?: HashMap<Any, Any?>().also { turn = it; mainHandler.post { turn = null } }
        return if (key in cache) cache[key] as T else make().also { cache[key] = it }
    }

    /** At key size as InputView makes it upright; a user layout at its base's size. */
    fun board(ctx: Context, shortcut: String, layout: String = defaultBoard(shortcut)): SceneKeyCell.Board {
        val screen = upright(ctx)
        val base = SceneLayouts.board(ctx, layout)?.fallback ?: layout
        val size = SwipeFates.size(screen, base, CODE_ROWS[base] ?: ImKeyboards.Layout.TEXT, rowsOf(ctx, layout))
        // corners as BaseKeyboard.keyReadings says them; marks are the resolver's
        val alts = if (firstShown()) {
            // keyed by all they depend on: a setting changed within the turn is no stale reading
            val state = keysState()
            thisTurn(listOf(layout, state, KeyListsCache.user.current, SwipeSlotsCache.current, nineLetters())) {
                resolver(ctx, layout)?.readings(state).orEmpty()
            }
        } else {
            emptyMap()
        }
        return SceneKeyCell.Board(layout, KeyId.of(shortcut).name, size.width, size.height, alts,
            AppPrefs.getInstance().keyboard.letterCase.getValue().case, size.floating, size.split, screen.landscape, size.resize)
    }

    /** Qwerty's where qwerty has it (九键's 重输 and 0 are the nine keys', 分词 the 14 keys'). */
    fun defaultBoard(shortcut: String): String = KeyId.of(shortcut).let { id ->
        if (id.isFunction) FUNCTION_BOARDS[id.key] ?: KeyNames.QWERTY else BOARDS[id.form]?.first ?: KeyNames.QWERTY
    }

    /** Cells weighted by these make every aspect-fitted cap the keyboard's height (注音九键 mixes 14 and 24). */
    fun widths(ctx: Context, layout: String, shortcut: String): Map<String, Double> =
        SceneKeyCell(ctx, ThemeManager.activeTheme).run {
            this.board = board(ctx, shortcut, layout)
            // any size will do: the board's own size lays the keys out
            layout(0, 0, 1, 1)
            // the first of a name, as a cap draws: 注音九键 has ， twice
            scene?.keys.orEmpty().filter { it.copy == 0 && it.shortcut != null }.distinctBy { it.shortcut }.associate { it.shortcut!! to it.bounds.width }
        }

    private val BOARDS = mapOf(
        KeyShortcutPreset.FULL to (KeyNames.QWERTY to ImKeyboards.Layout.TEXT),
        KeyShortcutPreset.NINE to (KeyNames.NINE to ImKeyboards.Layout.PINYIN_NINE),
        KeyShortcutPreset.GROUPED14 to (KeyNames.K14 to ImKeyboards.Layout.PINYIN14),
        KeyShortcutPreset.GROUPED18 to (KeyNames.K18 to ImKeyboards.Layout.PINYIN18),
        KeyShortcutPreset.ZHUYIN_NINE to (KeyNames.ZHUYIN_NINE to ImKeyboards.Layout.ZHUYIN_NINE),
        KeyShortcutPreset.ZHUYIN_FULL to (KeyNames.ZHUYIN to ImKeyboards.Layout.ZHUYIN_FULL),
        KeyShortcutPreset.NUMBER to (KeyNames.NUMBER to ImKeyboards.Layout.TEXT),
    )

    private val FUNCTION_BOARDS = mapOf("retype" to KeyNames.NINE, "zero" to KeyNames.NINE, "separator" to KeyNames.K14)

    private val CODE_ROWS = BOARDS.values.toMap()

    fun label(ctx: Context, text: String, size: Float = 18f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
    }

    /** With a [shortcut] the cap is the keyboard's very key, and [text] and [hint] are only spoken. */
    fun key(
        ctx: Context, text: String, hint: String, marked: Boolean, dimmed: Boolean, selected: Boolean = false,
        state: String? = null,
        said: String? = null,
        shortcut: Shortcut? = null,
        tag: String? = null,
    ): FrameLayout = KeyFrame(ctx).apply {
        isClickable = true
        isFocusable = true
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
            getDrawable(0).also { recycle() }
        }
        contentDescription = listOfNotNull(text, hint, said, tag).filter { it.isNotEmpty() }.joinToString(", ")
        ViewCompat.setStateDescription(this, state)
        isSelected = selected
        foreground = ripple
        add(sceneKey(ctx, text, hint, marked, dimmed, selected, shortcut), lParams(matchParent, matchParent))
        if (tag != null) {
            add(TextView(ctx).apply {
                this.text = tag
                textSize = TAG_SP
                setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lParams(wrapContent, wrapContent, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
    }

    private const val TAG_SP = 9f

    // the engine's cell takes every touch, so a tap never reached this frame on a device (Robolectric's performClick bypassed it)
    private class KeyFrame(ctx: Context) : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
    }
}
