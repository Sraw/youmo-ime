/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/**
 * The part of Rime's config compiler (librime's, which Trime builds its themes with) that puts a theme's YAML files
 * together, over [SkinYaml]'s trees: `__include: file:/path` (`file` is `file.yaml` of the pack, none: this file; a
 * trailing `?` optional), then the node's own keys over it, `__merge`, `__append`, `__patch` (one or a list, a
 * reference or a map of `path: value`) and last `<file>.custom.yaml`'s `patch:`; a key's `/+` appends or merges, `/=`
 * replaces; `@N`, `@last`, `@next`, `@before N`, `@after N` index a list. Written from Rime's documentation of its
 * config format, none of its code.
 *
 * A tree read is never changed (an alias's value is shared): what is edited is copied. Trime's own files (`trime:`,
 * `default:`) and files not in the pack are skipped ([Composed.skipped]). A skin is hostile: references at most
 * [MAX_CHAIN] deep and [MAX_REFERENCES] distinct, at most [MAX_NODES] nodes built; past one, or a node including
 * itself, a [SkinError] (BROKEN). One instance shares those limits and what it has composed over every file it is
 * asked for.
 *
 * @param files a file's tree by its id (`base` for `base.yaml`), null where the pack has none
 */
class RimeCompose(private val files: (String) -> Any?) {
    /** [root] put together, and the references skipped, as written. */
    class Composed(val root: Any?, val skipped: List<String>)

    private data class Key(val file: String, val path: List<String>)

    // a node composed; what it cost and skipped counted again where it is used again
    private class Done(val value: Any?, val cost: Int, val skipped: Set<String>)

    private val trees = HashMap<String, Any?>()
    private val done = HashMap<Key, Done>()
    private val busy = HashSet<Key>()
    private var skipped = LinkedHashSet<String>()
    private var nodes = 0
    private var chain = 0
    private var references = 0

    /** The file [root] (its id [id], which its own references and its `.custom.yaml` go by) put together. */
    fun compose(root: Any?, id: String): Composed {
        skipped = LinkedHashSet()
        trees[id] = root
        val key = Key(id, emptyList())
        val d = done[key]
        val v = if (d != null) reuse(d) else fresh(root, key)
        return Composed(v, skipped.toList())
    }

    private fun tree(id: String): Any? {
        if (id !in trees) trees[id] = files(id)
        return trees[id]
    }

    private fun reuse(d: Done): Any? {
        tick(d.cost)
        skipped += d.skipped
        return d.value
    }

    private fun fresh(raw: Any?, key: Key): Any? {
        refer()
        busy += key
        val from = nodes
        val seen = skipped
        skipped = LinkedHashSet()
        val v = node(raw, null, false, key.file, root = key.path.isEmpty())
        done[key] = Done(v, nodes - from, skipped)
        seen += skipped
        skipped = seen
        busy -= key
        return v
    }

    private fun refer() {
        if (++references > MAX_REFERENCES) throw SkinError(SkinError.Reason.BROKEN, "past $MAX_REFERENCES references")
    }

    private fun tick(n: Int = 1) {
        nodes += n
        if (nodes > MAX_NODES) throw SkinError(SkinError.Reason.BROKEN, "past $MAX_NODES nodes")
    }

    // [raw] composed in [file]; over [inherited], the value its parent's include gave it, where [merging]
    private fun node(raw: Any?, inherited: Any?, merging: Boolean, file: String, root: Boolean = false): Any? {
        tick()
        if (raw is List<*>) return raw.map { node(it, null, false, file) }
        if (raw !is Map<*, *>) return raw
        val base = raw["__include"]?.let { reference(it, file) } ?: inherited
        val own = raw.filterKeys { it !in DIRECTIVES }
        var out: Any? = if (base != null || merging) {
            merge(base ?: emptyMap<String, Any?>(), own, file)
        } else {
            tick(own.size)
            LinkedHashMap<String, Any?>().also { m -> for ((k, v) in own) if (k is String) m[k] = node(v, null, false, file) }
        }
        (raw["__merge"] as? Map<*, *>)?.let { out = merge(out as? Map<*, *> ?: emptyMap<String, Any?>(), it, file) }
        raw["__append"]?.let { out = add(out.takeUnless { it is Map<*, *> && it.isEmpty() }, node(it, null, false, file)) }
        raw["__patch"]?.let { p -> out = if (p is List<*>) p.fold(out) { t, one -> patch(t, one, file) } else patch(out, p, file) }
        if (root && !file.endsWith(CUSTOM)) out = patch(out, "$file$CUSTOM:/patch?", file)
        return out
    }

    // [own]'s keys over [target]: a map's deeper, a list's by index
    private fun merge(target: Any?, own: Map<*, *>, file: String): Any? {
        if (own.isEmpty()) return target
        return when (target) {
            is Map<*, *> -> {
                tick(target.size)
                val m = LinkedHashMap<String, Any?>()
                for ((k, v) in target) if (k is String) m[k] = v
                for ((k, v) in own) if (k is String) m[bare(k)] = leaf(k, v, m[bare(k)], file)
                m
            }
            is List<*> -> {
                tick(target.size)
                val l = target.toMutableList()
                for ((k, v) in own) {
                    val at = (k as? String)?.let { index(bare(it), l.size, l) } ?: continue
                    if (at == l.size) l += leaf(k, v, null, file) else l[at] = leaf(k, v, l[at], file)
                }
                l
            }
            else -> target
        }
    }

    // the value a key of a node's own ([k]: [raw]) leaves where [existing] was
    private fun leaf(k: String, raw: Any?, existing: Any?, file: String): Any? = when {
        k.endsWith(ADD) && raw is Map<*, *> && "__include" !in raw -> node(raw, existing ?: emptyMap<String, Any?>(), true, file)
        k.endsWith(ADD) -> add(existing, node(raw, null, false, file))
        k.endsWith(SET) -> node(raw, null, false, file)
        else -> node(raw, existing, true, file)
    }

    // [value] appended to a list or merged into a map: Rime's `/+`
    private fun add(existing: Any?, value: Any?): Any? = when {
        existing == null -> value
        existing is List<*> && value is List<*> -> {
            // the copy is new every time: appending one shared list over and over is no small theme
            tick(existing.size + value.size)
            existing + value
        }
        existing is Map<*, *> && value is Map<*, *> -> over(existing, value)
        else -> existing
    }

    // a composed map [value] merged into [target], deeper where both are maps
    private fun over(target: Map<*, *>, value: Map<*, *>): Map<String, Any?> {
        tick(target.size + value.size)
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in target) if (k is String) m[k] = v
        for ((k, v) in value) {
            if (k !is String) continue
            val key = bare(k)
            val old = m[key]
            m[key] = when {
                k.endsWith(SET) -> v
                k.endsWith(ADD) -> add(old, v)
                old is Map<*, *> && v is Map<*, *> -> over(old, v)
                else -> v
            }
        }
        return m
    }

    // one `__patch`: a reference to a map of edits, or the map itself
    private fun patch(target: Any?, p: Any?, file: String): Any? {
        val edits = when (p) {
            is String -> reference(p, file) as? Map<*, *> ?: return target
            is Map<*, *> -> p.mapValues { node(it.value, null, false, file) }
            else -> return target
        }
        var t = target
        for ((k, v) in edits) {
            if (k !is String) continue
            val path = bare(k).trimStart('/').split('/')
            // each part is a level edited by recursion: none deeper than a YAML read is
            if (path.size > SkinYaml.MAX_DEPTH) throw SkinError(SkinError.Reason.BROKEN, "a patch's path past ${SkinYaml.MAX_DEPTH} parts")
            t = edit(t, path, 0) { old -> if (k.endsWith(ADD)) add(old, v) else v }
        }
        return t
    }

    // [node] with what is at [path] (from [i]) set to [leaf] of it, copied on the way; unchanged where the path does not fit
    private fun edit(node: Any?, path: List<String>, i: Int, leaf: (Any?) -> Any?): Any? {
        if (i == path.size) return leaf(node)
        tick()
        val seg = path[i]
        if (seg.isEmpty()) return edit(node, path, i + 1, leaf)
        if (seg.startsWith("@")) {
            val l = when (node) {
                null -> ArrayList()
                is List<*> -> node.toMutableList().also { tick(it.size) }
                else -> return node
            }
            val at = index(seg, l.size, l) ?: return node
            val v = edit(l.getOrNull(at), path, i + 1, leaf)
            if (at == l.size) l += v else l[at] = v
            return l
        }
        val m = LinkedHashMap<String, Any?>()
        when (node) {
            null -> Unit
            is Map<*, *> -> {
                tick(node.size)
                for ((k, v) in node) if (k is String) m[k] = v
            }
            else -> return node
        }
        m[seg] = edit(m[seg], path, i + 1, leaf)
        return m
    }

    /** The node [ref] names, composed in its file; null (and told) where it is not in the pack. */
    private fun reference(ref: Any?, file: String): Any? {
        val text = (ref as? String)?.trim() ?: return null
        val optional = text.endsWith("?")
        val r = text.removeSuffix("?")
        val colon = r.indexOf(':')
        val id = if (colon <= 0) file else r.substring(0, colon)
        val path = (if (colon < 0) r else r.substring(colon + 1)).split('/').filter { it.isNotEmpty() }
        // a theme that is itself `trime.yaml` refers to its own keys
        if ((id != file && id in BUILT_IN) || tree(id) == null) {
            if (!optional) skipped += text
            return null
        }
        if (++chain > MAX_CHAIN) throw SkinError(SkinError.Reason.BROKEN, "references past $MAX_CHAIN deep")
        val v = try {
            at(id, path)
        } finally {
            chain--
        }
        if (v == null && !optional) skipped += text
        return v
    }

    // the node at [path] of [file], composed: through the first node there that has a directive (composed whole)
    private fun at(file: String, path: List<String>): Any? {
        val key = Key(file, path)
        done[key]?.let { return reuse(it) }
        if (key in busy) throw SkinError(SkinError.Reason.BROKEN, "a reference to itself: $file:/${path.joinToString("/")}")
        var node = tree(file)
        for (i in path.indices) {
            val upper = Key(file, path.subList(0, i))
            val d = done[upper]
            // a part of one composed is taken from it; one being composed: its own keys are what there is of it yet
            val whole = when {
                d != null -> d.value.also { skipped += d.skipped }
                upper !in busy && directed(node, file, i == 0) -> at(file, upper.path)
                else -> null
            }
            if (d != null || whole != null) {
                val v = path.subList(i, path.size).fold(whole) { v, seg -> select(v, seg) }
                // shared, not built: one node, but a reference of its own
                refer()
                tick()
                done[key] = Done(v, 1, done[upper]?.skipped.orEmpty())
                return v
            }
            node = select(node, path[i]) ?: return null
        }
        return fresh(node, key)
    }

    private fun directed(node: Any?, file: String, root: Boolean): Boolean =
        (node is Map<*, *> && DIRECTIVES.any { it in node }) || (root && !file.endsWith(CUSTOM) && tree("$file$CUSTOM") != null)

    private fun select(node: Any?, seg: String): Any? = when (node) {
        is Map<*, *> -> node[seg]
        is List<*> -> index(seg, node.size)?.let { node.getOrNull(it) }
        else -> null
    }

    /**
     * A list's index as Rime reads `@N`, `@last`, `@next`, `@before N`, `@after N` in a list of [size]: null where
     * [seg] is none or past the end. A `before`/`after` one makes room at it in [room].
     */
    private fun index(seg: String, size: Int, room: MutableList<Any?>? = null): Int? {
        if (!seg.startsWith("@")) return null
        var s = seg.substring(1)
        var after = 0
        var making = false
        when {
            s.startsWith("next") -> return size
            s.startsWith("before") -> {
                s = s.removePrefix("before")
                making = true
            }
            s.startsWith("after") -> {
                s = s.removePrefix("after")
                after = 1
                making = true
            }
        }
        s = s.trim()
        // an empty list's last is where its first goes
        val n = if (s == "last") size - 1 else s.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val at = (after.toLong() + n).coerceAtLeast(0).takeIf { it <= size }?.toInt() ?: return null
        if (making && room != null) room.add(at, null)
        return at
    }

    companion object {
        /** References nested in references (a theme on a theme on a base): real ones go three or four deep. */
        const val MAX_CHAIN = 16

        /** Distinct nodes referred to; a theme refers to one many times (a key style on every key). */
        const val MAX_REFERENCES = 256

        const val MAX_NODES = 200_000

        private val DIRECTIVES = setOf("__include", "__patch", "__append", "__merge")
        private val BUILT_IN = setOf("trime", "default")
        private const val CUSTOM = ".custom"
        private const val ADD = "/+"
        private const val SET = "/="

        private fun bare(k: String) = k.removeSuffix(ADD).removeSuffix(SET)
    }
}
