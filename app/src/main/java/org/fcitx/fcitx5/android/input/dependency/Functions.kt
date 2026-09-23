/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dependency

import android.view.ContextThemeWrapper
import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.mechdancer.dependency.UniqueComponentWrapper
import org.mechdancer.dependency.manager.DependencyManager
import org.mechdancer.dependency.manager.mustWrapped


fun DependencyManager.fcitx() =
    mustWrapped<UniqueComponentWrapper<FcitxConnection>, FcitxConnection>()

fun DependencyManager.context() =
    mustWrapped<UniqueComponentWrapper<ContextThemeWrapper>, ContextThemeWrapper>()

fun DependencyManager.inputView() =
    mustWrapped<UniqueComponentWrapper<InputView>, InputView>()

fun DependencyManager.inputMethodService() =
    mustWrapped<UniqueComponentWrapper<FcitxInputMethodService>, FcitxInputMethodService>()

/**
 * The input method service's `lifecycleScope`: it lives as long as the service, not as long
 * as one input session or one InputView -- work launched here outlives both, and is only
 * cancelled when the service is destroyed.
 *
 * Components that only need somewhere to launch such work should take this rather
 * than the whole [FcitxInputMethodService]: it is the difference between depending on a
 * concrete `InputMethodService` and depending on a `CoroutineScope`.
 */
fun DependencyManager.imeScope() =
    mustWrapped<UniqueComponentWrapper<CoroutineScope>, CoroutineScope>()

fun DependencyManager.theme() =
    mustWrapped<UniqueComponentWrapper<Theme>, Theme>()