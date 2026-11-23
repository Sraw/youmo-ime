/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.editing.NumberKind
import org.fcitx.fcitx5.android.input.keyboard.StickyNumbers.Mode
import org.fcitx.fcitx5.android.input.voice.VoiceCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyNumbersTest {

    private enum class Kind(val plain: Boolean, val number: NumberKind?, val type: Int) {
        TEXT(true, null, 0x1),
        EMAIL(false, null, 0x21),
        NUMBER(false, NumberKind.NUMBER, 0x2),
        PHONE(false, NumberKind.PHONE, 0x3),
    }

    private class Keys {
        val sticky = StickyNumbers()
        private var nextId = 1

        /** As KeyboardWindow: a field starts, then its own switch shows (not the user's). */
        fun field(kind: Kind = Kind.TEXT, pkg: String? = APP, id: Int = nextId++, restarting: Boolean = false, identical: Boolean = false): Boolean {
            val kept = sticky.fieldStarted(kind.plain, kind.number, VoiceCommit.Field(pkg, id, kind.type), identical, restarting)
            sticky.shown(isNumberPad = kept || kind.number != null, byUser = false)
            return kept
        }

        fun tap123() = sticky.shown(isNumberPad = true, byUser = true)

        fun back() = sticky.shown(isNumberPad = false, byUser = true)
    }

    private fun keys(block: Keys.() -> Unit) = Keys().apply(block)

    @Test
    fun theNumberKeyboardTheUserOpenedStaysAcrossPlainFieldsOfTheApp() = assertRows(
        "123, then a plain field of the same app" to {
            keys { field(); tap123(); assertTrue(field()) }
        },
        "a number field's own pad, then a plain field" to {
            keys { field(Kind.NUMBER); assertFalse(field()) }
        },
        "back first" to {
            keys { field(); tap123(); back(); assertFalse(field()) }
        },
        "hidden between" to {
            keys { field(); tap123(); sticky.hidden(); assertFalse(field()) }
        },
        "another app" to {
            keys { field(); tap123(); assertFalse(field(pkg = "other.app")); assertFalse(field()) }
        },
        "no package" to {
            keys { field(); tap123(); assertFalse(field(pkg = null)) }
        },
        "plain, number, plain" to {
            keys { field(); tap123(); assertFalse(field(Kind.NUMBER)); assertTrue(field()) }
        },
        "plain, email, plain" to {
            keys { field(); tap123(); assertFalse(field(Kind.EMAIL)); assertFalse(field()) }
        },
        "back then 123 in a phone field gives its pad back only" to {
            keys { field(Kind.PHONE); back(); tap123(); assertFalse(field()) }
        },
        "armed, then 符 and 123 in a phone field" to {
            keys { field(); tap123(); field(Kind.PHONE); tap123(); assertTrue(field()) }
        },
        "a switch shown after hiding arms nothing" to {
            keys { field(); sticky.hidden(); tap123(); assertFalse(field()) }
        },
    )

    @Test
    fun theSameFieldStartedAgainIsNoNewField() = assertRows(
        "restarted with the same traits, an email field too" to {
            keys { field(Kind.EMAIL, id = 7); tap123(); assertTrue(field(Kind.EMAIL, id = 7, restarting = true)) }
        },
        "restarted as an email field (a WebView's next)" to {
            keys { field(id = 7); tap123(); assertFalse(field(Kind.EMAIL, id = 7, restarting = true)); assertFalse(field()) }
        },
        "a native view's next field of the same id and type" to {
            keys { field(Kind.EMAIL, id = 7); tap123(); assertFalse(field(Kind.EMAIL, id = 7)) }
        },
        "an unset id tells nothing" to {
            keys { field(Kind.EMAIL, id = 0); tap123(); assertFalse(field(Kind.EMAIL, id = 0, restarting = true)) }
        },
        "the same EditorInfo (a turn)" to {
            keys { field(Kind.EMAIL, id = 7); tap123(); assertTrue(field(Kind.EMAIL, id = 7, restarting = true, identical = true)) }
        },
        "locked, an email field's letters turned" to {
            keys { field(); sticky.toggleLock(); assertFalse(field(Kind.EMAIL, id = 7)); assertFalse(field(Kind.EMAIL, id = 7, identical = true)) }
        },
        "started twice before its switch shows" to {
            keys {
                field(); sticky.toggleLock()
                val email = VoiceCommit.Field(APP, 7, Kind.EMAIL.type)
                assertFalse(sticky.fieldStarted(false, null, email, identical = false, restarting = false))
                assertFalse(sticky.fieldStarted(false, null, email, identical = true, restarting = false))
            }
        },
    )

    @Test
    @Uat("PAN-43")
    fun theLockKeepsThroughHidingAndLetterFields() = assertRows(
        "locked, hidden, another plain field" to {
            keys {
                field(); tap123(); sticky.toggleLock()
                assertTrue(sticky.locked)
                sticky.hidden()
                assertTrue(field())
                assertTrue(sticky.locked)
            }
        },
        "locked, an email field's letters, a plain field" to {
            keys { field(); sticky.toggleLock(); assertFalse(field(Kind.EMAIL)); assertTrue(field()) }
        },
        "locked, back" to {
            keys { field(); sticky.toggleLock(); back(); assertEquals(Mode.Off, sticky.mode); assertFalse(field()) }
        },
        "locked, another app" to {
            keys { field(); sticky.toggleLock(); assertFalse(field(pkg = "other.app")); assertEquals(Mode.Off, sticky.mode) }
        },
        "unlocked in a plain field: kept as 123 keeps it" to {
            keys { field(); sticky.toggleLock(); sticky.toggleLock(); assertEquals(Mode.Armed, sticky.mode); assertTrue(field()) }
        },
        "locked in a phone field, then a plain field" to {
            keys { field(Kind.PHONE); sticky.toggleLock(); assertTrue(field()) }
        },
        "unlocked in a phone field: nothing kept" to {
            keys { field(Kind.PHONE); sticky.toggleLock(); sticky.toggleLock(); assertEquals(Mode.Off, sticky.mode); assertFalse(field()) }
        },
        "a lock tapped while hidden does nothing" to {
            keys { field(); sticky.hidden(); sticky.toggleLock(); assertFalse(sticky.locked) }
        },
    )

    private companion object {
        const val APP = "org.example.app"
    }
}
