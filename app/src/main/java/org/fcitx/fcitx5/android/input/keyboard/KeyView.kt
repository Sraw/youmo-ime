/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.parentId
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.existingOrNewId
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

abstract class KeyView(ctx: Context, val theme: Theme, val def: KeyDef.Appearance) :
    CustomGestureView(ctx) {

    val bordered: Boolean
    val borderStroke: Boolean
    val rippled: Boolean
    val radius: Float
    val hMargin: Int
    val vMargin: Int

    init {
        val prefs = ThemeManager.prefs
        bordered = prefs.keyBorder.getValue()
        borderStroke = prefs.keyBorderStroke.getValue()
        rippled = prefs.keyRippleEffect.getValue()
        radius = dp(prefs.keyRadius.getValue().toFloat())
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hMarginPref =
            if (landscape) prefs.keyHorizontalMarginLandscape else prefs.keyHorizontalMargin
        val vMarginPref =
            if (landscape) prefs.keyVerticalMarginLandscape else prefs.keyVerticalMargin
        hMargin = if (def.margin) dp(hMarginPref.getValue()) else 0
        vMargin = if (def.margin) dp(vMarginPref.getValue()) else 0
    }

    /** Its name in a theme, its keyboard's [BaseKeyboard.themeBoard] the board: [KeyDef.name]. */
    var themeKey: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    // where it is now, asked each time: the floating keyboard moves by translation, which lays
    // nothing out again (FloatingPlacer), so a rect kept from the last layout would be stale
    val bounds: Rect
        get() = cachedBounds.also { updateBounds() }

    /**
     * KeyView content left margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginLeft = 0f

    /**
     * KeyView content right margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginRight = 0f

    /**
     * [KeyView] contains 2 parts: `TouchEventView` and `AppearanceView`.
     *
     * `TouchEventView` is the outer [CustomGestureView] that handles touch events.
     *
     * `AppearanceView` in the inner [ConstraintLayout], it can be smaller than its parent,
     * and holds the [bounds] for popup.
     */
    protected val appearanceView = constraintLayout {
        // sync any state from parent
        isDuplicateParentStateEnabled = true
    }

    /** What a key draws of its swipes ([KeyMarks]): its corner symbol, a command's icon, a text's whole. */
    sealed interface Mark {
        data object Symbol : Mark
        data class Icon(@DrawableRes val res: Int) : Mark
        data class Text(val text: String) : Mark
    }

    // the shortcut marks drawn, by slot, an up shortcut's before a function key's down one; made as drawn
    private val markViews = linkedMapOf<KeyMarks.Slot, View>()

    /** The shortcut's mark drawn in [slot], if one is. */
    internal fun markAt(slot: KeyMarks.Slot): View? = markViews[slot]

    /** The shortcut marks drawn, by slot. */
    internal val shortcutMarks: Map<KeyMarks.Slot, View> get() = markViews

    /**
     * Draws [placed]'s shortcuts in their slots; its [Mark.Symbol], the key's own corner symbol, which
     * only [AltTextKeyView] has, is placed by its override.
     */
    open fun showMarks(placed: KeyMarks.Placed<Mark>) {
        markViews.values.forEach { appearanceView.removeView(it) }
        markViews.clear()
        placed.at.forEach { (slot, mark) -> if (mark != Mark.Symbol) showMark(slot, mark) }
    }

    /** The face's sides, its key's margins in. */
    protected fun face() = KeyMarkYield.Ink(
        hMargin.toFloat(), vMargin.toFloat(), (appearanceView.width - hMargin).toFloat(), (appearanceView.height - vMargin).toFloat(),
    )

    /** Where its glyph and marks may move: the face, clear of its rounded corners. */
    protected fun room() = face().let { KeyMarkYield.Ink(it.left + dp(2), it.top + dp(1), it.right - dp(2), it.bottom - dp(1)) }

    // [r], in [v]'s coordinates, in the face's
    protected fun ink(v: View, r: RectF) = KeyMarkYield.Ink(v.left + r.left, v.top + r.top, v.left + r.right, v.top + r.bottom)

    private val inkRect = RectF()

    /** [v]'s ink in the face's coordinates as laid out: a text's glyphs, another's box; null if not shown or not measured. */
    protected fun inkOf(v: View?): KeyMarkYield.Ink? {
        if (v == null || v.visibility != View.VISIBLE) return null
        if (v is AutoScaleTextView) {
            if (!v.inkBounds(inkRect)) return null
        } else {
            inkRect.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
        }
        return ink(v, inkRect)
    }

    /** What its marks keep clear of: its label or icon; null for none. */
    protected open val glyph: View? get() = null

    /** Its own symbol and the slot it is in, which only [AltTextKeyView] has. */
    protected open val symbol: Pair<View, KeyMarks.Slot>? get() = null

    /** Its glyph and marks back as laid out: each one moved, smaller or left out last time put back. */
    protected fun unsettle() {
        for (v in listOfNotNull(glyph, symbol?.first) + markViews.values) {
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
        symbol?.first?.alpha = 1f
        markViews.values.forEach { it.alpha = 1f }
    }

    /** Its symbol and shortcut marks drawn, as [KeyMarkYield] takes them: the symbol left out last, a down shortcut first. */
    protected fun yieldMarks(): List<Pair<View, KeyMarkYield.Mark>> {
        val density = resources.displayMetrics.density
        // a text no smaller than a symbol's least, an icon than an icon's
        fun minScale(v: View) = if (v is AutoScaleTextView) {
            KeyMarkYield.minScale(KeyMarkYield.MIN_SYMBOL_DP, v.textSize / density)
        } else {
            KeyMarkYield.minScale(KeyMarkYield.MIN_ICON_DP, v.height / density)
        }
        val out = mutableListOf<Pair<View, KeyMarkYield.Mark>>()
        symbol?.let { (v, slot) -> inkOf(v)?.let { out += v to KeyMarkYield.Mark(it, slot, minScale(v), KeyMarkYield.DROP_SYMBOL) } }
        markViews.entries.forEachIndexed { i, (slot, v) ->
            inkOf(v)?.let { out += v to KeyMarkYield.Mark(it, slot, minScale(v), KeyMarkYield.DROP_SHORTCUT + i) }
        }
        return out
    }

    /** This view, whose ink is [from], drawn as [to] (as wide to tall, from its own top left), or not at all (null). */
    protected fun View.settle(from: KeyMarkYield.Ink, to: KeyMarkYield.Ink?) {
        if (to == null) {
            alpha = 0f
            return
        }
        val s = if (from.width > 0f && abs(to.width - from.width) > SAME) to.width / from.width else 1f
        pivotX = 0f
        pivotY = 0f
        scaleX = s
        scaleY = s
        translationX = (to.left - left - (from.left - left) * s).takeIf { abs(it) > SAME } ?: 0f
        translationY = (to.top - top - (from.top - top) * s).takeIf { abs(it) > SAME } ?: 0f
    }

    // its glyph and marks clear of each other where their ink meets (KeyMarkYield.key): 大千's symbols
    // on its narrow keys (ㄌ under the scissors), handwriting's and 紧凑's low backspace (the bin on its
    // tip). After the face's own layout: the key's onLayout saw the marks before they were placed
    protected open fun settleMarks() {
        unsettle()
        val g = glyph ?: return
        val marks = yieldMarks()
        if (marks.isEmpty()) return
        val ink = inkOf(g) ?: return
        val s = KeyMarkYield.key(ink, marks.map { it.second }, room(), gap = dp(1).toFloat())
        g.settle(ink, s.glyph)
        marks.forEachIndexed { i, (v, m) -> v.settle(m.ink, s.marks[i]) }
    }

    private fun showMark(slot: KeyMarks.Slot, mark: Mark) {
        val v = markView(slot, mark) ?: return
        v.isClickable = false
        v.isFocusable = false
        appearanceView.add(v, markParams(slot, mark is Mark.Text))
        markViews[slot] = v
    }

    private fun markView(slot: KeyMarks.Slot, mark: Mark): View? {
        val color = theme.altKeyTextColor
        return when (mark) {
            is Mark.Icon -> ImageView(context).apply {
                imageTintList = ColorStateList.valueOf(color)
                imageResource = mark.res
            }
            // a text's whole, shrunk sideways to fit the corner rather than cut (C6)
            is Mark.Text -> AutoScaleTextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, MARK_TEXT_DP * keyTextScale(context))
                setTextColor(color)
                scaleMode = AutoScaleTextView.Mode.Horizontal
                // from the corner in, however short
                gravity = (if (slot.left) Gravity.START else if (slot.right) Gravity.END else Gravity.CENTER_HORIZONTAL) or
                    (if (slot.bottom) Gravity.BOTTOM else Gravity.TOP)
                setSingleLine()
                text = mark.text
            }
            Mark.Symbol -> null
        }
    }

    // [slot]'s place on the face; 紧凑: smaller and into the corner, or on 大千's narrow low keys the
    // icon sat on the symbol
    private fun markParams(slot: KeyMarks.Slot, text: Boolean): ConstraintLayout.LayoutParams {
        val compact = keyboardCompact()
        val size = dp(if (compact) MARK_ICON_COMPACT_DP else MARK_ICON_DP)
        val inset = dp(if (compact) 1 else 2)
        val insetSide = dp(if (compact) 2 else 3)
        return ConstraintLayout.LayoutParams(if (text) 0 else size, if (text) wrapContent else size).apply {
            if (text) matchConstraintPercentWidth = MARK_TEXT_WIDTH
            if (!slot.right) {
                leftToLeft = parentId
                if (slot.left) leftMargin = hMargin + insetSide
            }
            if (!slot.left) {
                rightToRight = parentId
                if (slot.right) rightMargin = hMargin + insetSide
            }
            if (slot.bottom) {
                bottomToBottom = parentId; bottomMargin = vMargin + inset
            } else {
                topToTop = parentId; topMargin = vMargin + inset
            }
        }
    }

    init {
        appearanceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> settleMarks() }
    }

    init {
        // trigger setEnabled(true)
        isEnabled = true
        isClickable = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = def.viewId
        }
        // Set on the touch target, not on the inner ImageView/TextView: those are decoration
        // with isClickable = false, so a screen reader would announce a label that belongs to
        // a node the user cannot activate.
        if (def.contentDescription != 0) {
            contentDescription = context.getString(def.contentDescription)
        }
        // key border
        if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val bkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                Variant.Alternative -> theme.altKeyBackgroundColor
                Variant.Accent -> theme.accentKeyBackgroundColor
            }
            val borderOrShadowWidth = dp(1)
            // background: key border
            appearanceView.background = if (borderStroke) borderedKeyBackgroundDrawable(
                bkgColor, theme.keyShadowColor,
                radius, borderOrShadowWidth, hMargin, vMargin
            ) else shadowedKeyBackgroundDrawable(
                bkgColor, theme.keyShadowColor,
                radius, borderOrShadowWidth, hMargin, vMargin
            )
            // foreground: press highlight or ripple
            setupPressHighlight()
        } else {
            // normal press highlight for keys without special background
            // special background is handled in `onSizeChanged()`
            if (def.border != Border.Special) {
                setupPressHighlight()
            }
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private fun setupPressHighlight(mask: Drawable? = null) {
        appearanceView.foreground = if (rippled) {
            RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                // ripple should be masked with an opaque color
                mask ?: highlightMaskDrawable(Color.WHITE)
            )
        } else if (bordered && borderStroke) {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    borderedKeyBackgroundDrawable(
                        Color.TRANSPARENT, theme.keyShadowColor,
                        radius, dp(2), hMargin, vMargin
                    )
                )
            }
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    // use mask drawable as highlight directly
                    mask ?: highlightMaskDrawable(theme.keyPressHighlightColor)
                )
            }
        }
    }

    private fun highlightMaskDrawable(@ColorInt color: Int): Drawable {
        return if (bordered) insetRadiusDrawable(hMargin, vMargin, radius, color)
        else InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else styledFloat(android.R.attr.disabledAlpha)
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        // across, the face drawn (less hMargin, none on keys without margins): popups are as wide as
        // it; down, to the margin's foot, which popups align their bottoms with
        cachedBounds.set(x + hMargin, y, x + appearanceView.width - hMargin, y + appearanceView.height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // margins set back to 0 (a row fitted again, BaseKeyboard.fitRow) must reach the face too
        val face = appearanceView.layoutParams as LayoutParams
        val faceHasMargins = face.leftMargin != 0 || face.rightMargin != 0
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f || faceHasMargins) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
            }
            // sets `measuredWidth` and `measuredHeight` of `AppearanceView`
            // https://developer.android.com/guide/topics/ui/how-android-draws#measure
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (bordered) return
        when (def.viewId) {
            R.id.button_space -> {
                val bkgRadius = dp(3f)
                val minHeight = dp(26)
                val hInset = dp(10)
                val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
                appearanceView.background = insetRadiusDrawable(
                    hInset, vInset, bkgRadius, theme.spaceBarColor
                )
                // InsetDrawable sets padding to container view; remove padding to prevent text from bing clipped
                appearanceView.padding = 0
                // apply press highlight for background area
                setupPressHighlight(
                    insetRadiusDrawable(
                        hInset, vInset, bkgRadius,
                        if (rippled) Color.WHITE else theme.keyPressHighlightColor
                    )
                )
            }
            R.id.button_return -> {
                val drawableSize = min(min(w, h), dp(35))
                val hInset = (w - drawableSize) / 2
                val vInset = (h - drawableSize) / 2
                appearanceView.background = insetOvalDrawable(
                    hInset, vInset, theme.accentKeyBackgroundColor
                )
                appearanceView.padding = 0
                setupPressHighlight(
                    insetOvalDrawable(
                        hInset, vInset, if (rippled) Color.WHITE else theme.keyPressHighlightColor
                    )
                )
            }
        }
    }
}

/**
 * The keys' text: floating ([FloatingKeyboard]), as much smaller as the keys are shorter, or a letter
 * and the digit under it ran into each other; 紧凑 ([keyboardCompact]) smaller too, the smaller of
 * the two where both are ([KeyTextScale]).
 */
internal fun keyTextScale(context: Context): Float {
    // a keyboard drawn elsewhere (the theme's preview in the settings) is not the floating one, but
    // is 紧凑 as the real one is, so the preview shows what the level does
    val floating = if (ThemeManager.prefs.floatingKeyboard.getValue() && context.inInputMethod()) {
        val screen = context.resources.configuration.screenWidthDp
        FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen)
    } else {
        null
    }
    return KeyTextScale.of(keyboardCompact(), floating)
}

private fun Context.inInputMethod(): Boolean {
    var at: Context? = this
    while (at is ContextWrapper) {
        if (at is InputMethodService) return true
        at = at.baseContext
    }
    return false
}

@SuppressLint("ViewConstructor")
open class TextKeyView(ctx: Context, theme: Theme, def: KeyDef.Appearance.Text) :
    KeyView(ctx, theme, def) {
    val mainText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        background = null
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize * keyTextScale(ctx))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        // keep original typeface, apply textStyle only
        setTypeface(typeface, def.textStyle)
        setTextColor(
            when (def.variant) {
                Variant.Normal -> theme.keyTextColor
                Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                Variant.Accent -> theme.accentKeyTextColor
            }
        )
    }

    init {
        appearanceView.apply {
            add(mainText, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }

    override val glyph: View? get() = mainText

    private var badge: ImageView? = null

    /**
     * A small icon before the label, the two centred together; null for none: what holding the
     * key does, where that is not the usual (voice input on the space).
     */
    fun showBadge(@DrawableRes icon: Int?) {
        if (icon == null) {
            badge?.let { appearanceView.removeView(it) }
            badge = null
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                startToEnd = unset
                startToStart = parentId
            }
            return
        }
        val img = badge ?: imageView { configure(theme, icon, Variant.AltForeground) }.also {
            badge = it
            appearanceView.add(it, appearanceView.lParams(dp(BADGE), dp(BADGE)) {
                centerVertically()
                startToStart = parentId
                endToStart = mainText.existingOrNewId
                horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
                marginEnd = dp(BADGE_GAP)
            })
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                startToStart = unset
                startToEnd = it.existingOrNewId
            }
        }
        img.setImageResource(icon)
    }

    private companion object {
        const val BADGE = 15
        const val BADGE_GAP = 3
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(ctx: Context, theme: Theme, def: KeyDef.Appearance.AltText) :
    TextKeyView(ctx, theme, def) {

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        // TODO hardcoded alt text size
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.666667f * keyTextScale(ctx))
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                Variant.Accent -> theme.accentKeyTextColor
            }
        )
    }

    // the slot its symbol is laid out in; null for not drawn
    private var symbolLaidOut: KeyMarks.Slot? = null

    override val symbol: Pair<View, KeyMarks.Slot>? get() = symbolLaidOut?.let { altText to it }

    /** The slot its symbol is drawn in; null for none. */
    internal val symbolSlot: KeyMarks.Slot? get() = symbolLaidOut

    init {
        appearanceView.apply {
            add(altText, lParams(wrapContent, wrapContent))
        }
        applyLayout(resources.configuration.orientation)
    }

    // the letter stays where it is, as its row's do: the keyboard settles them all at once (letters)
    override fun settleMarks() = Unit

    // its letter's ink and marks as measured for letters
    private var letterInk: KeyMarkYield.Ink? = null
    private var settling: List<Pair<View, KeyMarkYield.Mark>> = emptyList()

    /**
     * Its letter and marks as [KeyMarkYield.letters] takes them, after [BaseKeyboard]'s layout;
     * null for a letter not measured.
     */
    internal fun letterKey(): KeyMarkYield.LetterKey? {
        unsettle()
        letterInk = null
        settling = emptyList()
        val letter = inkOf(mainText) ?: return null
        letterInk = letter
        settling = yieldMarks()
        return KeyMarkYield.LetterKey(letter, baseline = (mainText.top + mainText.baseline).toFloat(), settling.map { it.second }, room())
    }

    /** Its letter and marks where [settled] puts them. */
    internal fun settle(settled: KeyMarkYield.Settled) {
        letterInk?.let { mainText.settle(it, settled.glyph) }
        settling.forEachIndexed { i, (v, m) -> v.settle(m.ink, settled.marks.getOrNull(i)) }
    }

    // in a corner, the letter where it is
    private fun applyCornerAltTextPosition(slot: KeyMarks.Slot) {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topMargin = 0
            bottomToTop = unset
            // set
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = unset; topMargin = 0
            bottomToBottom = unset; bottomMargin = 0
            leftToLeft = unset; leftMargin = 0
            rightToRight = unset; rightMargin = 0
            if (slot.bottom) {
                bottomToBottom = parentId; bottomMargin = vMargin
            } else {
                topToTop = parentId; topMargin = vMargin
            }
            if (slot.left) {
                leftToLeft = parentId; leftMargin = hMargin + dp(4)
            } else {
                rightToRight = parentId; rightMargin = hMargin + dp(4)
            }
        }
    }

    private fun applyBottomAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            bottomToBottom = unset
            // set
            topToTop = parentId; topMargin = vMargin
            bottomToTop = altText.existingOrNewId
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topToTop = unset; topMargin = 0
            leftMargin = 0; rightMargin = 0
            // set
            leftToLeft = parentId
            rightToRight = parentId
            bottomToBottom = parentId; bottomMargin = vMargin + dp(2)
        }
    }

    private fun applyNoAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topMargin = 0
            bottomToTop = unset
            // set
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.visibility = View.GONE
    }

    // where the symbol is drawn as the swipes' marks are placed ([showMarks]); null, as 标点位置 says
    private var marked = false
    private var markedSymbol: KeyMarks.Slot? = null

    override fun showMarks(placed: KeyMarks.Placed<Mark>) {
        super.showMarks(placed)
        marked = true
        markedSymbol = placed.slotOf(Mark.Symbol)
        applyLayout(resources.configuration.orientation)
    }

    private fun applyLayout(orientation: Int) {
        // a key no swipe marks: its symbol alone, where 标点位置 puts it
        val slot = if (marked) {
            markedSymbol
        } else {
            val spots = markSpots()
            KeyMarks.symbolSlot(
                spots.symbol, spots.shortcut, landscape = orientation == Configuration.ORIENTATION_LANDSCAPE, compact = keyboardCompact(),
            )
        }
        symbolLaidOut = slot
        when (slot) {
            KeyMarks.Slot.TopRight, KeyMarks.Slot.BottomRight, KeyMarks.Slot.TopLeft, KeyMarks.Slot.BottomLeft -> applyCornerAltTextPosition(slot)
            KeyMarks.Slot.Below -> applyBottomAltTextPosition()
            else -> {
                symbolLaidOut = null
                applyNoAltTextPosition()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        applyLayout(newConfig.orientation)
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(ctx: Context, theme: Theme, def: KeyDef.Appearance.Image) :
    KeyView(ctx, theme, def) {
    val img = imageView { configure(theme, def.src, def.variant) }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }

    // its icon's box, not ink: an icon's is its drawable's
    override val glyph: View? get() = img
}

private fun ImageView.configure(theme: Theme, @DrawableRes src: Int, variant: Variant) = apply {
    isClickable = false
    isFocusable = false
    imageTintList = ColorStateList.valueOf(
        when (variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
    )
    imageResource = src
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(ctx: Context, theme: Theme, def: KeyDef.Appearance.ImageText) :
    TextKeyView(ctx, theme, def) {
    val img = imageView {
        configure(theme, def.src, def.variant)
    }

    init {
        appearanceView.apply {
            add(img, lParams(dp(13), dp(13)))
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToBottom = parentId
            bottomMargin = vMargin + dp(4)
            topToTop = unset
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            topToTop = parentId
        }
        updateMargins(resources.configuration.orientation)
    }

    private fun updateMargins(orientation: Int) {
        when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(2)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(4)
                }
            }
            else -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(4)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(8)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        updateMargins(newConfig.orientation)
    }
}

// a shortcut mark's size: an icon's side, a text's size and the share of the key's width it fits in
private const val MARK_ICON_DP = 11
private const val MARK_ICON_COMPACT_DP = 8
private const val MARK_TEXT_DP = 9f
private const val MARK_TEXT_WIDTH = 0.45f

// a move or scale below this many pixels, a hundredth of one, no move: a float's rounding
private const val SAME = 0.01f
