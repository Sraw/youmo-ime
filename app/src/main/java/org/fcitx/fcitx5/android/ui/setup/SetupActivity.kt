/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivitySetupBinding
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.firstUndonePage
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.isLastPage

class SetupActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    private lateinit var skipButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySetupBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            windowInsets
        }
        setContentView(binding.root)
        // left by back as by skip: MainActivity, shown again, would bring it back at once
        onBackPressedDispatcher.addCallback(this) {
            skipped = true
            finish()
        }
        skipButton = binding.skipButton.apply {
            text = getString(R.string.skip)
            setOnClickListener {
                skipped = true
                finish()
            }
        }
        prevButton = binding.prevButton.apply {
            text = getString(R.string.prev)
            setOnClickListener { viewPager.currentItem -= 1 }
        }
        nextButton = binding.nextButton.apply {
            setOnClickListener {
                if (viewPager.currentItem != SetupPage.entries.size - 1)
                    viewPager.currentItem += 1
                else finish()
            }
        }
        viewPager = binding.viewpager.apply {
            adapter = Adapter()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateButtons()
            })
        }
        // skip to undone page
        firstUndonePage()?.let { viewPager.currentItem = it.ordinal }
        updateButtons()
    }

    private fun updateButtons() {
        val allDone = !SetupPage.hasUndonePage()
        val isFirstPage = viewPager.currentItem == 0
        val isLastPage = viewPager.currentItem.isLastPage()

        prevButton.visibility = if (isFirstPage) View.GONE else View.VISIBLE
        skipButton.visibility = if (allDone) View.GONE else View.VISIBLE
        nextButton.text = getString(if (isLastPage) R.string.done else R.string.next)
        nextButton.visibility = if (isLastPage && !allDone) View.GONE else View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        supportFragmentManager.fragments.forEach {
            if (it.isVisible) (it as SetupFragment).sync()
        }
        updateButtons()
    }

    private inner class Adapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment =
            SetupFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(SetupFragment.PAGE, SetupPage.valueOf(position))
                }
            }
    }

    companion object {
        // skipped in this process: not shown again till it restarts. Shown once was not enough to keep
        // it away: a user leaving it for the system's settings (a tap on the keyboard's name opens the
        // app's) came back to the app with nothing to select the keyboard
        @VisibleForTesting
        internal var skipped = false
        fun shouldShowUp() = !skipped && SetupPage.hasUndonePage()
    }
}
