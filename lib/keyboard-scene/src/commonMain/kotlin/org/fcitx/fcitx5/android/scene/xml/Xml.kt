/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.xml

import org.fcitx.fcitx5.android.scene.Parsed

/**
 * An element as read: its [name] (never prefixed: such an element is refused), its [attributes] in the order written
 * (prefixed ones, `xmlns` among them, kept as written), its [children] and the [line] it starts on.
 */
class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    val text: String,
    val line: Int,
    /** Where it is in the source, only where it was asked for ([Xml.parse]'s `offsets`). */
    val offsets: XmlOffsets? = null,
) {
    operator fun get(attribute: String): String? = attributes[attribute]

    /** Its children named [name]. */
    fun all(name: String): List<XmlElement> = children.filter { it.name == name }

    override fun toString(): String = "<$name> at line $line"
}

/**
 * Where an element is in its source, by index: [from] its `<`, [close] its start tag's `/>` or `>`, [to] just past its
 * end; [values] each attribute's value between its quotes, as written (references not read). What a rewrite of the
 * text in place needs (a pack's qwerty.xml given 分词, PackSeparator.addSeparator), the rest of the file kept as it is.
 */
class XmlOffsets(val from: Int, val close: Int, val to: Int, val values: Map<String, IntRange>)

/**
 * The XML a layout or a theme is written in, and no more (060-render-v2 §6): elements, attributes,
 * comments, text, the XML declaration. No DTD, so no entities of a file's own and no XXE; the five
 * predefined entities and character references are read (`&quot;` is how a `"` is written in an
 * attribute). CDATA is refused too: nothing here needs it. Every problem carries its line.
 *
 * Limits: a theme comes from anywhere, so a file is [MAX_CHARS] at most and [MAX_DEPTH] deep.
 */
object Xml {
    const val MAX_CHARS = 1_000_000
    const val MAX_DEPTH = 32

    /**
     * Whether [c] is a character of XML 1.0 (§2.2): not a C0 control but tab, LF and CR, nor NUL, U+FFFE or U+FFFF. A
     * value is only ever of these, whichever way it was written (a control or U+FFFF in a name the page and the app
     * would show): one written as it is dropped (a file 0.6.0 wrote or read with one still reads, R26 F8187), a
     * reference to one refused; the writers leave them out.
     */
    fun allows(c: Char): Boolean = !(c < ' ' && c != '\t' && c != '\n' && c != '\r' || c == '\uFFFE' || c == '\uFFFF')

    /** The root element of [source], or why not; [offsets]: each element with its [XmlOffsets]. */
    fun parse(source: String, offsets: Boolean = false): Parsed<XmlElement> =
        if (source.length > MAX_CHARS) {
            Parsed.Failed(0, "the file is over $MAX_CHARS characters")
        } else {
            try {
                Parsed.Ok(Reader(source, offsets).document())
            } catch (e: XmlException) {
                Parsed.Failed(e.line, e.message.orEmpty())
            }
        }
}

/** Thrown inside the reader only, caught by [Xml.parse]: a parse stops at its first problem. */
private class XmlException(val line: Int, message: String) : Exception(message)

private class Reader(private val s: String, private val offsets: Boolean) {
    private var i = 0
    private var line = 1

    fun document(): XmlElement {
        skipProlog()
        if (!at("<") || at("</")) fail("no root element")
        val root = element(0)
        skipMisc()
        if (i < s.length) fail("text after the root element")
        return root
    }

    private fun fail(message: String): Nothing = throw XmlException(line, message)

    private fun at(t: String): Boolean = s.startsWith(t, i)

    private fun advance(n: Int) {
        repeat(n) {
            // a line ends at LF, CRLF (counted at its LF) or a CR alone
            if (endsLine(i)) line++
            i++
        }
    }

    private fun skipSpace() {
        while (i < s.length && s[i].isXmlSpace()) advance(1)
    }

    private fun skipProlog() {
        if (s.startsWith("﻿")) i = 1
        skipMisc()
    }

    // spaces, comments and processing instructions (the declaration among them) between elements
    private fun skipMisc() {
        while (true) {
            skipSpace()
            when {
                at("<!--") -> comment()
                at("<?") -> skipPast("?>", "a processing instruction")
                at("<!DOCTYPE") -> fail("a DOCTYPE is not allowed")
                else -> return
            }
        }
    }

    private fun comment() {
        advance(4)
        val end = s.indexOf("--", i)
        if (end < 0) fail("a comment not closed")
        advance(end - i)
        if (!at("-->")) fail("'--' inside a comment")
        advance(3)
    }

    private fun skipPast(end: String, what: String) {
        val at = s.indexOf(end, i)
        if (at < 0) fail("$what not closed")
        advance(at + end.length - i)
    }

    private fun name(): String {
        val start = i
        while (i < s.length && s[i].isNameChar()) advance(1)
        if (i == start || !s[start].isNameStart()) fail("a name expected")
        return s.substring(start, i)
    }

    private fun element(depth: Int): XmlElement {
        if (depth >= Xml.MAX_DEPTH) fail("elements nested over ${Xml.MAX_DEPTH} deep")
        val startLine = line
        val from = i
        advance(1)
        val qName = name()
        // the files are in one namespace, the default one: a prefixed element (l:key) is another's, or one not declared
        if (':' in qName) fail("<$qName>: an element with a prefix (write the namespace as xmlns=)")
        val values = valuesAsked()
        val attributes = attributes(qName, values)
        val close = i
        fun placed(to: Int) = offsetsOf(from, close, to, values)
        if (at("/>")) {
            advance(2)
            return XmlElement(qName, attributes, emptyList(), "", startLine, placed(i))
        }
        if (!at(">")) fail("'>' expected in <$qName>")
        advance(1)
        val children = ArrayList<XmlElement>()
        val text = StringBuilder()
        while (true) {
            when {
                i >= s.length -> fail("<$qName> not closed")
                at("</") -> {
                    advance(2)
                    val closing = name()
                    if (closing != qName) fail("</$closing> closes <$qName>")
                    skipSpace()
                    if (!at(">")) fail("'>' expected in </$qName>")
                    advance(1)
                    return XmlElement(qName, attributes, children, text.toString(), startLine, placed(i))
                }
                at("<!--") -> comment()
                at("<![CDATA[") -> fail("CDATA is not allowed")
                at("<!") -> fail("a declaration is not allowed here")
                at("<?") -> skipPast("?>", "a processing instruction")
                at("<") -> children += element(depth + 1)
                else -> {
                    val textLine = line
                    val t = chars('<')
                    // no element here holds text: a word between them is a mistake (a quote not closed, a > too many)
                    val first = t.indexOfFirst { !it.isXmlSpace() }
                    if (first >= 0) {
                        // the line the word is on, not the one the whitespace before it started
                        throw XmlException(textLine + t.take(first).replace("\r\n", "\n").count { it == '\n' || it == '\r' }, "text in <$qName>: \"${t.trim().take(TEXT_SHOWN)}\"")
                    }
                    text.append(t)
                }
            }
        }
    }

    private fun valuesAsked(): MutableMap<String, IntRange>? = if (offsets) LinkedHashMap() else null

    private fun offsetsOf(from: Int, close: Int, to: Int, values: Map<String, IntRange>?) = values?.let { XmlOffsets(from, close, to, it) }

    private fun attributes(element: String, values: MutableMap<String, IntRange>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        while (true) {
            val spaced = i < s.length && s[i].isXmlSpace()
            skipSpace()
            if (i >= s.length) fail("<$element> not closed")
            if (at("/>") || at(">")) return out
            if (!spaced) fail("a space expected between attributes")
            val name = name()
            skipSpace()
            if (!at("=")) fail("'=' expected after $name")
            advance(1)
            skipSpace()
            val quote = s.getOrNull(i)
            if (quote != '"' && quote != '\'') fail("a quoted value expected for $name")
            advance(1)
            val start = i
            val value = chars(quote, attribute = true)
            if (i >= s.length) fail("the value of $name not closed")
            values?.put(name, start until i)
            advance(1)
            if (name in out) fail("$name given twice")
            out[name] = value
        }
    }

    // text up to [end] (not taken), references read; '<' is never in a value. A literal tab, LF or CR (CRLF as
    // one) is a space, as XML 1.0 §3.3.3 normalises an attribute value; one written as a reference stays
    private fun endsLine(at: Int): Boolean {
        val c = s.getOrNull(at)
        return c == '\n' || c == '\r' && s.getOrNull(at + 1) != '\n'
    }

    // what ends a run of plain characters: [end], a reference, an attribute's line break
    private fun plainEnds(c: Char, end: Char, attribute: Boolean) = c == end || c == '&' || attribute && c in BREAKS

    private fun chars(end: Char, attribute: Boolean = false): String {
        val start = i
        while (i < s.length && !plainEnds(s[i], end, attribute) && Xml.allows(s[i])) {
            if (s[i] == '<') fail("'<' in a value")
            advance(1)
        }
        // no reference or character to drop in it (most values): the text as it is, not copied a character at a time
        if (i >= s.length || s[i] == end) return s.substring(start, i)
        val b = StringBuilder().appendRange(s, start, i)
        while (i < s.length && s[i] != end) {
            when (val c = s[i]) {
                '&' -> b.append(reference())
                '<' -> fail("'<' in a value")
                else -> if (attribute && c in BREAKS) {
                    b.append(' ')
                    advance(if (c == '\r' && s.getOrNull(i + 1) == '\n') 2 else 1)
                } else {
                    if (Xml.allows(c)) b.append(c)
                    advance(1)
                }
            }
        }
        return b.toString()
    }

    private fun reference(): String {
        val end = s.indexOf(';', i)
        if (end < 0 || end - i > MAX_REFERENCE) fail("'&' not starting a reference")
        val body = s.substring(i + 1, end)
        val text = when {
            body.startsWith("#x") -> codePoint(body.substring(2).takeIf(HEX_DIGITS::matches)?.toIntOrNull(HEX))
            body.startsWith("#") -> codePoint(body.substring(1).takeIf(DIGITS::matches)?.toIntOrNull())
            else -> PREDEFINED[body] ?: fail("the entity &$body; is not one of XML's five")
        }
        advance(end + 1 - i)
        return text
    }

    private fun codePoint(c: Int?): String {
        if (c == null || c !in 1..MAX_CODE_POINT) fail("a character reference to no character")
        if (c in SURROGATES || c < SUPPLEMENTARY && !Xml.allows(c.toChar())) fail("a character reference to no character")
        if (c < SUPPLEMENTARY) return c.toChar().toString()
        val v = c - SUPPLEMENTARY
        return charArrayOf((HIGH_SURROGATE + (v shr TEN_BITS)).toChar(), (LOW_SURROGATE + (v and LOW_MASK)).toChar()).concatToString()
    }

    private companion object {
        const val BREAKS = "\t\n\r"

        val PREDEFINED = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'")
        const val MAX_REFERENCE = 12
        const val TEXT_SHOWN = 20
        val HEX_DIGITS = Regex("[0-9A-Fa-f]{1,6}")
        val DIGITS = Regex("[0-9]{1,7}")
        const val HEX = 16
        const val MAX_CODE_POINT = 0x10FFFF
        val SURROGATES = 0xD800..0xDFFF
        const val SUPPLEMENTARY = 0x10000
        const val HIGH_SURROGATE = 0xD800
        const val LOW_SURROGATE = 0xDC00
        const val TEN_BITS = 10
        const val LOW_MASK = 0x3FF
    }
}

private fun Char.isXmlSpace(): Boolean = this == ' ' || this == '\n' || this == '\t' || this == '\r'

// names are ASCII: what Unicode calls a letter the JVM's and JS's tables may not agree on, and no name here needs one.
// `:` for an attribute's namespace prefix (xmlns:xsi, xsi:schemaLocation); an element's is refused
private fun Char.isNameStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '_' || this == ':'

private fun Char.isNameChar(): Boolean = isNameStart() || this in '0'..'9' || this == '-' || this == '.'
