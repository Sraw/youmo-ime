/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.view.inputmethod.CursorAnchorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Size
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fcitx.fcitx5.android.input.floating.FloatingButtonPlacement
import org.fcitx.fcitx5.android.input.floating.FloatingButtonPlacement.Area
import org.fcitx.fcitx5.android.input.floating.FloatingButtonPlacement.Spot
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.padding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: FcitxInputMethodService,
    fcitx: HostConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val ctx = context.withTheme(R.style.Theme_InputViewTheme)

    private val candidatesPrefs = AppPrefs.getInstance().candidates
    private val orientation by candidatesPrefs.orientation
    private val fontSize by candidatesPrefs.fontSize

    private var inputPanel = HostEvent.InputPanel.EMPTY
    private var paged = HostEvent.Paged.EMPTY

    override val pagedCandidates = true

    /** Deleted, or what it learned forgotten. Whether one was highlighted. */
    fun forgetHighlighted(): Boolean {
        val word = paged.candidates.getOrNull(paged.cursorIndex) ?: return false
        forgetCandidate(paged.cursorIndex, word.text)
        return true
    }

    /** horizontal, bottom, top */
    private val anchorPosition = floatArrayOf(0f, 0f, 0f)
    private val parentSize = floatArrayOf(0f, 0f)

    // false where the bottom-left corner stands in: no CursorAnchorInfo, or its cursor out of sight
    private var cursorKnown = false

    private var shouldUpdatePosition = false

    private val placement = FloatingButtonPlacement()
    private val internalPrefs = AppPrefs.getInstance().internal
    private var savedButtonX by internalPrefs.keyboardButtonX
    private var savedButtonY by internalPrefs.keyboardButtonY
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // past the slop; where the finger and button were at its down
    private var dragging = false
    private val dragStart = floatArrayOf(0f, 0f, 0f, 0f)

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener = OnGlobalLayoutListener {
        shouldUpdatePosition = true
    }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener = OnPreDrawListener {
        if (shouldUpdatePosition) {
            updatePosition()
        }
        true
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    // where touchEventReceiverWindow goes: x, y, width, height
    private val receiverPlace = intArrayOf(0, 0, 0, 0)
    private var receiverShown = false
    private val placeReceiver = Runnable {
        // the IME window not up: BadTokenException. Placed at the next draw
        if (!isAttachedToWindow) return@Runnable
        receiverShown = true
        touchEventReceiverWindow.showAt(receiverPlace[0], receiverPlace[1], receiverPlace[2], receiverPlace[3])
    }

    private val setupTextView: TextView.() -> Unit = {
        textSize = fontSize.toFloat()
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private val preeditUi = PreeditUi(ctx, theme, setupTextView)

    private val candidatesUi = PagedCandidatesUi(
        ctx, theme, setupTextView,
        onCandidateClick = { index, text -> fcitx.launchOnReady { it.select(index, text) } },
        onCandidateAction = { index, text, view -> showCandidateActionMenu(index, text, view) },
        onPrevPage = { fcitx.launchOnReady { it.offsetCandidatePage(-1) } },
        onNextPage = { fcitx.launchOnReady { it.offsetCandidatePage(1) } }
    )

    // as Gboard's floating button: an app that tells no IME of a tap in its field (Chrome) leaves no other way
    private val keyboardButton = imageView {
        showTouchKeyboardIcon(this)
        background = borderlessRippleDrawable(theme.keyPressHighlightColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(R.string.show_touch_keyboard)
        setOnClickListener { service.showTouchKeyboard() }
        setOnTouchListener(::onButtonTouch)
    }

    // a theme's `touch-keyboard` too
    private fun showTouchKeyboardIcon(v: ImageView) {
        BarIconDrawables.show(v, PackIcons.of(theme), BarIcons.TOUCH_KEYBOARD, theme.altKeyTextColor, v.dp(TOUCH_KEYBOARD_ICON_DP)) { showTouchKeyboardIcon(v) }
    }

    override fun onStartHandleFcitxEvent() {
        // read together; a change meanwhile has its event come after, to the collector
        val (panel, page) = fcitx.runImmediately { inputPanelCached to pagedCached }
        handleFcitxEvent(panel)
        handleFcitxEvent(page)
    }

    override fun handleFcitxEvent(it: HostEvent) {
        when (it) {
            is HostEvent.InputPanel -> {
                inputPanel = it
                updateUi()
            }
            is HostEvent.Paged -> {
                paged = it
                updateUi()
            }
            else -> {}
        }
    }

    /**
     * Only as the floating window: with the touch keyboard up it takes no events and stays GONE, else its
     * lone button came up over the app; drawn when it takes them again.
     */
    fun codePlaceChanged() {
        if (handleEvents) updateUi()
    }

    private fun updateUi() {
        // the code in the field: the window draws none of it
        preeditUi.update(service.forStrip(inputPanel))
        preeditUi.root.visibility = if (preeditUi.visible) VISIBLE else GONE
        candidatesUi.update(paged, orientation)
        updateVisibility()
        // typed on mid-drag: the button is no longer alone, the window follows the cursor again
        if (!keyboardButtonAlone()) dragging = false
        shouldUpdatePosition = true
    }

    /**
     * Shown with nothing typed too, the keyboard button alone beside the cursor. With no cursor told, at
     * the top right, not the bottom-left stand-in, where it would cover an app's button. Hidden is
     * INVISIBLE, not GONE: RecyclerView won't update its items when ancestor view is GONE.
     */
    private fun updateVisibility() {
        visibility = VISIBLE
    }

    private var bottomInsets = 0
    private var topInsets = 0

    private fun updatePosition() {
        if (visibility != VISIBLE || dragging) {
            // skip unnecessary updates; a drag places it itself
            return
        }
        if (!isAttachedToWindow) {
            // a hardware key with the IME window not up (split screen): placed at its first draw
            shouldUpdatePosition = true
            return
        }
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            // panic, bail
            translationX = 0f
            translationY = 0f
            return
        }
        val w: Int = width
        val h: Int = height
        if (keyboardButtonAlone()) {
            placeAlone(w, h)
            return
        }
        val (tX, tY) = underCursor(w.toFloat(), h.toFloat())
        placeAt(tX, tY, w, h)
    }

    // above it where there is more room there
    private fun underCursor(selfWidth: Float, selfHeight: Float): Pair<Float, Float> {
        val (parentWidth, parentHeight) = parentSize
        val (horizontal, bottom, top) = anchorPosition
        val tX: Float = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val rtlOffset = parentWidth - horizontal
            if (rtlOffset + selfWidth > parentWidth) selfWidth - parentWidth else -rtlOffset
        } else {
            if (horizontal + selfWidth > parentWidth) parentWidth - selfWidth else horizontal
        }
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - bottom
        // move CandidatesView above cursor anchor, only when
        val tY: Float = if (
            bottom + selfHeight > bottomLimit   // bottom space is not enough
            && top > bottomSpace                // top space is larger than bottom
        ) top - selfHeight else bottom
        return tX to tY
    }

    // nothing typed: after the cursor on its line, where it fits. Under it, it covered a note's next line;
    // in a corner, an app's button (a chat's send); before it, what was just typed
    private fun placeAlone(w: Int, h: Int) {
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val area = area()
        val spot = if (!cursorKnown) {
            val saved = if (savedButtonX < 0f || savedButtonY < 0f) null else Spot(savedButtonX, savedButtonY)
            placement.noCursor(saved, selfWidth, selfHeight, area, dp(8).toFloat())
        } else {
            val (horizontal, bottom, top) = anchorPosition
            // RTL: after the cursor is to its left
            val fits = if (rtl) horizontal - dp(4) - selfWidth >= 0 else horizontal + dp(4) + selfWidth <= area.width
            val (tX, tY) = if (fits) {
                // RTL: laid out from the right edge, as translationX is
                val x = if (rtl) horizontal - dp(4) - area.width else horizontal + dp(4)
                x to (top + bottom - selfHeight) / 2
            } else {
                underCursor(selfWidth, selfHeight)
            }
            placement.besideCursor(toLeft(tX, w), tY, selfWidth, selfHeight, area)
        }
        placeAt(toTranslation(spot.x, w), spot.y, w, h)
    }

    private fun area() = Area(parentSize[0], parentSize[1], topInsets.toFloat(), parentSize[1] - bottomInsets)

    // translationX counts from the right edge in RTL; places, from the left
    private fun toLeft(tX: Float, w: Int) =
        FloatingButtonPlacement.toLeft(tX, w.toFloat(), parentSize[0], layoutDirection == LAYOUT_DIRECTION_RTL)

    private fun toTranslation(left: Float, w: Int) =
        FloatingButtonPlacement.toTranslation(left, w.toFloat(), parentSize[0], layoutDirection == LAYOUT_DIRECTION_RTL)

    /** Events come through touchEventReceiverWindow, which moves along, so raw (screen) places measure the drag. */
    @SuppressLint("ClickableViewAccessibility")
    private fun onButtonTouch(v: View, e: MotionEvent): Boolean {
        val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        // a drag is on only while the button is alone: updateUi ends it as that stops
        if (ending && dragging) {
            dragging = false
            dropButton()
            return true
        }
        if (!keyboardButtonAlone()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                dragStart[0] = e.rawX
                dragStart[1] = e.rawY
                dragStart[2] = toLeft(translationX, width)
                dragStart[3] = translationY
            }
            MotionEvent.ACTION_MOVE -> dragMove(v, e)
            else -> {}
        }
        return dragging
    }

    private fun dragMove(v: View, e: MotionEvent) {
        val dx = e.rawX - dragStart[0]
        val dy = e.rawY - dragStart[1]
        if (!dragging && FloatingButtonPlacement.isDrag(dx, dy, touchSlop)) {
            dragging = true
            // the press drawn and the click its up would make: cancelled, this is a drag
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            v.onTouchEvent(cancel)
            cancel.recycle()
        }
        if (!dragging) return
        val w = width
        val spot = placement.clamp(dragStart[2] + dx, dragStart[3] + dy, w.toFloat(), height.toFloat(), area())
        translationX = toTranslation(spot.x, w)
        translationY = spot.y
        // its window right under the finger, not after the settle delay: the next taps land on it
        receiverPlace[0] = translationX.roundToInt()
        receiverPlace[1] = translationY.roundToInt()
        removeCallbacks(placeReceiver)
        placeReceiver.run()
    }

    private fun dropButton() {
        val at = Spot(toLeft(translationX, width), translationY)
        placement.drop(at, area())?.let {
            savedButtonX = it.x
            savedButtonY = it.y
        }
        updatePosition()
    }

    private fun placeAt(tX: Float, tY: Float, w: Int, h: Int) {
        translationX = tX
        translationY = tY
        // update touchEventReceiverWindow's position after CandidatesView's
        val sameSize = w == receiverPlace[2] && h == receiverPlace[3]
        // a jump past the button's size (a tap elsewhere) is no scroll: the window left behind would take the
        // app's taps while taps on the button went through
        val near = FloatingButtonPlacement.isScroll(
            tX.roundToInt() - receiverPlace[0], tY.roundToInt() - receiverPlace[1], w, h, sameSize
        )
        receiverPlace[0] = tX.roundToInt()
        receiverPlace[1] = tY.roundToInt()
        receiverPlace[2] = w
        receiverPlace[3] = h
        removeCallbacks(placeReceiver)
        // following a scroll: its window (a relayout in the system's process each move) follows once the cursor rests
        if (receiverShown && near && keyboardButtonAlone()) {
            postDelayed(placeReceiver, RECEIVER_SETTLE_MS)
        } else {
            placeReceiver.run()
        }
        shouldUpdatePosition = false
    }

    private fun keyboardButtonAlone() = inputPanel.preedit.isEmpty() && paged.candidates.isEmpty() &&
            inputPanel.auxUp.isEmpty() && inputPanel.auxDown.isEmpty()

    fun updateCursorAnchor(@Size(4) anchor: FloatArray, @Size(2) parent: FloatArray) {
        val horizontal = anchor[0]
        val bottom = anchor[1]
        val top = anchor[3]
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        cursorKnown = true
        // GONE, the touch keyboard's, stays so
        if (handleEvents) updateVisibility()
        updatePosition()
    }

    // [x, bottom, x, top] in the window, then over the screen
    private val cursorPoints = floatArrayOf(0f, 0f, 0f, 0f)

    /** To the bottom-left corner where [info] has no valid place. */
    fun updateCursorAnchor(info: CursorAnchorInfo, @Size(2) decorLocation: FloatArray, @Size(2) parent: FloatArray) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            cursorPoints[0] = horizontal
            cursorPoints[1] = bounds.bottom
            cursorPoints[2] = horizontal
            cursorPoints[3] = bounds.top
        } else {
            cursorPoints[0] = info.insertionMarkerHorizontal
            cursorPoints[1] = info.insertionMarkerBottom
            cursorPoints[2] = info.insertionMarkerHorizontal
            cursorPoints[3] = info.insertionMarkerTop
        }
        if (cursorPoints.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            updateCursorAnchor(parent)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(cursorPoints)
        val (xOffset, yOffset) = decorLocation
        cursorPoints[0] -= xOffset
        cursorPoints[1] -= yOffset
        cursorPoints[2] -= xOffset
        cursorPoints[3] -= yOffset
        updateCursorAnchor(cursorPoints, parent)
    }

    fun forgetCursor() {
        cursorKnown = false
        dragging = false
        placement.newField()
        if (handleEvents) updateVisibility()
        // now, not at a draw that may not come while shown: an editor that never reports still gets the top right
        shouldUpdatePosition = true
        updatePosition()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition[0] = 0f
        anchorPosition[1] = bottom
        anchorPosition[2] = bottom
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        cursorKnown = false
        if (handleEvents) updateVisibility()
        updatePosition()
    }

    init {
        // invisible by default
        visibility = INVISIBLE

        padding = dp(4)
        background = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            shape = GradientDrawable.RECTANGLE
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        add(preeditUi.root, lParams(wrapContent, wrapContent) {
            topOfParent()
            startOfParent()
            before(keyboardButton)
            horizontalBias = 0f
            constrainedWidth = true
        })
        add(candidatesUi.root, lParams(matchConstraints, wrapContent) {
            matchConstraintMinWidth = wrapContent
            below(preeditUi.root)
            startOfParent()
            before(keyboardButton)
            bottomOfParent()
        })
        add(keyboardButton, lParams(dp(36), dp(36)) {
            endOfParent()
            bottomOfParent()
        })

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
            topInsets = insets.getInsets(WindowInsets.Type.statusBars()).top
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            dragging = false
            removeCallbacks(placeReceiver)
            receiverShown = false
            touchEventReceiverWindow.dismiss()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        removeCallbacks(placeReceiver)
        receiverShown = false
        dragging = false
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val RECEIVER_SETTLE_MS = 150L

        const val TOUCH_KEYBOARD_ICON_DP = 24
    }
}
