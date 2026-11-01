/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

/**
 * The user's two lists of the ends of addresses (「通用输入 → 网址和邮箱」), in their order: [email]'s
 * domains without their `@` (`qq.com`), [url]'s with their dot (`.com`). Each kept as [serialize]
 * writes it, one a line; an entry is what [emailEntry] or [urlEntry] makes of what the user typed.
 */
data class AddressLists(val email: List<String> = DEFAULT_EMAIL, val url: List<String> = DEFAULT_URL) {

    companion object {
        /** What an email suffix is shown and counted with, before its domain. */
        const val EMAIL_MARK = "@"

        val DEFAULT_EMAIL = listOf("qq.com", "163.com", "gmail.com", "outlook.com", "126.com", "foxmail.com", "icloud.com")
        val DEFAULT_URL = listOf(".com", ".cn", ".com.cn", ".net", ".org")

        fun serialize(list: List<String>): String = list.joinToString("\n")

        /** A list as [serialize] wrote it, each entry made again ([entry]): what is not one dropped, each once. */
        fun parse(raw: String, entry: (String) -> String?): List<String> = raw.lines().mapNotNull(entry).distinct()

        /** [typed] as an email suffix, `@QQ.com` and `qq.com` alike `qq.com`; null for what is no domain. */
        fun emailEntry(typed: String): String? =
            typed.trim().trimStart('@').lowercase().takeIf { isDomain(it) && '.' in it }

        /** [typed] as a URL suffix, `com` and `.COM` alike `.com`; null for what is none. */
        fun urlEntry(typed: String): String? =
            typed.trim().lowercase().let { if (it.startsWith('.')) it else ".$it" }.takeIf { it.length > 1 && isDomain(it.substring(1)) }

        // labels of letters, digits and hyphens (and an IDN's own letters), between single dots
        internal fun isDomain(s: String) =
            s.isNotEmpty() && s.split('.').all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } }
    }
}
