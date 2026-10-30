package kz.epharm.lms

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CourseLessonAttachmentDto
import kz.epharm.lms.service.CourseExcelImportService
import kz.epharm.lms.service.CoursePackageImportService
import kz.epharm.lms.service.CoursePackageReader
import kz.epharm.lms.service.CourseService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.storage.MediaStorage
import kz.epharm.training.access.TrainingAdministrativeAccess
import kz.epharm.training.domain.TrainingStageType
import kz.epharm.training.dto.CreateTrainingProgramRequest
import kz.epharm.training.dto.TrainingProgramDto
import kz.epharm.training.service.TrainingProgramAssetService
import kz.epharm.training.service.TrainingService
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class CoursePackageImportServiceTest {
    private val courses = mockk<CourseService>()
    private val reader = CoursePackageReader(CourseExcelImportService(courses))

    @Test
    fun `downloadable package example is accepted by the production reader`() {
        val bytes = Files.readAllBytes(Path.of("../frontend/public/templates/epharm-course-package-example.zip"))
        val parsed = reader.read(upload("example.zip", bytes))
        assertThat(parsed.course.lessons).hasSize(2)
        assertThat(parsed.files).hasSize(1)
        assertThat(parsed.files.single().kind).isEqualTo("attachment")
        assertThat(parsed.certificate).isNotNull()
    }

    @Test
    fun `plain workbook previews without writes and exact bytes have stable digest`() {
        val bytes = workbook()
        val first = reader.read(upload("course.xlsx", bytes))
        val second = reader.read(upload("course.xlsx", bytes))
        assertThat(first.digest).matches("[a-f0-9]{64}").isEqualTo(second.digest)
        assertThat(first.course.lessons).hasSize(2)
        assertThat(first.preview().files).isZero()
        assertThat(first.preview().createsProgram).isFalse()
        verify { courses wasNot Called }
    }

    @Test
    fun `zip requires root workbook and manifest for each media file`() {
        assertInvalid(zip("nested/course.xlsx" to workbook()), "В корне ZIP")
        assertInvalid(zip("course.xlsx" to workbook(), "notes.txt" to "note".toByteArray()), "не указаны")
    }

    @Test
    fun `raw zip traversal absolute paths backslashes case duplicates and malformed directories are rejected`() {
        listOf("../a.txt", "/a.txt", "a/../b.txt", "a\\b.txt", "C:/a.txt", "dir//", "a/./b.txt").forEach { bad ->
            assertInvalid(zip("course.xlsx" to workbook(), bad to byteArrayOf(1)), "Недопустимый")
        }
        assertInvalid(zip("course.xlsx" to workbook(), "MEDIA/a.txt" to byteArrayOf(1), "media/A.txt" to byteArrayOf(2)), "Повторный путь")
    }

    @Test
    fun `directory entries with hidden payload are rejected without draining them`() {
        assertInvalid(zip("course.xlsx" to workbook(), "payload/" to ByteArray(1024 * 1024)), "не должна содержать данные")
    }

    @Test
    fun `author workbook cannot be attached to a learner lesson`() {
        val bytes = workbook { book -> manifest(book, listOf(listOf(lessonCode(book), "attachment", "course.xlsx", "Ответы"))) }
        assertInvalid(zip("course.xlsx" to bytes), "нельзя прикрепить")
    }

    @Test
    fun `manifest validates missing files codes kinds duplicate rows and formulas`() {
        val media = "note".toByteArray()
        fun bookRows(rows: (String) -> List<List<String>>) = workbook { book -> manifest(book, rows(lessonCode(book))) }
        assertInvalid(zip("course.xlsx" to bookRows { listOf(listOf(it, "attachment", "missing.txt", "")) }), "Не найден файл")
        assertInvalid(zip("course.xlsx" to bookRows { listOf(listOf("unknown", "attachment", "a.txt", "")) }, "a.txt" to media), "не найден код")
        assertInvalid(zip("course.xlsx" to bookRows { listOf(listOf(it, "execute", "a.txt", "")) }, "a.txt" to media), "Неизвестное назначение")
        assertInvalid(zip("course.xlsx" to bookRows { List(2) { _ -> listOf(it, "attachment", "a.txt", "") } }, "a.txt" to media), "один раз")
        val formula = workbook { book ->
            manifest(book, listOf(listOf(lessonCode(book), "attachment", "a.txt", "")))
            book.getSheet("Файлы").getRow(1).getCell(3).cellFormula = "1+1"
        }
        assertInvalid(zip("course.xlsx" to formula, "a.txt" to media), "формулы запрещены")
        val duplicateHeader = workbook { book ->
            manifest(book, listOf(listOf(lessonCode(book), "attachment", "a.txt", "")))
            book.getSheet("Файлы").getRow(0).createCell(4).setCellValue("Путь")
        }
        assertInvalid(zip("course.xlsx" to duplicateHeader, "a.txt" to media), "ровно один раз")
    }

    @Test
    fun `valid text attachment and certificate image are described in preview`() {
        val bytes = workbook { book ->
            certificate(book)
            manifest(book, listOf(listOf(lessonCode(book), "attachment", "media/note.txt", "Памятка"),
                listOf("", "cover", "media/cover.png", "Обложка")))
        }
        val parsed = reader.read(upload("course.zip", zip("course.xlsx" to bytes,
            "media/note.txt" to "note".toByteArray(), "media/cover.png" to image())))
        assertThat(parsed.preview().files).isEqualTo(2)
        assertThat(parsed.preview().createsProgram).isTrue()
        assertThat(parsed.preview().partner).isEqualTo("Партнёр")
        assertThat(parsed.files.last().mime).isEqualTo("image/png")
        verify { courses wasNot Called }
    }

    @Test
    fun `package image and audio MIME types retain learner attachment kinds`() {
        val bytes = workbook { book ->
            manifest(book, listOf(listOf(lessonCode(book), "attachment", "photo.jpg", "Фото"),
                listOf(lessonCode(book), "attachment", "audio.mp3", "Аудио")))
        }
        val jpg = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB), "jpg", output)
            output.toByteArray()
        }
        val parsed = reader.read(upload("course.zip", zip("course.xlsx" to bytes,
            "photo.jpg" to jpg, "audio.mp3" to byteArrayOf(0x49, 0x44, 0x33))))
        val attachments = parsed.files.map { file -> CourseLessonAttachmentDto(
            id = "fixture-${file.path}", title = file.title, fileName = file.path,
            contentType = file.mime, mediaUrl = "/s3/${file.path}",
            sizeBytes = parsed.bytes.getValue(file.path).size.toLong(), createdAt = Instant.EPOCH,
        ) }
        assertThat(attachments.map { it.contentType }).containsExactly("image/jpeg", "audio/mpeg")
        assertThat(attachments.map { it.kind }).containsExactly("image", "audio")
        assertInvalid(zip("course.xlsx" to bytes, "photo.jpg" to "invalid image".toByteArray(),
            "audio.mp3" to byteArrayOf(0x49, 0x44, 0x33)), "изображением")
    }

    @Test
    fun `preview validates actual image format dimensions and content before writes`() {
        fun imagePackage(name: String, content: ByteArray, kind: String = "cover"): ByteArray {
            val bytes = workbook { book ->
                certificate(book)
                manifest(book, listOf(listOf("", kind, name, "")))
            }
            return zip("course.xlsx" to bytes, name to content)
        }
        assertInvalid(imagePackage("cover.png", "not an image".toByteArray()), "изображением")
        assertInvalid(imagePackage("cover.jpg", image()), "не соответствует")
        assertInvalid(imagePackage("cover.png", image(64, 64)), "от 240")
        val bomb = image().copyOf()
        ByteBuffer.wrap(bomb, 16, 8).putInt(4000).putInt(3000)
        val crc = CRC32().apply { update(bomb, 12, 17) }.value
        ByteBuffer.wrap(bomb, 29, 4).putInt(crc.toInt())
        assertInvalid(imagePackage("cover.png", bomb), "8000000")
        assertInvalid(imagePackage("cover.png", image().copyOf(40)), "изображением")
        assertThat(reader.read(upload("course.zip", imagePackage("logo.png", image(64, 64), "company_logo"))).certificate).isNotNull()
    }

    @Test
    fun `workbook and attachment byte limits and file count are enforced`() {
        assertInvalid(zip("course.xlsx" to ByteArray(2 * 1024 * 1024 + 1)), "размер файла")
        val many = (1..1103).map { "dir$it/" to byteArrayOf() }.toTypedArray()
        assertInvalid(zip(*many), "слишком много файлов")
        val bytes = workbook { book -> manifest(book, listOf(listOf(lessonCode(book), "attachment", "large.txt", ""))) }
        assertInvalid(zip("course.xlsx" to bytes, "large.txt" to ByteArray(25 * 1024 * 1024 + 1)), "25 МБ")
        val oversized = mockk<org.springframework.web.multipart.MultipartFile>()
        every { oversized.isEmpty } returns false
        every { oversized.size } returns 60L * 1024 * 1024 + 1
        assertThatThrownBy { reader.read(oversized) }.isInstanceOf(AppException::class.java).hasMessageContaining("60 МБ")
        verify(exactly = 0) { oversized.bytes }
    }

    @Test
    fun `exact retry rechecks visibility and returns receipt without creating another course`() {
        val jdbc = mockk<JdbcTemplate>()
        val actor = AdminPrincipal(UUID.randomUUID(), "import@example.test", "Importer", AdminRole.TRAINING_MANAGER, "Company")
        val existing = mockk<CourseDto>()
        every { courses.get("crs_existing") } returns existing
        every { jdbc.queryForList(any<String>(), any<String>()) } returns emptyList()
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, UUID?>>>(), any<UUID>(), any<String>()) } returns listOf("crs_existing" to null)
        val importer = CoursePackageImportService(CourseExcelImportService(courses), courses,
            mockk<TrainingService>(), mockk<TrainingProgramAssetService>(), mockk<MediaStorage>(),
            mockk<TrainingAdministrativeAccess>(relaxed = true), jdbc)
        val file = upload("course.xlsx", workbook())
        val result = importer.importPackage(file, reader.read(file).digest, actor)
        assertThat(result.reused).isTrue()
        assertThat(result.course).isSameAs(existing)
        verify(exactly = 1) { courses.get("crs_existing") }
        verify(exactly = 0) { courses.create(any(), any()) }
        assertThatThrownBy { importer.importPackage(file, "different-digest", actor) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("Файл изменился")
    }

    private fun assertInvalid(bytes: ByteArray, message: String) {
        assertThatThrownBy { reader.read(upload("course.zip", bytes)) }
            .isInstanceOf(AppException::class.java).hasMessageContaining(message)
    }

    @Test
    fun `certificate package creates a draft course route without an extra manual final stage`() {
        val jdbc = mockk<JdbcTemplate>()
        val actor = AdminPrincipal(UUID.randomUUID(), "import@example.test", "Importer", AdminRole.TRAINING_MANAGER, "Company")
        val course = mockk<CourseDto>()
        every { course.id } returns "crs_new"
        every { course.lessonItems } returns emptyList()
        every { courses.create(any(), actor.userId.toString()) } returns course
        every { courses.createLesson("crs_new", any()) } returns course
        every { courses.get("crs_new") } returns course
        every { jdbc.queryForList(any<String>(), any<String>()) } returns emptyList()
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, UUID?>>>(), any<UUID>(), any<String>()) } returns emptyList()
        every { jdbc.update(any<String>(), any<UUID>(), any<String>(), any<String>(), any<UUID>()) } returns 1
        val training = mockk<TrainingService>()
        val request = slot<CreateTrainingProgramRequest>()
        val program = mockk<TrainingProgramDto>()
        val programId = UUID.randomUUID()
        every { program.id } returns programId
        every { training.createProgram(capture(request), actor) } returns program
        val access = mockk<TrainingAdministrativeAccess>(relaxed = true)
        val importer = CoursePackageImportService(CourseExcelImportService(courses), courses,
            training, mockk<TrainingProgramAssetService>(), mockk<MediaStorage>(),
            access, jdbc)
        val file = upload("course.xlsx", workbook(::certificate))
        val result = importer.importPackage(file, reader.read(file).digest, actor)
        assertThat(result.programId).isEqualTo(programId)
        assertThat(request.captured.onlineCourseId).isEqualTo("crs_new")
        assertThat(request.captured.completionBonus).isEqualTo(reader.read(file).course.bonus.toLong())
        if (reader.read(file).course.bonus != 0) {
            verify { access.requireAll("courses", "configure_bonus") }
            verify { access.requireAll("programs", "configure_bonus") }
        }
        assertThat(request.captured.stages).hasSize(1)
        assertThat(request.captured.stages.single().type).isEqualTo(TrainingStageType.online_course)
        assertThat(request.captured.stages.single().required).isTrue()
        assertThat(request.captured.stages.single().manualReview).isFalse()
        assertThat(request.captured.status.name).isEqualTo("draft")
    }

    private fun upload(name: String, bytes: ByteArray) = MockMultipartFile("file", name, "application/octet-stream", bytes)

    private fun workbook(edit: (XSSFWorkbook) -> Unit = {}): ByteArray {
        val template = Files.readAllBytes(Path.of("../frontend/public/templates/epharm-course-import-template.xlsx"))
        return XSSFWorkbook(ByteArrayInputStream(template)).use { book ->
            listOf("Файлы", "Сертификат").forEach { name ->
                book.getSheetIndex(name).takeIf { it >= 0 }?.let(book::removeSheetAt)
            }
            edit(book)
            ByteArrayOutputStream().use { output -> book.write(output); output.toByteArray() }
        }
    }

    private fun lessonCode(book: XSSFWorkbook): String {
        val sheet = book.getSheet("Уроки")
        val column = sheet.getRow(0).first { it.stringCellValue == "Код урока" }.columnIndex
        return sheet.getRow(1).getCell(column).stringCellValue
    }

    private fun manifest(book: XSSFWorkbook, rows: List<List<String>>) {
        val sheet = book.createSheet("Файлы")
        (listOf(listOf("Код урока", "Назначение", "Путь", "Название")) + rows).forEachIndexed { index, values ->
            val row = sheet.createRow(index)
            values.forEachIndexed { col, value -> row.createCell(col).setCellValue(value) }
        }
    }

    private fun certificate(book: XSSFWorkbook) {
        val sheet = book.createSheet("Сертификат")
        listOf(listOf("Компания-партнёр", "Подписант", "Срок, мес"), listOf("Партнёр", "Подписант", "36"))
            .forEachIndexed { index, values ->
                val row = sheet.createRow(index)
                values.forEachIndexed { col, value -> row.createCell(col).setCellValue(value) }
            }
    }

    private fun image(width: Int = 240, height: Int = 240): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        output.toByteArray()
    }
}
