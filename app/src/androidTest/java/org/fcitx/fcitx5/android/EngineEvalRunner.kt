/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.device.BigCorePin
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.backup.BackupMerge
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.ModelLevel
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.fcitx.fcitx5.android.engine.user.HistoryLines
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.host.EngineOptions
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.ModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KProperty1

/**
 * Runs a lib/ime-eval set through the shipped engine and writes `eval/<evalSet>-<evalIme>.tsv` for `:lib:ime-eval`
 * to score; driven by lib/ime-eval/run-on-device.sh, skipped unless asked for.
 *
 * Scores compare only on an app with no data of its own, so it stops where the app has learned words or logs
 * unless `-e evalLearned true`. `-e evalHistory <name>` replaces the learning record with that history first.
 * `-e evalLevel low|medium|high` types at that 性能档位 with no emoji, as ime-eval lists none, refuses other settings than the defaults, and writes
 * `eval/<evalSet>-<evalIme>-<level>.tsv` (tier-guard.sh --device); a set pushed to `eval/<evalSet>.tsv` is read before the test assets.
 */
class EngineEvalRunner {

    // -e evalNine true: digits as the nine keys send them (Keyboard.pinyinNineKey, Keyboard.nineKey)
    private var nine = false

    private var english = false

    // -e evalGrouped: codes as that grouped keyboard sends them (see GROUPED and [wired])
    private var grouped: String? = null

    @Test
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val set = args.getString("evalSet")
        assumeTrue("pass -e evalSet <name> to run", set != null)
        checkThisInputMethodIsNotBound()
        val history = args.getString("evalHistory")
        val ime = args.getString("evalIme") ?: InputMethodCatalog.PINYIN
        nine = args.getString("evalNine") == "true"
        english = ime == InputMethodCatalog.ENGLISH
        grouped = args.getString("evalGrouped")?.also { require(it in GROUPED) { "evalGrouped $it: one of $GROUPED" } }
        require(!(nine && grouped != null)) { "evalNine and evalGrouped: one at a time" }
        val level = args.getString("evalLevel")?.let { key ->
            requireNotNull(ModelLevel.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }) { "evalLevel $key: one of low, medium, high" }
        }
        // -e pin off: unpinned, to measure what the big cores bring
        val pin = args.getString("pin") != "off"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val dir = checkNotNull(context.getExternalFilesDir("eval")) { "external storage unavailable" }
        val samples = samples(File(dir, "$set.tsv").takeIf { it.isFile }?.bufferedReader() ?: testContext.assets.open("$set.tsv").bufferedReader())
        val out = File(dir, "$set-$ime${level?.let { "-${it.key.lowercase()}" } ?: ""}.tsv")
        history?.let { name ->
            val lines = historyLines(dir, testContext, name)
            val counted = writeHistory(File(context.filesDir, ENGINE_USER_DIR), lines)
            android.util.Log.i("EngineEvalRunner", "history $name: $counted words of ${lines.count { it.isNotBlank() }} lines")
        }
        val pinBefore = BigCorePin.enabled
        val fcitx = KotlinHost(context)
        val scope = MainScope()
        try {
            BigCorePin.enabled = pin
            runBlocking {
                withTimeout(STARTUP_TIMEOUT_MS) {
                    // subscribe before start(): eventFlow has no replay (see HostTest.setup)
                    val ready = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fcitx.eventFlow.filterIsInstance<HostEvent.Ready>().first()
                    }
                    fcitx.start()
                    ready.join()
                }
                val ownWords = fcitx.userWordCounts().filterValues { it > 0 }
                // read after userWordCounts, which opens pinyin's log and fills a new one from libime's
                val logs = learnedLogs(File(context.filesDir, ENGINE_USER_DIR)).filter { history == null || it.name != LearnLog.FILE }
                val learned = ownWords.isNotEmpty() || logs.isNotEmpty()
                check(!learned || args.getString("evalLearned") == "true") {
                    "the app has learned (words $ownWords, logs ${logs.map { it.name }}): clear its data (adb shell pm clear), " +
                        "or pass -e evalLearned true to score on them"
                }
                fcitx.activate(UID, PKG)
                fcitx.focus(true)
                // setEnabledIme saves the profile at once; put the user's list back afterwards
                val enabledBefore = fcitx.enabledIme().map { it.uniqueName }.toTypedArray()
                // shared by the Chinese input methods and saved at once, like the list: put back afterwards
                val optionsBefore = fcitx.imOptions(InputMethodCatalog.PINYIN).orEmpty().filterKeys { it == EngineSettings.MODEL_LEVEL || it == EngineSettings.EMOJI }
                try {
                    level?.let { useLevel(fcitx, it) }
                    fcitx.setEnabledIme(arrayOf(ime))
                    // fcitx takes a misspelt name silently and would score the previous input method
                    assertEquals("input method", ime, fcitx.currentIme().uniqueName)
                    // the first keystrokes pay for class loading and page faults
                    samples.take(WARM_UP_INPUTS).forEach { typeAndCollect(fcitx, it) }
                    val s = fcitx.engineSettings()
                    android.util.Log.i(
                        "EngineEvalRunner",
                        "settings: level ${s.modelLevel}, fuzzy ${s.fuzzy}, typos ${s.typos}, latin ${s.latinWords}, " +
                            "shuangpin ${s.shuangpin}, nine keys ${s.nineKeys}, prediction ${s.prediction}, emoji ${s.emoji}, page ${s.pageSize}",
                    )
                    out.bufferedWriter().use { writer ->
                        samples.forEach { sample ->
                            writer.write(typeAndCollect(fcitx, sample))
                            writer.newLine()
                        }
                    }
                    fcitx.reset()
                } finally {
                    if (level != null) fcitx.setImOptions(InputMethodCatalog.PINYIN, optionsBefore)
                    fcitx.setEnabledIme(enabledBefore)
                    fcitx.focus(false)
                    fcitx.deactivate(UID)
                }
            }
        } finally {
            fcitx.stop()
            scope.cancel()
            // the process's flag: the instrumentation's later tests would run unpinned
            BigCorePin.enabled = pinBefore
        }
    }

    /**
     * Types [sample] from a clean state and returns its result line. Each latency is one `sendKey` round trip,
     * host-thread hops included, so compare engines only through this runner; queued host work is waited out
     * by the uncounted `isEmpty()` so it cannot land on the next key's clock.
     */
    private suspend fun typeAndCollect(fcitx: HostInstance, sample: Sample): String = withTimeout(INPUT_TIMEOUT_MS) {
        fcitx.reset()
        if (sample.context.isNotEmpty()) fcitx.engineContext(sample.context)
        fcitx.isEmpty()
        val latencies = sample.input.mapIndexed { i, c ->
            val start = SystemClock.elapsedRealtimeNanos()
            val touch = sample.touches?.getOrNull(i)
            when {
                nine -> fcitx.sendKey(KeySym(UNICODE + if (english) Keyboard.nineKey(c, upper = false) else Keyboard.pinyinNineKey(c)), KeyStates.Virtual, touch = touch)
                grouped != null -> fcitx.sendKey(KeySym(UNICODE + wired(grouped!!, c, sample.places?.getOrNull(i) ?: Float.NaN)), KeyStates.Virtual, touch = touch)
                touch != null -> fcitx.sendKey(c.toString(), touch = touch)
                else -> fcitx.sendKey(c)
            }
            val micros = (SystemClock.elapsedRealtimeNanos() - start) / 1000
            fcitx.isEmpty()
            micros
        }
        tsvLine(sample.input, latencies, fcitx.getCandidates(0, CANDIDATES).map { it.text })
    }

    private suspend fun useLevel(fcitx: KotlinHost, wanted: ModelLevel) {
        val choice = ModelChoice.entries.single { it.level == wanted }.key
        fcitx.setImOptions(InputMethodCatalog.PINYIN, mapOf(EngineSettings.MODEL_LEVEL to choice, EngineSettings.EMOJI to "False"))
        val s = fcitx.engineSettings()
        check(s.modelLevel == wanted && !s.emoji) { "evalLevel ${wanted.key}: the engine reads $s" }
        // tier-guard.sh holds the rows to the JVM's, which reads the app's defaults: another setting would differ for no fault of the engine
        val defaults = EngineOptions().toSettings(wanted)
        val changed = SHAPING.filter { it.get(s) != it.get(defaults) }.map { "${it.name} ${it.get(s)}" }
        check(changed.isEmpty()) { "evalLevel ${wanted.key}: settings not the defaults ($changed): put them back, or adb shell pm clear" }
    }

    private fun samples(set: java.io.BufferedReader): List<Sample> = set.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            // input<TAB>expected<TAB>tag[<TAB>context[<TAB>touches[<TAB>places]]], as EvalSet reads it; `-` for none
            val fields = line.split('\t')
            val places = fields.getOrNull(5)?.split(' ')?.map { it.toFloatOrNull() ?: Float.NaN }
            Sample(fields[0], fields.getOrElse(3) { "" }, fields.getOrNull(4)?.split(' ')?.map(Touch::parse), places)
        }.toList()
    }

    private fun historyLines(dir: File, testContext: android.content.Context, name: String): List<String> {
        val pushed = File(dir, "$name.txt")
        if (pushed.isFile) return pushed.readLines()
        val asset = runCatching { testContext.assets.open("$name.txt") }.getOrNull()
        return requireNotNull(asset) { "evalHistory $name: no $name.txt in ${dir.path} nor in lib/ime-eval/data (run-on-device.sh HISTORY=<file.txt> pushes one)" }
            .bufferedReader().use { it.readLines() }
    }

    /** Replaces the learning record in [dir] with [lines], counted as `ime-eval --history` counts them; returns how many. */
    private fun writeHistory(dir: File, lines: List<String>): Int {
        val data = PinyinData.load(AppData.map(InputMethodCatalog.PINYIN_DATA), verify = false)
        val rows = HistoryLines.rows(UserModel(data.dictionary, data.vocabulary, index = data.wordIndex), data, lines)
        dir.mkdirs()
        File(dir, LearnLog.FILE).delete()
        LearnLog(JvmFiles(dir).file(LearnLog.FILE)).use { log ->
            log.open()
            rows.forEach { log.record(listOf(it)) }
        }
        return rows.size
    }

    /** Same layout as `RunResultFormat` in :lib:ime-eval, which this APK cannot depend on. */
    private fun tsvLine(input: String, latencies: List<Long>, candidates: List<String>) =
        (listOf(input, latencies.joinToString(",")) + candidates.map { it.replace(SEPARATORS, " ") })
            .joinToString("\t")

    /** The logs under [dir] that hold a record; a header alone, or an empty learning-record snapshot, learned nothing. */
    private fun learnedLogs(dir: File): List<File> = dir.walkTopDown().filter {
        it.isFile && when {
            // the install's backup origin, which a fresh app writes before anything is learned
            it.name == LearnLog.FILE -> !LearnLog.replay(it.readBytes()).apply { kept.removeAll { r -> r is LearnRecord.Unknown && r.type == BackupMerge.ORIGIN } }.isEmpty()
            else -> it.name.endsWith(LOG_SUFFIX) && it.length() > RecordFormat.HEADER_SIZE
        }
    }.toList()

    // what grouped keyboard [board] sends for code [c] tapped [at] across its key (NaN: no place), as GroupedKeyboard.tapped
    private fun wired(board: String, c: Char, at: Float): Int = when (board) {
        "18" -> if (at.isNaN()) Keyboard.pinyin18Key(c) else Keyboard.pinyin18Tap(c, at)
        "14" -> if (at.isNaN()) c.code else Keyboard.tapKey(c, at)
        "sp14" -> Keyboard.shuangpin14Key(c)
        "zy9" -> Keyboard.ZHUYIN_NINE.first { Keyboard.keyedChar(it) == c }
        "zy" -> Keyboard.ZHUYIN_FULL.first { Keyboard.keyedChar(it) == c }
        else -> c.code
    }

    private data class Sample(val input: String, val context: String, val touches: List<Touch?>? = null, val places: List<Float>? = null)

    private companion object {
        const val UID = 0
        const val PKG = "org.fcitx.fcitx5.android.eval"
        const val CANDIDATES = 5
        const val WARM_UP_INPUTS = 10
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val INPUT_TIMEOUT_MS = 10_000L
        /** Must match EngineBridge's user data dir. */
        const val ENGINE_USER_DIR = "engine"
        const val LOG_SUFFIX = ".user"
        val SEPARATORS = Regex("[\t\r\n]")

        val GROUPED = setOf("14", "18", "sp18", "sp14", "sp9", "zy9", "zy")

        // beside the level and emoji, what changes the candidates a set's keys show
        val SHAPING = listOf<KProperty1<EngineSettings, *>>(
            EngineSettings::shuangpin, EngineSettings::fuzzy, EngineSettings::typos, EngineSettings::prediction, EngineSettings::latinWords,
            EngineSettings::chaizi, EngineSettings::builtInPhrases, EngineSettings::nineKeys, EngineSettings::ownKeys,
        )
    }
}
