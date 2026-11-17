/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Properties

val json = Json { prettyPrint = true }

inline fun envOrDefault(env: String, default: () -> String) =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: default()

inline fun Project.propertyOrDefault(prop: String, default: () -> String) =
    runCatching { property(prop)!!.toString() }.getOrElse { default() }

internal inline fun Project.ep(env: String, prop: String, block: () -> String) =
    envOrDefault(env) {
        propertyOrDefault(prop) {
            block()
        }
    }

fun Project.epn(env: String, prop: String) =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: runCatching { property(prop)!!.toString() }.getOrNull()

/** [value] trimmed, a leading `~/` taken as [home] (as it is, a `$` in it no group reference): a machine setting's path */
internal fun homed(value: String, home: String): String = value.trim().replaceFirst(Regex("^~(?=/)"), Regex.escapeReplacement(home))

/** [project]'s packs.properties (the packs' versions), read once for all of them a plugin asks [packsProperty] (R26 F8282) */
internal fun packsProperties(project: Project): Properties =
    Properties().apply { project.file("packs.properties").inputStream().use(::load) }

/** a whole number of [packs] ([packsProperties]): AssetPacksPlugin's and VoiceDataPlugin's */
internal fun packsProperty(packs: Properties, name: String): Int =
    packs.getProperty(name)?.trim()?.toIntOrNull() ?: throw GradleException("app/packs.properties: no whole number $name")

fun String.capitalized(): String =
    if (get(0).isUpperCase()) this else replaceFirstChar { get(0).uppercaseChar() }
