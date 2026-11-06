/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.ui.main.settings.QuickPhraseFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.views.topPadding
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        intent.dropDeepLinks()
        navController.graph = SettingsRoute.createGraph(navController)
        viewModel.toolbarTitle.observe(this) {
            supportActionBar!!.title = it
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            // the home page is the top level: no up arrow there (system back still leaves)
            supportActionBar?.setDisplayHomeAsUpEnabled(!dest.hasRoute<SettingsRoute.Index>())
        }
        // made again (a rotation, a new app language): the pages are restored, the intent was acted on
        if (savedInstanceState == null) processIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // each time it comes back, not only made: the system's own settings open this activity
        // (singleTask) over the setup, which went, and the launcher then only brings the task back
        if (intent?.action != Intent.ACTION_VIEW && SetupActivity.shouldShowUp()) {
            startActivity<SetupActivity>()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        // "minimize" the activity if we can't go back
        return navController.navigateUp() || super.onSupportNavigateUp() || moveTaskToBack(false)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_VIEW -> intent.data?.let {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pinyin_dict)
                    .setMessage(R.string.whether_import_dict)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        navController.popBackStack(SettingsRoute.Index, false)
                        navController.navigateWithAnim(SettingsRoute.PinyinDict(it))
                    }
                    .show()
            }
            Intent.ACTION_RUN -> {
                val route = try {
                    intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE)
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    // exported: another app's parcel need not read
                    Timber.w(e, "Unreadable settings route")
                    null
                }
                if (route == null) return
                // any app can send this: only the pages the keyboard itself opens
                if (!AppUtil.isLaunchRoute(route)) {
                    Timber.w("Settings route not opened from outside: ${route::class.simpleName}")
                    return
                }
                if (route is SettingsRoute.QuickPhrase) {
                    // the keyboard's own refuses a longer one before it sends it; another app's is refused here
                    if (route.content.length > QuickPhraseBook.MAX_CONTENT) return
                    // quick input's page up with its editor changed: it asks before that is dropped
                    val page = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.childFragmentManager?.primaryNavigationFragment
                    if ((page as? QuickPhraseFragment)?.takeRequest(route) == true) return
                }
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
    }

}

/**
 * Takes out of this intent the deep links Navigation opens on the first graph: MainActivity is
 * exported, and the app sends none, so they could only be another app's way to any page.
 */
// the keys are Navigation's own (restricted to its library group), and the only way to take its
// deep links out before the NavController reads them
@SuppressLint("RestrictedApi")
internal fun Intent.dropDeepLinks() {
    try {
        removeExtra(NavController.KEY_DEEP_LINK_IDS)
        removeExtra(NavController.KEY_DEEP_LINK_ARGS)
        removeExtra(NavController.KEY_DEEP_LINK_EXTRAS)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        // unreadable extras would crash Navigation as well; the app's own always read
        Timber.w(e, "Dropped unreadable intent extras")
        replaceExtras(null as Bundle?)
    }
}
