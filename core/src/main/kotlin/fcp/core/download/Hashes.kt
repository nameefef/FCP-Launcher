// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.core.download

import java.io.File
import java.security.MessageDigest

fun sha1Hex(bytes: ByteArray): String = digest(bytes, "SHA-1")

fun sha1Hex(file: File): String = digest(file, "SHA-1")

fun sha256Hex(bytes: ByteArray): String = digest(bytes, "SHA-256")

fun sha256Hex(file: File): String = digest(file, "SHA-256")

private fun digest(bytes: ByteArray, algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(bytes).toHex()

private fun digest(file: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
