/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PunctuationComponent :
    UniqueComponent<PunctuationComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val fcitx by manager.fcitx()
    private val broadcaster: InputBroadcaster by manager.must()

    // the table's mapping now: the engine's state's (empty while punctuation is off)
    private var loaded: Map<String, String> = emptyMap()

    // the keys that now type their own character (HostEvent.PunctuationAsTyped): the engine's call, not made again here
    private var asTyped: Set<String> = emptySet()

    /**
     * What each punctuation key types now, the table's mapping less the keys typing themselves: a
     * keyboard attached again takes it (KeyboardWindow.attachLayout), and shows on its keys what they type.
     */
    val mapping: Map<String, String> get() = if (asTyped.isEmpty()) loaded else loaded - asTyped

    var enabled: Boolean = false
        private set

    fun transform(p: String) = mapping.getOrDefault(p, p)

    /**
     * The engine's status ([entries], its input method's [language]): the keys shown as it now types them,
     * in this event, before any key typed after it (显示和输出一致). The table is read once
     * ([PunctuationManager] keeps it) and, not read yet, read here at once ([runImmediately][org.fcitx.fcitx5.android.daemon.HostConnection.runImmediately]):
     * read later, the keys would show their own characters for a while the engine maps.
     */
    fun updatePunctuationMapping(entries: List<StatusEntry>, language: String) {
        enabled = entries.any {
            // TODO: A better way to check if punctuation mapping is enabled
            it.name == "punctuation" && it.icon == "fcitx-punc-active"
        }
        val now = if (enabled) table(language) else emptyMap()
        if (now == loaded) return
        loaded = now
        broadcaster.onPunctuationUpdate(mapping)
    }

    private fun table(language: String): Map<String, String> {
        val items = PunctuationManager.known(language) ?: fcitx.runImmediately { PunctuationManager.load(this, language) }
        // a key's first entry its mapping
        val map = HashMap<String, String>()
        items.forEach { map.putIfAbsent(it.key, it.mapping) }
        return map
    }

    /** The engine's keys typing their own character now ([org.fcitx.fcitx5.android.host.HostEvent.PunctuationAsTyped]): the keys shown anew. */
    fun updateAsTyped(marks: Set<String>) {
        if (marks == asTyped) return
        asTyped = marks
        broadcaster.onPunctuationUpdate(mapping)
    }
}
