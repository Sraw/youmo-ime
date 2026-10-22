/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.host.ModelLevel
import org.fcitx.fcitx5.android.engine.host.TableSettings
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The numbers and the names the androidengine addon and ime-core agree on, as the addon's headers
 * have them: nothing else holds the two sides together, and a slip gives a long press another
 * word's action, or a setting to a key the engine never reads.
 */
class AndroidEngineHeaderTest {

    // by the path the build gives (build.gradle.kts), wherever the test runs from
    private fun read(name: String) =
        File(checkNotNull(System.getProperty("header.$name")) { "no path to $name given: run by Gradle" }).readText()

    private val header = read("androidengine_public.h")
    private val config = read("androidengine.h")
    private val addon = read("androidengine.cpp")

    /** The entries of the C++ enum [name], each name to what it is set to. */
    private fun entries(name: String): Map<String, String> {
        val found = checkNotNull(Regex("""enum (?:class )?$name : int \{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(header)) {
            "enum $name not found in androidengine_public.h"
        }
        val body = found.groupValues[1].replace(Regex("//.*"), "")
        return Regex("""(\w+) = ([^,]+),""").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** The options of the configuration [name] in androidengine.h, each key to its type, the ENGINE_ macros in it read out. */
    private fun options(name: String): Map<String, String> {
        val macros = Regex("""#define (ENGINE_\w+)((?:.*\\\n)*.*)""").findAll(config).associate { it.groupValues[1] to it.groupValues[2] }
        val start = checkNotNull(Regex("""FCITX_CONFIGURATION\(\s*$name,""").find(config)) {
            "configuration $name not found in androidengine.h"
        }.range.last + 1
        // to the parenthesis that closes it: those in it come in pairs
        var depth = 1
        val end = (start until config.length).first { i ->
            when (config[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            depth == 0
        }
        val body = Regex("""\bENGINE_\w+""").replace(config.substring(start, end)) { macros.getValue(it.value) }
        return Regex("""\bOption(?:WithAnnotation)?<([^>]*)>\s*\w+\{[\s\\]*this,\s*"(\w+)"""").findAll(body)
            .associate { it.groupValues[2] to it.groupValues[1].substringBefore(',').trim() }
    }

    /** The names the C++ enum [name] is saved by, as its FCITX_CONFIG_ENUM_NAME_WITH_I18N lists them. */
    private fun enumNames(name: String): List<String> {
        val found = checkNotNull(Regex("""FCITX_CONFIG_ENUM_NAME_WITH_I18N\(\s*$name,(.*?)\)\s*\n""", RegexOption.DOT_MATCHES_ALL).find(config)) {
            "the names of $name not found in androidengine.h"
        }
        return Regex("""N_\("([^"]*)"\)""").findAll(found.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    // the keyboard's private-use characters the addon swallows, never text: the same numbers
    @Test
    fun theCharactersNeverTypedAreTheKeyboards() {
        fun constant(name: String): Int = checkNotNull(Regex("""constexpr uint32_t $name = ([^;]+);""").find(addon)) { "$name not in androidengine.cpp" }
            .groupValues[1].split('+').sumOf { part ->
                val p = part.trim()
                when {
                    p.startsWith("0x") -> p.removePrefix("0x").toInt(16)
                    p.first().isDigit() -> p.toInt()
                    else -> constant(p)
                }
            }
        assertEquals(Keyboard.GLIDE, constant("GlideChar"))
        assertEquals(Keyboard.NINE_DIGITS.first, constant("NineKeysFirst"))
        assertEquals(Keyboard.NINE_LETTERS.first, constant("NineLettersFirst"))
        assertEquals(Keyboard.NINE_LETTERS.last + 1, constant("NineKeysEnd"))
    }

    @Test
    fun eachEventIsNumberedAsTheAddonNumbersIt() {
        val addon = entries("EngineEvent").map { (name, value) ->
            name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").uppercase() to value.toInt()
        }.toMap()
        val engine = EngineEvent::class.java.declaredFields.filter { !it.isSynthetic && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertEquals(addon, engine)
    }

    @Test
    fun eachOfferIsTheBitTheAddonReadsForIt() {
        val addon = entries("EngineOffer").map { (name, value) ->
            val (one, shift) = value.split("<<").map { it.trim().toInt() }
            name.removePrefix("Offer").replace(Regex("(?<=.)([A-Z])"), "_$1").uppercase() to (one shl shift)
        }.toMap()
        assertEquals(addon, Offer.entries.associate { it.name to it.bit })
        assertEquals(mapOf("FORGET" to 1, "PIN" to 2, "UNPIN" to 4, "BLOCK" to 8, "DELETE_PHRASE" to 16), addon)
    }

    // the addon hands the engine its config as these options save it (AndroidEngine::pushSettings):
    // a key of another name is dropped there, whatever the app wrote
    @Test
    fun eachOptionTheEngineReadsIsOneTheAddonKeeps() {
        val engine = options("AndroidEngineConfig")
        val groups = Engines.TABLES.values.map { it.group }
        val settings = with(EngineSettings) { setOf(SHUANGPIN_PROFILE, PAGE_SIZE, PREDICTION, EMOJI, CHAIZI, BUILT_IN_PHRASES, MODEL_LEVEL, LATIN_WORDS, FUZZY, ENGLISH_SUGGEST) }
        assertEquals(settings + groups, engine.keys)
        assertEquals("EngineFuzzyConfig", engine[EngineSettings.FUZZY])
        groups.forEach { assertEquals(it, "EngineTableConfig", engine[it]) }
        assertEquals(Fuzzy.entries.map { it.name }.toSet() + EngineSettings.TYPOS, options("EngineFuzzyConfig").keys)
        assertEquals(TableSettings.KEYS, options("EngineTableConfig").keys)
        // what the app's page of an input method shows, under the config's own keys
        for (page in listOf("EnginePinyinPage", "EngineShuangpinPage", "EngineEnglishPage")) {
            val shown = options(page)
            assertEquals(page, shown, engine.filterKeys(shown::containsKey))
        }
    }

    // the app's page of what every input method shares reads and writes pinyin's page
    // (InputMethodSettings.SHARED, SharedInputFragment): each of them must be on it, and be the
    // addon's own option, not a table's, which every session reads (EngineSettings, one for all)
    @Test
    fun theSharedOptionsAreOnPinyinsPageAndTheAddonsOwn() {
        val shared = with(EngineSettings) { setOf(PREDICTION, EMOJI, MODEL_LEVEL, FUZZY, PAGE_SIZE) }
        val pinyin = options("EnginePinyinPage")
        assertEquals(shared, shared intersect pinyin.keys)
        val engine = options("AndroidEngineConfig")
        shared.forEach { assertEquals(it, engine[it], pinyin[it]) }
    }

    // a level saved by another name is read as HIGH (EngineSettings.parse): 低档 would save no memory
    @Test
    fun eachModelLevelIsNamedAsTheAddonSavesIt() {
        assertEquals(enumNames("EngineModelLevel"), ModelLevel.entries.map { it.key })
    }
}
