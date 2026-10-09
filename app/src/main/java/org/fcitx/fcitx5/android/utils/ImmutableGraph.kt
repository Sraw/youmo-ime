/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import java.util.LinkedList
import java.util.Queue

class ImmutableGraph<V, L>(
    edges: List<Edge<V, L>>
) {

    data class Edge<V, L>(val vertex1: V, val vertex2: V, val label: L)

    val vertices = edges
        .flatMap { listOf(it.vertex1, it.vertex2) }
        .distinct()

    val labels = edges
        .map { it.label }
        .distinct()

    val adjacencyMatrix = Array(vertices.size) {
        IntArray(vertices.size) { -1 }
    }.apply {
        edges.forEach { (v1, v2, l) ->
            this[vertices.indexOf(v1)][vertices.indexOf(v2)] = labels.indexOf(l)
        }
    }

    private data class Step(val vertex: Int, val label: Int, val cont: Boolean, val depth: Int)

    /**
     * @param predicate: whether to continue searching after this node, given the depth of the
     * node it was reached from (0 for [vertex])
     */
    fun bfs(vertex: V, predicate: (Int, V, L) -> Boolean = { _, _, _ -> true }): List<Pair<V, L>> {
        val start = vertices.indexOf(vertex).takeIf { it != -1 } ?: return emptyList()
        val visited = BooleanArray(vertices.size)
        val queue: Queue<Step> = LinkedList()
        val result = mutableListOf<Pair<Int, Int>>()
        visited[start] = true
        queue.add(Step(start, -1, true, 0))
        while (queue.isNotEmpty()) {
            val step = queue.remove()
            val x = step.vertex
            if (start != x)
                result.add(x to step.label)
            if (step.cont)
                visited.indices.forEach { i ->
                    val l = adjacencyMatrix[x][i].takeIf { it != -1 }
                    if (l != null && !visited[i]) {
                        queue.add(Step(i, l, predicate(step.depth, vertices[i], labels[l]), step.depth + 1))
                        visited[i] = true
                    }
                }
        }
        return result.map { (v, l) -> vertices[v] to labels[l] }
    }

}