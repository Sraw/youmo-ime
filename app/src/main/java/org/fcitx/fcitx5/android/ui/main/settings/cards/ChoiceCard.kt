/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.cards.CardColumns
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.resources.styledDrawable
import com.google.android.material.R as MaterialR

/**
 * A card one of a row picks (dev/design/061-settings-cards.md §2.3, §2.8): a keyboard on the 按键字符和快捷键
 * page. Its body, [glyph] over [name], is the one target that picks it ([onPick]; a tap never changes data) and
 * reads as a RadioButton named [name], how many keys are changed in its stateDescription. A badge hung over the
 * card's top corner counts them ([edited]; none at 0), clear of the picture (§2.3). [showStrip] puts a [CheckStrip]
 * under the body (多选), the badge then gone. The card is [view]; [cell], the card with its badge, is the child of
 * a [CardGrid] that says its place in the row.
 */
class ChoiceCard(ctx: Context, val name: String, private val glyph: KeyboardGlyphView) {

    val view: MaterialCardView = settingsCard(ctx)

    /**
     * The card and its badge, a grid's cell. The badge hangs over the card's corner, past its bounds: a card
     * 76–89 dp wide left no room for it beside the picture (§2.3). The grid and what holds it must not clip it.
     */
    val cell: FrameLayout = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
    }

    var onPick: (() -> Unit)? = null

    private val nameView = TextView(ctx).apply {
        text = name
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        // two lines of a like length where it must break, not one word left alone
        breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
        textSize = CardColumns.NAME_SP.toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val badge = TextView(ctx).apply {
        textSize = BADGE_SP
        gravity = Gravity.CENTER
        minWidth = ctx.dp(BADGE_DP)
        minimumWidth = ctx.dp(BADGE_DP)
        setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        includeFontPadding = false
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnPrimary))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(BADGE_DP / 2).toFloat()
            setColor(ctx.styledColor(android.R.attr.colorPrimary))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private val body: FrameLayout = object : FrameLayout(ctx) {
        override fun getAccessibilityClassName(): CharSequence = RadioButton::class.java.name

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            // said outright: Robolectric leaves it unset, and the UAT scripts find a keyboard by it and its text
            info.className = RadioButton::class.java.name
            info.text = name
            info.isCheckable = true
            info.isChecked = selected
            (cell.parent as? CardGrid)?.itemOf(cell)?.let { AccessibilityNodeInfoCompat.wrap(info).setCollectionItemInfo(it) }
        }
    }

    /** The strip under the body while [showStrip]: the owner sets its state, count, saying and [CheckStrip.onCheck]. */
    val strip = CheckStrip(ctx)

    var selected: Boolean = false
        set(value) {
            field = value
            view.isSelected = value
            cell.isSelected = value
            look()
        }

    /** How many of its keys are changed: the badge, and said after its state. */
    var edited: Int = 0
        set(value) {
            field = value
            badge.text = "$value"
            look()
        }

    var showStrip: Boolean
        get() = strip.isVisible
        set(value) {
            strip.isVisible = value
            look()
        }

    /** The name's size in sp ([CardColumns.nameSp]). */
    var nameSp: Int = CardColumns.NAME_SP
        set(value) {
            field = value
            nameView.textSize = value.toFloat()
        }

    /** The lines the name takes at least: every card of a row of names, some of two lines, as high as the rest. */
    var nameLines: Int = 1
        set(value) {
            field = value
            nameView.minLines = value
        }

    /** The name as drawn: a test's. */
    internal val nameText: TextView get() = nameView

    /** The picture as drawn: a test's. */
    internal val glyphView: KeyboardGlyphView get() = glyph

    /** The badge as drawn: a test's. */
    internal val badgeView: TextView get() = badge

    init {
        body.apply {
            background = styledDrawable(android.R.attr.selectableItemBackground)
            isClickable = true
            isFocusable = true
            setOnClickListener { onPick?.invoke() }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ctx.dp(PAD_TOP_DP), 0, ctx.dp(PAD_DP))
                addView(glyph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(KeyboardGlyphView.HEIGHT_DP)))
                addView(nameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(4)
                    marginStart = ctx.dp(NAME_EDGE_DP)
                    marginEnd = ctx.dp(NAME_EDGE_DP)
                })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        strip.visibility = View.GONE
        view.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        cell.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // centred on the card's top edge and BADGE_OUT_DP past its end, as Material's badges: it takes no room,
        // and its bottom stays above the picture, which keeps to the middle 70% (PAD_TOP_DP)
        cell.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, ctx.dp(BADGE_DP), Gravity.TOP or Gravity.END).apply {
            topMargin = -ctx.dp(OVERHANG_DP)
            marginEnd = -ctx.dp(BADGE_OUT_DP)
        })
        look()
    }

    // the chosen card's fill and border (§2.3), the badge where it counts, and the state said
    private fun look() {
        val ctx = view.context
        view.setCardBackgroundColor(ctx.styledColor(if (selected) MaterialR.attr.colorSecondaryContainer else MaterialR.attr.colorSurfaceContainerLow))
        view.strokeWidth = ctx.dp(if (selected) 2 else 1)
        view.strokeColor = ctx.styledColor(if (selected) android.R.attr.colorPrimary else MaterialR.attr.colorOutlineVariant)
        val text = ctx.styledColor(if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurface)
        nameView.setTextColor(text)
        glyph.setColor(ctx.styledColor(if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOutline))
        badge.isVisible = edited > 0 && !showStrip
        // a stateDescription set, a reader no longer says the checked state itself: said here in full (§2.8)
        val state = ctx.getString(if (selected) R.string.shortcut_card_selected else R.string.shortcut_card_unselected)
        ViewCompat.setStateDescription(body, if (edited > 0) ctx.getString(R.string.shortcut_card_edited, state, edited) else state)
    }

    /** The body, the card's target and node: a test's. */
    internal val target: View get() = body

    companion object {
        private const val PAD_DP = 8

        // the picture's top: under the badge's bottom (BADGE_DP / 2 into the card), a little apart
        private const val PAD_TOP_DP = 10
        private const val BADGE_OUT_DP = 6
        const val NAME_EDGE_DP = 4
        private const val BADGE_DP = 16
        private const val BADGE_SP = 11f

        /** How far the badge hangs above the card: whatever holds a row of these keeps at least this much room above it. */
        const val OVERHANG_DP = BADGE_DP / 2
    }
}
