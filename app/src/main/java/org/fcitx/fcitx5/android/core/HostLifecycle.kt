/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

class HostLifecycleRegistry : HostLifecycle {

    private val internalStateFlow = MutableStateFlow(HostLifecycle.State.STOPPED)

    override val stateFlow = internalStateFlow.asStateFlow()

    override val currentState: HostLifecycle.State
        get() = internalStateFlow.value

    private val job = SupervisorJob()

    override val lifecycleScope = CoroutineScope(job + Dispatchers.Default)

    fun postEvent(event: HostLifecycle.Event) {
        val newState = internalStateFlow.updateAndGet {
            when (event) {
                HostLifecycle.Event.ON_START -> {
                    ensureAt(it, HostLifecycle.State.STOPPED)
                    HostLifecycle.State.STARTING
                }
                HostLifecycle.Event.ON_READY -> {
                    ensureAt(it, HostLifecycle.State.STARTING)
                    HostLifecycle.State.READY
                }
                HostLifecycle.Event.ON_STOP -> {
                    ensureAt(it, HostLifecycle.State.READY)
                    HostLifecycle.State.STOPPING
                }
                HostLifecycle.Event.ON_STOPPED -> {
                    ensureAt(it, HostLifecycle.State.STOPPING)
                    HostLifecycle.State.STOPPED
                }
            }
        }
        if (newState >= HostLifecycle.State.STOPPING) {
            job.cancelChildren()
        }
    }

    private fun ensureAt(currentState: HostLifecycle.State, state: HostLifecycle.State) {
        if (currentState != state) {
            throw IllegalStateException("Currently not at $state!")
        }
    }
}

interface HostLifecycle {
    val stateFlow: StateFlow<State>
    val currentState: State
    val lifecycleScope: CoroutineScope

    enum class State {
        STARTING,
        READY,
        STOPPING,
        STOPPED
    }

    enum class Event {
        ON_START,
        ON_READY,
        ON_STOP,
        ON_STOPPED
    }
}

interface HostLifecycleOwner {
    val lifecycle: HostLifecycle
}

val HostLifecycleOwner.lifeCycleScope
    get() = lifecycle.lifecycleScope

suspend inline fun <T> HostLifecycle.whenAtState(
    state: HostLifecycle.State,
    block: suspend CoroutineScope.() -> T
): T {
    stateFlow.first { it == state }
    return block(lifecycleScope)
}

suspend inline fun <T> HostLifecycle.whenReady(noinline block: suspend CoroutineScope.() -> T) =
    whenAtState(HostLifecycle.State.READY, block)

fun <T> HostLifecycle.launchWhenReady(block: suspend CoroutineScope.() -> T) =
    lifecycleScope.launch { whenReady(block) }
