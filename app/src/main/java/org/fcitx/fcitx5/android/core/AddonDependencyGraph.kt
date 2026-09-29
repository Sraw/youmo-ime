/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.utils.ImmutableGraph

/**
 * Which addons depend, directly or transitively, on a given addon.
 *
 * Plain data in, plain data out: the engine supplies [addons], and both [Fcitx] and the test
 * fake answer [FcitxAPI.getAddonReverseDependencies] through this one implementation. Not
 * thread-safe; [Fcitx] confines it to the fcitx thread.
 */
class AddonDependencyGraph(addons: Array<AddonInfo>) {

    private val graph = ImmutableGraph(
        addons.flatMap { a ->
            a.dependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Required)
            } + a.optionalDependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Optional)
            }
        }
    )

    private val cache = mutableMapOf<String, List<Pair<String, FcitxAPI.AddonDep>>>()

    fun reverseDependencies(addon: String): List<Pair<String, FcitxAPI.AddonDep>> =
        cache.getOrPut(addon) {
            graph.bfs(addon) { level, _, dep ->
                // stop when the direct child is an optional dependency
                dep == FcitxAPI.AddonDep.Required
                        || (level == 1 && dep == FcitxAPI.AddonDep.Optional)
            }
        }
}
