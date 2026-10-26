/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.LearningUse
import java.io.File
import java.nio.file.Files

/**
 * What the user sees after doing something: scripted behaviour (type, pick, a context, a day
 * passing) run through the pinyin sessions as the app has them, one user per scenario ([EvalUser]:
 * its learning record and user model, a `day` its clock a calendar day on), and
 * the order of the candidates then checked against what the spec of learning
 * (`dev/design/learning-ux/final/draft-simple.md`) says. The scripts are in `data/learn-ux`; the
 * format is [LearnUxScript]'s.
 *
 * An expectation is [Status.PENDING] where the engine cannot do yet what comes before it: in a
 * scenario marked `pending`, after a `todo` (an action
 * not driven yet: a table, handwriting, an import) or a keyboard this runner does not make.
 */
class LearnUx(
    private val data: PinyinData,
    /** Makes a session's reranker, as the app gives each session its own. */
    private val reranker: (() -> SentencePicker)? = null,
    private val refiner: (() -> SentenceRefiner)? = null,
    private val use: LearningUse = LearningUse.DEFAULT,
    /** Committed (picked as expected) before each scenario, into its learning record: a user's history. */
    private val seed: List<Sample> = emptyList(),
) {
    enum class Status { PASS, FAIL, PENDING, NOTE }

    /** An expectation's outcome (or a `show`'s list, a [Status.NOTE]); [detail] what was shown, or why it is pending. */
    data class Result(val scenario: Scenario, val line: Int, val text: String, val status: Status, val detail: String = "")

    fun run(scenarios: List<Scenario>): List<Result> {
        // the seed's learning record, typed once a run and copied for each scenario: none without a seed or scenarios
        val seeded = if (seed.isEmpty() || scenarios.isEmpty()) null else seedLog()
        return try {
            scenarios.flatMap { scenario -> runSeeded(scenario, seeded) }
        } finally {
            seeded?.parentFile?.deleteRecursively()
        }
    }

    private fun runSeeded(scenario: Scenario, seeded: File?): List<Result> {
        val dir = seeded?.let { Files.createTempDirectory("learn-ux").toFile() }
        try {
            val user = if (dir == null) EvalUser(data) else EvalUser(data, checkNotNull(seeded).copyTo(File(dir, LearnLog.FILE)))
            try {
                return Run(scenario, user).results()
            } finally {
                user.log.close()
            }
        } finally {
            dir?.deleteRecursively()
        }
    }

    // without the sentence models: the text committed is the sample's either way, and thousands are typed.
    // Typed with today's use whatever is measured: the seed is the user's past, the same for every
    // setting compared; what it learned changes with the ranking. All on one day, the scenario's first
    private fun seedLog(): File {
        val dir = Files.createTempDirectory("learn-ux-seed").toFile()
        val user = EvalUser(data, File(dir, LearnLog.FILE))
        val segmenter = PinyinSegmenter(neighbours = true, latin = LatinWords.of(data.dictionary))
        val typist = KeystrokeRun(PinyinSession(data, segmenter, user = user.model, prediction = false, use = LearningUse.DEFAULT, learnTo = user.learnTo()))
        seed.forEach { typist.type(it) }
        user.log.close()
        return File(dir, LearnLog.FILE)
    }

    private fun session(segmenter: Segmenter, spell: Boolean, user: EvalUser, kind: String) = PinyinSession(
        data, segmenter, spell = spell, user = user.model, use = use, learnTo = user.learnTo(kind), addedWords = { user.added },
        refiner = refiner?.invoke(), reranker = reranker?.invoke(),
    )

    /** One scenario run: its own [user], and the sessions made as its keyboards are used. */
    private inner class Run(private val scenario: Scenario, private val user: EvalUser) {
        private val sessions = HashMap<String, PinyinSession>()
        private var active: PinyinSession? = null
        private var context: String? = null // to give the next session typed in: "" for none
        private var cleared = false // that context is a field's that cleared itself
        private var reached = 0 // the expectations come to, of [expects]
        private var typing = false // something typed and not committed
        private var predicting = false // 联想 shown after a commit, nothing typed
        private var undriven = false // typed on a keyboard not driven yet: what is done with it is skipped
        private val commits = ArrayList<String>()
        private var pending = scenario.pending // why what follows is pending, if it is
        private val out = ArrayList<Result>()

        fun results(): List<Result> {
            try {
                scenario.steps.forEach(::step)
            } catch (e: Stop) {
                // what a pending scenario cannot do yet is no failure either
                val status = if (pending == null) Status.FAIL else Status.PENDING
                val why = listOfNotNull(pending?.let { "($it)" }, e.message).joinToString(" ")
                // each expectation not come to is one too: the count is the script's, however far a run gets
                val left = expects(scenario.steps).drop(reached)
                // an expectation that stopped the run is come to: its own row, with why
                if (e.onExpect || left.isEmpty()) out += Result(scenario, e.line, e.text, status, why)
                left.forEach { out += Result(scenario, it.line, it.source, status, "not reached: stopped at line ${e.line} (${e.text}): $why") }
            }
            return out
        }

        private fun step(s: Step) {
            // what is done with keys typed on a keyboard not driven
            if (s is Step.Expect) reached++
            if (undriven && s.onInput) {
                if (s is Step.Expect) out += Result(scenario, s.line, s.source, Status.PENDING, "($pending)")
                return
            }
            when (s) {
                is Step.Ctx -> settle(s.text, s.cleared)
                is Step.Clear -> settle("", false)
                is Step.Type -> type(s)
                is Step.Pick -> pick(s)
                is Step.Space -> space(s)
                is Step.Del -> del(s)
                is Step.Day -> user.nextDay()
                is Step.Add -> user.add(s.text, s.reading)
                is Step.Todo -> pend("not driven yet: ${s.what}")
                is Step.Import -> import(s)
                is Step.Unimport -> unimport(s)
                is Step.Show -> out += Result(scenario, s.line, "show", Status.NOTE, shown(active(s)))
                is Step.Expect -> expect(s)
                is Step.Repeat -> repeat(s.times) { s.steps.forEach(::step) }
            }
        }

        // the first reason given stands
        private fun pend(why: String) {
            if (pending == null) pending = why
        }

        private fun pick(s: Step.Pick) {
            val session = active(s)
            val index = list(session, PICKED).indexOf(s.text)
            if (index < 0) throw Stop(s, "${s.text} not offered: ${shown(session)}")
            after(session, session.apply(Action.Pick(index)))
        }

        // a context taken, or none: what was typed and not committed is dropped
        private fun settle(text: String, cleared: Boolean) {
            if (typing || predicting) active?.apply(Action.Reset)
            typing = false
            predicting = false
            undriven = false
            context = text
            this.cleared = cleared
        }

        private fun type(s: Step.Type) {
            val session = sessionFor(s.kb)
            undriven = session == null
            if (session == null) {
                pend("keyboard ${s.kb} not driven yet")
                return
            }
            if (typing) throw Stop(s, "typed with ${shown(active!!)} not committed")
            predicting = false
            val given = context
            when {
                // as the host tells it: with the field's learn flags (null is the engine's own context)
                given != null -> session.apply(if (given.isEmpty()) Action.Reset else Action.Context(given, cleared, learnFlags = 0))
                // another keyboard: what this one committed is no context of that one
                session !== active -> session.apply(Action.Reset)
            }
            context = null
            active = session
            var shot: Snapshot? = null
            for (c in s.keys) shot = session.apply(Action.Key(c))
            shot?.let { after(session, it) }
        }

        // the user pauses, as KeystrokeRun's: the refiner's pick is what they see
        private fun after(session: PinyinSession, snapshot: Snapshot) {
            var shot = snapshot
            while (shot.refines) shot = session.apply(Action.Refine)
            if (shot.commit.isNotEmpty()) commits += shot.commit
            typing = shot.preedit.isNotEmpty()
            predicting = shot.predicting
        }

        // the app's space selects only while composing (Keyboard.kt): with only 联想 shown it types a space, not driven here
        private fun space(s: Step.Space) {
            val session = active(s)
            if (!typing) throw Stop(s, if (predicting) "space with only 联想 shown: the app's space types a space then" else "space with nothing typed: the app's space types a space then")
            after(session, session.apply(Action.Select(0)))
        }

        private fun import(s: Step.Import) {
            if (user.import(listOf(ImportedWords.Incoming(s.text, s.reading))) == 0) throw Stop(s, "${s.text} not imported: a word already")
        }

        private fun unimport(s: Step.Unimport) {
            if (!user.unimport()) throw Stop(s, "nothing imported")
        }

        // the last [Step.Del.commits] commits taken back by Backspace, a code point a press, as the app's Backspace sends them;
        // the last press's snapshot is what is shown then (Backspace drops the 联想)
        private fun del(s: Step.Del) {
            val session = active(s)
            if (typing) throw Stop(s, "del with ${shown(session)} not committed")
            if (s.commits > commits.size) throw Stop(s, "del ${s.commits}: only ${commits.size} committed")
            var shot: Snapshot? = null
            repeat(s.commits) {
                val text = commits.removeAt(commits.size - 1)
                repeat(text.codePointCount(0, text.length)) { shot = session.apply(Action.Backspace) }
            }
            shot?.let { after(session, it) }
        }

        private fun expect(s: Step.Expect) {
            val session = active(s)
            val list = list(session)
            val passed = s.holds(list)
            val status = when {
                pending != null -> Status.PENDING
                passed -> Status.PASS
                else -> Status.FAIL
            }
            val detail = buildString {
                if (pending != null) append("($pending; now ${if (passed) "passes" else "fails"}) ")
                if (status != Status.PASS) append("shown: ").append(list.take(SHOWN).joinToString(" "))
            }
            out += Result(scenario, s.line, s.source, status, detail)
        }

        // what was typed, or the 联想 after a commit: a pick, a space or an expectation then is of those
        private fun active(s: Step): PinyinSession {
            if (!typing && !predicting && s !is Step.Del) throw Stop(s, "nothing typed")
            return active ?: throw Stop(s, "nothing typed")
        }

        // in the order a run comes to them, a repeat's as often as it repeats
        private fun expects(steps: List<Step>): List<Step.Expect> = steps.flatMap { s ->
            when (s) {
                is Step.Expect -> listOf(s)
                is Step.Repeat -> List(s.times) { expects(s.steps) }.flatten()
                else -> emptyList()
            }
        }

        private fun list(session: PinyinSession, count: Int = LISTED) = session.candidates(0, count).map { it.text }

        private fun shown(session: PinyinSession) = list(session).take(SHOWN).joinToString(" ")

        /** The session of keyboard [kb], made on first use; null for one this runner does not make. */
        private fun sessionFor(kb: String): PinyinSession? {
            sessions[kb]?.let { return it }
            val made = when {
                kb == QP -> session(PinyinSegmenter(neighbours = true, latin = LatinWords.of(data.dictionary)), false, user, Kinds.FULL)
                // the nine keys' kind is the recorder's, from the keys pressed
                kb == T9 -> session(T9Segmenter(abbreviations = false), true, user, Kinds.FULL)
                kb.startsWith(SP) -> {
                    val name = kb.removePrefix(SP)
                    val scheme = EngineSettings.SCHEMES.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                        ?: throw IllegalArgumentException("${scenario.file}: no 双拼 scheme $name")
                    session(ShuangpinSegmenter(scheme.value), true, user, Kinds.shuangpin(scheme.key))
                }
                else -> return null
            }
            sessions[kb] = made
            return made
        }
    }

    private class Stop(step: Step, message: String) : Exception(message) {
        val line = step.line
        val text = (step as? Step.Expect)?.source ?: step.javaClass.simpleName.lowercase()
        val onExpect = step is Step.Expect
    }

    companion object {
        const val QP = "qp"
        const val T9 = "t9"
        const val SP = "sp:"

        // read for an expectation, and looked through for a pick (dl's 点了 is past the 50th), shown when one fails
        private const val LISTED = 200
        private const val PICKED = 5000
        const val SHOWN = 5

        /** The results, each a line, then the tally per scenario and in all. */
        fun report(results: List<Result>): String = buildString {
            var at: Scenario? = null
            for (r in results) {
                if (r.scenario !== at) {
                    at = r.scenario
                    appendLine("== ${r.scenario.file}: ${r.scenario.name}")
                }
                val where = "${r.scenario.file}:${r.line}"
                appendLine(String.format("%-8s %-22s %s%s", r.status, where, r.text, if (r.detail.isEmpty()) "" else "   ${r.detail}").trimEnd())
            }
            appendLine()
            val counted = results.filter { it.status != Status.NOTE }
            for ((scenario, of) in counted.groupBy { it.scenario }) appendLine(String.format("%-48s %s", "${scenario.file}: ${scenario.name}", tally(of)))
            append(String.format("%-48s %s", "all", tally(counted)))
        }

        private fun tally(results: List<Result>) =
            listOf(Status.PASS, Status.FAIL, Status.PENDING).joinToString("  ") { s -> "$s ${results.count { it.status == s }}" }
    }
}

/** A scenario of [LearnUxScript]: run on a user model of its own. [pending] says why its expectations are all pending, if they are. */
class Scenario(val name: String, val file: String, val pending: String?, val steps: List<Step>)

/** A line of a script, at [line] of its file. */
sealed class Step {
    abstract val line: Int

    /** Whether it acts on what was typed: skipped after keys a keyboard not driven yet typed. */
    open val onInput: Boolean get() = false

    /** The app's text before the cursor ([Action.Context]) for what is typed next. */
    data class Ctx(override val line: Int, val text: String, val cleared: Boolean = false) : Step()

    /** No text before the cursor: [Action.Reset]. */
    data class Clear(override val line: Int) : Step()

    /** [keys] typed on keyboard [kb] (`qp`, `sp:<scheme>`, `t9`; another is not driven yet), then a pause. */
    data class Type(override val line: Int, val keys: String, val kb: String) : Step()

    /** The candidate [text] picked, wherever it is in the list. */
    data class Pick(override val line: Int, val text: String) : Step() {
        override val onInput get() = true
    }

    /** Space: the first candidate. */
    data class Space(override val line: Int) : Step() {
        override val onInput get() = true
    }

    /** The last [commits] commits taken back by Backspace. */
    data class Del(override val line: Int, val commits: Int) : Step() {
        override val onInput get() = true
    }

    /** A typing day passes. */
    data class Day(override val line: Int) : Step()

    /** [text] read [reading] imported as a list of its own (U-101), as another input method's list is. */
    data class Import(override val line: Int, val text: String, val reading: String) : Step()

    /** The last list imported undone (U-102). */
    data class Unimport(override val line: Int) : Step()

    /** [text], read [reading], added by hand to the word list. */
    data class Add(override val line: Int, val text: String, val reading: String) : Step()

    /** Something this runner cannot do yet ([what]): what follows is pending. */
    data class Todo(override val line: Int, val what: String) : Step()

    /** The candidates shown, noted in the report. */
    data class Show(override val line: Int) : Step() {
        override val onInput get() = true
    }

    data class Repeat(override val line: Int, val times: Int, val steps: List<Step>) : Step()

    /** `expect <kind> <words>` as written ([source]), checked on the candidates shown (the 联想 after a commit). */
    data class Expect(override val line: Int, val source: String, val kind: Kind, val words: List<String>, val within: Int = 0) : Step() {
        override val onInput get() = true

        /** As written, but [TOP]: `top<n>`, n the words given. */
        enum class Kind(val words: Int) { FIRST(1), NOTFIRST(1), BEFORE(2), HAS(1), LACKS(1), TOP(0), WITHIN(1), LACKSN(1) }

        fun holds(list: List<String>): Boolean = when (kind) {
            Kind.FIRST -> list.firstOrNull() == words[0]
            Kind.NOTFIRST -> list.firstOrNull() != words[0]
            Kind.HAS -> words[0] in list
            Kind.LACKS -> words[0] !in list
            Kind.BEFORE -> list.indexOf(words[0]).let { a -> a >= 0 && list.indexOf(words[1]).let { b -> b < 0 || a < b } }
            // the first n, in this order
            Kind.TOP -> list.take(words.size) == words
            Kind.WITHIN -> words[0] in list.take(within)
            Kind.LACKSN -> words[0] !in list.take(within)
        }
    }
}

/**
 * The scenario scripts: plain lines, `#` a comment (quoting the spec line a step encodes).
 * ```
 * scenario <name>          a new scenario: a fresh user model
 * pending <why>            its expectations are pending (a later step of the plan makes them hold)
 * ctx <text>               the app's text before the cursor (Action.Context) for the next input;
 *                          in "quotes" to keep spaces at its ends ("我 ": text ending in a space)
 * ctx! <text>              the same, of a field that cleared itself (Action.Context.cleared: a message sent)
 * clear                    no text before the cursor (no 前文)
 * type <keys> [kb=<kb>]    keys typed: kb qp (full pinyin, the default), sp:<scheme>, t9; another (wubi, hw) is not driven yet
 * pick <text>              the candidate picked wherever it is (B; A where first); a piece of the input picks it alone (C);
 *                          the first of that text, whether it is a word or a sentence of words (点|了);
 *                          after a commit, with nothing typed, one of the 联想 offered (D)
 * space                    the first candidate (A), of what is typed: with only 联想 shown it stops the scenario
 *                          (the app's space types a space then, not driven here)
 * del <n>                  the last n commits deleted by Backspace
 * day                      a typing day passes: the clock a calendar day on, the next commit on the next
 * import <text> <pinyin>   the word imported as a list of its own (U-101): pinyin with ' between syllables
 * unimport                 the last list imported undone (U-102)
 * add <text> <reading>     a word added by hand to the word list, its syllables apart by spaces (U-65)
 * todo <what>              an action not driven yet: expectations after it are pending
 * show                     the candidates shown, noted
 * expect top<n> a,b,...    the first n are these, in this order
 * expect within<n> a       a among the first n
 * expect lacks<n> a        a not among the first n
 * expect first a | expect notfirst a | expect before a b | expect has a | expect lacks a
 *                          notfirst holds for no candidates; before holds where b is not in the first 200;
 *                          has / lacks: a among the first 200 or not. After a commit, with nothing typed,
 *                          an expectation (and show) is of the 联想 offered
 * repeat <n> {             the lines up to a `}` of their own, n times
 * ```
 */
object LearnUxScript {
    private val TOP = Regex("top([1-9][0-9]?)")
    private val WITHIN = Regex("within([1-9][0-9]?)")
    private val LACKSN = Regex("lacks([1-9][0-9]?)")

    fun parse(file: String, lines: List<String>): List<Scenario> = Parser(file).apply { lines.forEachIndexed { i, line -> line(i + 1, line.trim()) } }.done()

    private class Parser(private val file: String) {
        private val scenarios = ArrayList<Scenario>()
        private var name: String? = null
        private var pending: String? = null
        private var steps = ArrayList<Step>()

        // the repeats open, innermost last: the line each starts at, its count and its steps
        private val open = ArrayList<Triple<Int, Int, ArrayList<Step>>>()

        fun done(): List<Scenario> {
            close()
            return scenarios
        }

        private fun close() {
            if (open.isNotEmpty()) throw IllegalArgumentException("$file:${open.last().first}: repeat not closed")
            name?.let { scenarios += Scenario(it, file, pending, steps) }
            steps = ArrayList()
            pending = null
        }

        fun line(n: Int, line: String) {
            if (line.isEmpty() || line.startsWith("#")) return
            val bad = { why: String -> throw IllegalArgumentException("$file:$n: $why: $line") }
            val verb = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "").trim()
            if (verb == "scenario") {
                if (rest.isEmpty()) bad("no name")
                close()
                name = rest
                return
            }
            if (name == null) bad("before any scenario")
            when (verb) {
                "pending" -> if (open.isNotEmpty() || steps.isNotEmpty()) bad("pending goes first") else pending = rest.ifEmpty { "pending" }
                "repeat" -> {
                    val times = rest.takeIf { it.endsWith("{") }?.removeSuffix("{")?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: bad("repeat <n> {")
                    open += Triple(n, times, ArrayList())
                }
                "}" -> {
                    val (at, times, inner) = open.removeLastOrNull() ?: bad("no repeat to close")
                    (open.lastOrNull()?.third ?: steps) += Step.Repeat(at, times, inner)
                }
                else -> (open.lastOrNull()?.third ?: steps) += step(n, line, verb, rest, bad)
            }
        }

        private fun step(n: Int, line: String, verb: String, rest: String, bad: (String) -> Nothing): Step = when (verb) {
            "ctx", "ctx!" -> ctx(n, rest, cleared = verb == "ctx!", bad)
            "clear" -> Step.Clear(n)
            "type" -> type(n, rest, bad)
            "pick" -> Step.Pick(n, rest.ifEmpty { bad("no text") })
            "space" -> Step.Space(n)
            "del" -> Step.Del(n, rest.toIntOrNull()?.takeIf { it > 0 } ?: bad("del <n>"))
            "day" -> Step.Day(n)
            "add" -> rest.split(' ', limit = 2).let { if (it.size < 2) bad("add <text> <reading>") else Step.Add(n, it[0], it[1]) }
            "todo" -> Step.Todo(n, rest.ifEmpty { bad("todo <what>") })
            "import" -> import(n, rest, bad)
            "unimport" -> Step.Unimport(n)
            "show" -> Step.Show(n)
            "expect" -> expect(n, line, rest, bad)
            else -> bad("unknown")
        }

        private fun type(n: Int, rest: String, bad: (String) -> Nothing): Step {
            val parts = rest.split(Regex("\\s+"))
            if (rest.isEmpty() || parts.size > 2) bad("type <keys> [kb=<kb>]")
            val kb = parts.getOrNull(1)?.let { if (it.startsWith("kb=")) it.removePrefix("kb=") else bad("not kb=") } ?: LearnUx.QP
            return Step.Type(n, parts[0], kb)
        }

        // in quotes to keep spaces at its ends, the line being trimmed
        private fun ctx(n: Int, rest: String, cleared: Boolean, bad: (String) -> Nothing): Step {
            val text = if (rest.length >= 2 && rest.startsWith('"') && rest.endsWith('"')) rest.substring(1, rest.length - 1) else rest
            return Step.Ctx(n, text.ifEmpty { bad("no text") }, cleared)
        }

        private fun import(n: Int, rest: String, bad: (String) -> Nothing): Step {
            val parts = rest.split(Regex("\\s+"))
            if (parts.size != 2 || parts[1].any { it !in 'a'..'z' && it != '\'' }) bad("import <text> <pin'yin>")
            return Step.Import(n, parts[0], parts[1].replace('\'', ' '))
        }

        private fun expect(n: Int, line: String, rest: String, bad: (String) -> Nothing): Step {
            val name = rest.substringBefore(' ')
            val words = rest.substringAfter(' ', "").trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            val top = TOP.matchEntire(name)?.groupValues?.get(1)?.toInt()
            val lacks = LACKSN.matchEntire(name)?.groupValues?.get(1)?.toInt()
            val within = WITHIN.matchEntire(name)?.groupValues?.get(1)?.toInt() ?: lacks
            val counted = setOf(Step.Expect.Kind.TOP, Step.Expect.Kind.WITHIN, Step.Expect.Kind.LACKSN)
            val kind = when {
                top != null -> Step.Expect.Kind.TOP
                lacks != null -> Step.Expect.Kind.LACKSN
                within != null -> Step.Expect.Kind.WITHIN
                else -> Step.Expect.Kind.entries.firstOrNull { it !in counted && it.name.lowercase() == name }
            }
            val wanted = top ?: kind?.words ?: bad("expect top<n>|within<n>|lacks<n>|first|notfirst|before|has|lacks")
            if (words.size != wanted) bad("$wanted words wanted")
            return Step.Expect(n, line, checkNotNull(kind), words, within ?: 0)
        }
    }
}
