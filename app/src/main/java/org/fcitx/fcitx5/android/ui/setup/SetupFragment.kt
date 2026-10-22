/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.databinding.FragmentSetupBinding
import org.fcitx.fcitx5.android.ui.main.settings.LearningRestore
import org.fcitx.fcitx5.android.utils.serializable

class SetupFragment : Fragment() {

    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable(PAGE)!! }

    // 以前用过？从备份恢复 (U-106): the file picked and restored here, the settings not reached
    // before the setup is done (review-E-ui M1); fcitx run for it alone, until the page goes
    private var fcitx: FcitxConnection? = null
    private val restore = LearningRestore(this, { fcitx ?: FcitxDaemon.connect(javaClass.name).also { fcitx = it } })
    private lateinit var pickBackup: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(restore::from) }
    }

    override fun onDestroy() {
        if (fcitx != null) FcitxDaemon.disconnect(javaClass.name)
        fcitx = null
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        sync()
        return binding.root
    }

    // called on window focus changed
    fun sync() {
        val done = page.isDone()
        with(binding) {
            hintText.text = page.getHintText(requireContext())
            actionButton.visibility = if (done) View.GONE else View.VISIBLE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
            // the icon sits where the button is: left visible it covered the button's text
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            // on the first page, where someone back from another phone starts
            restoreText.visibility = if (page == SetupPage.Enable) View.VISIBLE else View.GONE
            restoreText.setOnClickListener { pickBackup.launch(arrayOf("*/*")) }
        }
    }

    companion object {
        const val PAGE = "page"
    }

}