/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Path
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment
import org.fcitx.fcitx5.android.scene.theme.Shown
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Values
import org.fcitx.fcitx5.android.utils.LowMemory
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.data.theme.Theme as AppTheme
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * Where the keys fell back to the built-in theme, none: the app's own icons are drawn. An icon is read when
 * first drawn on [ThemeAssets]' decode thread, not the main one (the largest took hundreds of ms, far past a
 * frame); till then the app's is drawn and the view told to draw again. Its length is looked at before its
 * file is read: a restored backup's pack was never held to the import's checks.
 *
 * [tinted]: the raster files drawn as a mask in the icon colour. [ahead]: the files a state the board does not show
 * yet draws, read with the board ([readAhead]) so the first lock or enter action draws the theme's.
 */
data class PackIcons(val files: Map<String, String>, val tinted: Set<String> = emptySet(), val ahead: List<Ahead> = emptyList()) {

    /** A file another state draws, a sticker or else an icon, for the keys its selector may pick. */
    data class Ahead(
        val key: String?,
        val roles: Set<String>,
        val file: String,
        val starRole: String? = null,
        val classes: Set<String> = emptySet(),
        val sticker: Boolean = false,
    ) {
        // a class other than a kind is the layout's own, which a node does not carry: taken as picked
        fun picks(k: KeyNode) = (key == null || key == k.id) && (starRole == null || starRole == k.role) &&
            roles.all { it == k.role } && classes.all { it == k.kind || it !in KINDS }
    }

    /** Null: the app's own icon of that name. */
    fun file(name: String): String? = files[name]

    /**
     * An SVG's outline or a PNG or WebP to fit [width] × [height] px, decoded to fit [decodeWidth] × [decodeHeight]
     * (one decode for places of several sizes); null as [outline]'s.
     */
    fun icon(
        file: String,
        width: Int,
        height: Int,
        ready: () -> Unit = {},
        decodeWidth: Int = width,
        decodeHeight: Int = height,
    ): PackIcon? = if (RasterIcon.named(file)) RASTERS.get(file, width, height, ready, decodeWidth, decodeHeight) else outline(file, ready)

    /** Null where gone, too long or no icon (logged once), or not read yet: [ready] told on the main thread once it is. */
    fun outline(file: String, ready: () -> Unit = {}): Outline? {
        kept[file]?.let { return it.takeIf { it !== NONE } }
        val waiting = waiters[file]
        if (waiting != null) {
            waiting += ready
            return null
        }
        waiters[file] = mutableListOf(ready)
        val generation = forgotten
        ThemeAssets.runner {
            // anything a read throws is no icon: left waiting, every frame would miss
            val read = runCatching { read(file) }.onFailure { Timber.w(it, "theme icon %s unread; the app's drawn", file) }.getOrNull() ?: NONE
            ThemeAssets.poster {
                // read before a forget (a pack's files written over): neither kept nor waited on any more
                if (generation != forgotten) return@poster
                kept[file] = read
                waiters.remove(file)?.forEach { it() }
            }
        }
        // in already where the runner and poster ran at once (tests)
        return kept[file]?.takeIf { it !== NONE }
    }

    fun pending(file: String): Boolean = file in waiters || RASTERS.pending(file)

    /** False only once its file is known not to draw: one not read yet counts as drawn. */
    fun draws(file: String): Boolean = if (RasterIcon.named(file)) !RASTERS.broken(file) else outline(file) != null || pending(file)

    fun tintMask(file: String): Boolean = file in tinted

    /** A sticker decoded to fit [width] × [height] px, null till then ([ready] told) or where it never reads. */
    fun sticker(file: String, width: Int, height: Int, ready: () -> Unit = {}): Raster? = STICKERS.get(file, width, height, ready)

    fun stickerPending(file: String): Boolean = STICKERS.pending(file)

    /** Every face's icons and stickers, and the icons a state not shown yet names, at the size the board draws them. */
    fun readAhead(keys: List<KeyNode>) {
        epoch++
        // what shows now first: past the budget, what goes unkept is a state not shown yet
        for (k in keys) k.faces[k.states]?.forEach(::ahead)
        for (k in keys) {
            for ((states, ops) in k.faces) if (states != k.states) ops.forEach(::ahead)
            if (ahead.isNotEmpty()) unshown(k)
        }
    }

    // at the size it is drawn in that state as near as the key tells: a sticker fills its content, an icon is the
    // glyph's now or else an icon key's (a mark's is smaller); one off is lent the read one till its own is in
    private fun unshown(k: KeyNode) {
        val picked = ahead.filter { it.picks(k) }
        if (picked.isEmpty()) return
        val side = ICON_DP * appContext.resources.displayMetrics.density
        val glyph = k.ops(false).filterIsInstance<Op.Icon>().map { it.box }.filter { it.width >= side / 2 }.maxByOrNull { it.width * it.height }
        val icon = glyph ?: k.content.let { Box(it.centerX - side / 2, it.centerY - side / 2, it.centerX + side / 2, it.centerY + side / 2) }
        for (a in picked) {
            val box = if (a.sticker) k.content else icon
            if (a.sticker) sticker(a.file, px(box.left, box.right), px(box.top, box.bottom))
            else icon(a.file, px(box.left, box.right), px(box.top, box.bottom))
        }
    }

    private fun ahead(op: Op) {
        when (op) {
            is Op.Icon -> file(op.name)?.let { icon(it, px(op.box.left, op.box.right), px(op.box.top, op.box.bottom)) }
            is Op.Sticker -> sticker(op.file, px(op.box.left, op.box.right), px(op.box.top, op.box.bottom))
            else -> {}
        }
    }

    // as ScenePainter rounds a box drawn unscaled
    private fun px(from: Double, to: Double) = to.roundToInt() - from.roundToInt()

    sealed interface PackIcon

    /** In the SVG's own units ([SvgSubset.Icon.fit] places them). */
    class Outline(val icon: SvgSubset.Icon, val paths: List<Path>) : PackIcon

    /**
     * Drawn [width] × [height] px, its proportions kept; [exact]: [bitmap] is that size, else another decode of the
     * file lent till the exact one is in (not to be kept: it is drawn scaled).
     */
    class Raster(val bitmap: Bitmap, val width: Int, val height: Int, val exact: Boolean = true) : PackIcon

    companion object {
        val EMPTY = PackIcons(emptyMap())

        private var last: Pair<SceneTheme, PackIcons>? = null

        // asked by each bar button: worked out once a theme
        fun of(theme: AppTheme): PackIcons {
            val drawn = (theme as? AppTheme.Pack)?.drawn ?: return EMPTY
            last?.takeIf { it.first === drawn }?.let { return it.second }
            val ahead = ahead(drawn)
            if (drawn.icons.isEmpty() && ahead.isEmpty()) return EMPTY
            return PackIcons(drawn.icons, drawn.tintedIcons.mapNotNull(drawn.icons::get).toSet(), ahead).also { last = drawn to it }
        }

        // the icons a key's state names (Shift's and enter's own names, `content`, `return-icon-*`) and the stickers a
        // state's style sets: each drawn only in a board built for that state
        private fun ahead(drawn: SceneTheme): List<Ahead> {
            val named = STATE_ICONS.mapNotNull { (name, role) -> drawn.icons[name]?.let { Ahead(null, setOf(role), it) } }
            val styled = drawn.styles.flatMap { s ->
                val keys = s.selectors.filter { it.widget == "key" && (it.part == null || it.part == "icon") }
                val icons = s.values.filter { (k, _) -> k == "content" || k.startsWith(Values.RETURN_ICON) }.values
                    .mapNotNull { ((it as? Value.OfShown)?.shown as? Shown.Icon)?.name?.let(drawn.icons::get) }
                val sticker = (s.values[STICKER] as? Value.Word)?.word?.takeIf { it != Values.NONE }
                icons.flatMap { f -> keys.map { Ahead(it.key, it.roles, f, it.starRole, it.classes) } } +
                    listOfNotNull(sticker).flatMap { f ->
                        keys.filter { it.part == null && it.states.isNotEmpty() }.map { Ahead(it.key, it.roles, f, it.starRole, it.classes, sticker = true) }
                    }
            }
            return (named + styled).distinct()
        }

        private val STATE_ICONS = listOf("shift-once" to "shift", "shift-locked" to "shift") + Icons.RETURN.map { it to "enter" }

        // the layout reader's kinds, a key's first class
        private val KINDS = setOf("letter", "function", "space", "enter")

        private const val STICKER = "sticker"

        // the engine's icon-only glyph (IconSizes.MATERIAL)
        private const val ICON_DP = 24.0

        /** Keys drawn with no board (the settings' caps): what was drawn before may make room. */
        fun newRound() {
            epoch++
        }

        private const val KEPT = 256
        private val kept = lruMap<String, Outline>(KEPT)
        private val waiters = HashMap<String, MutableList<() -> Unit>>()
        private var forgotten = 0
        private val NONE = Outline(SvgSubset.Icon(1.0, 1.0, emptyList()), emptyList())

        // a board built anew: what it no longer draws may make room
        private var epoch = 0

        /** The round the caches' kept icons are marked drawn in. */
        @VisibleForTesting
        internal val round: Int get() = epoch

        /** Null: as the phone is ([LowMemory]); tests set it. */
        @VisibleForTesting
        var lowMemory: Boolean? = null

        private val low: Boolean get() = lowMemory ?: LowMemory.on(appContext).also { lowMemory = it }

        private const val MB = 1024L * 1024

        @VisibleForTesting
        internal val RASTERS = RasterCache(
            "icon", ThemeAssetLimits.MAX_RASTER_ICON_SIDE, ThemeAssetLimits.MAX_RASTER_ICON_BYTES, { epoch },
        ) { if (low) 2 * MB else 4 * MB }

        @VisibleForTesting
        internal val STICKERS = RasterCache(
            "sticker", ThemeAssetLimits.MAX_STICKER_SIDE, ThemeAssetLimits.MAX_STICKER_BYTES, { epoch },
        ) { if (low) 3 * MB else 6 * MB }

        /** The views waiting on a read are told to draw again, as they would have been once it was in. */
        fun forget() {
            kept.clear()
            val waiting = waiters.values.flatten()
            waiters.clear()
            forgotten++
            last = null
            waiting.forEach { it() }
            RASTERS.forget()
            STICKERS.forget()
        }

        internal fun read(file: String): Outline? {
            val f = File(file)
            val length = f.length()
            if (!f.isFile || ThemeAssetLimits.icon(length) != null) {
                Timber.w("theme icon %s: %s; the app's drawn", file, if (f.isFile) "$length bytes" else "gone")
                return null
            }
            val text = runCatching { f.readText() }.getOrElse {
                Timber.w(it, "theme icon %s unread; the app's drawn", file)
                return null
            }
            return when (val read = SvgSubset.read(text)) {
                is Parsed.Ok -> Outline(read.value, read.value.paths.map { pathOf(it) })
                is Parsed.Failed -> null.also { Timber.w("theme icon %s: %s; the app's drawn", file, read.problems.firstOrNull()) }
            }
        }

        private fun pathOf(p: SvgSubset.Path): Path = Path().apply {
            fillType = if (p.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            for (s in p.segments) {
                when (s) {
                    is Segment.Move -> moveTo(s.x.toFloat(), s.y.toFloat())
                    is Segment.Line -> lineTo(s.x.toFloat(), s.y.toFloat())
                    is Segment.Quad -> quadTo(s.cx.toFloat(), s.cy.toFloat(), s.x.toFloat(), s.y.toFloat())
                    is Segment.Cubic -> cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x.toFloat(), s.y.toFloat())
                    Segment.Close -> close()
                }
            }
        }
    }
}

/**
 * PNG and WebP images decoded on [ThemeAssets]' thread at the size drawn (a box's fit, never past 3 times the file's),
 * kept least lately used within [limit] bytes (main thread). One the board drew since it was last built ([epoch]) is
 * never let go for another: past the limit the newer is not kept, else a board drawing more than fits would decode
 * forever. A file over [maxSide] or [maxBytes] is not decoded: a restored backup was never held to the import's checks.
 */
internal class RasterCache(
    private val what: String,
    private val maxSide: Int,
    private val maxBytes: Long,
    private val epoch: () -> Int,
    private val limit: () -> Long,
) {
    private data class Key(val file: String, val width: Int, val height: Int)

    private class Kept(val bitmap: Bitmap?, var drawn: Int, val raster: PackIcons.Raster? = null) {
        val bytes get() = bitmap?.allocationByteCount?.toLong() ?: 0L
    }

    private val kept = LinkedHashMap<Key, Kept>(16, LOAD, true)
    private val waiters = HashMap<Key, MutableList<() -> Unit>>()
    private val broken = HashSet<String>()
    private val natural = HashMap<String, Pair<Int, Int>>()
    private var forgotten = 0

    /** For tests: the bytes kept and the decodes made. */
    var bytes = 0L
        private set
    // counted on the decode threads, several at once
    val decodes = AtomicInteger()

    /** Decoded to fit [decodeWidth] × [decodeHeight], drawn to fit [width] × [height]; till then another decode of it lent. */
    fun get(file: String, width: Int, height: Int, ready: () -> Unit, decodeWidth: Int = width, decodeHeight: Int = height): PackIcons.Raster? {
        if (minOf(width, height, decodeWidth, decodeHeight) <= 0 || file in broken) return null
        val key = Key(file, decodeWidth, decodeHeight)
        val had = kept[key]
        // one not kept for room is tried again once the board is built anew
        if (had != null && (had.bitmap != null || had.drawn == epoch())) {
            had.drawn = epoch()
            return drawn(had, file, width, height, decodeWidth, decodeHeight) ?: lent(file, width, height)
        }
        waiters[key]?.let {
            it += ready
            return lent(file, width, height)
        }
        waiters[key] = mutableListOf(ready)
        val generation = forgotten
        ThemeAssets.runner {
            val read = runCatching { decode(file, decodeWidth, decodeHeight) }
                .onFailure { Timber.w(it, "theme %s %s not decoded", what, file) }.getOrNull() ?: Decoded(null, true)
            ThemeAssets.poster {
                if (generation != forgotten) return@poster
                keep(key, read)
                waiters.remove(key)?.forEach { it() }
            }
        }
        // in already where the runner and poster ran at once (tests)
        return kept[key]?.let { drawn(it, file, width, height, decodeWidth, decodeHeight) } ?: lent(file, width, height)
    }

    // its own decode at its size kept as one object, so a view keying drawables by it keeps one
    private fun drawn(k: Kept, file: String, width: Int, height: Int, decodeWidth: Int, decodeHeight: Int): PackIcons.Raster? {
        val b = k.bitmap ?: return null
        if (width == decodeWidth && height == decodeHeight) return k.raster
        val (w, h) = fitted(file, width, height) ?: (b.width to b.height)
        return PackIcons.Raster(b, w, h)
    }

    // a menu's or a state's size not decoded yet: the largest decode of the file in, scaled, rather than the app's
    private fun lent(file: String, width: Int, height: Int): PackIcons.Raster? {
        val (w, h) = fitted(file, width, height) ?: return null
        val other = kept.entries.filter { (k, v) -> k.file == file && v.bitmap != null }.maxByOrNull { it.key.width * it.key.height }?.value
            ?: return null
        other.drawn = epoch()
        return PackIcons.Raster(other.bitmap!!, w, h, exact = false)
    }

    private fun fitted(file: String, width: Int, height: Int): Pair<Int, Int>? = natural[file]?.let { (nw, nh) -> fit(nw, nh, width, height) }

    fun pending(file: String): Boolean = waiters.keys.any { it.file == file }

    fun broken(file: String): Boolean = file in broken

    @VisibleForTesting
    fun keptOf(file: String): Int = kept.count { (k, v) -> k.file == file && v.bitmap != null }

    fun forget() {
        kept.clear()
        broken.clear()
        natural.clear()
        bytes = 0
        val waiting = waiters.values.flatten()
        waiters.clear()
        forgotten++
        waiting.forEach { it() }
    }

    private class Decoded(val bitmap: Bitmap?, val fileFailed: Boolean, val natural: Pair<Int, Int>? = null)

    private fun keep(key: Key, read: Decoded) {
        val b = read.bitmap
        if (b == null) {
            if (read.fileFailed) broken += key.file
            return
        }
        read.natural?.let { natural[key.file] = it }
        val size = b.allocationByteCount.toLong()
        val now = epoch()
        // an earlier board's not-kept marks tell get() nothing (it decodes those again), but lent() reads past them each draw
        kept.values.removeAll { it.bitmap == null && it.drawn != now }
        val room = kept.entries.iterator()
        while (bytes + size > limit() && room.hasNext()) {
            val e = room.next().value
            if (e.drawn == now) continue
            bytes -= e.bytes
            room.remove()
        }
        if (bytes + size > limit()) {
            Timber.w("theme %s %s not kept: the board's %s over %d bytes", what, key.file, what, limit())
            kept[key] = Kept(null, now)
            return
        }
        kept[key] = Kept(b, now, PackIcons.Raster(b, b.width, b.height))
        bytes += size
    }

    private fun decode(file: String, width: Int, height: Int): Decoded {
        decodes.incrementAndGet()
        val f = File(file)
        val length = f.length()
        val size = if (f.isFile && length <= maxBytes) ThemeAssets.decoder.size(file) else null
        if (size == null || maxOf(size[0], size[1]) > maxSide) {
            Timber.w("theme %s %s: %s; not drawn", what, file, if (!f.isFile) "gone" else size?.joinToString("×") ?: "$length bytes")
            return Decoded(null, true)
        }
        val (w, h) = fit(size[0], size[1], width, height)
        var sample = 1
        while (size[0] / (sample * 2) >= w && size[1] / (sample * 2) >= h) sample *= 2
        val decoded = ThemeAssets.decoder.decode(file, sample) ?: return Decoded(null, true)
        val natural = size[0] to size[1]
        if (decoded.width == w && decoded.height == h) return Decoded(decoded, false, natural)
        return Decoded(Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }, false, natural)
    }

    private companion object {
        const val LOAD = 0.75f
        const val MAX_ENLARGED = 3.0

        fun fit(nw: Int, nh: Int, width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(width.toDouble() / nw, height.toDouble() / nh, MAX_ENLARGED)
            return maxOf(1, (nw * scale).roundToInt()) to maxOf(1, (nh * scale).roundToInt())
        }
    }
}
