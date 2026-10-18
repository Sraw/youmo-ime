/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.handwriting.BuildConfig as HandwritingBuildConfig
import org.fcitx.fcitx5.android.input.voice.VoiceFeature
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.openUrl

class LicensesFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val res = resources
        val ort = HandwritingBuildConfig.ORT_VERSION
        // the engine's data: whose text its words and model are counted from
        val engineData = listOf(
            VoiceFeature.Credit(getString(R.string.engine_data_libime), "LGPL-2.1-or-later", "https://github.com/fcitx/libime"),
            VoiceFeature.Credit(getString(R.string.engine_data_fineweb), "ODC-By-1.0", "https://huggingface.co/datasets/HuggingFaceFW/fineweb-2"),
            VoiceFeature.Credit(getString(R.string.engine_data_handwriting), "Arphic Public License", "https://github.com/skishore/makemeahanzi"),
            VoiceFeature.Credit(getString(R.string.engine_data_stroke), "LGPL-3.0-only", "https://github.com/rime/rime-stroke"),
            VoiceFeature.Credit(getString(R.string.engine_data_emoji), "Unicode-3.0", "https://www.unicode.org/license.txt"),
            VoiceFeature.Credit(getString(R.string.engine_data_chaizi), "CC-BY-3.0", "https://github.com/kfcd/chaizi"),
            // what runs the handwriting recognizers (lib/handwriting), and in a voice build the speech one
            VoiceFeature.Credit("onnxruntime:$ort", "MIT", "https://github.com/microsoft/onnxruntime/blob/main/LICENSE"),
            // others' code built into libonnxruntime.so: the notices of the release it is built from
            VoiceFeature.Credit(
                "onnxruntime third-party notices:$ort", "Apache-2.0, BSD-3-Clause, MIT and others",
                "https://github.com/microsoft/onnxruntime/blob/v$ort/ThirdPartyNotices.txt",
            ),
        )
        lifecycleScope.launch {
            // a few hundred libraries' entries: read and parsed off the main thread
            val libraries = withContext(Dispatchers.IO) {
                val jsonString = res.openRawResource(R.raw.aboutlibraries)
                    .bufferedReader()
                    .use { it.readText() }
                Libs.Builder()
                    .withJson(jsonString)
                    .build()
                    .libraries
                    .sortedBy {
                        if (it.tag == "native") it.uniqueId.uppercase() else it.uniqueId.lowercase()
                    }
            }
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                (engineData + VoiceFeature.credits(requireContext())).forEach { credit ->
                    addPreference(title = credit.title, summary = credit.licence) {
                        requireContext().openUrl(credit.url)
                    }
                }
                libraries.forEach {
                    addPreference(
                        title = "${it.uniqueId}:${it.artifactVersion}",
                        summary = it.licenses.joinToString { l -> l.spdxId ?: l.name }
                    ) {
                        showLicenseDialog(it.uniqueId, it.licenses)
                    }
                }
            }
        }
    }

    private fun showLicenseDialog(uniqueId: String, licenses: Set<License>): Boolean {
        when (licenses.size) {
            0 -> {}
            1 -> showLicenseContent(licenses.first())
            else -> {
                val licenseArray = licenses.toTypedArray()
                val licenseNames = licenseArray.map { it.spdxId ?: it.name }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(uniqueId)
                    .setItems(licenseNames) { _, idx ->
                        showLicenseContent(licenseArray[idx])
                    }
                    .setPositiveButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    private fun showLicenseContent(license: License) {
        license.url?.takeIf { it.isNotBlank() }?.let { requireContext().openUrl(it) }
    }
}
