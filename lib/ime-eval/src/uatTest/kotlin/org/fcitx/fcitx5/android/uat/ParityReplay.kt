/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.eval.parity.Header
import org.fcitx.fcitx5.android.eval.parity.Output
import org.fcitx.fcitx5.android.eval.parity.ParityFiles
import org.fcitx.fcitx5.android.eval.parity.Provenance
import org.fcitx.fcitx5.android.eval.parity.Sequence
import org.fcitx.fcitx5.android.eval.parity.State
import org.fcitx.fcitx5.android.eval.parity.Step
import org.fcitx.fcitx5.android.eval.parity.outputHeader
import org.fcitx.fcitx5.android.host.HostStrings
import org.fcitx.fcitx5.android.host.KeySyms
import java.io.File
import kotlin.system.exitProcess

/**
 * A parity sequence file run on the Kotlin host and the real engines ([HostReplay]), as a device
 * runs it with the test editor (uat/parity/parity.py): each sequence from a fresh install, the
 * header's input methods enabled, what the user sees after each step written as an output file
 * that `ime-eval parity-compare --jvm` compares with a device's (fcitx-replacement §7.2).
 *
 *   ./gradlew :lib:ime-eval:parityReplay --args="<sequences.jsonl> <out.jsonl> [<first n> | <k>/<n> | all] [<output.jsonl>]"
 *
 * With an output file (a device's run of a sample): only the sequences it has, in their file order.
 * About a second a sequence (the refine at each pause runs the large model on the JVM's kernel):
 * the 10k of a random file in shards, `k/n` the sequences whose place in the file is k modulo n,
 * one process each; `parity-compare` takes the shards' outputs together.
 */
object ParityReplay {
    /** The test editors' uids (two apps: parity.py's editor packages a and b). */
    private const val APP_A = 1
    private const val APP_B = 2

    fun run(header: Header, sequence: Sequence): Output {
        val states = ArrayList<State>()
        return try {
            val replay = HostReplay(
                engines = UatEngines.fresh(), im = sequence.im, field = field(sequence.field), inputMethods = header.ims,
                strings = DEVICE_STRINGS, firstField = fieldName(APP_A, sequence.field),
            )
            var app = APP_A
            val screen = Screen(replay)
            for (step in sequence.steps) {
                screen.before(step)
                app = apply(replay, step, app)
                states += screen.after(step)
            }
            Output(sequence.id, states)
        } catch (e: AssertionError) {
            // a pick HostReplay refuses: the rest left out, the sequence stopped
            stopped(sequence, states, e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // a malformed step too (pick 0, a hw key not named): this sequence stopped, not the shard's
            // hours of output, written only at the end
            stopped(sequence, states, e)
        }
    }

    /** What the device's screen shows around the host's panel, and what the driver does to get at it. */
    private class Screen(private val replay: HostReplay) {
        // another app brought up keeps showing the bar and code row the last one had, until the host
        // next updates them or the input method changes (the app's views, on either host: native
        // shows it): what the bar showed then
        private var stale: State? = null

        // what the bar shows, under a hardware key's floating window too (updated there as ever); the
        // screen's keyboard used again (any step but a hardware key) brings the bar back
        private var bar: State? = null
        private var floating = false
        private var updates = 0

        // the keyboard put away by a single-line field's editor action
        private var hidden = false
        private var actions = 0
        private var floated = 0

        fun before(step: Step) {
            if (hidden && step.op in SCREEN_KEYS) {
                // the driver's need_keyboard: the field tapped (at its end, the text short) to bring it back
                replay.keyboardShown(tapped = true)
                hidden = false
            } else if (hidden && step.op == "hw" && step.arg == "Return") {
                // a hardware key with a character shows it again (InputDeviceManager.evaluateOnKeyDown)
                replay.keyboardShown(tapped = false)
                hidden = false
            }
            if (step.op == "app" || step.op == "field") hidden = false
            // the screen's keyboard wanted back from under the floating window: the driver's need_keyboard
            // taps the field, at its end (a tall field's bottom right, a short one's middle past its text)
            if (floating && step.op in SCREEN_KEYS) replay.tappedAtEnd()
            if (step.op != "hw") floating = false
            if (step.op == "app") stale = bar
            actions = replay.editorActions
            floated = replay.floatingShown
        }

        /** What the device shows after [step]. */
        fun after(step: Step): State {
            if (step.op == "app") updates = replay.panelUpdates + replay.switches
            if (replay.panelUpdates + replay.switches != updates) stale = null
            if (replay.floatingShown != floated) floating = true
            if (replay.editorActions != actions) {
                replay.keyboardHidden()
                hidden = true
            }
            val now = state(replay)
            val onBar = stale?.let { now.copy(preedit = it.preedit, cands = it.cands) } ?: now
            bar = onBar
            // the floating window shows the panel as it is
            return if (floating) now else onBar
        }
    }

    // a step on the replay; the app in front after it
    private fun apply(replay: HostReplay, step: Step, front: Int): Int {
        var app = front
        when (step.op) {
            "type" -> replay.type(step.arg!!)
            "space" -> replay.space()
            "enter" -> replay.enter()
            "bksp" -> replay.backspace(step.arg!!.toInt())
            // the bar's nth, nothing if it has fewer, as the device's driver does
            "pick" -> (step.arg!!.toInt() - 1).takeIf { it < replay.page.size }?.let(replay::pickShown)
            "pickText" -> step.arg!!.takeIf { it in replay.candidates }?.let(replay::pick)
            "hw" -> replay.hardware(HW.getValue(step.arg!!))
            "ime" -> replay.ime(step.arg!!)
            "field" -> replay.focus("", field(step.arg!!), app = app, name = fieldName(app, step.arg!!))
            "app" -> {
                app = if (app == APP_A) APP_B else APP_A
                replay.focus("", EngineReplay.Field.TEXT, app = app, name = fieldName(app, "text"))
            }
            "pause" -> replay.pause()
            "toggle" -> {
                // the driver's way to the toolbar (uat.py expand_toolbar): next-word suggestions holding
                // the bar, the keyboard put away and the field tapped to bring it back without them
                if (replay.preedit.isEmpty() && replay.page.isNotEmpty()) replay.keyboardHiddenAndShown()
                replay.toggle(step.arg!!)
            }
            "quick" -> replay.quick()
            "unicode" -> replay.unicode()
            else -> error("no step ${step.op}")
        }
        return app
    }

    // the steps the driver taps the screen's keyboard for (uat.py's keys(), its need_keyboard)
    private val SCREEN_KEYS = setOf("type", "space", "enter", "bksp", "ime", "toggle", "quick", "unicode")

    // a field as the service tells one from another (ContextMemory.field): the editor app's package and
    // the kind of field, each kind one EditText the editor makes again on each start
    private fun fieldName(app: Int, kind: String) = "editor$app/$kind"

    private fun stopped(sequence: Sequence, states: List<State>, e: Throwable) =
        Output(sequence.id, states, "${e.javaClass.simpleName}: ${e.message}")

    private fun state(r: HostReplay): State {
        val password = r.field == EngineReplay.Field.PASSWORD
        val text = if (password) State.mask(r.text) else r.text
        return State(text, r.editor.selectionStart to r.editor.selectionEnd, r.row, r.shown)
    }

    private fun field(kind: String) = when (kind) {
        "text" -> EngineReplay.Field.TEXT
        "password" -> EngineReplay.Field.PASSWORD
        "url" -> EngineReplay.Field.URL
        "email" -> EngineReplay.Field.EMAIL
        else -> error("no field $kind")
    }

    private val HW = mapOf("Left" to KeySyms.LEFT, "Right" to EngineReplay.RIGHT, "BackSpace" to KeySyms.BACKSPACE, "Return" to KeySyms.RETURN)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size !in 2..4) {
            System.err.println("usage: <sequences.jsonl> <out.jsonl> [<first n> | <k>/<n> | all] [<output.jsonl>]")
            exitProcess(2)
        }
        val (header, file) = ParityFiles.sequences(File(args[0]))
        // a device's sample: the ids its output has
        val all = args.getOrNull(3)?.let { out -> ParityFiles.outputs(File(out)).second.map { it.id }.toSet() }
            ?.let { ids -> file.filter { it.id in ids } } ?: file
        val sequences = when (val part = args.getOrNull(2)) {
            null, "all" -> all
            else -> part.split('/').map(String::toInt).let { kn ->
                if (kn.size == 2) all.filterIndexed { i, _ -> i % kn[1] == kn[0] } else all.take(kn[0])
            }
        }
        // the engine this replay ran on, which parity-compare --jvm holds to the recording's (F3990); run from :lib:ime-eval.
        // Taken before the hours of replay: a checkout changed meanwhile is not what ran, and a hash that throws loses nothing
        val ran = JsonObject(Provenance.fingerprint(File("../..")))
        val start = System.nanoTime()
        val outputs = sequences.mapIndexed { i, s ->
            run(header, s).also { if ((i + 1) % PROGRESS == 0) println("${i + 1}/${sequences.size}") }
        }
        ParityFiles.writeOutputs(File(args[1]), outputHeader(header, "jvm").with(Header.ENGINE_RUN to ran), outputs)
        val seconds = (System.nanoTime() - start) / NANOS
        println("${outputs.size} sequences in ${seconds}s, ${outputs.count { it.error != null }} stopped: ${args[1]}")
        UatEngines.settle()
        exitProcess(0)
    }

    // the prompts as the parity devices show them, in their zh-CN (fcitx's English when not run by Gradle)
    private val DEVICE_STRINGS: HostStrings by lazy { ResourceHostStrings.device() ?: HostStrings.ENGLISH }

    private const val PROGRESS = 500
    private const val NANOS = 1_000_000_000L
}
