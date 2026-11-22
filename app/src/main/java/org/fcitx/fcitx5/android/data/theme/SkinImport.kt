/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.theme.SkinZip
import org.fcitx.fcitx5.android.input.theme.ThemeFileName
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinConversion
import org.fcitx.fcitx5.android.scene.skin.SkinConverter
import org.fcitx.fcitx5.android.scene.skin.SkinError
import org.fcitx.fcitx5.android.scene.skin.SkinFormat
import org.fcitx.fcitx5.android.scene.skin.SkinImage
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import org.fcitx.fcitx5.android.scene.skin.SkinOutput
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import java.io.InputStream

internal object SkinImport {
    /** Past it the conversion stops at its next image or text, refused as a broken file: a crafted skin must not spin for ever. */
    @VisibleForTesting
    internal var maxMillis = MAX_MILLIS

    fun convert(src: InputStream, fileName: String, codec: ThemeFilesManager.SkinCodec): SkinConversion = try {
        val timed = Timed(codec, System.nanoTime() + maxMillis * NANOS_PER_MILLI)
        val entries = src.use {
            // a format told apart by its name alone is not read at all (an APK may be past the size a skin is held to)
            SkinConverter.unsupported(fileName, emptySet())?.let { f -> throw SkinError(SkinError.Reason.UNSUPPORTED, f) }
            SkinZip.readSkin(it, fileName)
        }
        SkinConverter.convert(entries, timed, fileName).also { if (timed.stopped) timedOut() }
    } catch (e: SkinError) {
        // a .zip that is no skin: most likely a theme exported under the old name
        if (e.reason == SkinError.Reason.UNKNOWN && ThemeFileName.isZip(fileName)) {
            errorRuntime(R.string.exception_theme_filename, fileName)
        }
        refused(e)
    }

    /** OOM or stack overflow becomes a refusal: a skin crafted past [SkinLimits] must not take the input method down. */
    fun <T> backstop(r: Result<T>): Result<T> = r.recoverCatching { e ->
        when (e) {
            is OutOfMemoryError -> refused(SkinError(SkinError.Reason.UNPACKED_TOO_BIG, cause = e))
            is StackOverflowError -> refused(SkinError(SkinError.Reason.BROKEN, cause = e))
            else -> throw e
        }
    }

    /** Halved till within its bytes, where it may be (a picture, the preview). */
    fun png(out: SkinOutput.Image, codec: ThemeFilesManager.SkinCodec): ByteArray {
        var p = out.pixels
        var bytes = codec.png(p)
        fun over() = out.shrink && bytes.size > out.maxBytes
        while (over() && p.width * p.height > 1) {
            p = p.resized(maxOf(1, p.width / 2), maxOf(1, p.height / 2))
            bytes = codec.png(p)
        }
        return bytes
    }

    private fun refused(e: SkinError): Nothing = when (e.reason) {
        SkinError.Reason.NOT_ZIP -> errorRuntime(R.string.exception_skin_not_zip)
        SkinError.Reason.UNKNOWN -> errorRuntime(R.string.exception_skin_unknown)
        SkinError.Reason.TOO_BIG -> errorRuntime(R.string.exception_skin_too_big, (SkinLimits.MAX_ARCHIVE_BYTES / MB).toString())
        SkinError.Reason.TOO_MANY_FILES -> errorRuntime(R.string.exception_theme_entries, SkinLimits.MAX_ENTRIES.toString())
        SkinError.Reason.UNPACKED_TOO_BIG -> errorRuntime(R.string.exception_skin_unpacked_too_big)
        SkinError.Reason.ENCRYPTED -> errorRuntime(R.string.exception_skin_encrypted)
        SkinError.Reason.UNSAFE_NAME -> errorRuntime(R.string.exception_skin_unsafe_name, e.detail)
        SkinError.Reason.SOGOU_ENCRYPTED -> errorRuntime(R.string.exception_skin_sogou_encrypted)
        SkinError.Reason.SOGOU_PC -> errorRuntime(R.string.exception_skin_sogou_pc)
        SkinError.Reason.NO_KEYBOARD -> errorRuntime(R.string.exception_skin_no_keyboard)
        SkinError.Reason.BROKEN -> errorRuntime(R.string.exception_skin_not_zip)
        SkinError.Reason.UNSUPPORTED -> errorRuntime(R.string.exception_skin_unsupported, FORMATS[e.detail]?.let(appContext::getString) ?: e.detail)
    }

    /**
     * [codec] that stops the conversion once [deadline] ([System.nanoTime]) is past or the thread is interrupted (the
     * import let go): at its next image, tile or text, and after it, where a reader caught that and went on.
     */
    private class Timed(private val codec: ThemeFilesManager.SkinCodec, private val deadline: Long) : ThemeFilesManager.SkinCodec by codec {
        var stopped = false
            private set

        private fun check() {
            if (stopped || Thread.currentThread().isInterrupted || System.nanoTime() - deadline > 0) {
                stopped = true
                timedOut()
            }
        }

        override fun image(bytes: ByteArray): SkinImage? {
            check()
            val image = codec.image(bytes) ?: return null
            return object : SkinImage by image {
                override fun pixels(x: Int, y: Int, w: Int, h: Int): Pixels {
                    check()
                    return image.pixels(x, y, w, h)
                }
            }
        }

        override fun legacyText(bytes: ByteArray): String {
            check()
            return codec.legacyText(bytes)
        }

        override fun sogouPlain(container: ByteArray, maxBytes: Int): ByteArray? {
            check()
            return codec.sogouPlain(container, maxBytes)
        }
    }

    private fun timedOut(): Nothing = throw SkinError(SkinError.Reason.BROKEN, "timed out")

    /** [SkinFormat]'s ids in the user's words; a test holds it to every id. */
    internal val FORMATS = mapOf(
        SkinFormat.SHOUXIN to R.string.skin_format_shouxin,
        SkinFormat.GBOARD to R.string.skin_format_gboard,
        SkinFormat.CANG to R.string.skin_format_cang,
        SkinFormat.APK to R.string.skin_format_apk,
        SkinFormat.SOGOU_DIY to R.string.skin_format_sogou_diy,
    )

    private const val MB = 1024L * 1024
    private const val MAX_MILLIS = 60_000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
