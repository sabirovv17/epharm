package kz.epharm.lms

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.service.CoursePackageImportService
import kz.epharm.shared.storage.MediaStorage
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Real PostgreSQL commits/advisory locks with isolated in-memory storage, no production data. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(CoursePackageImportIntegrationTest.StorageConfiguration::class)
class CoursePackageImportIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("package_import_test").withUsername("epharm").withPassword("local-test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    class RecordingStorage : MediaStorage {
        val objects = ConcurrentHashMap<String, ByteArray>()
        @Volatile var beforeUpload: (() -> Unit)? = null
        override fun upload(bytes: ByteArray, contentType: String, originalName: String): String {
            beforeUpload?.invoke()
            return "http://test-storage/${UUID.randomUUID()}".also { objects[it] = bytes.copyOf() }
        }
        override fun read(url: String): ByteArray? = objects[url]
        override fun delete(url: String) { objects.remove(url) }
    }

    @TestConfiguration
    class StorageConfiguration {
        @Bean @Primary fun recordingPackageStorage() = RecordingStorage()
    }

    @Autowired lateinit var importer: CoursePackageImportService
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var storage: RecordingStorage
    private lateinit var actor: AdminPrincipal

    @BeforeEach fun actor() {
        val user = users.saveAndFlush(AdminUserEntity(email = "package-${UUID.randomUUID()}@example.invalid",
            roleRaw = AdminRole.TRAINING_MANAGER.name))
        actor = AdminPrincipal(user.id, user.email, user.name, user.role, user.company)
    }

    private fun counts() = listOf("courses", "course_lessons", "course_lesson_attachments", "training_programs",
        "training_program_versions", "training_program_stages", "training_audit_log", "course_package_import_receipts")
        .associateWith { jdbc.queryForObject("select count(*) from $it", Long::class.java) }

    @Test fun `preview is read only and committed retry retains one draft program and its media`() {
        val file = packageFile()
        val before = counts()
        val existingObjects = storage.objects.keys.toSet()
        val preview = importer.preview(file, actor)
        assertThat(counts()).isEqualTo(before)
        assertThat(storage.objects.keys).containsExactlyInAnyOrderElementsOf(existingObjects)
        val first = importer.importPackage(file, preview.digest, actor)
        val retry = importer.importPackage(file, preview.digest, actor)
        assertThat(first.reused).isFalse()
        assertThat(retry.reused).isTrue()
        assertThat(retry.course.id).isEqualTo(first.course.id)
        assertThat(retry.programId).isEqualTo(first.programId).isNotNull()
        assertThat(first.course.status.name).isEqualTo("draft")
        assertThat(jdbc.queryForObject("select status from training_programs where id=?", String::class.java, first.programId))
            .isEqualTo("draft")
        assertThat(counts().getValue("courses")).isEqualTo(before.getValue("courses")!! + 1)
        assertThat(counts().getValue("training_programs")).isEqualTo(before.getValue("training_programs")!! + 1)
        assertThat(counts().getValue("course_package_import_receipts")).isEqualTo(before.getValue("course_package_import_receipts")!! + 1)
        assertThat(storage.objects.keys - existingObjects).hasSize(3) // attachment, cover, company logo
        val attachment = retry.course.lessonItems.flatMap { it.attachments }.single()
        assertThat(storage.read(attachment.mediaUrl)).isNotNull()
        assertThat(importer.preview(file, actor).existingCourseId).isEqualTo(first.course.id)
    }

    @Test fun `late receipt failure rolls back all drafts and deletes only new uploaded objects`() {
        val file = packageFile()
        val digest = importer.preview(file, actor).digest
        val before = counts()
        val sentinel = storage.upload(byteArrayOf(1, 2, 3), "text/plain", "existing.txt")
        val existingObjects = storage.objects.keys.toSet()
        jdbc.execute("alter table course_package_import_receipts add constraint synthetic_receipt_failure " +
            "check (author_id <> '${actor.userId}'::uuid) not valid")
        try {
            assertThrows<DataIntegrityViolationException> { importer.importPackage(file, digest, actor) }
        } finally {
            jdbc.execute("alter table course_package_import_receipts drop constraint synthetic_receipt_failure")
        }
        assertThat(counts()).isEqualTo(before)
        assertThat(storage.objects.keys).containsExactlyInAnyOrderElementsOf(existingObjects)
        assertThat(storage.read(sentinel)).containsExactly(1, 2, 3)
        assertThat(importer.preview(file, actor).existingCourseId).isNull()
        assertThat(importer.importPackage(file, digest, actor).reused).isFalse()
    }

    @Test fun `simultaneous exact retry commits one course and one receipt`() {
        val file = packageFile()
        val digest = importer.preview(file, actor).digest
        val before = counts()
        val existingObjects = storage.objects.keys.toSet()
        val firstHoldsLock = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val firstUpload = AtomicBoolean(true)
        storage.beforeUpload = {
            if (firstUpload.compareAndSet(true, false)) {
                firstHoldsLock.countDown()
                check(finishFirst.await(15, TimeUnit.SECONDS))
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<kz.epharm.lms.service.CoursePackageResult> {
                importer.importPackage(file, digest, actor)
            }
            check(firstHoldsLock.await(10, TimeUnit.SECONDS))
            val second = pool.submit<kz.epharm.lms.service.CoursePackageResult> {
                importer.importPackage(file, digest, actor)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (!waiting && !second.isDone && System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and not granted " +
                    "and database=(select oid from pg_database where datname=current_database())", Long::class.java)!! > 0
                if (!waiting) Thread.sleep(10)
            }
            assertThat(waiting).describedAs("second import must wait on the first import advisory lock").isTrue()
            finishFirst.countDown()
            val results = listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
            assertThat(results.map { it.course.id }.distinct()).hasSize(1)
            assertThat(results.map { it.reused }).containsExactlyInAnyOrder(false, true)
            assertThat(results.map { it.programId }.distinct()).hasSize(1)
            assertThat(counts().getValue("courses")).isEqualTo(before.getValue("courses")!! + 1)
            assertThat(counts().getValue("course_package_import_receipts")).isEqualTo(before.getValue("course_package_import_receipts")!! + 1)
            assertThat(storage.objects.keys - existingObjects).hasSize(3)
        } finally {
            finishFirst.countDown()
            storage.beforeUpload = null
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    private fun packageFile(): MockMultipartFile {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(Files.newInputStream(Path.of("../frontend/public/templates/epharm-course-package-example.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            }
        }
        XSSFWorkbook(ByteArrayInputStream(entries.getValue("course.xlsx"))).use { book ->
            val manifest = book.getSheet("Файлы")
            listOf("cover" to "media/cover.png", "company_logo" to "media/company.png").forEach { (kind, path) ->
                val row = manifest.createRow(manifest.lastRowNum + 1)
                listOf("", kind, path, "Synthetic image").forEachIndexed { column, value -> row.createCell(column).setCellValue(value) }
            }
            entries["course.xlsx"] = ByteArrayOutputStream().use { output -> book.write(output); output.toByteArray() }
        }
        val png = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        entries["media/cover.png"] = png
        entries["media/company.png"] = png
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry() }
            }
            output.toByteArray()
        }
        return MockMultipartFile("file", "course.zip", "application/zip", bytes)
    }
}
