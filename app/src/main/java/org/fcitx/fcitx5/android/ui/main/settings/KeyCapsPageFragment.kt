/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import splitties.dimensions.dp
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapInScrollView

/**
 * The scrolling page the key-editing settings (九键's letters and symbols, long press) draw their
 * keys on: [rebuild] fills [content] anew after each change.
 */
abstract class KeyCapsPageFragment : Fragment() {

    protected lateinit var content: LinearLayout

    protected abstract fun rebuild()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        content = ctx.verticalLayout {
            val pad = ctx.dp(16)
            setPadding(pad, ctx.dp(8), pad, pad)
        }
        return content.wrapInScrollView {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onStop() {
        // before the first unlock the keyboard reads them from device-protected storage
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }
}
