/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.JavaVersion

object Versions {

    val java = JavaVersion.VERSION_11
    const val compileSdk = 36
    const val minSdk = 29
    const val targetSdk = 36

    // the translations the app ships (gettext's names), English being the source: here once for
    // the app's resources (localeFilters) and fcitx's catalogs (FcitxComponentPlugin)
    val translations = listOf("zh_CN", "zh_TW")

    const val defaultCMake = "3.31.6"
    const val defaultNDK = "28.0.13004108"
    const val defaultBuildTools = "36.1.0"

    // NOTE: raise this for a release; the version code follows it (versionCodeOf)
    const val baseVersionName = "0.3.1"
    val baseVersionCode = versionCodeOf(baseVersionName)

    private val abiIds = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
    val supportedABIs = abiIds.keys
    const val fallbackABI = "arm64-v8a"

    fun calculateVersionCode(abi: String = fallbackABI): Int {
        val abiId = abiIds[abi] ?: 0
        return baseVersionCode * 10 + abiId
    }

    /**
     * The version code of the release [name], major.minor.patch, so that raising the name raises the
     * code: 12 at 0.1.0, the first release (past the test builds before it, which carried upstream's
     * 11, so they update in place), 1 more a patch, 100 a minor, 10,000 a major.
     */
    internal fun versionCodeOf(name: String): Int {
        // minor and patch below 100: 0.1.100 would have 0.2.0's code
        val (major, minor, patch) = requireNotNull(Regex("(\\d+)\\.(\\d{1,2})\\.(\\d{1,2})").matchEntire(name)) {
            "version name $name: not major.minor.patch with minor and patch below 100"
        }.destructured.toList().map(String::toInt)
        val code = 12 + major * 10_000 + (minor - 1) * 100 + patch
        require(code >= 12) { "version name $name: before 0.1.0, the first release" }
        return code
    }

    /**
     * [versionName] (as appVersionName makes it of the tag), once checked: a release's, built at its
     * tag, must be [baseVersionName]. Tagged without raising it, the release would carry the last
     * one's version code ([baseVersionCode] follows the name), which an updater comparing codes takes
     * for no update.
     */
    fun checkedVersionName(versionName: String): String {
        check(!RELEASE_NAME.matches(versionName) || versionName == baseVersionName) {
            "built at v$versionName with baseVersionName $baseVersionName: raise baseVersionName in Versions.kt (baseVersionCode follows it)"
        }
        return versionName
    }

    // a release's name at its tag: 0.1.0, not 0.1.0-5-gabc1234 past it nor 0.1.0-untagged
    private val RELEASE_NAME = Regex("\\d+(\\.\\d+)*")
}
