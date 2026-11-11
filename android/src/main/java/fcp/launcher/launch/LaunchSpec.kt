// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** What the `:game` process needs. The classpath is too large for an Intent extra. */
data class LaunchSpec(
    val versionId: String,
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val javaHome: String,
    val nativesDirectory: String,
    val nativeLibraryDir: String,
    val logFile: String,
    val gameLogFile: String,
    val secrets: List<String>,
    val stateFile: String,
) {
    fun write(file: File) {
        val root = JSONObject()
        root.put("versionId", versionId)
        root.put("command", JSONArray(command))
        root.put("environment", JSONObject(environment))
        root.put("workingDirectory", workingDirectory)
        root.put("javaHome", javaHome)
        root.put("nativesDirectory", nativesDirectory)
        root.put("nativeLibraryDir", nativeLibraryDir)
        root.put("logFile", logFile)
        root.put("gameLogFile", gameLogFile)
        root.put("secrets", JSONArray(secrets))
        root.put("stateFile", stateFile)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    companion object {
        fun read(file: File): LaunchSpec {
            val root = JSONObject(file.readText())
            val command = root.getJSONArray("command")
            val environment = root.getJSONObject("environment")
            val secrets = root.optJSONArray("secrets") ?: JSONArray()
            return LaunchSpec(
                versionId = root.getString("versionId"),
                command = List(command.length()) { command.getString(it) },
                environment = buildMap {
                    val keys = environment.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, environment.getString(key))
                    }
                },
                workingDirectory = root.getString("workingDirectory"),
                javaHome = root.getString("javaHome"),
                nativesDirectory = root.getString("nativesDirectory"),
                nativeLibraryDir = root.optString("nativeLibraryDir"),
                logFile = root.getString("logFile"),
                gameLogFile = root.getString("gameLogFile"),
                secrets = List(secrets.length()) { secrets.getString(it) },
                stateFile = root.getString("stateFile"),
            )
        }
    }
}

/** Heartbeat the UI process polls. Writes are atomic so a reader never sees a torn file. */
object LaunchState {
    data class Snapshot(val phase: String, val stampMs: Long, val detail: String)

    fun write(file: File, phase: String, detail: String = "") {
        val text = buildString {
            append(phase)
            append(' ')
            append(System.currentTimeMillis())
            if (detail.isNotEmpty()) {
                append('\n')
                append(detail)
            }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    fun read(file: File): Snapshot? {
        val text = try {
            if (!file.isFile) return null else file.readText()
        } catch (_: Exception) {
            return null
        }
        val lines = text.split('\n', limit = 2)
        val head = lines.firstOrNull()?.trim().orEmpty()
        if (head.isEmpty()) return null
        val phase = head.substringBefore(' ')
        if (phase.isEmpty()) return null
        val stamp = head.substringAfter(' ', "").toLongOrNull() ?: 0L
        val detail = if (lines.size > 1) lines[1].trim() else ""
        return Snapshot(phase, stamp, detail)
    }
}
