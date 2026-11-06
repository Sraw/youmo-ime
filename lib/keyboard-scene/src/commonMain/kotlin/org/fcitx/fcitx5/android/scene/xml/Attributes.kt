/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.xml

import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.SceneText

/**
 * An element's attributes read one by one, each problem noted with the element's line; what was never
 * asked for is unknown ([done]), so a misspelt attribute is an error, not silently nothing.
 */
class Attributes(private val element: XmlElement, private val problems: Problems) {
    private val asked = HashSet<String>()

    val line: Int get() = element.line

    fun problem(message: String) = problems.add(element.line, "<${element.name}>: $message")

    fun string(name: String): String? {
        asked += name
        return element[name]
    }

    fun required(name: String): String? = string(name) ?: null.also { problem("$name= is required") }

    /** [name] as [read] makes it, a problem where it returns null. */
    fun <T : Any> value(name: String, what: String, read: (String) -> T?): T? {
        val raw = string(name) ?: return null
        return read(raw) ?: null.also { problem("$name=\"$raw\" is not $what") }
    }

    fun matching(name: String, pattern: Regex, what: String): String? = value(name, what) { it.takeIf(pattern::matches) }

    fun oneOf(name: String, values: Collection<String>): String? = value(name, values.joinToString("|")) { it.takeIf { v -> v in values } }

    fun boolean(name: String): Boolean? = value(name, "true or false") {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    /** A decimal in ([min], [max]] or [[min], [max]] where [minIncluded]. */
    fun decimal(name: String, min: Double, max: Double, minIncluded: Boolean): Double? {
        val range = if (minIncluded) "[${SceneText.n(min)}, ${SceneText.n(max)}]" else "(${SceneText.n(min)}, ${SceneText.n(max)}]"
        return value(name, "a number in $range") { raw ->
            decimal(raw)?.takeIf { (if (minIncluded) it >= min else it > min) && it <= max }
        }
    }

    fun positiveInt(name: String): Int? = value(name, "a positive whole number") { raw ->
        raw.takeIf(WHOLE::matches)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Notes every attribute not asked for, but namespace declarations. */
    fun done() {
        element.attributes.keys.filter { it !in asked && it != "xmlns" && !it.startsWith("xmlns:") }
            .forEach { problem("unknown attribute $it=") }
    }

    companion object {
        private val DECIMAL = Regex("[+-]?([0-9]{1,9}(\\.[0-9]{0,9})?|\\.[0-9]{1,9})")
        private val WHOLE = Regex("[0-9]{1,6}")

        /**
         * [raw] as a decimal where it is one as XSD writes them (no exponent, no `Infinity`, no hex):
         * the JVM's and JS's own number parsing differ past that, and a file must read the same on both.
         */
        fun decimal(raw: String): Double? = raw.takeIf(DECIMAL::matches)?.toDoubleOrNull()
    }
}
