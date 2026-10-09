/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.core.FcitxAPI.AddonDep.Optional
import org.fcitx.fcitx5.android.core.FcitxAPI.AddonDep.Required
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonDependencyGraphTest {

    private fun addon(name: String, deps: List<String> = emptyList(), opt: List<String> = emptyList()) =
        AddonInfo(
            uniqueName = name, name = name, comment = "", category = AddonCategory.Module,
            isConfigurable = false, enabled = true, defaultEnabled = true, onDemand = false,
            dependencies = deps.toTypedArray(), optionalDependencies = opt.toTypedArray()
        )

    private fun graph(vararg addons: AddonInfo) = AddonDependencyGraph(arrayOf(*addons))

    @Test
    fun anAddonNobodyDependsOnHasNoReverseDependencies() {
        val g = graph(addon("core"), addon("ui", deps = listOf("core")))
        assertTrue(g.reverseDependencies("ui").isEmpty())
    }

    @Test
    fun anAddonThatIsNotInTheGraphHasNoReverseDependencies() {
        assertTrue(graph(addon("a")).reverseDependencies("missing").isEmpty())
    }

    @Test
    fun requiredDependenciesAreFollowedTransitively() {
        val g = graph(
            addon("core"),
            addon("mid", deps = listOf("core")),
            addon("leaf", deps = listOf("mid")),
        )
        assertEquals(listOf("mid" to Required, "leaf" to Required), g.reverseDependencies("core"))
    }

    @Test
    fun aDirectOptionalDependentIsListedButItsOwnDependentsAreNot() {
        val g = graph(
            addon("core"),
            addon("plugin", opt = listOf("core")),
            addon("onlyViaPlugin", deps = listOf("plugin")),
        )
        assertEquals(listOf("plugin" to Optional), g.reverseDependencies("core"))
    }

    @Test
    fun aDependencyCycleTerminates() {
        val g = graph(addon("a", deps = listOf("b")), addon("b", deps = listOf("a")))
        assertEquals(listOf("b" to Required), g.reverseDependencies("a"))
    }

    @Test
    fun theOptionalDependentsOfEveryDirectDependentAreFollowedAlike() {
        val addons = listOf(
            addon("core"),
            addon("a", deps = listOf("core")),
            addon("b", deps = listOf("core")),
            addon("p", opt = listOf("a")),
            addon("q", opt = listOf("b")),
            addon("x", deps = listOf("p")),
            addon("y", deps = listOf("q")),
        )
        val expected = listOf(
            "a" to Required, "b" to Required, "p" to Optional, "q" to Optional, "x" to Required, "y" to Required
        )
        assertEquals(expected, graph(*addons.toTypedArray()).reverseDependencies("core"))
        // the order the engine lists addons in decides nothing but the order of the answer
        assertEquals(expected.toSet(), graph(*addons.reversed().toTypedArray()).reverseDependencies("core").toSet())
    }

    @Test
    fun anAddonReachableTwiceIsListedOnce() {
        val g = graph(
            addon("core"),
            addon("left", deps = listOf("core")),
            addon("right", deps = listOf("core")),
            addon("top", deps = listOf("left", "right")),
        )
        val names = g.reverseDependencies("core").map { it.first }
        assertEquals(names.toSet(), setOf("left", "right", "top"))
        assertEquals(3, names.size)
    }
}
