/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.resources.styledDrawable

/** A body to tap and look into, and a [CheckStrip] checking the whole group; two accessibility nodes, body then strip. */
class GroupCard(ctx: Context) {

    val view: MaterialCardView = settingsCard(ctx)

    val body = LinearLayout(ctx)

    val name = TextView(ctx).apply {
        textSize = NAME_SP
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val about = TextView(ctx).apply {
        textSize = ABOUT_SP
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    // one line, as many as fit: the rest wrap to a second line that is never shown
    val samples = TextView(ctx).apply {
        textSize = SAMPLES_SP
        setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurface))
        maxLines = 1
    }

    val strip = CheckStrip(ctx, edgeDp = EDGE_DP)

    /** Never a change. */
    var onOpen: (() -> Unit)? = null

    private var openLabel: CharSequence? = null

    init {
        val arrow = TextView(ctx).apply {
            text = "›"
            textSize = NAME_SP
            setTextColor(ctx.styledColor(MaterialR.attr.colorOnSurfaceVariant))
        }
        val title = LinearLayout(ctx).apply {
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(EDGE_DP), ctx.dp(EDGE_DP), ctx.dp(EDGE_DP), ctx.dp(8))
            background = styledDrawable(android.R.attr.selectableItemBackground)
            isClickable = true
            isFocusable = true
            id = View.generateViewId()
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(about, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
            addView(samples, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            setOnClickListener { onOpen?.invoke() }
        }
        // the body says it all: its parts are not read again
        listOf(title, name, arrow, about, samples).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        ViewCompat.setAccessibilityDelegate(body, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                openLabel?.let { info.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, it)) }
                (view.parent as? CardGrid)?.itemOf(view)?.let(info::setCollectionItemInfo)
            }
        })
        strip.id = View.generateViewId()
        // the card's two read together: its body, then its strip
        strip.accessibilityTraversalAfter = body.id
        view.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
    }

    /** Its strip, then this body. */
    fun readAfter(card: GroupCard) {
        body.accessibilityTraversalAfter = card.strip.id
    }

    /** [samples] empty: no row. */
    fun bind(name: CharSequence, about: CharSequence, samples: List<String>, said: CharSequence, open: CharSequence) {
        this.name.text = name
        this.about.text = about
        this.samples.text = samples.joinToString(" ")
        this.samples.visibility = if (samples.isEmpty()) View.GONE else View.VISIBLE
        body.contentDescription = said
        openLabel = open
    }

    companion object {
        private const val NAME_SP = 16f
        private const val ABOUT_SP = 12f
        private const val SAMPLES_SP = 18f
        private const val EDGE_DP = 12
    }
}
