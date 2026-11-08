/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PunctuationComponent :
    UniqueComponent<PunctuationComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val fcitx by manager.fcitx()
    private val imeScope by manager.imeScope()
    private val broadcaster: InputBroadcaster by manager.must()

    // the table's mapping last loaded
    private var loaded: Map<String, String> = emptyMap()

    // the keys that now type their own character (HostEvent.PunctuationAsTyped): the engine's call, not made again here
    private var asTyped: Set<String> = emptySet()

    /**
     * What each punctuation key types now, the table's mapping less the keys typing themselves: a
     * keyboard attached again takes it (KeyboardWindow.attachLayout), and shows on its keys what they type.
     */
    val mapping: Map<String, String> get() = if (asTyped.isEmpty()) loaded else loaded - asTyped

    // an older update still loading would otherwise overwrite a newer one's mapping
    private var update: Job? = null

    var enabled: Boolean = false
        private set

    fun transform(p: String) = mapping.getOrDefault(p, p)

    fun updatePunctuationMapping(entries: List<StatusEntry>) {
        enabled = entries.any {
            // TODO: A better way to check if punctuation mapping is enabled
            it.name == "punctuation" && it.icon == "fcitx-punc-active"
        }
        update?.cancel()
        update = imeScope.launch {
            loaded = if (enabled) {
                fcitx.runOnReady {
                    val items = PunctuationManager.load(this, inputMethodEntryCached.languageCode)
                    val map = HashMap<String, String>()
                    items.forEach {
                        // use first entry as mapping value
                        if (!map.containsKey(it.key)) {
                            map[it.key] = it.mapping
                        }
                    }
                    map
                }
            } else emptyMap()
            broadcaster.onPunctuationUpdate(mapping)
        }
    }

    /** The engine's keys typing their own character now ([org.fcitx.fcitx5.android.host.HostEvent.PunctuationAsTyped]): the keys shown anew. */
    fun updateAsTyped(marks: Set<String>) {
        if (marks == asTyped) return
        asTyped = marks
        broadcaster.onPunctuationUpdate(mapping)
    }
}
