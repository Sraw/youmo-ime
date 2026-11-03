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
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * A theme's files decoded: its picture (the cut for the orientation, each blur it is shown at), the
 * key images a keyboard draws, its fonts. Each once, kept till another theme is drawn (before 3.0 the
 * picture was decoded on the main thread each time the input view was made). Another theme's files
 * drop them all, a theme of none too ([retain]): a picture let go when a plain theme is picked.
 *
 * The picture is decoded ahead at every blur the theme shows it at ([prefetch], when it is picked);
 * one asked for before that is decoded on the asking thread (as slow as before, but once). Decoding
 * and blurring are done outside the lock, so a view asking for a font or a keyboard's images waits on
 * no picture; two threads asking for the same picture decode it once. The key images are a
 * keyboard's at a time, on a thread of their own; the keys are drawn as their kinds' colours till
 * they are in ([BoardImages.whenReady]). Kept by bytes, [BOARDS_LIMIT] in all (two keyboards' worth:
 * 26 keys and the numbers back and forth decode nothing again), the keyboard drawn longest ago
 * dropped first. A file that does not decode (or runs out of memory doing it) is drawn as none.
 *
 * The theme list's thumbnails ([thumbnail]) are apart: small, a few themes' kept, decoded on the
 * same thread.
 */
object ThemeAssets {

    /** How files become bitmaps and fonts: Android's; a test's counts. */
    interface Decoder {
        /** The image's width and height, or null if it does not read. */
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

    /** Where pictures ahead, key images and thumbnails are decoded: a thread of its own; a test's, at once. */
    @VisibleForTesting
    var runner: (Runnable) -> Unit = { background.execute(it) }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Where a decoded keyboard's waiting keys are told: the main thread; a test's, at once. */
    @VisibleForTesting
    var poster: (Runnable) -> Unit = { main.post(it) }

    const val BOARDS_LIMIT = 24L * 1024 * 1024

    /** The thumbnails kept, bytes: the theme list's, a screen of them and some. */
    const val THUMBNAILS_LIMIT = 12L * 1024 * 1024

    private val lock = Any()

    // the look the caches are of, and its files: another look of the same files (a colour, a
    // brightness, a blur changed: the theme imported again from the web page) decodes nothing again
    private var look: ThemeLook? = null
    private var files: List<String>? = null

    private data class PictureKey(val landscape: Boolean, val blurDp: Int)

    // a picture asked for: decoded by the first to take its own lock, outside the cache's
    private class PictureSlot {
        var done = false
        var bitmap: Bitmap? = null
    }

    private val pictures = HashMap<PictureKey, PictureSlot>()
    private val boards = LinkedHashMap<String, BoardImages>(8, 0.75f, true)
    private val fonts = HashMap<Pair<String?, Int>, Typeface?>()

    // under the lock: the caches of another theme's files are of no use
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

    /**
     * [look] is the theme drawn now: what is kept of another's files is let go, though [look] has
     * no files of its own to decode (a plain theme picked after a pictured one).
     */
    fun retain(look: ThemeLook) = synchronized(lock) { use(look) }

    /** Drops everything: the theme's files changed, or a test starts afresh. */
    fun clear() = synchronized(lock) {
        look = null
        files = null
        pictures.clear()
        boards.clear()
        fonts.clear()
        thumbnails.clear()
    }

    /**
     * [look]'s picture for [landscape] (its own cut, else the portrait one), blurred [blurDp] dp as
     * drawn [widthPx] wide at [density]; null for none or a file that does not read. Decoded no wider
     * than it is drawn, nearly, and no bigger than [MAX_PIXELS]: a cut of 4096 is 64 MB whole.
     */
    fun picture(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float): Bitmap? {
        val slot = synchronized(lock) {
            use(look)
            if (look.picture == null) return null
            val key = PictureKey(landscape, blurDp)
            pictures[key] ?: PictureSlot().also {
                // turned, the other orientation's are let go
                pictures.keys.removeAll { k -> k.landscape != landscape }
                pictures[key] = it
            }
        }
        synchronized(slot) {
            if (!slot.done) {
                slot.bitmap = decodeSafely("picture") { decodePicture(look, landscape, blurDp, widthPx, density) }
                slot.done = true
            }
            return slot.bitmap
        }
    }

    private fun decodePicture(look: ThemeLook, landscape: Boolean, blurDp: Int, widthPx: Int, density: Float): Bitmap? {
        val p = look.picture ?: return null
        return if (blurDp == 0) {
            decodePicture(if (landscape) p.landscape?.file ?: p.portrait.file else p.portrait.file, widthPx)
        } else {
            picture(look, landscape, 0, widthPx, density)?.let { blurred(it, blurDp, widthPx, density) }
        }
    }

    private fun decodePicture(path: String, widthPx: Int): Bitmap? {
        val size = decoder.size(path) ?: return null.also { Timber.w("Theme picture $path does not read") }
        return decoder.decode(path, pictureSample(size[0], size[1], widthPx))
    }

    /**
     * How much smaller a picture of [w]×[h] is decoded to be drawn [widthPx] wide: halved while it stays
     * as wide as that, and till it is no more than [MAX_PIXELS] and [MAX_SIDE] high (a tall picture over
     * a wide keyboard shows its middle only; 1080×4096 is 17 MB whole).
     */
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

    // a file that does not decode, or a decoding out of memory, is none: the theme drawn with its colours
    private inline fun <T> decodeSafely(what: String, f: () -> T?): T? =
        try {
            f()
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "Theme $what not decoded: out of memory")
            null
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // BitmapFactory's IllegalArgumentException for a file it half reads, say
            Timber.w(e, "Theme $what not decoded")
            null
        }

    // blurred as BoxBlur does, on a quarter a side: its radius there, the dp as drawn [widthPx] wide
    private fun blurred(b: Bitmap, blurDp: Int, widthPx: Int, density: Float): Bitmap {
        val w = b.width
        val h = b.height
        val pixels = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val small = BoxBlur.shrink(pixels, w, h, BLUR_SHRINK)
        val sw = maxOf(1, w / BLUR_SHRINK)
        val sh = maxOf(1, h / BLUR_SHRINK)
        val pxPerBitmapPx = if (widthPx > 0) widthPx.toFloat() / w else 1f
        val radius = maxOf(1, (blurDp * density / pxPerBitmapPx / BLUR_SHRINK).roundToInt())
        val out = BoxBlur.blur(small, sw, sh, radius)
        return Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
    }

    /** What the picture slots hold decoded now: a test's. */
    @VisibleForTesting
    fun pictureCount(): Int = synchronized(lock) { pictures.size }

    /**
     * The key images [files] of [board] (its kinds', its own keys', its roles' on every board:
     * KeyStyleResolver.imagesOn), decoding them on their thread if they are not in yet.
     */
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

    // the keyboards drawn longest ago dropped till the rest are in the limit; the one just decoded kept
    private fun trim(keep: BoardImages) {
        var total = boards.values.sumOf { it.bytes }
        val it = boards.entries.iterator()
        while (total > BOARDS_LIMIT && it.hasNext()) {
            val e = it.next()
            if (e.value === keep) continue
            total -= e.value.bytes
            it.remove()
        }
    }

    /** What the key images kept take now, bytes. */
    @VisibleForTesting
    fun boardBytes(): Long = synchronized(lock) { boards.values.sumOf { it.bytes } }

    /**
     * The font [path] (null: the system's) at [weight]; null for the system's as it is, so a theme of
     * no fonts draws as before. A font that does not read is the system's.
     */
    fun typeface(look: ThemeLook, path: String?, weight: Int): Typeface? {
        synchronized(lock) {
            use(look)
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

    /** [look]'s candidate font at its weight ([typeface]): the candidates', the code being typed. */
    fun candidateTypeface(look: ThemeLook): Typeface? = typeface(look, look.text.candidateFont, look.text.candidateWeight)

    /**
     * [look] picked: another theme's files let go at once ([retain]), its picture decoded ahead off the
     * main thread at each blur it is shown at ([ThemeLook.pictureBlurs]).
     */
    fun prefetch(look: ThemeLook, landscape: Boolean, widthPx: Int, density: Float) {
        retain(look)
        val blurs = look.pictureBlurs()
        if (blurs.isEmpty()) return
        // the sharp one first: the blurs are made from it
        runner { (listOf(0) + blurs).distinct().forEach { picture(look, landscape, it, widthPx, density) } }
    }

    private class Thumbnail {
        var done = false
        var bitmap: Bitmap? = null
        val waiting = ArrayList<(Bitmap?) -> Unit>()
        val bytes get() = bitmap?.allocationByteCount?.toLong() ?: 0L
    }

    private val thumbnails = LinkedHashMap<String, Thumbnail>(8, 0.75f, true)

    /**
     * [look]'s portrait picture for the theme list: [widthPx] wide at most, blurred as under its keyboard
     * drawn [screenPx] wide at [density]; [then] on the main thread with it (null: none, or one that does
     * not read), at once if it is in. Decoded on the assets' thread (a 4096 picture on the main thread
     * was 64 MB and a stutter a thumbnail), kept by bytes ([THUMBNAILS_LIMIT]), the one shown longest ago
     * dropped first. The brightness is the caller's (a colour filter).
     */
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
        // the blur as on the keyboard, the keyboard drawn the screen wide
        return if (blurDp > 0) blurred(small, blurDp, screenPx, density) else small
    }

    private fun trimThumbnails(keep: Thumbnail) {
        var total = thumbnails.values.sumOf { it.bytes }
        val it = thumbnails.entries.iterator()
        while (total > THUMBNAILS_LIMIT && it.hasNext()) {
            val e = it.next()
            if (e.value === keep || !e.value.done) continue
            total -= e.value.bytes
            it.remove()
        }
    }

    /** What the thumbnails kept take now, bytes. */
    @VisibleForTesting
    fun thumbnailBytes(): Long = synchronized(lock) { thumbnails.values.sumOf { it.bytes } }

    private const val BLUR_SHRINK = 4
    private const val NORMAL_WEIGHT = 400

    /** The most pixels a picture is decoded to: 2048² (16 MB). */
    const val MAX_PIXELS = 2048L * 2048

    /** The highest a picture is decoded to. */
    const val MAX_SIDE = 4096
}

/**
 * A keyboard's key images, decoded or on their way: a key draws its kind's colour till [whenReady]
 * says they are in. [sample] how much smaller than its file each was decoded (KeyImageBudget).
 */
class BoardImages internal constructor() {

    internal class Decoded(val bitmaps: Map<String, Bitmap>, val sizes: Map<String, IntArray>) {
        val bytes = bitmaps.values.sumOf { it.allocationByteCount.toLong() }
    }

    @Volatile
    private var decoded: Decoded? = null

    // the main thread's: told once the images are in
    private val waiting = ArrayList<() -> Unit>()

    internal val bytes get() = decoded?.bytes ?: 0L

    val ready get() = decoded != null

    /** The image of [file] as decoded, null till it is in or if it did not read. */
    fun bitmap(file: String): Bitmap? = decoded?.bitmaps?.get(file)

    /** Its file's own size, pixels, which the nine-slice's insets are in. */
    fun size(file: String): IntArray? = decoded?.sizes?.get(file)

    /** [f] on the main thread once the images are in: now, if they are. */
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
        /** A keyboard of no images: in at once. */
        val NONE = BoardImages().apply { decoded = Decoded(emptyMap(), emptyMap()) }
    }
}
