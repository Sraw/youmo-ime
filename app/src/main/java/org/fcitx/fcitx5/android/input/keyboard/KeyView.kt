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
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.theme.KeyFaceGeometry
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyStyle
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
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
import kotlin.math.min
import kotlin.math.roundToInt

abstract class KeyView(ctx: Context, val theme: Theme, val def: KeyDef.Appearance) :
    CustomGestureView(ctx) {

    /** How the theme draws its keys ([Theme.look]): blocks or flat, corners, gaps, a press's effect, each kind's face. */
    protected val keys = theme.look.keys

    /** What a theme styles it as: by the face colour it had before theme file 3.0 ([themeKind]). */
    val kind = def.themeKind

    val bordered: Boolean = keys.blocks
    val radius: Float = dp(keys.radiusDp.toFloat())
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hMargin: Int = if (def.margin) dp(if (landscape) keys.gap.hLand else keys.gap.h) else 0
    val vMargin: Int = if (def.margin) dp(if (landscape) keys.gap.vLand else keys.gap.v) else 0

    // its face's shape at a size: the theme's, as the key's own border has it
    private val geometry = { w: Float, h: Float ->
        KeyFaceGeometry.of(w, h, kind, keys, landscape, resources.displayMetrics.density, def.margin, def.border.face)
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

    // the shortcut marks drawn, top right and bottom right; made the first time one is drawn
    private val markViews = arrayOfNulls<View>(2)

    /** The mark drawn in the top right corner, if one is. */
    protected val topRightMark: View? get() = markViews[0]

    /**
     * Draws [topRight] and [bottomRight] in their corners, [below] under the label; a [Mark.Symbol] is
     * the key's own corner symbol, which only [AltTextKeyView] has, placed there by its override.
     */
    open fun showMarks(topRight: Mark?, bottomRight: Mark?, below: Mark?) {
        listOf(topRight, bottomRight).forEachIndexed { i, mark -> showMark(i, mark?.takeIf { it != Mark.Symbol }) }
    }

    private fun showMark(corner: Int, mark: Mark?) {
        markViews[corner]?.let { appearanceView.removeView(it) }
        markViews[corner] = null
        if (mark == null) return
        val color = theme.look.palette.keyHintColor
        val v: View = when (mark) {
            is Mark.Icon -> ImageView(context).apply {
                imageTintList = ColorStateList.valueOf(color)
                imageResource = mark.res
            }
            // a text's whole, shrunk sideways to fit the corner rather than cut (C6)
            is Mark.Text -> AutoScaleTextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, MARK_TEXT_DP * keyTextScale(context))
                setTextColor(color)
                scaleMode = AutoScaleTextView.Mode.Horizontal
                setSingleLine()
                text = mark.text
            }
            Mark.Symbol -> return
        }
        v.isClickable = false
        v.isFocusable = false
        // 紧凑: smaller and into the corner, or on 大千's narrow low keys the icon sat on the symbol
        val compact = keyboardCompact()
        val size = dp(if (compact) MARK_ICON_COMPACT_DP else MARK_ICON_DP)
        val inset = dp(if (compact) 1 else 2)
        val insetRight = dp(if (compact) 2 else 3)
        appearanceView.add(v, ConstraintLayout.LayoutParams(if (mark is Mark.Text) 0 else size, if (mark is Mark.Text) wrapContent else size).apply {
            if (mark is Mark.Text) matchConstraintPercentWidth = MARK_TEXT_WIDTH
            rightToRight = parentId; rightMargin = hMargin + insetRight
            if (corner == 0) {
                topToTop = parentId; topMargin = vMargin + inset
            } else {
                bottomToBottom = parentId; bottomMargin = vMargin + inset
            }
        })
        markViews[corner] = v
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
        faces(KeyStyleResolver.resolve(keys, null, null, kind), BoardImages.NONE)
        add(appearanceView, lParams(matchParent, matchParent))
    }

    /**
     * Draws it as the theme has the key [themeKey] of [board] (KeyStyleResolver: its own look, its
     * role's on every board, its kind's), its images [images]'s; the kind's alone for no name. Its
     * keyboard calls it once the key is named.
     */
    fun style(board: String?, images: BoardImages) {
        val s = KeyStyleResolver.resolve(keys, board, themeKey, kind)
        faces(s, images)
        showLabel(s.label == KeyLabel.Show)
    }

    private fun faces(s: KeyStyle, images: BoardImages) = appearanceView.drawAsKey(theme, s, kind, images, geometry)

    /** The theme's key text size, a share of each key's own ([Text.keyScale]). */
    protected val textScale = theme.look.text.keyScale / 100f

    /** The theme's key font at its weight, [bold] at 700; null for the system's as it is (no font, the normal weight). */
    protected fun keyTypeface(bold: Boolean): Typeface? = theme.look.let {
        ThemeAssets.typeface(it, it.text.keyFont, if (bold) BOLD_WEIGHT else it.text.keyWeight)
    }

    /** Its label (text, icon, corner symbol) shown, or not: the key's image has it ([KeyLabel.Hide]). Its description stays. */
    protected open fun showLabel(show: Boolean) = Unit

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
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize * keyTextScale(ctx) * textScale)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        // the theme's font, or the original typeface with textStyle only
        keyTypeface(def.textStyle == Typeface.BOLD)?.let { typeface = it } ?: setTypeface(typeface, def.textStyle)
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

    override fun showLabel(show: Boolean) {
        mainText.isInvisible = !show
    }

    /** What its top right corner draws, which the label gives way to: a shortcut's mark there. */
    protected open val topRightCorner: View? get() = topRightMark

    /** Its corner symbol, wherever it is: [AltTextKeyView]'s. */
    protected open val cornerSymbol: View? get() = null

    protected val labelInk = RectF()
    protected val markInk = RectF()

    /**
     * Its corner's mark, each one moved or shrunk last time put back first (the corner's maybe
     * another now), its ink and the label's measured; null for none, or ink not measured.
     */
    protected fun measureCorner(): View? {
        for (v in listOfNotNull(mainText, topRightMark, cornerSymbol)) {
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
        }
        val mark = topRightCorner?.takeIf { it.visibility == View.VISIBLE }
        if (mark == null || !mainText.inkBounds(labelInk)) return null
        if (mark is AutoScaleTextView) {
            if (!mark.inkBounds(markInk)) return null
        } else {
            markInk.set(0f, 0f, mark.width.toFloat(), mark.height.toFloat())
        }
        return mark
    }

    /** The face's sides, its key's margins in. */
    protected fun face() = KeyMarkYield.Ink(
        hMargin.toFloat(), vMargin.toFloat(), (appearanceView.width - hMargin).toFloat(), (appearanceView.height - vMargin).toFloat(),
    )

    // the label out of the way of its corner's mark, where their ink meets (KeyMarkYield): 大千's
    // symbols on its narrow keys (ㄌ under the scissors). After the face's own layout: the key's
    // onLayout saw the mark before it was placed
    protected open fun yieldToMark() {
        val mark = measureCorner() ?: return
        val y = KeyMarkYield.of(
            ink(mainText, labelInk), ink(mark, markInk), face(),
            gap = dp(1).toFloat(), markShrinks = mark is AutoScaleTextView,
        )
        mainText.translationX = y.dx
        mainText.translationY = y.dy
        if (y.labelScale < 1f) {
            mainText.pivotX = labelInk.left
            mainText.pivotY = labelInk.bottom
            mainText.scaleX = y.labelScale
            mainText.scaleY = y.labelScale
        }
        if (y.markScale < 1f) {
            mark.pivotX = markInk.right
            mark.pivotY = markInk.top
            mark.scaleX = y.markScale
            mark.scaleY = y.markScale
        }
    }

    // [r], in [v]'s coordinates, in the face's
    protected fun ink(v: View, r: RectF) = KeyMarkYield.Ink(v.left + r.left, v.top + r.top, v.left + r.right, v.top + r.bottom)

    init {
        appearanceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> yieldToMark() }
    }

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

    override val cornerSymbol: View? get() = altText

    // its symbol, while that is in the top right corner and no mark is
    override val topRightCorner: View?
        get() = super.topRightCorner ?: altText.takeIf {
            (it.layoutParams as ConstraintLayout.LayoutParams).run { topToTop == parentId && rightToRight == parentId }
        }

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        // TODO hardcoded alt text size
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.666667f * keyTextScale(ctx) * textScale)
        keyTypeface(bold = true)?.let { typeface = it } ?: setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.look.palette.keyHintColor
                Variant.Accent -> theme.accentKeyTextColor
            }
        )
    }

    override fun showLabel(show: Boolean) {
        super.showLabel(show)
        altText.isInvisible = !show
    }

    init {
        appearanceView.apply {
            add(altText, lParams(wrapContent, wrapContent))
        }
        applyLayout(resources.configuration.orientation)
    }

    // the letter stays where it is, as its row's do: the keyboard settles them all at once (letters)
    override fun yieldToMark() = Unit

    private var corner: View? = null

    /**
     * Its letter and corner as [KeyMarkYield.letters] takes them, after [BaseKeyboard]'s layout;
     * null for a letter not measured.
     */
    internal fun letterKey(): KeyMarkYield.LetterKey? {
        corner = measureCorner()
        if (corner == null && !mainText.inkBounds(labelInk)) return null
        val face = face()
        val mark = corner
        return KeyMarkYield.LetterKey(
            ink(mainText, labelInk),
            baseline = (mainText.top + mainText.baseline).toFloat(),
            symbol = mark?.let { ink(it, markInk) },
            // clear of the face's rounded corner
            room = KeyMarkYield.Ink(face.left, face.top + dp(1), face.right - dp(2), face.bottom),
            symbolDp = ((mark as? AutoScaleTextView)?.textSize ?: mark?.height?.toFloat() ?: 0f) / resources.displayMetrics.density,
        )
    }

    /** Its letter scaled by [scale] about its middle on its baseline, its corner as [c] says. */
    internal fun settle(scale: Float, c: KeyMarkYield.Corner) {
        if (scale < 1f) {
            mainText.pivotX = labelInk.centerX()
            mainText.pivotY = mainText.baseline.toFloat()
            mainText.scaleX = scale
            mainText.scaleY = scale
        }
        val mark = corner ?: return
        mark.translationX = c.dx
        mark.translationY = c.dy
        if (c.scale < 1f) {
            mark.pivotX = markInk.right
            mark.pivotY = markInk.top
            mark.scaleX = c.scale
            mark.scaleY = c.scale
        }
    }

    private fun applyTopRightAltTextPosition() {
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
            // reset
            bottomToBottom = unset; bottomMargin = 0
            // set
            topToTop = parentId; topMargin = vMargin
            leftToLeft = unset
            rightToRight = parentId; rightMargin = hMargin + dp(4)
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
            rightMargin = 0
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
    private var symbolAt: Int? = null

    override fun showMarks(topRight: Mark?, bottomRight: Mark?, below: Mark?) {
        super.showMarks(topRight, bottomRight, below)
        symbolAt = when (Mark.Symbol) {
            topRight -> SYMBOL_TOP_RIGHT
            bottomRight -> SYMBOL_BOTTOM_RIGHT
            below -> SYMBOL_BELOW
            else -> SYMBOL_NONE
        }
        applyLayout(resources.configuration.orientation)
    }

    private fun applyBottomRightAltTextPosition() {
        applyTopRightAltTextPosition()
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = unset; topMargin = 0
            bottomToBottom = parentId; bottomMargin = vMargin
        }
    }

    private fun applyLayout(orientation: Int) {
        when (symbolAt) {
            SYMBOL_TOP_RIGHT -> return applyTopRightAltTextPosition()
            SYMBOL_BOTTOM_RIGHT -> return applyBottomRightAltTextPosition()
            SYMBOL_BELOW -> return applyBottomAltTextPosition()
            SYMBOL_NONE -> return applyNoAltTextPosition()
        }
        // a key no swipe marks: its symbol alone, where 标点位置 puts one mark
        val placed = KeyMarks.place(
            Unit, null, null, markPosition(),
            landscape = orientation == Configuration.ORIENTATION_LANDSCAPE, compact = keyboardCompact(),
        )
        when {
            placed.topRight != null -> applyTopRightAltTextPosition()
            placed.below != null -> applyBottomAltTextPosition()
            else -> applyNoAltTextPosition()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // top right either way round: nothing to move
        if (symbolAt == null && (ThemeManager.prefs.punctuationPosition.getValue() == PunctuationPosition.TopRight || keyboardCompact())) {
            return
        }
        applyLayout(newConfig.orientation)
    }

    private companion object {
        const val SYMBOL_TOP_RIGHT = 0
        const val SYMBOL_BOTTOM_RIGHT = 1
        const val SYMBOL_BELOW = 2
        const val SYMBOL_NONE = 3
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

    override fun showLabel(show: Boolean) {
        img.isInvisible = !show
    }
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

    override fun showLabel(show: Boolean) {
        super.showLabel(show)
        img.isInvisible = !show
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
private const val BOLD_WEIGHT = 700
private const val MARK_ICON_COMPACT_DP = 8
private const val MARK_TEXT_DP = 9f
private const val MARK_TEXT_WIDTH = 0.45f
