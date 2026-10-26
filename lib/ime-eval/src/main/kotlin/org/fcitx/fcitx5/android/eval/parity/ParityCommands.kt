/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.eval.Command
import java.io.File

// relative to :lib:ime-eval, where `run` runs: the checkout's root
private fun repo(options: Map<String, String>) = File(options["repo"] ?: "../..")

private fun ims(options: Map<String, String>) = options["ims"]?.split(',') ?: SequenceGenerator.DEFAULT_IMS

/** The parity commands (fcitx-replacement §7.2; the device side is uat/parity/parity.py). */
internal val PARITY_COMMANDS = listOf(
    Command("parity-generate", "<out.jsonl> --seed <n> --count <n> [--ims <im>,...] [--repo <checkout>]", 1..1, "seed", "count", "ims", "repo") { args, out, _ ->
        val seed = args.options["seed"]?.toLongOrNull()
        val count = args.options["count"]?.toIntOrNull()?.takeIf { it > 0 }
        if (seed == null || count == null) {
            null
        } else {
            val ims = ims(args.options)
            val header = Provenance.header(repo(args.options), "random", ims, seed)
            ParityFiles.writeSequences(File(args.operands[0]), header, SequenceGenerator(ims, seed).generate(count))
            out.appendLine("$count sequences, seed $seed: ${args.operands[0]}")
            0
        }
    },
    Command("parity-seal", "<bare.jsonl> <out.jsonl> --kind <kind> [--ims <im>,...] [--repo <checkout>]", 2..2, "kind", "ims", "repo") { args, out, _ ->
        // sequences written without a header (uat/parity/parity.py uat-export) given this checkout's
        val kind = args.options["kind"] ?: return@Command null
        val (bare, to) = args.operands
        val sequences = File(bare).readLines().filter { it.isNotBlank() }.map { Sequence.of(ParityFiles.parse(it)) }
        ParityFiles.writeSequences(File(to), Provenance.header(repo(args.options), kind, ims(args.options)), sequences)
        out.appendLine("${sequences.size} sequences: $to")
        0
    },
    Command("parity-check", "<sequences.jsonl> [--repo <checkout>]", 1..1, "repo") { args, out, _ ->
        // the sequences' own check (their hash) is ParityFiles.sequences'; another engine is said, not refused:
        // a recording that cannot be made again (the native host's) is still compared, labelled
        val (header, sequences) = ParityFiles.sequences(File(args.operands[0]))
        val stale = Provenance.stale(header, repo(args.options))
        if (stale != null) out.appendLine("${Provenance.ENGINE_CHANGED} ($stale)")
        out.appendLine("${sequences.size} sequences; ims ${header.ims.joinToString(",")}")
        0
    },
    Command(
        "parity-compare", "<sequences.jsonl> <reference.jsonl>[,<shard.jsonl>...] <other.jsonl>[,<shard.jsonl>...] [--jvm --model <sentence-model.safetensors> --epsilon <e>] [--repo <checkout>]",
        3..3, "jvm", "model", "epsilon", "repo",
    ) { args, out, err ->
        val (seqPath, refPath, otherPath) = args.operands
        val jvm = "jvm" in args.options
        val epsilon = args.options["epsilon"]?.toFloatOrNull()
        if (jvm && (epsilon == null || "model" !in args.options)) return@Command null
        val (header, sequences) = ParityFiles.sequences(File(seqPath))
        // a run in shards (parityReplay's k/n, a device's sample on two redroids): their outputs together
        val refs = refPath.split(',').map { it to ParityFiles.outputs(File(it)) }
        val others = otherPath.split(',').map { it to ParityFiles.outputs(File(it)) }
        val refHeader = refs.first().second.first
        val otherHeader = others.first().second.first
        // refused, not compared: outputs of other sequences, input methods or engine say nothing of the host
        // (two device runs from different builds' sequence files among them)
        val refused = (refs + others).firstNotNullOfOrNull { (path, file) -> header.mismatch(file.first)?.let { "$path: $it" } }
        if (refused != null) {
            err.appendLine("refused: $refused")
            return@Command 1
        }
        // a JVM replay against a recording of another engine: compared, labelled, its own exit code. The engine
        // that ran the replay is its output's (engine-run), not the checkout comparing: a replay left from an
        // older checkout is told as such (F3990); an output without one is taken as this checkout's. Every
        // output, not the first: one stale shard among fresh ones is labelled too (F4268). Only the JVM's
        // (others): the reference is a device recording, never with an engine-run, and falling back to the
        // checkout for it labelled a fresh replay once the checkout had moved on (F4280)
        val changed = if (jvm) {
            val here by lazy { Provenance.stale(header, repo(args.options)) }
            others.firstNotNullOfOrNull { (_, file) ->
                val ran = file.first.fields[Header.ENGINE_RUN] as? JsonObject
                if (ran != null) Provenance.stale(header, ran) else here
            }
        } else {
            null
        }
        if (changed != null) out.appendLine("${Provenance.ENGINE_CHANGED} ($changed)")
        // loaded at the first swap to weigh (hundreds of MB)
        val scores = args.options["model"]?.let { m -> lazy { ModelScores(File(m)) } }
        val comparator = Comparator(jvm, epsilon ?: 0f) { c, a, b -> scores?.value?.delta(c, a, b) }
        val result = comparator.compare(sequences, refs.flatMap { it.second.second }, others.flatMap { it.second.second })
        out.appendLine("${refHeader[Header.HOST]} vs ${otherHeader[Header.HOST]}")
        Comparator.report(result, out)
        when {
            changed != null -> ENGINE_CHANGED_EXIT
            result.passed -> 0
            else -> 1
        }
    },
    Command("parity-rehead", "<file.jsonl>... [--repo <checkout>]", 1..Int.MAX_VALUE, "repo") { args, out, _ ->
        // sequence and output files given this checkout's fingerprint, the old one kept as engine-was; the
        // lines after the header are not touched, so the sequences' hash still holds
        args.operands.forEach { path ->
            val file = File(path)
            val lines = file.readText().split("\n")
            val header = Provenance.rehead(Header(ParityFiles.parse(lines.first())), repo(args.options))
            file.writeText((listOf(ParityFiles.encode(header.fields)) + lines.drop(1)).joinToString("\n"))
            out.appendLine("$path: ${header.fields[Header.ENGINE]}")
        }
        0
    },
    Command("parity-epsilon", "<sentence-model.safetensors> <outputs.jsonl> [--pairs <n>]", 2..2, "pairs") { args, out, _ ->
        val (model, outputs) = args.operands
        val pairs = args.options["pairs"]?.toIntOrNull() ?: Int.MAX_VALUE
        val (max, p999, n) = ModelScores.measure(File(model), ParityFiles.outputs(File(outputs)).second, limit = pairs)
        out.appendLine("$n neighbouring pairs: score difference moved by the arm64 kernel at most $max, 99.9% within $p999")
        0
    },
)

/** parity-compare's exit code when the JVM's engine is not the recording's: distinct from a pass (0) and a difference (1). */
const val ENGINE_CHANGED_EXIT = 3

/** An output file's header: [sequences]'s, the host that ran them added. */
fun outputHeader(sequences: Header, host: String, vararg more: Pair<String, String>): Header =
    sequences.with(Header.HOST to JsonPrimitive(host), *more.map { it.first to JsonPrimitive(it.second) }.toTypedArray())

