/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PunctuationComponent :
    UniqueComponent<PunctuationComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val broadcaster: InputBroadcaster by manager.must()

    // what each key types now, as the host last said (empty while punctuation is off)
    private var loaded: Map<String, String> = emptyMap()

    // the keys that now type their own character (HostEvent.PunctuationAsTyped): the engine's call, not made again here
    private var asTyped: Set<String> = emptySet()

    /**
     * What each punctuation key types now, the host's less the keys typing themselves: a keyboard attached
     * again takes it (KeyboardWindow.attachLayout), and shows on its keys what they type.
     */
    val mapping: Map<String, String> get() = if (asTyped.isEmpty()) loaded else loaded - asTyped

    val enabled: Boolean get() = loaded.isNotEmpty()

    fun transform(p: String) = mapping.getOrDefault(p, p)

    /**
     * The host's marks ([HostEvent.StatusArea.punctuation][org.fcitx.fcitx5.android.host.HostEvent.StatusArea.punctuation],
     * worked out on its thread with the status): the keys shown as it types them, in this event, before any key typed
     * after it (显示和输出一致). Null, not said: kept as they are.
     */
    fun update(keys: Map<String, String>?) {
        if (keys == null || keys == loaded) return
        loaded = keys
        broadcaster.onPunctuationUpdate(mapping)
    }

    /** The engine's keys typing their own character now ([org.fcitx.fcitx5.android.host.HostEvent.PunctuationAsTyped]): the keys shown anew. */
    fun updateAsTyped(marks: Set<String>) {
        if (marks == asTyped) return
        asTyped = marks
        broadcaster.onPunctuationUpdate(mapping)
    }
}
