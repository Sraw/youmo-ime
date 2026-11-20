/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.input.theme.BoxBlur
import org.fcitx.fcitx5.android.input.theme.KeyImageBudget
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A theme's decoded files, kept till another theme is drawn. Decoding happens outside the lock, so font or key-image
 * requests never wait on a picture; a view finding the picture decoding elsewhere draws without it till it is in.
 */
object ThemeAssets {

    interface Decoder {
        fun size(path: String): IntArray?

        fun decode(path: String, sample: Int): Bitmap?

        fun typeface(path: String): Typeface?
    }

    private object AndroidDecoder : Decoder {
        override fun size(path: String): IntArray? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
        }

        override fun decode(path: String, sample: Int): Bitmap? =
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })

        override fun typeface(path: String): Typeface? =
            runCatching { Typeface.Builder(File(path)).build() }.getOrNull()
    }

    @VisibleForTesting
    var decoder: Decoder = AndroidDecoder

    private val background: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ThemeAssets").apply { isDaemon = true }
    }

    @VisibleForTesting
    var runner: (Runnable) -> Unit = { background.execute(it) }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @VisibleForTesting
    var poster: (Runnable) -> Unit = { main.post(it) }

    const val BOARDS_LIMIT = 24L * 1024 * 1024

    /** About a screen of the theme list's thumbnails and some. */
    const val THUMBNAILS_LIMIT = 12L * 1024 * 1024

    private val lock = Any()

    // another look of the same files (a colour, brightness or blur changed) decodes nothing again
    private var look: ThemeLook? = null
    private var files: List<String>? = null

    // a blur's radius is in picture pixels, so made per drawn width (widths within a tenth share one); the sharp one is width 0
    private data class PictureKey(val landscape: Boolean, val blurDp: Int, val width: Int)

    // decoded by the first to take its own lock, outside the cache's; [waiting] are views told when it is in
    private class PictureSlot {
        val decoding = ReentrantLock()

        @Volatile
        var done = false
        var bitmap: Bitmap? = null
        val waiting = ArrayList<() -> Unit>()

        var queued = false
    }

    class Got internal constructor(val bitmap: Bitmap?)

    // access order: the least lately drawn widths' blurs go first
    private val pictures = LinkedHashMap<PictureKey, PictureSlot>(16, 0.75f, true)
    private val boards = LinkedHashMap<String, BoardImages>(8, 0.75f, true)
    private val fonts = HashMap<Pair<String?, Int>, Typeface?>()

    /** Bumped when files are dropped as changed: a bitmap held from an older generation may be of files rewritten since. */
    @Volatile
    var generation = 0
        private set

    private fun use(look: ThemeLook) {
        if (this.look === look) return
        this.look = look
        val files = look.files()
        if (this.files == files) return
        this.files = files
        pictures.clear()
        boards.clear()
        fonts.clear()
    }

    /** Drops another theme's files even if [look] has none. */
    fun retain(look: ThemeLook) = synchronized(lock) { use(look) }

    fun clear() = synchronized(lock) {
        generation++
        look = null
        files = null
        pictures.clear()
        boards.clear()
        fonts.clear()
        thumbnails.clear()
    }

    private fun current(look: ThemeLook) = this.look === look || files != null && look.files() == files

    /** Decoded about as wide as drawn and within [MAX_PIXELS] (a 4096 cut is 64 MB whole). */
    fun picture(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float): Bitmap? {
        synchronized(lock) { use(look) }
        return pictureOf(look, landscape, blurDp, widthPx, density, null)?.bitmap
    }

    /** Null till the decode thread has it, [then] told on the main thread: a view does not wait a frame on a 4096 picture. */
    fun pictureOrLater(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float, then: () -> Unit): Got? {
        synchronized(lock) { use(look) }
        return pictureOf(look, landscape, blurDp, widthPx, density, then)
    }

    /** Only if [look] is still drawn: a late prefetch after another pick decodes nothing. */
    private fun pictureOf(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float, then: (() -> Unit)?): Got? {
        if (blurDp > 0 && then != null) {
            // the sharp one first, without waiting: the blur's maker would wait on it
            pictureOf(look, landscape, 0, widthPx, density, then) ?: return null
        }
        val slot = synchronized(lock) {
            if (look.picture == null || !current(look)) return Got(null)
            val key = PictureKey(landscape, blurDp, if (blurDp == 0) 0 else widthBucket(widthPx))
            pictures[key] ?: PictureSlot().also {
                // another width's blur is dropped only past the last few widths and never while waited for, else two views at two
                // widths would keep dropping each other's
                letGoBeforeAdding(landscape, blurDp)
                pictures[key] = it
            }
        }
        if (slot.done) return Got(slot.bitmap)
        if (then == null) return fill(slot, look, landscape, blurDp, widthPx, density)
        return queueFill(slot, look, landscape, blurDp, widthPx, density, then)
    }

    // Got if it came in meanwhile, else null and [then] told later
    @Suppress("LongParameterList")
    private fun queueFill(
        slot: PictureSlot, look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float,
        then: () -> Unit,
    ): Got? {
        // a view never decodes or blurs on its own thread
        val free = slot.decoding.tryLock()
        val post = synchronized(lock) {
            if (slot.done) return Got(slot.bitmap).also { if (free) slot.decoding.unlock() }
            slot.waiting += then
            (free && !slot.queued).also { if (it) slot.queued = true }
        }
        if (free) slot.decoding.unlock()
        if (post) {
            // this slot even if dropped meanwhile: its waiting views are told from it
            runner {
                if (synchronized(lock) { current(look) }) fill(slot, look, landscape, blurDp, widthPx, density)
            }
        }
        return null
    }

    private fun letGoBeforeAdding(landscape: Boolean, blurDp: Int) {
        pictures.keys.removeAll { k -> k.landscape != landscape }
        if (blurDp == 0) return
        // a slot waited for is never done (fill sets done and empties waiting under the lock)
        val others = pictures.entries.filter { (k, v) -> k.blurDp == blurDp && v.done && v.waiting.isEmpty() }
        val over = pictures.keys.count { k -> k.blurDp == blurDp } + 1 - BLUR_WIDTHS_KEPT
        others.take(maxOf(0, over)).forEach { e -> pictures.remove(e.key) }
    }

    private fun fill(slot: PictureSlot, look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float): Got {
        slot.decoding.lock()
        val told = try {
            if (slot.done) return Got(slot.bitmap)
            val b = decodeSafely("picture") { decodePicture(look, landscape, blurDp, widthPx, density) }
            synchronized(lock) {
                slot.bitmap = b
                slot.done = true
                slot.waiting.toList().also { slot.waiting.clear() }
            }
        } finally {
            slot.decoding.unlock()
        }
        if (told.isNotEmpty()) poster(Runnable { told.forEach { it() } })
        return Got(slot.bitmap)
    }

    private fun decodePicture(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float): Bitmap? {
        val p = look.picture ?: return null
        return if (blurDp == 0) {
            decodePicture(if (landscape) p.landscape?.file ?: p.portrait.file else p.portrait.file, widthPx)
        } else {
            pictureOf(look, landscape, 0, widthPx, density, null)?.bitmap?.let { blurred(it, blurDp, widthPx, density) }
        }
    }

    // log base 1.1: steps of about a tenth
    private fun widthBucket(widthPx: Int) = if (widthPx > 0) (ln(widthPx.toDouble()) / ln(WIDTH_STEP)).roundToInt() else 0

    private fun decodePicture(path: String, widthPx: Int): Bitmap? {
        val size = decoder.size(path) ?: return null.also { Timber.w("Theme picture $path does not read") }
        return decoder.decode(path, pictureSample(size[0], size[1], widthPx))
    }

    /** Halved till within [MAX_PIXELS] and [MAX_SIDE] high (a tall picture over a wide keyboard shows its middle only). */
    @VisibleForTesting
    fun pictureSample(w: Int, h: Int, widthPx: Int): Int {
        var sample = 1
        while (true) {
            val wide = widthPx > 0 && w / (sample * 2) >= widthPx
            val big = (w / sample).toLong() * (h / sample) > MAX_PIXELS || h / sample > MAX_SIDE
            if (!wide && !big) return sample
            sample *= 2
        }
    }

    // a failed or out-of-memory decode is none: the theme drawn with its colours
    private inline fun <T> decodeSafely(what: String, f: () -> T?): T? =
        try {
            f()
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "Theme $what not decoded: out of memory")
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Timber.w(e, "Theme $what not decoded")
            null
        }

    // as BoxBlur does, on a quarter a side
    private fun blurred(b: Bitmap, blurDp: Int, widthPx: Int, density: Float): Bitmap {
        val w = b.width
        val h = b.height
        val pixels = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val small = BoxBlur.shrink(pixels, w, h, BLUR_SHRINK)
        val sw = maxOf(1, w / BLUR_SHRINK)
        val sh = maxOf(1, h / BLUR_SHRINK)
        val out = BoxBlur.blur(small, sw, sh, blurRadius(blurDp, widthPx, w, density))
        return Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
    }

    @VisibleForTesting
    fun blurRadius(blurDp: Int, widthPx: Int, bitmapWidth: Int, density: Float): Int {
        val pxPerBitmapPx = if (widthPx > 0) widthPx.toFloat() / bitmapWidth else 1f
        return maxOf(1, (blurDp * density / pxPerBitmapPx / BLUR_SHRINK).roundToInt())
    }

    @VisibleForTesting
    fun pictureCount(): Int = synchronized(lock) { pictures.size }

    fun boardImages(look: ThemeLook, board: String?, files: Collection<NineSlice>): BoardImages {
        val paths = files.map { it.file }.distinct().sorted()
        synchronized(lock) {
            use(look)
            if (paths.isEmpty()) return BoardImages.NONE
            val key = "$board:" + paths.joinToString("\u0000")
            boards[key]?.let { return it }
            val images = BoardImages()
            boards[key] = images
            runner {
                val decoded = decodeSafely("key images") { decodeBoard(paths) } ?: BoardImages.Decoded(emptyMap(), emptyMap())
                synchronized(lock) {
                    images.done(decoded)
                    trim(keep = images)
                }
            }
            return images
        }
    }

    private fun decodeBoard(paths: List<String>): BoardImages.Decoded {
        val sizes = paths.mapNotNull { p -> decoder.size(p)?.let { p to it } }.toMap()
        val sample = KeyImageBudget.sampleSize(sizes.map { (p, s) -> KeyImageBudget.Image(p, s[0], s[1]) })
        val bitmaps = sizes.keys.mapNotNull { p -> decodeSafely("key image $p") { decoder.decode(p, sample) }?.let { p to it } }.toMap()
        if (sample > 1) Timber.i("Key images of ${bitmaps.size} files decoded at 1/$sample: over ${KeyImageBudget.LIMIT_BYTES} bytes")
        return BoardImages.Decoded(bitmaps, sizes)
    }

    private fun trim(keep: BoardImages) = trimToLimit(boards, BOARDS_LIMIT, keep, { it.bytes })

    // under the lock; [keep] (just decoded) and those [inUse] stay
    private fun <V> trimToLimit(cache: LinkedHashMap<String, V>, limit: Long, keep: V, bytes: (V) -> Long, inUse: (V) -> Boolean = { false }) {
        var total = cache.values.sumOf(bytes)
        val entries = cache.entries.iterator()
        while (total > limit && entries.hasNext()) {
            val entry = entries.next().value
            if (entry === keep || inUse(entry)) continue
            total -= bytes(entry)
            entries.remove()
        }
    }

    @VisibleForTesting
    fun boardBytes(): Long = synchronized(lock) { boards.values.sumOf { it.bytes } }

    /** Null result means the system's as is, so a theme of no fonts draws as before. */
    fun typeface(look: ThemeLook, path: String?, weight: Int): Typeface? {
        synchronized(lock) {
            // the system's font is no theme's file: asked by another look, it must not drop the drawn look's files
            if (path != null) use(look)
            if (path == null && weight == NORMAL_WEIGHT) return null
            val key = path to weight
            if (key in fonts) return fonts[key]
            val base = path?.let { decoder.typeface(it) ?: null.also { Timber.w("Theme font $path does not read") } }
                ?: Typeface.DEFAULT
            val t = Typeface.create(base, weight, false)
            fonts[key] = t
            return t
        }
    }

    fun candidateTypeface(look: ThemeLook): Typeface? = typeface(look, look.text.candidateFont, look.text.candidateWeight)

    fun prefetch(look: ThemeLook, landscape: Boolean, widthPx: Int, density: Float) {
        retain(look)
        val blurs = look.pictureBlurs()
        if (blurs.isEmpty()) return
        // the sharp one first, as blurs are made from it
        runner { (listOf(0) + blurs).distinct().forEach { pictureOf(look, landscape, it, widthPx, density, null) } }
    }

    private class Thumbnail {
        var done = false
        var bitmap: Bitmap? = null
        val waiting = ArrayList<(Bitmap?) -> Unit>()
        val bytes get() = bitmap?.allocationByteCount?.toLong() ?: 0L
    }

    private val thumbnails = LinkedHashMap<String, Thumbnail>(8, 0.75f, true)

    /** On the assets' thread (a 4096 picture on the main thread was 64 MB and a stutter). The caller applies brightness. */
    fun thumbnail(look: ThemeLook, widthPx: Int, screenPx: Int, density: Float, then: (Bitmap?) -> Unit) {
        val p = look.picture ?: return then(null)
        val path = p.portrait.file
        val key = "$path:${p.blurDp}:$widthPx"
        val now = synchronized(lock) {
            val t = thumbnails[key]
            if (t != null && t.done) return@synchronized t
            if (t != null) {
                t.waiting += then
                return
            }
            val made = Thumbnail().also { it.waiting += then }
            thumbnails[key] = made
            runner {
                val b = decodeSafely("thumbnail") { decodeThumbnail(path, p.blurDp, widthPx, screenPx, density) }
                val told = synchronized(lock) {
                    made.bitmap = b
                    made.done = true
                    // a failure is not kept: asked again, tried again
                    if (b == null && thumbnails[key] === made) thumbnails.remove(key)
                    trimThumbnails(keep = made)
                    made.waiting.toList().also { made.waiting.clear() }
                }
                poster(Runnable { told.forEach { it(b) } })
            }
            null
        } ?: return
        then(now.bitmap)
    }

    private fun decodeThumbnail(path: String, blurDp: Int, widthPx: Int, screenPx: Int, density: Float): Bitmap? {
        val decoded = decodePicture(path, widthPx) ?: return null
        val small = if (decoded.width > widthPx && widthPx > 0) {
            val h = maxOf(1, (decoded.height.toLong() * widthPx / decoded.width).toInt())
            Bitmap.createScaledBitmap(decoded, widthPx, h, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        return if (blurDp > 0) blurred(small, blurDp, screenPx, density) else small
    }

    // one still being decoded stays: its waiting views are told from it
    private fun trimThumbnails(keep: Thumbnail) = trimToLimit(thumbnails, THUMBNAILS_LIMIT, keep, { it.bytes }, inUse = { !it.done })

    @VisibleForTesting
    fun thumbnailBytes(): Long = synchronized(lock) { thumbnails.values.sumOf { it.bytes } }

    private const val BLUR_SHRINK = 4
    private const val WIDTH_STEP = 1.1

    // two views drawing at two widths, and one more
    private const val BLUR_WIDTHS_KEPT = 3
    private const val NORMAL_WEIGHT = 400

    const val MAX_PIXELS = 2048L * 2048

    const val MAX_SIDE = 4096
}

/** [sample]: how much smaller than its file each was decoded. */
class BoardImages internal constructor() {

    internal class Decoded(val bitmaps: Map<String, Bitmap>, val sizes: Map<String, IntArray>) {
        val bytes = bitmaps.values.sumOf { it.allocationByteCount.toLong() }
    }

    @Volatile
    private var decoded: Decoded? = null

    // main thread only
    private val waiting = ArrayList<() -> Unit>()

    internal val bytes get() = decoded?.bytes ?: 0L

    val ready get() = decoded != null

    fun bitmap(file: String): Bitmap? = decoded?.bitmaps?.get(file)

    /** The file's own size in pixels, which the nine-slice's insets are in. */
    fun size(file: String): IntArray? = decoded?.sizes?.get(file)

    fun whenReady(f: () -> Unit) {
        val now = synchronized(waiting) {
            if (decoded == null) waiting += f
            decoded != null
        }
        if (now) f()
    }

    internal fun done(d: Decoded) {
        val told = synchronized(waiting) {
            decoded = d
            waiting.toList().also { waiting.clear() }
        }
        if (told.isNotEmpty()) ThemeAssets.poster(Runnable { told.forEach { it() } })
    }

    companion object {
        val NONE = BoardImages().apply { decoded = Decoded(emptyMap(), emptyMap()) }
    }
}
