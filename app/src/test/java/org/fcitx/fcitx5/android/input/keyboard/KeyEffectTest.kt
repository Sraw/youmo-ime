/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyEffects
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.scene.KeyEffect
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 060-render-p3 A3: a key's press effects on the canvas. A ripple grows from where the finger came down,
 * clipped to the key, fading; particles fly out by their press's seed; the pools never grow past their
 * most, however fast the keys are typed; 键盘动效 off or the keyboard hidden, none plays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class KeyEffectTest {

    @get:Rule
    val prefs = PrefsRule()

    private val realRunner = ThemeAssets.runner

    @Before
    fun decodeAtOnce() {
        ThemeAssets.runner = { it.run() }
        // it writes a user's pack: the themes settled writable whatever ran before in this JVM (phoneStorage)
        phoneStorage()
    }

    @After
    fun decodeAsBefore() {
        ThemeAssets.runner = realRunner
    }

    // a [px]-square PNG
    private fun png(px: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    // a user's pack of material's file with [styles] last and the images ring.png and star.png; its light version
    private fun theme(vararg styles: String): Theme {
        val text = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_material)
            .replace("name=\"material\"", "name=\"effects\"")
            .replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("theme.xml"))
                it.write(text.encodeToByteArray())
                it.putNextEntry(ZipEntry("ring.png"))
                it.write(png(RING_PX))
                it.putNextEntry(ZipEntry("star.png"))
                it.write(png(STAR_PX))
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
        val versions = opened.commit().getOrThrow()
        ThemeManager.packPlaced(versions[0].file)
        return versions.first { !it.isDark }
    }

    private fun ripple() = theme("<style select=\"key:pressed\" effect=\"ripple-image(ring.png, 100%, 300ms)\"/>")

    private fun particles() = theme("<style select=\"key:pressed\" effect=\"particles(star.png, 12, 24dp, 400ms)\"/>")

    private fun KeyboardHarness.canvas(): KeyboardCanvasView = KeyboardHarness.boardCanvas(inputView)!!

    private fun frames(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // the clock run on by [ms] and the effects ticked there, no looper run: Robolectric's paused looper jumps its clock past an
    // animator's frames, so a press a few ms after another would find the last ended
    private fun KeyboardCanvasView.clock(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        keyEffects.tick(SystemClock.uptimeMillis())
    }

    @Test
    fun aRippleGrowsFromTheFingerClippedToTheKeyAndEnds() = KeyboardHarness(theme = ripple()).start().use { h ->
        val c = h.canvas()
        val k = c.keyAt("q")!!
        val e = k.motion!!.effect as KeyEffect.Ripple
        val start = SystemClock.uptimeMillis()
        c.press("q")
        val fx = c.keyEffects
        assertEquals(1, fx.playing)
        val inner = fx.rippleNodes().single()
        val clip = fx.rippleClips().single()
        // clipped to the key's face, the image's middle where the finger came down (the test's press: the key's middle)
        assertTrue(clip.clipToOutline)
        assertEquals(k.shape.bounds.left.toFloat(), clip.left.toFloat(), 1f)
        assertEquals(k.anchor.x.toFloat(), clip.left + inner.translationX + inner.pivotX, 0.5f)
        assertEquals(k.anchor.y.toFloat(), clip.top + inner.translationY + inner.pivotY, 0.5f)
        // half way: grown to 3/4 of the radius (ease-out), half faded
        fx.tick(start + e.ms / 2)
        assertEquals((0.75 * e.radius / (RING_PX / 2.0)).toFloat(), inner.scaleX, 1e-3f)
        assertEquals(0.5f, clip.alpha, 1e-3f)
        c.press("q", down = false)
        frames(400)
        assertEquals(0, fx.playing)
        // ended: its animator too, no frame asked for
        assertTrue(!fx.running)
    }

    @Test
    fun particlesFlyByTheirPressesSeed() = KeyboardHarness(theme = particles()).start().use { h ->
        val c = h.canvas()
        val k = c.keyAt("q")!!
        val e = k.motion!!.effect as KeyEffect.Particles
        val start = SystemClock.uptimeMillis()
        c.press("q")
        val fx = c.keyEffects
        val nodes = fx.particleNodes()
        assertEquals(12, nodes.size)
        fx.tick(start + e.ms / 2)
        // the first press's flight: the seed 0's, eased 3/4 of the way
        val ends = e.ends(0)
        nodes.forEachIndexed { i, n ->
            assertEquals("x $i", (k.anchor.x - STAR_PX / 2.0 + ends[2 * i] * 0.75).toFloat(), n.translationX, 1e-2f)
            assertEquals("y $i", (k.anchor.y - STAR_PX / 2.0 + ends[2 * i + 1] * 0.75).toFloat(), n.translationY, 1e-2f)
            assertEquals(0.5f, n.alpha, 1e-3f)
        }
        // drawn at 3 of the image's px a dp
        assertEquals(h.service.resources.displayMetrics.density / 3f, nodes[0].scaleX, 1e-4f)
        c.press("q", down = false)
        frames(500)
        assertEquals(0, fx.playing)
    }

    @Test
    fun typedFastThePoolsDoNotGrow() = KeyboardHarness(theme = particles()).start().use { h ->
        val c = h.canvas()
        val fx = c.keyEffects
        repeat(200) {
            val key = "qwertyuiop"[it % 10].toString()
            c.showPressed(KeyRef(key), true)
            c.clock(10)
            c.showPressed(KeyRef(key), false)
            c.clock(10)
            assertTrue("${fx.pooled} after ${it + 1}", fx.pooled <= KeyEffects.MAX_PARTICLES)
        }
        // every press played (typed fast too): the pool full, the oldest taken
        assertEquals(KeyEffects.MAX_PARTICLES, fx.pooled)
        assertEquals(KeyEffects.MAX_PARTICLES, fx.playing)
        frames(500)
        assertEquals(0, fx.playing)
        assertEquals(KeyEffects.MAX_PARTICLES, fx.pooled)
    }

    @Test
    fun ripplesTypedFastTakeTheOldest() = KeyboardHarness(theme = ripple()).start().use { h ->
        val c = h.canvas()
        val fx = c.keyEffects
        repeat(30) {
            val key = "qwertyuiop"[it % 10].toString()
            c.showPressed(KeyRef(key), true)
            c.showPressed(KeyRef(key), false)
            c.clock(5)
        }
        assertEquals(KeyEffects.MAX_RIPPLES, fx.pooled)
        // pool full, all in the same ms: the next press takes the one pressed longest ago, each in turn
        fx.stop()
        val keys = "qwertyuio"
        keys.forEach { c.showPressed(KeyRef("$it"), true) }
        val clips = fx.rippleClips().map { it.left }
        assertEquals(c.keyAt("o")!!.shape.bounds.left.toInt(), clips[0])
        assertEquals(keys.drop(1).dropLast(1).map { c.keyAt("$it")!!.shape.bounds.left.toInt() }, clips.drop(1))
    }

    // API 29: a path's outline does not clip before 33, so a key not a rounded rectangle of one radius is clipped by its path
    @Test
    @Config(sdk = [29])
    fun aPathKeysRippleIsClippedToItsShapeNotItsBox() =
        KeyboardHarness(theme = theme("<style select=\"key\" shape=\"path(M.5 0 L1 1 L0 1 Z)\" effect=\"ripple-image(ring.png, 100%, 300ms)\"/>")).start().use { h ->
            val c = h.canvas()
            val k = c.keyAt("q")!!
            c.press("q")
            val clip = c.keyEffects.rippleClips().single()
            val path = c.keyEffects.rippleClipPaths().single()!!
            assertTrue(!clip.clipToOutline)
            assertEquals(android.graphics.Path.FillType.WINDING, path.fillType)
            val w = clip.width
            val h2 = clip.height
            val region = android.graphics.Region().apply { setPath(path, android.graphics.Region(0, 0, w, h2)) }
            // the triangle's apex in, the box's top corners out
            assertTrue(region.contains(w / 2, h2 / 4))
            assertTrue(!region.contains(1, 1) && !region.contains(w - 2, 1))
            assertEquals(k.shape.bounds.left.toInt(), clip.left)
        }

    @Test
    fun aRoundedRectanglesRippleIsClippedByItsOutline() = KeyboardHarness(theme = ripple()).start().use { h ->
        val c = h.canvas()
        c.press("q")
        assertTrue(c.keyEffects.rippleClips().single().clipToOutline)
        assertEquals(null, c.keyEffects.rippleClipPaths().single())
    }

    @Test
    fun switchedOffNonePlays() {
        prefs.set(AppPrefs.getInstance().advanced.keyboardMotion, false)
        KeyboardHarness(theme = particles()).start().use { h ->
            val c = h.canvas()
            c.press("q")
            c.press("q", down = false)
            assertEquals(0, c.keyEffects.pooled)
        }
    }

    @Test
    fun hiddenNonePlays() = KeyboardHarness(theme = particles()).start().use { h ->
        val c = h.canvas()
        c.press("q")
        assertEquals(12, c.keyEffects.playing)
        c.dispatchWindowVisibilityChanged(android.view.View.GONE)
        c.press("q", down = false)
        assertEquals(0, c.keyEffects.playing)
    }

    @Test
    fun aThemeWithoutEffectsPlaysNone() = KeyboardHarness(theme = theme()).start().use { h ->
        val c = h.canvas()
        assertTrue(c.shownKeys.all { it.motion == null })
        c.press("q")
        c.press("q", down = false)
        assertEquals(0, c.keyEffects.pooled)
    }

    @Test
    fun aPacksEffectImagesAreHeldToTheirSideAndDecodedByPath() {
        // the one it names decoded with the key images, by the pack's path
        val pack = particles() as Theme.Pack
        assertEquals(listOf(java.io.File(pack.dir, "star.png").path), ThemePackFiles.images(pack.drawn).map { it.file })
        // one past the side (its bytes and type by ThemeAssetLimitsTest): refused, said why
        val side = ThemeAssetLimits.MAX_EFFECT_IMAGE_SIDE
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry("theme.xml"))
                it.write(java.io.File(pack.dir, "theme.xml").readBytes())
                it.putNextEntry(ZipEntry("ring.png"))
                it.write(png(RING_PX))
                it.putNextEntry(ZipEntry("star.png"))
                it.write(png(side + 1))
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes())
        assertTrue("a theme over the images' limit imported", opened.isFailure)
        val refused = opened.exceptionOrNull()!!.message
        assertEquals(appContext.getString(R.string.exception_theme_effect_image_side, side.toString()), refused)
    }

    private companion object {
        const val RING_PX = 64
        const val STAR_PX = 12
    }
}
