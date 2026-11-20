/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dependency

import android.view.ContextThemeWrapper
import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputView
import org.mechdancer.dependency.UniqueComponentWrapper
import org.mechdancer.dependency.manager.DependencyManager
import org.mechdancer.dependency.manager.mustWrapped


fun DependencyManager.fcitx() =
    mustWrapped<UniqueComponentWrapper<HostConnection>, HostConnection>()

fun DependencyManager.context() =
    mustWrapped<UniqueComponentWrapper<ContextThemeWrapper>, ContextThemeWrapper>()

fun DependencyManager.inputView() =
    mustWrapped<UniqueComponentWrapper<InputView>, InputView>()

fun DependencyManager.inputMethodService() =
    mustWrapped<UniqueComponentWrapper<FcitxInputMethodService>, FcitxInputMethodService>()

/**
 * Cancelled when the input view is detached (replaced after a theme or layout setting change); work that must
 * survive the view belongs on the service's `lifecycleScope`. Registered as exactly [CoroutineScope]: a subtype
 * is not found, and a second one would be refused as a duplicate.
 */
fun DependencyManager.imeScope() =
    mustWrapped<UniqueComponentWrapper<CoroutineScope>, CoroutineScope>()

fun DependencyManager.theme() =
    mustWrapped<UniqueComponentWrapper<Theme>, Theme>()