/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.ui.main.settings.layout.LayoutTexts
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.skin.SkinNote
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import splitties.dimensions.dp
import java.io.File

object ThemePackDialogs {

    /** [format]: `4.0`, `3.0`, with the app version it needs past 0.6.0; what is not given is left out. */
    fun lines(ctx: Context, meta: Meta?, format: String): List<String> = listOfNotNull(
        meta?.author?.let { ctx.getString(R.string.theme_info_author, it) },
        meta?.version?.let { ctx.getString(R.string.theme_info_version, it) },
        meta?.license?.let { ctx.getString(R.string.theme_info_license, it) },
        meta?.minApp?.takeIf { ThemeReader.newer(it, FIRST_4_0_APP) }
            ?.let { ctx.getString(R.string.theme_info_format_min_app, format, it) }
            ?: ctx.getString(R.string.theme_info_format, format),
    )

    // every app reading 4.0 reads this far: not worth a word
    private const val FIRST_4_0_APP = "0.6.0"

    // Chinese joins with 、, others with a comma
    private fun listSeparator(ctx: Context) = ctx.getString(R.string.list_separator)

    private fun soundClass(key: SoundKey): Int = when (key) {
        SoundKey.LETTER -> R.string.sound_class_letter
        SoundKey.FUNCTION -> R.string.sound_class_function
        SoundKey.SPACE -> R.string.sound_class_space
        SoundKey.ENTER -> R.string.sound_class_enter
        SoundKey.BACKSPACE -> R.string.sound_class_backspace
    }

    fun confirmText(ctx: Context, pack: ThemeFilesManager.Opened.Pack): String {
        val staged = pack.staged
        val out = lines(ctx, staged.theme.meta, staged.theme.version).toMutableList()
        if (pack.replaces) out += ctx.getString(R.string.theme_pack_replaces)
        val layouts = staged.layoutReport
        if (layouts.listed.isNotEmpty()) {
            val listed = if (pack.fromSkin) R.plurals.theme_pack_skin_layouts else R.plurals.theme_pack_layouts_listed
            out += ctx.resources.getQuantityString(listed, layouts.listed.size, layouts.listed.size, layouts.listed.joinToString(listSeparator(ctx)))
            // what their keys type, what they write over, what has no room
            if (layouts.listedCommits > 0) out += ctx.getString(R.string.theme_pack_layouts_listed_commits, layouts.listedCommits)
            // a skin's are said with why, in its notes; each warning a line, as an imported layout's
            if (!pack.fromSkin) layouts.lacking.forEach { (name, lacks) ->
                out += ctx.getString(R.string.theme_pack_layouts_lacks, name) + lacks.joinToString("") { "\n· " + LayoutTexts.finding(ctx, it) }
            }
            layouts.replaced.forEach { out += ctx.getString(R.string.theme_pack_layouts_replaces, it) }
            if (layouts.notFitting > 0) {
                out += ctx.resources.getQuantityString(R.plurals.theme_pack_layouts_full, layouts.notFitting, layouts.notFitting, CustomLayoutFiles.MAX_LAYOUTS)
            }
        }
        if (layouts.used.isNotEmpty()) out += ctx.getString(R.string.theme_pack_layouts, layouts.used.joinToString(", "), layouts.commits)
        if (layouts.ignored > 0) out += ctx.resources.getQuantityString(R.plurals.theme_pack_layouts_ignored, layouts.ignored, layouts.ignored)
        if (layouts.unused.isNotEmpty()) out += ctx.getString(R.string.theme_pack_layouts_unused, layouts.unused.joinToString(", ") { it.first })
        val sounds = staged.theme.sounds.keys
        if (sounds.isNotEmpty()) {
            out += ctx.getString(R.string.theme_pack_sounds, SoundKey.entries.filter { it in sounds }.joinToString(listSeparator(ctx)) { ctx.getString(soundClass(it)) })
        }
        if (staged.warnings.isNotEmpty()) out += ctx.getString(R.string.theme_pack_warnings) + "\n" + staged.warnings.joinToString("\n") { "· $it" }
        if (pack.notes.isNotEmpty()) {
            out += ctx.getString(R.string.theme_pack_skin_notes) + "\n" + pack.notes.entries.joinToString("\n") { (note, args) -> "· " + skinNote(ctx, note, args) }
        }
        return out.joinToString("\n")
    }

    // the name the app's own layout of that id has, as a pack's layouts are named
    private fun builtInName(id: String): String? =
        CustomLayouts.sources.layouts[id]?.let { (LayoutReader.layout(it) as? Parsed.Ok)?.value?.name }

    // a count the reader did not give is left unsaid
    private fun skinNote(ctx: Context, note: SkinNote, args: List<String>): String {
        val count = args.firstOrNull()?.toIntOrNull()
        return when (note) {
            SkinNote.LAYOUT_SOME -> ctx.getString(
                R.string.theme_pack_skin_note_layout_some,
                args.joinToString(listSeparator(ctx)) { id -> builtInName(id) ?: id },
            )
            // one line each, as the caller marks the first
            SkinNote.LAYOUT_LACKS -> args.map { it.split('\t') }.joinToString("\n· ") { a ->
                ctx.getString(R.string.theme_pack_skin_note_layout_lacks, builtInName(a[0]) ?: a[0], LayoutTexts.finding(ctx, LayoutCheck.Finding(0, "lacks", a.drop(1))))
            }
            SkinNote.LAYOUT_EXTRA -> ctx.getString(
                R.string.theme_pack_skin_note_layout_extra,
                args.joinToString(listSeparator(ctx)) { id -> builtInName(id) ?: id },
            )
            SkinNote.TOOLBAR_ICONS -> count?.let { ctx.getString(R.string.theme_pack_skin_note_toolbar_icons_count, it) }
                ?: ctx.getString(R.string.theme_pack_skin_note_toolbar_icons)
            SkinNote.INCLUDES -> count?.let { ctx.getString(R.string.theme_pack_skin_note_includes_count, it) }
                ?: ctx.getString(R.string.theme_pack_skin_note_includes)
            // a note given no words yet said by its name, not a crash on the main thread
            else -> SKIN_NOTES[note]?.let(ctx::getString) ?: note.name
        }
    }

    // the notes said without arguments; a test holds it to every other note
    internal val SKIN_NOTES: Map<SkinNote, Int> = mapOf(
        SkinNote.LAYOUT to R.string.theme_pack_skin_note_layout,
        SkinNote.DECORATION to R.string.theme_pack_skin_note_decoration,
        SkinNote.ANIMATION to R.string.theme_pack_skin_note_animation,
        SkinNote.KEYBOARD_ANIMATION to R.string.theme_pack_skin_note_keyboard_animation,
        SkinNote.FRAME_ANIMATION to R.string.theme_pack_skin_note_frame_animation,
        SkinNote.TEXT_SIZE to R.string.theme_pack_skin_note_text_size,
        SkinNote.FONT to R.string.theme_pack_skin_note_font,
        SkinNote.KEY_ICONS to R.string.theme_pack_skin_note_key_icons,
        SkinNote.LETTER_IMAGES to R.string.theme_pack_skin_note_letter_images,
        SkinNote.SOUND to R.string.theme_pack_skin_note_sound,
        SkinNote.DARK_PICTURE to R.string.theme_pack_skin_note_dark_picture,
        SkinNote.NO_DARK to R.string.theme_pack_skin_note_no_dark,
        SkinNote.MORE_PERIODS to R.string.theme_pack_skin_note_more_periods,
        SkinNote.IMAGES to R.string.theme_pack_skin_note_images,
        SkinNote.MORE_THEMES to R.string.theme_pack_skin_note_more_themes,
        SkinNote.PANELS to R.string.theme_pack_skin_note_panels,
    )

    fun infoText(ctx: Context, theme: Theme): String = when (theme) {
        is Theme.Pack -> lines(ctx, theme.scene.meta, theme.scene.version).joinToString("\n")
        else -> lines(ctx, null, CUSTOM_FORMAT).joinToString("\n")
    }

    private const val CUSTOM_FORMAT = "3.0"

    /** [preview] above (a pack's, checked as an image at import). */
    fun builder(ctx: Context, title: String, text: String, preview: File?, below: View? = null): AlertDialog.Builder {
        val padding = ctx.dp(PADDING_DP)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            image(ctx, preview)?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(PREVIEW_DP))) }
            addView(TextView(ctx).apply { this.text = text })
            below?.let(::addView)
        }
        // scrolled: an AlertDialog's view does not scroll, so on a low screen the preview and text would hide [below]
        return AlertDialog.Builder(ctx).setTitle(title).setView(ScrollView(ctx).apply { addView(body) })
    }

    // at most the dialog's size: a 2048 px one decoded at a quarter
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

    fun preview(theme: Theme): File? = (theme as? Theme.Pack)?.scene?.meta?.preview?.let { File(theme.dir, it) }

    /** Its file's name (its versions are one card). */
    fun title(theme: Theme): String = (theme as? Theme.Versioned)?.file ?: theme.name

    private const val PADDING_DP = 24
    private const val PREVIEW_DP = 160
}
