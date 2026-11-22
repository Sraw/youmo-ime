/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.os.Bundle
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.ThisDevice
import org.fcitx.fcitx5.android.device.DeviceProfile
import org.fcitx.fcitx5.android.device.Dotprod
import org.fcitx.fcitx5.android.device.LevelReason
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.openUrl

class AboutFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy, key = PRIVACY_KEY) {
                requireContext().openUrl(Const.privacyPolicyUrl)
            }
            addPreference(
                R.string.open_source_licenses,
                R.string.licenses_of_third_party_libraries,
                key = LICENSES_KEY,
            ) {
                navigateWithAnim(SettingsRoute.License)
            }
            addPreference(R.string.source_code, R.string.github_repo, key = SOURCE_KEY) {
                requireContext().openUrl(Const.githubRepo)
            }
            addPreference(R.string.license, Const.licenseSpdxId, key = LICENSE_KEY) {
                requireContext().openUrl(Const.licenseUrl)
            }
            addCategory(R.string.version) {
                isIconSpaceReserved = false
                addPreference(R.string.current_version, Const.versionName, key = VERSION_KEY)
                val commitPage = commitUrl(Const.githubRepo, BuildConfig.BUILD_GIT_HASH)
                addPreference(
                    R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH, key = COMMIT_KEY,
                    onClick = commitPage?.let { url -> { requireContext().openUrl(url) } }
                )
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME), key = BUILT_KEY)
                addPreference(
                    R.string.engine_data,
                    "${BuildConfig.ENGINE_DATA}, ${BuildConfig.ENGINE_WORDS}, ${BuildConfig.SENTENCE_MODELS}, ${BuildConfig.ENGLISH_DATA}",
                    key = DATA_KEY,
                ) {
                    requireContext().openUrl("${Const.releasesUrl}/tag/${BuildConfig.ENGINE_DATA}")
                }
                addPreference(R.string.device_processor, deviceSummary(requireContext(), ThisDevice.profile), key = PROCESSOR_KEY)
            }
        }
    }

    companion object : SearchablePage {
        private const val PRIVACY_KEY = "privacy_policy"
        private const val LICENSES_KEY = "open_source_licenses"
        private const val SOURCE_KEY = "source_code"
        private const val LICENSE_KEY = "license"
        private const val VERSION_KEY = "current_version"
        private const val COMMIT_KEY = "build_git_hash"
        private const val BUILT_KEY = "build_time"
        private const val DATA_KEY = "engine_data"
        const val PROCESSOR_KEY = "device_processor"

        override fun searchEntries(context: Context) = listOf(
            R.string.privacy_policy to PRIVACY_KEY,
            R.string.open_source_licenses to LICENSES_KEY,
            R.string.source_code to SOURCE_KEY,
            R.string.license to LICENSE_KEY,
            R.string.current_version to VERSION_KEY,
            R.string.build_git_hash to COMMIT_KEY,
            R.string.build_time to BUILT_KEY,
            R.string.engine_data to DATA_KEY,
            R.string.device_processor to PROCESSOR_KEY,
        ).map { (title, key) -> PageEntry(context.getString(title), SearchHighlight.PREF + key) }
    }
}

/** Null when [hash] names no commit: a build made without git has "N/A". */
internal fun commitUrl(repo: String, hash: String): String? {
    val commit = hash.substringBefore('-')
    val isHash = commit.isNotEmpty() && commit.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    return if (isHash) "$repo/commit/$commit" else null
}

internal fun levelText(context: Context, level: ModelLevel): String = context.getString(
    when (level) {
        ModelLevel.LOW -> R.string.device_level_low
        ModelLevel.MEDIUM -> R.string.device_level_medium
        ModelLevel.HIGH -> R.string.device_level_high
    }
)

/** Why the automatic level is what it is. */
internal fun reasonText(context: Context, profile: DeviceProfile): String = when (profile.reason) {
    LevelReason.I8MM -> context.getString(R.string.device_reason_i8mm)
    LevelReason.NEW_BIG_CORE -> context.getString(R.string.device_reason_new_big_core)
    LevelReason.LITTLE_ONLY -> context.getString(R.string.device_reason_little_only)
    LevelReason.OFFLINE -> context.resources.getQuantityString(R.plurals.device_reason_offline, profile.unread, profile.unread)
    LevelReason.UNREAD -> context.getString(R.string.device_reason_unread)
    LevelReason.NO_DOTPROD -> context.getString(R.string.device_reason_no_dotprod)
    LevelReason.DOTPROD -> context.getString(R.string.device_reason_dotprod)
}

/** 「中档 · 8 核（4 大）· 无 dotprod · 内核 MLAL」, and why it is medium where cores were offline or unread, as the settings say. */
internal fun deviceSummary(context: Context, profile: DeviceProfile): String {
    val level = levelText(context, profile.autoLevel)
    val dotprod = context.getString(if (profile.dotprod == Dotprod.NONE) R.string.device_no_dotprod else R.string.device_dotprod)
    val cores = profile.cores
    val line = when {
        cores == 0 -> context.getString(R.string.device_processor_summary_unread, level, dotprod, profile.kernel.name)
        profile.bigCores == 0 -> context.resources.getQuantityString(R.plurals.device_processor_summary, cores, level, cores, dotprod, profile.kernel.name)
        else -> context.resources.getQuantityString(
            R.plurals.device_processor_summary_big, cores, level, cores, profile.bigCores, dotprod, profile.kernel.name,
        )
    }
    return when (profile.reason) {
        LevelReason.OFFLINE, LevelReason.UNREAD -> "$line · ${reasonText(context, profile)}"
        else -> line
    }
}
