/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.theme.SkinZip
import org.fcitx.fcitx5.android.input.theme.ThemeFileName
import org.fcitx.fcitx5.android.scene.skin.SkinConversion
import org.fcitx.fcitx5.android.scene.skin.SkinConverter
import org.fcitx.fcitx5.android.scene.skin.SkinError
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import org.fcitx.fcitx5.android.scene.skin.SkinOutput
import org.fcitx.fcitx5.android.utils.errorRuntime
import java.io.InputStream

internal object SkinImport {
    fun convert(src: InputStream, fileName: String, codec: ThemeFilesManager.SkinCodec): SkinConversion = try {
        SkinConverter.convert(src.use { SkinZip.readSkin(it, fileName) }, codec, ThemeFileName.base(fileName))
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
    }

    private const val MB = 1024L * 1024
}
