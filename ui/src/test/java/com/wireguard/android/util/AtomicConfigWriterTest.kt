package com.wireguard.android.util

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AtomicConfigWriterTest {
    @Test fun encryptionFailureRetainsWorkingConfig() {
        val directory = Files.createTempDirectory("atomic-config-test").toFile()
        try {
            val file = directory.resolve("working.conf").apply { writeText("old ciphertext") }
            try { AtomicConfigWriter.write(file) { error("Keystore unavailable") }; fail() } catch (_: IllegalStateException) {}
            assertEquals("old ciphertext", file.readText())
            assertEquals(listOf("working.conf"), directory.list()?.toList())
        } finally { directory.deleteRecursively() }
    }
    @Test fun replacementIsCompleteAndLeavesNoTemporaryCopy() {
        val directory = Files.createTempDirectory("atomic-config-test").toFile()
        try {
            val file = directory.resolve("working.conf").apply { writeText("old") }
            AtomicConfigWriter.write(file) { ByteArray(100_000) { 42 } }
            assertArrayEquals(ByteArray(100_000) { 42 }, file.readBytes())
            assertEquals(listOf("working.conf"), directory.list()?.toList())
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedReplacementRetainsExistingTargetAndCleansTemporaryFile() {
        val directory = Files.createTempDirectory("atomic-config-test").toFile()
        try {
            val blocked = directory.resolve("working.conf").apply { mkdir(); resolve("sentinel").writeText("keep") }
            try { AtomicConfigWriter.write(blocked) { byteArrayOf(1) }; fail() } catch (_: java.io.IOException) {}
            assertEquals("keep", blocked.resolve("sentinel").readText())
            assertEquals(listOf("working.conf"), directory.list()?.toList())
        } finally { directory.deleteRecursively() }
    }
}
