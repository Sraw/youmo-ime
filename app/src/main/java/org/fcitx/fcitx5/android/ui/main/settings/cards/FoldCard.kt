/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.cards.Accordion
import org.fcitx.fcitx5.android.utils.letGoOfFocusedField
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.resources.styledDrawable
import com.google.android.material.R as MaterialR

/**
 * A card that folds (dev/design/061-settings-cards.md §2.2, §5.1): a title row always shown — [title], what is
 * set now ([summary]), an ⓘ where it has [info], an arrow — over [content], shown while [expanded]. The title row
 * is one target and one node, a button named [title] that says its summary and whether it is open; the ⓘ is a
 * node of its own. The content scrolls inside the card when an [AccordionLayout] caps it.
 */
class FoldCard(ctx: Context, val title: String, info: CharSequence? = null) {

    val view: MaterialCardView = settingsCard(ctx)

    /** What the card holds: its owner adds to it. */
    val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(EDGE_DP), 0, ctx.dp(EDGE_DP), ctx.dp(12))
    }

    /** The title tapped (an [AccordionLayout] opens or folds the card). */
    var onTitle: (() -> Unit)? = null

    /** The ⓘ tapped. */
    var onInfo: (() -> Unit)? = null

    private val titleView = TextView(ctx).apply {
        text = title
        textSize = 14f
        setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val summaryView = TextView(ctx).apply {
        textSize = 15f
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        compoundDrawablePadding = ctx.dp(4)
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val arrow = ImageView(ctx).apply {
        setColorFilter(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // the long hint folded behind the ⓘ, at the top of the content
    private val infoView = TextView(ctx).apply {
        text = info
        textSize = 12f
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(0, 0, 0, ctx.dp(8))
        visibility = View.GONE
    }

    /** The ⓘ, where the card has [info]: a 48 dp button in the title row, said 说明. */
    val infoButton: ImageButton? = info?.let {
        ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_outline_info_24)
            setColorFilter(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
            background = styledDrawable(android.R.attr.selectableItemBackgroundBorderless)
            contentDescription = ctx.getString(R.string.settings_card_info)
            setOnClickListener { onInfo?.invoke() }
        }
    }

    /** The title row: the card's target and node. */
    val header: LinearLayout = object : LinearLayout(ctx) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // said outright: Robolectric leaves it unset; the UAT scripts find a card by its title as the text
            info.className = Button::class.java.name
            info.text = title
            AccessibilityNodeInfoCompat.wrap(info).addAction(
                AccessibilityActionCompat(
                    AccessibilityNodeInfo.ACTION_CLICK,
                    ctx.getString(if (expanded) R.string.settings_card_collapse else R.string.settings_card_expand),
                ),
            )
        }
    }

    // the content's own scroll: capped by the accordion when the titles stay put, else as high as the content
    private val scroll = object : NestedScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val c = cap
            super.onMeasure(widthMeasureSpec, if (c == null) heightMeasureSpec else MeasureSpec.makeMeasureSpec(c, MeasureSpec.AT_MOST))
        }
    }

    /** The most the content shows before it scrolls inside the card; null, all of it. Set by [applyCap]. */
    var cap: Int? = null
        private set

    /**
     * [cap] set from an [AccordionLayout]'s measure, which measures again at once: no requestLayout there (asked
     * mid-measure, it asks for another pass up to the window). The card's views are only marked to be measured
     * anew, their measure otherwise kept for the same spec; [AccordionLayout] marks its column.
     */
    internal fun applyCap(px: Int?) {
        if (cap == px) return
        cap = px
        var v: View? = scroll
        while (v != null && v !== view.parent) {
            v.forceLayout()
            v = v.parent as? View
        }
    }

    /** The content's height as measured, its scroll's: the part of the card that is not its title row. */
    val contentHeight: Int get() = if (scroll.isVisible) scroll.measuredHeight else 0

    var expanded: Boolean = false
        set(value) {
            field = value
            // folded over a focused field (下滑's text, typed in, then another title tapped): the keyboard put
            // away and the focus let go, or the keys went on typing into a field no longer shown
            if (!value) scroll.letGoOfFocusedField()
            scroll.isVisible = value
            if (!value) infoShown = false
            arrow.setImageResource(if (value) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
            say()
        }

    /** The ⓘ's text shown at the top of the content. */
    var infoShown: Boolean
        get() = infoView.isVisible
        set(value) {
            infoView.isVisible = value
        }

    private var summaryText: CharSequence = ""

    /** What is set now, one line by the title; [icon] before it (a command's, as the key draws it). */
    fun summary(text: CharSequence, icon: Drawable? = null) {
        summaryText = text
        summaryView.text = text
        icon?.let {
            val size = (summaryView.textSize * ICON_PART).toInt()
            it.setBounds(0, 0, size, size)
            it.setTint(summaryView.currentTextColor)
        }
        summaryView.setCompoundDrawablesRelative(icon, null, null, null)
        say()
    }

    /** The summary as shown: a test's. */
    val summaryShown: CharSequence get() = summaryText

    private fun say() {
        header.contentDescription = if (summaryText.isEmpty()) title else view.context.getString(R.string.swipe_ways_said, title, summaryText)
        ViewCompat.setStateDescription(header, view.context.getString(if (expanded) R.string.settings_card_expanded else R.string.settings_card_collapsed))
    }

    init {
        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(TITLE_MIN_DP)
            setPadding(ctx.dp(EDGE_DP), 0, ctx.dp(4), 0)
            background = styledDrawable(android.R.attr.selectableItemBackground)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTitle?.invoke() }
            addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(summaryView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(12) })
            infoButton?.let { addView(it, LinearLayout.LayoutParams(ctx.dp(TITLE_MIN_DP), ctx.dp(TITLE_MIN_DP))) }
            addView(arrow, LinearLayout.LayoutParams(ctx.dp(ARROW_DP), ctx.dp(ARROW_DP)).apply {
                marginStart = ctx.dp(4)
                marginEnd = ctx.dp(8)
            })
        }
        content.addView(infoView, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(ctx.dp(16))
            addView(content)
        }
        view.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        expanded = false
    }

    companion object {
        const val TITLE_MIN_DP = 48
        private const val EDGE_DP = 16
        private const val ARROW_DP = 24
        private const val ICON_PART = 1.1f
    }
}

/**
 * The key editor's cards, one open at a time ([Accordion]; §5.1), in one tree that never changes: the titles
 * stay on screen and the open card scrolls inside itself while it has [Accordion.MIN_CONTENT_DP] (× the font
 * scale) of room; with less (a phone on its side, the keyboard up) the whole scrolls, the open card uncapped.
 * Only the cap changes between the two: no view moves to another parent, so a focused field keeps its input
 * connection. The editor opens on the first card, or with all folded when the whole scrolls; after that a
 * change of room leaves the open card as it is.
 */
class AccordionLayout(ctx: Context, val cards: List<FoldCard>) : NestedScrollView(ctx) {

    private var accordion = Accordion(cards.size, Accordion.opening(fixedTitles = true))

    private val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val minContent = (ctx.dp(Accordion.MIN_CONTENT_DP) * ctx.resources.configuration.fontScale).toInt()

    /** Whether the titles stay put (the open card capped) as last measured. */
    var fixedTitles = true
        private set

    // what opens decided (decide)
    private var decided = false

    val open: Int? get() = accordion.open

    init {
        cards.forEachIndexed { i, card ->
            card.onTitle = { accordion.tap(i); show() }
            card.onInfo = {
                val shown = accordion.open == i && card.infoShown
                if (accordion.open != i) accordion.tap(i)
                show()
                card.infoShown = !shown
            }
            column.addView(card.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = ctx.dp(GAP_DP)
            })
        }
        addView(column)
        show()
    }

    /** [card] holds an error: opened, whatever was. */
    fun showError(card: Int) {
        accordion.showError(card)
        show()
    }

    private fun show() {
        cards.forEachIndexed { i, card -> card.expanded = accordion.open == i }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        cards.forEach { it.applyCap(null) }
        column.forceLayout()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            fixedTitles = true
            return
        }
        val room = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val all = column.measuredHeight
        if (all <= room) {
            fixedTitles = true
            if (decide()) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val opened = accordion.open?.let(cards::get)
        val titles = all - (opened?.contentHeight ?: 0)
        fixedTitles = Accordion.fixedTitles(room, titles, minContent)
        if (decide()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (fixedTitles && opened != null) {
            opened.applyCap(room - titles)
            column.forceLayout()
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    // the first bounded measure decides what opens: all folded where the whole scrolls; true where that changed
    // the cards, to be measured again at once (folded in the first layout instead, a landscape dialog kept the
    // open card's height, empty)
    private fun decide(): Boolean {
        if (decided) return false
        decided = true
        val start = Accordion.opening(fixedTitles)
        if (start == accordion.open) return false
        accordion = Accordion(cards.size, start)
        show()
        return true
    }

    companion object {
        private const val GAP_DP = 8
    }
}
