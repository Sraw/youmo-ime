/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import splitties.dimensions.dp
import java.io.File

/**
 * What a theme is (060-render-p3 T8a): a pack's metadata (`<meta>`: author, version, license, preview) before it
 * is imported, with what it warns of, and any theme of the user's on 信息.
 */
object ThemePackDialogs {

    /** [meta]'s lines and the theme's format ([format]: `4.0`, `3.0`), what is not given left out. */
    fun lines(ctx: Context, meta: Meta?, format: String): List<String> = listOfNotNull(
        meta?.author?.let { ctx.getString(R.string.theme_info_author, it) },
        meta?.version?.let { ctx.getString(R.string.theme_info_version, it) },
        meta?.license?.let { ctx.getString(R.string.theme_info_license, it) },
        ctx.getString(R.string.theme_info_format, format),
    )

    // Chinese lists with 、, others with a comma
    private fun listSeparator(ctx: Context) = if (ctx.resources.configuration.locales[0].language == "zh") "、" else ", "

    private fun soundClass(key: SoundKey): Int = when (key) {
        SoundKey.LETTER -> R.string.sound_class_letter
        SoundKey.FUNCTION -> R.string.sound_class_function
        SoundKey.SPACE -> R.string.sound_class_space
        SoundKey.ENTER -> R.string.sound_class_enter
        SoundKey.BACKSPACE -> R.string.sound_class_backspace
    }

    /** What the import of [pack] asks the user to take: its metadata, that it replaces one, what it warns of. */
    fun confirmText(ctx: Context, pack: ThemeFilesManager.Opened.Pack): String {
        val staged = pack.staged
        val out = lines(ctx, staged.theme.meta, staged.theme.version).toMutableList()
        if (pack.replaces) out += ctx.getString(R.string.theme_pack_replaces)
        val layouts = staged.layoutReport
        if (layouts.used.isNotEmpty()) out += ctx.getString(R.string.theme_pack_layouts, layouts.used.joinToString(", "), layouts.commits)
        if (layouts.unused.isNotEmpty()) out += ctx.getString(R.string.theme_pack_layouts_unused, layouts.unused.joinToString(", ") { it.first })
        val sounds = staged.theme.sounds.keys
        if (sounds.isNotEmpty()) {
            out += ctx.getString(R.string.theme_pack_sounds, SoundKey.entries.filter { it in sounds }.joinToString(listSeparator(ctx)) { ctx.getString(soundClass(it)) })
        }
        if (staged.warnings.isNotEmpty()) out += ctx.getString(R.string.theme_pack_warnings) + "\n" + staged.warnings.joinToString("\n") { "· $it" }
        return out.joinToString("\n")
    }

    /** [theme]'s 信息: a pack's metadata, a 3.0 theme's name and format. */
    fun infoText(ctx: Context, theme: Theme): String = when (theme) {
        is Theme.Pack -> lines(ctx, theme.scene.meta, theme.scene.version).joinToString("\n")
        else -> lines(ctx, null, CUSTOM_FORMAT).joinToString("\n")
    }

    private const val CUSTOM_FORMAT = "3.0"

    /** [title] and [text], with [preview] above it (a pack's; checked an image as it was imported). */
    fun builder(ctx: Context, title: String, text: String, preview: File?): AlertDialog.Builder {
        val padding = ctx.dp(PADDING_DP)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            image(ctx, preview)?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(PREVIEW_DP))) }
            addView(TextView(ctx).apply { this.text = text })
        }
        return AlertDialog.Builder(ctx).setTitle(title).setView(body)
    }

    // the preview at most the dialog's size: a 2048 px one (ThemeAssetLimits) decoded at a quarter
    private fun image(ctx: Context, file: File?): ImageView? {
        if (file == null || !file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= ctx.dp(PREVIEW_DP)) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return ImageView(ctx).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
    }

    /** A pack's preview in its folder ([Theme.Pack.dir]), if it has one. */
    fun preview(theme: Theme): File? = (theme as? Theme.Pack)?.scene?.meta?.preview?.let { File(theme.dir, it) }

    /** A pack's name as the list shows it: its file's (its versions one card). */
    fun title(theme: Theme): String = (theme as? Theme.Versioned)?.file ?: theme.name

    private const val PADDING_DP = 24
    private const val PREVIEW_DP = 160
}
