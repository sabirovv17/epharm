package kz.epharm.merch.tasks

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode

/** Private files only. No delete operation, public bucket or remote URL fetch exists here. */
@Component
class TaskEvidenceStorage(
    @Value("\${app.merch-tasks.evidence-root:}") private val configuredRoot: String,
    @Value("\${app.merch-tasks.legacy-files-root:}") private val configuredLegacyRoot: String,
) {
    fun write(key: String, bytes: ByteArray): String {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) invalid()
        val base = root(configuredRoot)
        val path = resolve(base, key)
        var directory = base
        for (part in base.relativize(path.parent)) {
            directory = directory.resolve(part)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) unavailable()
        }
        val digest = hash(bytes)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || hash(readPath(path)) != digest) conflict()
            return digest
        }
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        return digest
    }

    fun read(backend: String, key: String, expectedHash: String?): ByteArray {
        val base = when (backend) {
            "private_host" -> root(configuredRoot)
            "legacy_reference" -> root(configuredLegacyRoot)
            else -> unavailable()
        }
        val path = resolve(base, key)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            !path.toRealPath().startsWith(base)) notFound()
        val bytes = readPath(path)
        if (expectedHash != null && hash(bytes) != expectedHash) conflict()
        return bytes
    }

    private fun root(value: String): Path {
        if (value.isBlank()) unavailable()
        val path = runCatching { Path.of(value) }.getOrElse { unavailable() }
        if (!path.isAbsolute || !Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) unavailable()
        val physical = runCatching { path.toRealPath() }.getOrElse { unavailable() }
        if (path.normalize() != physical) unavailable()
        val permissions = runCatching { Files.getPosixFilePermissions(physical) }.getOrElse { unavailable() }
        if (permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) unavailable()
        return physical
    }
    private fun resolve(root: Path, key: String): Path {
        if (key.isBlank() || key.length > 1000 || key.startsWith('/') || key.contains('\\') || key.any(Char::isISOControl) ||
            key.split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid()
        return root.resolve(key).normalize().also { if (!it.startsWith(root)) invalid() }
    }
    private fun readPath(path: Path): ByteArray = Files.newInputStream(path).use { input ->
        input.readNBytes(MAX_BYTES + 1).also { if (it.size > MAX_BYTES) invalid() }
    }
    companion object {
        const val MAX_BYTES = 30 * 1024 * 1024
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun unavailable(): Nothing = throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Закрытое хранилище доказательств не настроено", HttpStatus.SERVICE_UNAVAILABLE)
        private fun invalid(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный файл доказательства")
        private fun conflict(): Nothing = throw AppException(ErrorCode.CONFLICT, "Контрольная сумма доказательства не совпала; файл сохранён", HttpStatus.CONFLICT)
        private fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Файл доказательства не найден", HttpStatus.NOT_FOUND)
    }
}
