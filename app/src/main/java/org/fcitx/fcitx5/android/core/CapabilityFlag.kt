/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.host.Capability
import org.fcitx.fcitx5.android.input.FieldPolicy
import splitties.bitflags.hasFlag

/**
 * fcitx's capability bits ([capabilityflags.h](https://github.com/fcitx/fcitx5/blob/5.1.1/src/lib/fcitx-utils/capabilityflags.h)):
 * those the host reads from its [Capability], the rest set here alone, which no host code looks at.
 */
enum class CapabilityFlag(bits: Long) {
    NoFlag(0L),
    Preedit(Capability.PREEDIT),
    Password(Capability.PASSWORD),
    ClientUnfocusCommit(Capability.CLIENT_UNFOCUS_COMMIT),
    Email(Capability.EMAIL),
    Uppercase(1L shl 9),
    Url(Capability.URL),
    Dialable(1L shl 13),
    Number(1L shl 14),
    SpellCheck(1L shl 16),
    NoSpellCheck(Capability.NO_SPELL_CHECK),
    UppercaseWords(1L shl 19),
    UppercaseSentences(1L shl 20),
    Alpha(1L shl 21),
    Date(1L shl 33),
    Time(1L shl 34),
    Multiline(1L shl 35),
    Sensitive(Capability.SENSITIVE),

    CommitStringWithCursor(Capability.COMMIT_STRING_WITH_CURSOR),

    PasswordOrSensitive(Capability.PASSWORD_OR_SENSITIVE);

    val flag: ULong = bits.toULong()
}

operator fun ULong.plus(other: CapabilityFlag) = or(other.flag)
operator fun ULong.minus(other: CapabilityFlag) = and(other.flag.inv())

@JvmInline
value class CapabilityFlags constructor(val flags: ULong) {
    companion object {
        fun mergeFlags(arr: Array<out CapabilityFlag>): ULong =
            arr.fold(CapabilityFlag.NoFlag.flag) { acc, it -> acc or it.flag }

        val DefaultFlags = CapabilityFlags(
            CapabilityFlag.Preedit,
            CapabilityFlag.ClientUnfocusCommit,
            CapabilityFlag.CommitStringWithCursor
        )

        /** A field of this app's own package is Sensitive: it learns nothing, as a 无痕 field. */
        fun forField(info: EditorInfo, ownPackage: String): CapabilityFlags = fromEditorInfo(info).let {
            val own = FieldPolicy.ownApp(info.packageName, ownPackage) &&
                !(BuildConfig.DEBUG && info.privateImeOptions == TEST_HOST_FIELD)
            if (own) CapabilityFlags(it.flags + CapabilityFlag.Sensitive) else it
        }

        /** Marks the debug TypingHostActivity's field, typed into as another app's though in this package. */
        const val TEST_HOST_FIELD = "io.github.sraw.youmo.testHostField"

        fun fromEditorInfo(info: EditorInfo): CapabilityFlags {
            var flags = DefaultFlags.flags
            info.imeOptions.let {
                if (it.hasFlag(EditorInfo.IME_FLAG_FORCE_ASCII)) {
                    flags += CapabilityFlag.Alpha
                }
                if (it.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
                    flags += CapabilityFlag.Sensitive
                }
            }
            info.inputType.let {
                when (it and InputType.TYPE_MASK_CLASS) {
                    InputType.TYPE_NULL -> {
                        flags -= CapabilityFlag.Preedit
                        flags += CapabilityFlag.NoSpellCheck
                    }
                    InputType.TYPE_CLASS_TEXT -> {
                        (it and InputType.TYPE_MASK_FLAGS).run {
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) ||
                                hasFlag(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                            ) {
                                flags += CapabilityFlag.NoSpellCheck
                            }
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)) {
                                flags += CapabilityFlag.SpellCheck
                            }
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) {
                                flags += CapabilityFlag.Uppercase
                            }
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)) {
                                flags += CapabilityFlag.UppercaseSentences
                            }
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_CAP_WORDS)) {
                                flags += CapabilityFlag.UppercaseWords
                            }
                            if (hasFlag(InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) ||
                                hasFlag(InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                            ) {
                                flags += CapabilityFlag.Multiline
                            }
                        }
                        (it and InputType.TYPE_MASK_VARIATION).run {
                            if (equals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) ||
                                equals(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
                            ) {
                                flags += CapabilityFlag.Email
                            }
                            if (equals(InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
                                equals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                            ) {
                                flags += CapabilityFlag.Password
                            }
                            if (equals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
                                flags += CapabilityFlag.Sensitive
                                flags += CapabilityFlag.NoSpellCheck
                            }
                            if (equals(InputType.TYPE_TEXT_VARIATION_URI)) {
                                flags += CapabilityFlag.Url
                            }
                        }
                    }
                    InputType.TYPE_CLASS_NUMBER -> {
                        flags += CapabilityFlag.NoSpellCheck
                        flags += CapabilityFlag.Number
                        // a PIN: as a password
                        if ((it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                            flags += CapabilityFlag.Password
                        }
                    }
                    InputType.TYPE_CLASS_PHONE -> {
                        flags += CapabilityFlag.NoSpellCheck
                        flags += CapabilityFlag.Dialable
                    }
                    InputType.TYPE_CLASS_DATETIME -> {
                        flags += CapabilityFlag.NoSpellCheck
                        (it and InputType.TYPE_MASK_VARIATION).run {
                            if (hasFlag(InputType.TYPE_DATETIME_VARIATION_DATE)) {
                                flags += CapabilityFlag.Date
                            }
                            if (hasFlag(InputType.TYPE_DATETIME_VARIATION_TIME)) {
                                flags += CapabilityFlag.Time
                            }
                        }
                    }
                }
            }
            return CapabilityFlags(flags)
        }

        /** A shown password reaches fcitx only as [CapabilityFlag.Sensitive], so what the keyboard keeps out of passwords asks here. */
        fun isPassword(info: EditorInfo): Boolean =
            fromEditorInfo(info).has(CapabilityFlag.Password) ||
                (info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)) ==
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    }

    constructor(vararg flags: CapabilityFlag) : this(mergeFlags(flags))

    fun has(flag: ULong) = flags.hasFlag(flag)

    fun has(flag: CapabilityFlag) = flags.hasFlag(flag.flag)

    /** [has] needs all bits, which a field rarely has of a combined flag such as [CapabilityFlag.PasswordOrSensitive]. */
    fun hasAny(flag: CapabilityFlag) = (flags and flag.flag) != 0UL

    fun toLong() = flags.toLong()
}
