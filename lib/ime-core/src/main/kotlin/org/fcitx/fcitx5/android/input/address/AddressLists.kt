/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

/** The user's address ends (网址和邮箱), in order: [email]'s domains without `@` (`qq.com`), [url]'s with their dot (`.com`). */
data class AddressLists(val email: List<String> = DEFAULT_EMAIL, val url: List<String> = DEFAULT_URL) {

    companion object {
        const val EMAIL_MARK = "@"

        val DEFAULT_EMAIL = listOf("qq.com", "163.com", "gmail.com", "outlook.com", "126.com", "foxmail.com", "icloud.com")
        val DEFAULT_URL = listOf(".com", ".cn", ".com.cn", ".net", ".org")

        fun serialize(list: List<String>): String = list.joinToString("\n")

        /** Each entry made again ([entry]): what is not one dropped, each once. */
        fun parse(raw: String, entry: (String) -> String?): List<String> = raw.lines().mapNotNull(entry).distinct()

        /** `@QQ.com` and `qq.com` alike `qq.com`; null for no domain. */
        fun emailEntry(typed: String): String? =
            typed.trim().trimStart('@').lowercase().takeIf { isDomain(it) && '.' in it }

        /** `com` and `.COM` alike `.com`. */
        fun urlEntry(typed: String): String? =
            typed.trim().lowercase().let { if (it.startsWith('.')) it else ".$it" }.takeIf { it.length > 1 && isDomain(it.substring(1)) }

        // labels of letters, digits and hyphens (and an IDN's own letters), between single dots
        internal fun isDomain(s: String) =
            s.isNotEmpty() && s.split('.').all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } }
    }
}
