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
 * The coroutine scope of the InputView the component belongs to, on the main thread: cancelled
 * when that view is detached (replaced after a theme or layout setting change), so work
 * launched here does not outlive the components it touches. Work that must survive the view
 * belongs on the service's own `lifecycleScope`.
 *
 * Components that only need somewhere to launch view-scoped work should take this rather
 * than the whole [FcitxInputMethodService]: it is the difference between depending on a
 * concrete `InputMethodService` and depending on a `CoroutineScope`.
 */
fun DependencyManager.imeScope() =
    mustWrapped<UniqueComponentWrapper<CoroutineScope>, CoroutineScope>()

fun DependencyManager.theme() =
    mustWrapped<UniqueComponentWrapper<Theme>, Theme>()