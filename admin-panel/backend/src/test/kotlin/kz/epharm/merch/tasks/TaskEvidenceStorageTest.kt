package kz.epharm.merch.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows

class TaskEvidenceStorageTest {
    @TempDir lateinit var temp: Path
    private fun root(): Path = temp.toRealPath().also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }

    @Test fun `private bytes are exact and idempotent with no deletion or overwrite on changed payload`() {
        val directory = root()
        val storage = TaskEvidenceStorage(directory.toString(), directory.toString())
        val bytes = byteArrayOf(1, 2, 3)
        val hash = storage.write("native/attempt/evidence.jpg", bytes)
        assertArrayEquals(bytes, storage.read("private_host", "native/attempt/evidence.jpg", hash))
        assertEquals(hash, storage.write("native/attempt/evidence.jpg", bytes))
        assertThrows<AppException> { storage.write("native/attempt/evidence.jpg", byteArrayOf(9)) }
        assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("native/attempt/evidence.jpg")))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("native/attempt/evidence.jpg")))
    }

    @Test fun `unconfigured public permissive or symlink roots fail closed`() {
        assertThrows<AppException> { TaskEvidenceStorage("", "").write("file.jpg", byteArrayOf(1)) }
        val directory = root()
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertThrows<AppException> { TaskEvidenceStorage(directory.toString(), "").write("file.jpg", byteArrayOf(1)) }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        Files.createSymbolicLink(directory.resolve("alias"), directory)
        assertThrows<AppException> { TaskEvidenceStorage(directory.resolve("alias").toString(), "").write("file.jpg", byteArrayOf(1)) }
    }

    @Test fun `legacy references retain their path but traversal outside symlinks and changed hashes cannot be downloaded`() {
        val directory = root()
        val storage = TaskEvidenceStorage(directory.toString(), directory.toString())
        Files.write(directory.resolve("legacy.jpg"), byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), storage.read("legacy_reference", "legacy.jpg", null))
        for (key in listOf("../legacy.jpg", "/legacy.jpg", "nested/../../legacy.jpg", "bad\u0000.jpg", "a\\b.jpg")) {
            assertThrows<AppException> { storage.read("legacy_reference", key, null) }
        }
        assertThrows<AppException> { storage.read("legacy_reference", "legacy.jpg", "0".repeat(64)) }
        val outside = Files.createTempFile("synthetic-task-outside", ".jpg")
        try {
            Files.createSymbolicLink(directory.resolve("outside.jpg"), outside)
            assertThrows<AppException> { storage.read("legacy_reference", "outside.jpg", null) }
        } finally { Files.deleteIfExists(outside) } // Synthetic test-owned temporary file only.
    }
}
