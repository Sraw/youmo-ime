/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/*
 * The parity files (fcitx-replacement §7.2), JSON lines the device tools (uat/parity/parity.py)
 * read and write too: a sequence file is a header line, then one [Sequence] a line; an output file
 * (a device's recording or the JVM's replay of one) is the sequence file's header with what made it
 * added, then one [Output] a line, in the sequence file's order.
 */

/** What a user does at one step: [op] one of [OPS], [arg] its argument (the keys, a count, a name), if it takes one. */
data class Step(val op: String, val arg: String? = null) {
    init {
        require(op in OPS) { "no step $op" }
        require((arg != null) == (op in TAKES_ARG)) { "$op ${if (arg == null) "needs" else "takes no"} argument" }
    }

    fun toJson(): JsonArray = buildJsonArray {
        add(JsonPrimitive(op))
        arg?.let { add(JsonPrimitive(it)) }
    }

    override fun toString() = if (arg == null) op else "$op $arg"

    companion object {
        /**
         * type: keys tapped on the screen's keyboard (letters, the nine keys' digits, `,` `.`);
         * space, enter; bksp: the backspace key [arg] times; pick: the [arg]th (1-based) candidate
         * on the bar, nothing if there are fewer; pickText: the candidate reading [arg], nothing
         * if none does; hw: a hardware key by name ([HW_KEYS], `adb shell input keyevent` on a
         * device); ime: the input method named [arg] (one of the header's ims); field: a new field
         * of kind [arg] ([FIELDS]) in the same app; app: the other test editor app (another uid),
         * a text field; pause: nothing, the user waits; toggle: the status area's [TOGGLES] entry
         * [arg] tapped (a Chinese input method's: 简繁, 标点, 全角); quick, unicode: quick input and
         * Unicode search opened (the 符 and 123 keys held).
         */
        val OPS = setOf("type", "space", "enter", "bksp", "pick", "pickText", "hw", "ime", "field", "app", "pause", "toggle", "quick", "unicode")
        private val TAKES_ARG = setOf("type", "bksp", "pick", "pickText", "hw", "ime", "field", "toggle")
        val TOGGLES = listOf("chttrans", "punctuation", "fullwidth")
        val FIELDS = listOf("text", "password", "url", "email")
        val HW_KEYS = listOf("Left", "Right", "BackSpace", "Return")

        fun of(json: JsonElement): Step {
            val a = json.jsonArray
            require(a.size in 1..2) { "a step is [op] or [op, arg]: $json" }
            return Step(a[0].jsonPrimitive.content, a.getOrNull(1)?.jsonPrimitive?.content)
        }
    }
}

/** One sequence: its [id], the input method and field it starts in, its [steps]. */
data class Sequence(val id: String, val im: String, val field: String, val steps: List<Step>) {
    init {
        require(field in Step.FIELDS) { "no field $field" }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("im", im)
        put("field", field)
        put("steps", JsonArray(steps.map { it.toJson() }))
    }

    companion object {
        fun of(o: JsonObject) = Sequence(
            o.string("id"), o.string("im"), o.string("field"),
            o.getValue("steps").jsonArray.map(Step::of),
        )
    }
}

/**
 * What the user sees after a step: the field's [text] and selection [sel], the code shown above
 * the candidates ([preedit]), the candidates on the bar ([cands]; a device's bar shows as many as fit,
 * the JVM's its whole page). [ic]: the `InputConnection` calls the step made, as the test editor
 * recorded them (`[name, args…]`); a device's only. A password field's text and arguments are masked
 * (`•` for each character): its recording keeps only what happened, not what was typed. [busy]: the
 * recorder's wait for the engine to go idle ran out before this state was read (parity.py), so it
 * may show the step half done.
 */
data class State(
    val text: String,
    val sel: Pair<Int, Int>,
    val preedit: String,
    val cands: List<String>,
    val ic: List<JsonArray>? = null,
    val busy: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("text", text)
        put("sel", buildJsonArray { add(JsonPrimitive(sel.first)); add(JsonPrimitive(sel.second)) })
        put("pre", preedit)
        put("cands", JsonArray(cands.map(::JsonPrimitive)))
        ic?.let { put("ic", JsonArray(it)) }
        if (busy) put("busy", true)
    }

    companion object {
        fun of(o: JsonObject): State {
            val sel = o.getValue("sel").jsonArray
            return State(
                o.string("text"), sel[0].jsonPrimitive.int to sel[1].jsonPrimitive.int, o.string("pre"),
                o.getValue("cands").jsonArray.map { it.jsonPrimitive.content },
                o["ic"]?.jsonArray?.map { it.jsonArray },
                o["busy"]?.jsonPrimitive?.boolean ?: false,
            )
        }

        /** [text] as a password field's recording keeps it. */
        fun mask(text: String) = "•".repeat(text.codePointCount(0, text.length))
    }
}

/** What one [Sequence] gave: a [State] after each of its steps; [error] if the run could not go on (the rest left out). */
data class Output(val id: String, val states: List<State>, val error: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("states", JsonArray(states.map { it.toJson() }))
        error?.let { put("error", it) }
    }

    companion object {
        fun of(o: JsonObject) = Output(
            o.string("id"), o.getValue("states").jsonArray.map { State.of(it.jsonObject) },
            o["error"]?.jsonPrimitive?.content,
        )
    }
}

/**
 * A parity file's first line. What makes two files comparable ([mismatch]): the engine
 * ([ENGINE], [Provenance.engine]: behaviour version and config digest), the data releases ([DATA]), the input methods
 * enabled in that order ([IMS]) and the sequences ([SEQUENCES], a hash of the sequence file's lines
 * after the header). An output adds [HOST] (native, kotlin, jvm) and what else made it.
 */
data class Header(val fields: JsonObject) {
    val ims: List<String> get() = fields.getValue(IMS).jsonArray.map { it.jsonPrimitive.content }

    operator fun get(key: String): String? = (fields[key] as? JsonPrimitive)?.content

    fun with(vararg added: Pair<String, JsonElement>) = Header(JsonObject(fields + added))

    /** Why [other] cannot be compared with this one, or null if it can. */
    fun mismatch(other: Header): String? = COMPARED.firstOrNull { fields[it] != other.fields[it] }
        ?.let { "$it differs: ${fields[it]} vs ${other.fields[it]}" }

    companion object {
        const val VERSION = "parity"
        const val ENGINE = "engine"
        const val DATA = "data"
        const val IMS = "ims"
        const val SEQUENCES = "sequences"
        const val HOST = "host"
        const val KIND = "kind"
        const val SEED = "seed"
        const val ENGINE_WAS = "engine-was"
        const val ENGINE_RUN = "engine-run"
        private val COMPARED = listOf(VERSION, ENGINE, DATA, IMS, SEQUENCES)
    }
}

object ParityFiles {
    private val json = Json { prettyPrint = false }

    private fun lines(file: File): List<String> = file.readLines().filter { it.isNotBlank() }

    fun header(file: File): Header = Header(parse(lines(file).first()))

    fun sequences(file: File): Pair<Header, List<Sequence>> {
        val ls = lines(file)
        val header = Header(parse(ls.first()))
        val body = ls.drop(1)
        // a file edited after its header was written (or a header from another file) is not the one recorded
        check(header[Header.SEQUENCES] == hash(body)) { "$file: its sequences are not the ones its header was written for" }
        return header to body.map { Sequence.of(parse(it)) }
    }

    fun outputs(file: File): Pair<Header, List<Output>> {
        val ls = lines(file)
        return Header(parse(ls.first())) to ls.drop(1).map { Output.of(parse(it)) }
    }

    /** [sequences] under [header], whose [Header.SEQUENCES] is set to theirs. */
    fun writeSequences(file: File, header: Header, sequences: List<Sequence>): Header {
        val body = sequences.map { json.encodeToString(JsonObject.serializer(), it.toJson()) }
        val sealed = header.with(Header.SEQUENCES to JsonPrimitive(hash(body)))
        file.writeText((listOf(encode(sealed.fields)) + body).joinToString("\n", postfix = "\n"))
        return sealed
    }

    fun writeOutputs(file: File, header: Header, outputs: List<Output>) {
        file.writeText((listOf(encode(header.fields)) + outputs.map { encode(it.toJson()) }).joinToString("\n", postfix = "\n"))
    }

    fun encode(o: JsonObject): String = json.encodeToString(JsonObject.serializer(), o)

    fun parse(line: String): JsonObject = json.parseToJsonElement(line).jsonObject

    /** The sequences' hash: of their lines as written, each ended by a newline. */
    fun hash(body: List<String>): String = sha256 { md -> body.forEach { md.update((it + "\n").toByteArray()) } }

    fun sha256(feed: (MessageDigest) -> Unit): String =
        MessageDigest.getInstance("SHA-256").also(feed).digest().joinToString("") { "%02x".format(it) }
}

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
