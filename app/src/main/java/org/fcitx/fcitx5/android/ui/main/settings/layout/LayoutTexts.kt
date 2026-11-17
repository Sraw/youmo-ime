/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck

/**
 * What the layouts' page says of a layout (061-custom-layout §4.2, §4.3): a check's findings in words (every one of
 * [LayoutCheck.KEYS], as the theme maker's layout page has them), what it can be used for, its 信息, an import's
 * confirmation and refusal.
 */
object LayoutTexts {

    /** [f] in words, with its line where it has one (`第 12 行：…`). */
    fun finding(ctx: Context, f: LayoutCheck.Finding): String {
        val words = words(ctx, f)
        return if (f.line > 0) ctx.getString(R.string.layout_import_line, f.line, words) else words
    }

    /** The string [key] is said with; null for one of no string of its own (based-on='s and no-way's are made of more). */
    internal fun string(key: String): Int? = STRINGS[key]

    private val STRINGS = mapOf(
        "read" to R.string.layout_finding_read,
        "newer" to R.string.layout_finding_newer,
        "id-length" to R.string.layout_finding_id_length,
        "id-built-in" to R.string.layout_finding_id_built_in,
        "id-prefix" to R.string.layout_finding_id_prefix,
        "name-length" to R.string.layout_finding_name_length,
        "family" to R.string.layout_finding_family,
        "based-on" to R.string.layout_finding_based_on,
        "too-big" to R.string.layout_finding_too_big,
        "too-many-keys" to R.string.layout_finding_too_many_keys,
        "commit-long" to R.string.layout_finding_commit_long,
        "too-many-commits" to R.string.layout_finding_too_many_commits,
        "label-long" to R.string.layout_finding_label_long,
        "include" to R.string.layout_finding_include,
        "layout-target" to R.string.layout_finding_layout_target,
        "base" to R.string.layout_finding_base,
        "scene" to R.string.layout_finding_scene,
        "blank" to R.string.layout_finding_blank,
        "name-no-slash" to R.string.layout_finding_name_no_slash,
        "name-prefix" to R.string.layout_finding_name_prefix,
        "lacks" to R.string.layout_finding_lacks,
        "groups" to R.string.layout_finding_groups,
        "hidden-chars" to R.string.layout_finding_hidden_chars,
        "command" to R.string.layout_finding_command,
        "no-way" to R.string.layout_finding_no_way,
        "narrow" to R.string.layout_finding_narrow,
        "commits" to R.string.layout_finding_commits,
        "theme-blank" to R.string.layout_finding_theme_blank,
        "unnamed" to R.string.layout_finding_unnamed,
        "held" to R.string.layout_finding_held,
        "held-letters" to R.string.layout_finding_held_letters,
        "held-popups" to R.string.layout_finding_held_popups,
        "gap-look" to R.string.layout_finding_gap_look,
        "pad-lacks" to R.string.layout_finding_pad_lacks,
        "bottom-left" to R.string.layout_finding_bottom_left,
        "split-scaled" to R.string.layout_finding_split_scaled,
    )

    private fun words(ctx: Context, f: LayoutCheck.Finding): String {
        val a = f.args
        val sep = ctx.getString(R.string.list_separator)
        return when (f.key) {
            "based-on" -> if (a.firstOrNull().isNullOrEmpty()) ctx.getString(R.string.layout_finding_based_on_missing) else ctx.getString(R.string.layout_finding_based_on, a[0])
            "too-big" -> ctx.getString(R.string.layout_finding_too_big, a.getOrElse(0) { "" }, ((a.getOrNull(1)?.toLongOrNull() ?: 0L) / KB).toString())
            "no-way" -> ctx.getString(R.string.layout_finding_no_way, a.joinToString(sep) { board(ctx, it) })
            // a key of the app's and its board by the name its key page has, whichever it is told of
            "held", "held-letters" -> ctx.getString(STRINGS.getValue(f.key), a.getOrElse(0) { "" }, a.getOrNull(1)?.let { spaced(ctx, appBoard(ctx, it)) } ?: "")
            "held-popups" -> ctx.getString(R.string.layout_finding_held_popups, a.getOrNull(0)?.let { spaced(ctx, appBoard(ctx, it)) } ?: "")
            "pad-lacks" -> ctx.getString(R.string.layout_finding_pad_lacks, a.getOrNull(0)?.let { pad(ctx, it) } ?: "", a.getOrElse(1) { "" })
            "bottom-left" -> ctx.getString(R.string.layout_finding_bottom_left, a.getOrElse(0) { "" }.split(' ').filter { it.isNotEmpty() }.joinToString(sep) { role(ctx, it) })
            else -> string(f.key)?.let { ctx.getString(it, *Array(ARGS) { i -> a.getOrElse(i) { "" } }) }
                ?: (f.key + a.joinToString("") { " $it" })
        }
    }

    private fun board(ctx: Context, b: String): String = when (b) {
        "Number" -> ctx.getString(R.string.layout_section_number)
        "Symbol" -> ctx.getString(R.string.layout_section_symbols)
        else -> b
    }

    // a board's name set between Chinese words: a space where it begins or ends with a digit or a Latin letter
    // (「是 18 键的键」, as 「第 12 行」), none where it is Chinese (「是数字键盘的键」); other languages space their own words
    // (R26 F8374)
    private fun spaced(ctx: Context, name: String): String {
        if (ctx.resources.configuration.locales[0].language != "zh" || name.isEmpty()) return name
        fun latin(c: Char) = c < '\u0080' && c.isLetterOrDigit()
        return (if (latin(name.first())) " " else "") + name + (if (latin(name.last())) " " else "")
    }

    // one of the app's boards (LayoutCheck's held: KeyData's ids) by the name its key page has
    private fun appBoard(ctx: Context, b: String): String = when (b) {
        "qwerty" -> ctx.getString(R.string.shortcut_page_full)
        "k14" -> ctx.getString(R.string.shortcut_page_g14)
        "k18" -> ctx.getString(R.string.shortcut_page_g18)
        "nine" -> ctx.getString(R.string.shortcut_page_nine)
        "zhuyin" -> ctx.getString(R.string.shortcut_page_zyfull)
        "zhuyin-nine" -> ctx.getString(R.string.shortcut_page_zy9)
        "number" -> ctx.getString(R.string.layout_section_number)
        "symbols" -> ctx.getString(R.string.layout_section_symbols)
        "emoji" -> ctx.getString(R.string.layout_board_emoji)
        "emoticon" -> ctx.getString(R.string.layout_board_emoticon)
        "emoji-search" -> ctx.getString(R.string.layout_board_emoji_search)
        "expanded-side" -> ctx.getString(R.string.layout_board_expanded_side)
        "number-row" -> ctx.getString(R.string.layout_board_number_row)
        else -> b
    }

    private fun role(ctx: Context, r: String): String = when (r) {
        "symbols" -> ctx.getString(R.string.layout_role_symbols)
        "back" -> ctx.getString(R.string.layout_role_back)
        "numbers" -> ctx.getString(R.string.layout_role_numbers)
        else -> r
    }

    /** A number pad's name (`pads=`'s word). */
    fun pad(ctx: Context, p: String): String = when (p) {
        "full" -> ctx.getString(R.string.layout_pad_full)
        "digits" -> ctx.getString(R.string.layout_pad_digits)
        "signed" -> ctx.getString(R.string.layout_pad_signed)
        "decimal" -> ctx.getString(R.string.layout_pad_decimal)
        "signed-decimal" -> ctx.getString(R.string.layout_pad_signed_decimal)
        "phone" -> ctx.getString(R.string.layout_pad_phone)
        "datetime" -> ctx.getString(R.string.layout_pad_datetime)
        else -> p
    }

    private fun im(ctx: Context, im: String): String = when (im) {
        "pinyin" -> ctx.getString(R.string.layout_im_pinyin)
        "shuangpin" -> ctx.getString(R.string.layout_im_shuangpin)
        "english" -> ctx.getString(R.string.layout_im_english)
        "table" -> ctx.getString(R.string.layout_im_table)
        else -> im
    }

    /** What a layout checked as [report] can be used for (能用在哪): its input methods' keyboards, its pads, the symbols panel. */
    fun usedFor(ctx: Context, report: LayoutCheck.Report): String {
        val sep = ctx.getString(R.string.list_separator)
        val what = when {
            report.family == "number" -> ctx.getString(R.string.layout_for_pads, report.pads.joinToString(sep) { pad(ctx, it) })
            report.family == "symbols" -> ctx.getString(R.string.layout_for_symbols)
            else -> ctx.getString(R.string.layout_for_ims, report.ims.joinToString(sep) { im(ctx, it) })
        }
        return ctx.getString(R.string.layout_used_for, what)
    }

    /** A layout's [name] as shown: what is not shown left out ([LayoutCheck.plain]), its [id] where nothing is left. */
    fun shown(name: String, id: String): String = LayoutCheck.plain(name).takeIf { it.isNotBlank() } ?: id

    // its <meta>: author, version, license, then the description; what is not shown left out (one taken unchecked, restored)
    private fun meta(ctx: Context, layout: Layout): List<String> {
        val m = layout.meta
        fun line(id: Int, v: String?) = v?.let(LayoutCheck::plain)?.takeIf { it.isNotBlank() }?.let { ctx.getString(id, it) }
        return listOfNotNull(
            line(R.string.theme_info_author, m?.author),
            line(R.string.theme_info_version, m?.version),
            line(R.string.theme_info_license, m?.license),
            line(R.string.layout_info_description, m?.description),
        )
    }

    /** The texts [shown] listed, [more] counted. */
    private fun commits(ctx: Context, shown: List<String>, more: Int): String {
        val listed = shown.joinToString(ctx.getString(R.string.list_separator)) { ctx.getString(R.string.layout_quoted, it) }
        return if (more > 0) ctx.getString(R.string.layout_import_commits_more, listed, more) else listed
    }

    /** What the import of [ok] asks the user to take (§4.2 step 3, 4). */
    fun confirm(ctx: Context, ok: LayoutImport.Verdict.Ok): String {
        val out = ArrayList<String>()
        ok.replaces?.let { old ->
            val new = ok.layout.meta?.version
            out += if (old.version != null && new != null) {
                ctx.getString(R.string.layout_import_replaces, old.name, old.version, new)
            } else {
                ctx.getString(R.string.layout_import_replaces_plain, old.name)
            }
        }
        out += meta(ctx, ok.layout)
        out += usedFor(ctx, ok.report)
        if (ok.commits.isNotEmpty()) {
            out += ctx.getString(R.string.layout_import_commits, ok.report.commits.size, commits(ctx, ok.commits, ok.moreCommits))
        }
        warnings(ctx, ok.report)?.let { out += it }
        return out.joinToString("\n")
    }

    // [report]'s warnings, but the commits' (its own line above them)
    private fun warnings(ctx: Context, report: LayoutCheck.Report): String? {
        val warnings = report.warnings.filter { it.key != "commits" }
        if (warnings.isEmpty()) return null
        return ctx.getString(R.string.theme_pack_warnings) + "\n" + warnings.joinToString("\n") { "· " + finding(ctx, it) }
    }

    /** Why [refused] is not imported: its first errors, and how many more (§4.2 step 2). */
    fun refused(ctx: Context, refused: LayoutImport.Verdict.Refused): String {
        val lines = refused.errors.map { "· " + finding(ctx, it) }.toMutableList()
        if (refused.more > 0) lines += ctx.getString(R.string.layout_import_more, refused.more)
        return lines.joinToString("\n")
    }

    /**
     * A layout of one's own's 信息 (§4.3): its metadata, what it can be used for ([report], checked as one restored is),
     * its texts, what the check tells of it (the strict check's errors among them, for one taken unchecked).
     */
    fun info(ctx: Context, layout: Layout, report: LayoutCheck.Report?): String {
        val out = ArrayList(meta(ctx, layout))
        if (report != null) {
            out += usedFor(ctx, report)
            if (report.commits.isNotEmpty()) {
                out += ctx.getString(R.string.layout_info_commits, commits(ctx, report.commits.take(LayoutImport.SHOWN), (report.commits.size - LayoutImport.SHOWN).coerceAtLeast(0)))
            }
            warnings(ctx, report)?.let { out += it }
        }
        return out.joinToString("\n")
    }

    private const val KB = 1024L

    // the most arguments a finding has: the strings take what they name
    private const val ARGS = 2
}
