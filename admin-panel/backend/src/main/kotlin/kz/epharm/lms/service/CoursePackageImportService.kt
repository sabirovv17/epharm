package kz.epharm.lms.service

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import kz.epharm.training.access.TrainingAdministrativeAccess
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.domain.TrainingStageType
import kz.epharm.training.dto.CreateTrainingProgramRequest
import kz.epharm.training.dto.CreateTrainingStageRequest
import kz.epharm.training.service.TrainingService
import kz.epharm.training.service.TrainingProgramAssetService
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

data class CoursePackagePreview(
    val digest: String,
    val title: String,
    val lessons: List<CoursePackageLessonPreview>,
    val files: Int,
    val expandedBytes: Long,
    val createsProgram: Boolean,
    val partner: String?,
    val existingCourseId: String? = null,
)
data class CoursePackageLessonPreview(val code: String, val title: String, val type: String, val questions: Int)
data class CoursePackageResult(val course: CourseDto, val programId: UUID?, val reused: Boolean)

/** Bounded parsing never extracts client paths to disk. Writes are one DB transaction. */
@Service
class CoursePackageImportService(
    private val excel: CourseExcelImportService,
    private val courses: CourseService,
    private val training: TrainingService,
    private val assets: TrainingProgramAssetService,
    private val storage: MediaStorage,
    private val access: TrainingAdministrativeAccess,
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun preview(file: MultipartFile, actor: AdminPrincipal): CoursePackagePreview {
        access.requireAll("courses", "import")
        val parsed = CoursePackageReader(excel).read(file)
        if (parsed.certificate != null) access.requireAll("programs", "create")
        requireBonusPermissions(parsed)
        val existing = receipt(actor.userId, parsed.digest)
        existing?.let { courses.get(it.first) } // Re-check current visibility, including on retries.
        return parsed.preview(existing?.first)
    }

    @Transactional
    fun importPackage(file: MultipartFile, expectedDigest: String, actor: AdminPrincipal): CoursePackageResult {
        access.lockWriter()
        access.requireAll("courses", "import")
        val parsed = CoursePackageReader(excel).read(file)
        if (expectedDigest != parsed.digest) packageInvalid("Файл изменился. Проверьте пакет ещё раз")
        if (parsed.certificate != null) access.requireAll("programs", "create")
        requireBonusPermissions(parsed)
        // Serialize exact retries across app instances; receipt and draft commit together.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", "course-import:${actor.userId}:${parsed.digest}")
        receipt(actor.userId, parsed.digest)?.let {
            return CoursePackageResult(courses.get(it.first), it.second, true)
        }
        val course = excel.createImported(parsed.course, actor.userId.toString())
        val lessonIds = courses.get(course.id).lessonItems.sortedBy { it.order }.map { it.id }
        val codeToId = parsed.course.lessons.sortedBy { it.order }.map { it.code }.zip(lessonIds).toMap()
        parsed.files.filter { it.lessonCode.isNotBlank() }.forEach { item ->
            val lessonId = codeToId.getValue(item.lessonCode)
            val media = PackageMultipart(item.path, parsed.bytes.getValue(item.path), item.mime)
            if (item.kind == "video") courses.uploadLessonVideo(course.id, lessonId, media)
            else courses.uploadLessonAttachment(course.id, lessonId, media, item.title)
        }
        val programId = parsed.certificate?.let { config ->
            fun image(kind: String): String? = parsed.files.singleOrNull { it.kind == kind }?.let { item ->
                val media = PackageMultipart(item.path, parsed.bytes.getValue(item.path), item.mime)
                val url = if (kind == "cover") assets.uploadCover(media).coverUrl
                    else assets.uploadCertificateAsset(media).assetUrl
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) storage.delete(url)
                    }
                })
                url
            }
            training.createProgram(CreateTrainingProgramRequest(
                name = parsed.course.title,
                description = parsed.course.description,
                category = parsed.course.category,
                manufacturer = config.partner,
                certificateSignerName = config.signer,
                certificateValidityMonths = config.months,
                coverUrl = image("cover"),
                certificateEpharmLogoUrl = image("company_logo"),
                certificatePartnerLogoUrl = image("partner_logo"),
                allowedFormats = setOf(TrainingFormat.online),
                onlineCourseId = course.id,
                completionBonus = parsed.course.bonus.toLong(),
                stages = listOf(CreateTrainingStageRequest(
                    key = "online_course", type = TrainingStageType.online_course,
                    title = parsed.course.title, order = 0, required = true,
                    applicableFormats = setOf(TrainingFormat.online),
                )),
            ), actor).id
        }
        jdbc.update("insert into course_package_import_receipts(author_id,digest,course_id,program_id) values (?,?,?,?)",
            actor.userId, parsed.digest, course.id, programId)
        return CoursePackageResult(courses.get(course.id), programId, false)
    }

    private fun receipt(author: UUID, digest: String): Pair<String, UUID?>? = jdbc.query(
        "select course_id,program_id from course_package_import_receipts where author_id=? and digest=?",
        { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }, author, digest,
    ).firstOrNull()

    private fun requireBonusPermissions(parsed: ParsedCoursePackage) {
        if (parsed.course.bonus == 0) return
        access.requireAll("courses", "configure_bonus")
        if (parsed.certificate != null) access.requireAll("programs", "configure_bonus")
    }
}

internal data class PackageCertificate(val partner: String, val signer: String, val months: Int)
internal data class PackageFile(val lessonCode: String, val kind: String, val path: String, val title: String, val mime: String)
internal data class ParsedCoursePackage(
    val digest: String,
    val course: CourseExcelImportService.ImportedCourse,
    val bytes: Map<String, ByteArray>,
    val files: List<PackageFile>,
    val certificate: PackageCertificate?,
) {
    fun preview(existing: String? = null) = CoursePackagePreview(digest, course.title,
        course.lessons.sortedBy { it.order }.map { CoursePackageLessonPreview(it.code, it.request.title, it.request.kind.name, it.request.quizQuestions.size) },
        files.size, bytes.values.sumOf { it.size.toLong() }, certificate != null, certificate?.partner, existing)
}

internal class CoursePackageReader(private val excel: CourseExcelImportService) {
    fun read(file: MultipartFile): ParsedCoursePackage {
        if (file.isEmpty || file.size > MAX_UPLOAD) packageInvalid("Выберите Excel или ZIP не более 60 МБ")
        val raw = file.bytes
        if (raw.size.toLong() > MAX_UPLOAD) packageInvalid("Размер пакета превышает 60 МБ")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val extension = file.originalFilename.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT)
        val files = when (extension) {
            "xlsx" -> mapOf("course.xlsx" to raw)
            "zip" -> unzip(raw)
            else -> packageInvalid("Поддерживаются .xlsx и .zip")
        }
        val workbook = files["course.xlsx"] ?: packageInvalid("В корне ZIP должен быть файл course.xlsx")
        val course = excel.parse(PackageMultipart("course.xlsx", workbook, XLSX))
        val manifest = mutableListOf<PackageFile>()
        var certificate: PackageCertificate? = null
        XSSFWorkbook(ByteArrayInputStream(workbook)).use { book ->
            val format = DataFormatter(Locale.forLanguageTag("ru-RU"))
            fun rows(name: String, headers: List<String>): List<Map<String, String>> {
                val sheet = book.getSheet(name) ?: return emptyList()
                if (sheet.lastRowNum > 1001) packageInvalid("Лист «$name»: слишком много строк")
                fun text(row: Int, col: Int): String {
                    val cell = sheet.getRow(row)?.getCell(col) ?: return ""
                    if (cell.cellType == CellType.FORMULA) packageInvalid("Лист «$name», строка ${row + 1}: формулы запрещены")
                    return format.formatCellValue(cell).trim()
                }
                val indexes = headers.map { label ->
                    val matching = (0 until (sheet.getRow(0)?.lastCellNum?.toInt() ?: 0)).filter { text(0, it) == label }
                    if (matching.size != 1) packageInvalid("Лист «$name»: столбец «$label» должен быть указан ровно один раз")
                    matching.single()
                }
                return (1..sheet.lastRowNum).map { row -> headers.zip(indexes.map { text(row, it) }).toMap() }
                    .filter { it.values.any(String::isNotBlank) }
            }
            val certificateRows = rows("Сертификат", listOf("Компания-партнёр", "Подписант", "Срок, мес"))
            if (certificateRows.size > 1) packageInvalid("На листе «Сертификат» разрешена одна строка")
            certificateRows.singleOrNull()?.let { row ->
                val partner = row.getValue("Компания-партнёр")
                val signer = row.getValue("Подписант")
                val months = row.getValue("Срок, мес").toIntOrNull()
                if (partner.length > 255 || signer.isBlank() || signer.length > 255 || months == null || months !in 1..120) {
                    packageInvalid("Сертификат: подписант до 255 символов, компания до 255, срок от 1 до 120 месяцев")
                }
                certificate = PackageCertificate(partner, signer, months)
            }
            val lessons = course.lessons.associateBy { it.code }
            rows("Файлы", listOf("Код урока", "Назначение", "Путь", "Название")).forEach { row ->
                val code = row.getValue("Код урока")
                val kind = row.getValue("Назначение").lowercase(Locale.ROOT)
                val path = safePath(row.getValue("Путь"))
                // The authoring workbook contains quiz answer keys and must never become learner material.
                if (path.equals("course.xlsx", ignoreCase = true)) packageInvalid("course.xlsx нельзя прикрепить как материал урока")
                val bytes = files[path] ?: packageInvalid("Не найден файл «$path»")
                val ext = path.substringAfterLast('.').lowercase(Locale.ROOT)
                val title = row.getValue("Название")
                if (title.length > 255) packageInvalid("Название файла: не более 255 символов")
                val lesson = lessons[code]
                val mime = when (kind) {
                    "video" -> {
                        if (lesson?.request?.kind != CourseLessonKind.video) packageInvalid("Файл «$path»: видео прикрепляется к уроку типа «Видео»")
                        if (ext !in setOf("mp4", "webm") || bytes.size > MAX_UPLOAD) packageInvalid("Видео: MP4/WebM, до 60 МБ")
                        "video/$ext"
                    }
                    "attachment" -> {
                        if (lesson == null) packageInvalid("Для файла «$path» не найден код урока «$code»")
                        val attachmentMime = ATTACHMENT_TYPES[ext]
                        if (attachmentMime == null || bytes.size > 25L * 1024 * 1024) packageInvalid("Неподдерживаемый материал или размер более 25 МБ: $path")
                        if (attachmentMime.startsWith("image/")) validateImage(bytes, ext, 1)
                        attachmentMime
                    }
                    "cover", "company_logo", "partner_logo" -> {
                        if (code.isNotBlank() || certificate == null) packageInvalid("Для обложки/логотипа заполните «Сертификат», код урока оставьте пустым")
                        if (ext !in setOf("jpg", "jpeg", "png") || bytes.size > 5L * 1024 * 1024) packageInvalid("Обложка и логотипы: JPG/PNG до 5 МБ")
                        validateImage(bytes, ext, if (kind == "cover") 240 else 64)
                        if (ext == "png") "image/png" else "image/jpeg"
                    }
                    else -> packageInvalid("Неизвестное назначение файла «$kind»")
                }
                if (bytes.isEmpty()) packageInvalid("Файл «$path» пуст")
                manifest += PackageFile(code, kind, path, title, mime)
            }
        }
        if (manifest.map { it.path }.distinct().size != manifest.size) packageInvalid("Каждый файл указывайте в таблице один раз")
        manifest.groupBy { it.lessonCode to it.kind }.forEach { (key, rows) ->
            if (rows.size > if (key.second == "attachment") 10 else 1) packageInvalid("Слишком много файлов назначения «${key.second}» для «${key.first}»")
        }
        val unlisted = files.keys - manifest.map { it.path }.toSet() - "course.xlsx"
        if (unlisted.isNotEmpty()) packageInvalid("Файлы не указаны на листе «Файлы»: ${unlisted.take(3).joinToString()}")
        return ParsedCoursePackage(digest, course, files, manifest, certificate)
    }

    private fun unzip(raw: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var expanded = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > 1102) packageInvalid("В ZIP слишком много файлов и папок")
                    val path = safePath(if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name)
                    if (!names.add(path.lowercase(Locale.ROOT))) packageInvalid("Повторный путь ZIP: $path")
                    if (entry.isDirectory) {
                        // getNextEntry drains the previous entry, so even directory bodies must be bounded.
                        if (zip.read() != -1) packageInvalid("Папка ZIP не должна содержать данные")
                        zip.closeEntry()
                        continue
                    }
                    val limit = if (path == "course.xlsx") 2 * 1024 * 1024 else MAX_UPLOAD.toInt()
                    val bytes = zip.readNBytes(limit + 1)
                    if (bytes.size > limit) packageInvalid("Превышен размер файла «$path»")
                    expanded += bytes.size
                    if (expanded > 120L * 1024 * 1024) packageInvalid("Распакованный ZIP превышает 120 МБ")
                    files[path] = bytes
                    zip.closeEntry()
                }
            }
        } catch (error: AppException) { throw error }
        catch (_: Exception) { packageInvalid("Не удалось прочитать ZIP. Проверьте целостность архива") }
        return files
    }

    private fun safePath(value: String): String {
        if (value.isBlank() || value.length > 240 || value.startsWith('/') || value.contains('\\') ||
            value.contains(':') || value.any { it.code < 32 } || value.split('/').any { it in setOf("", ".", "..") }) {
            packageInvalid("Недопустимый относительный путь в пакете")
        }
        return value
    }

    private fun validateImage(bytes: ByteArray, extension: String, minSide: Int) {
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) packageInvalid("Файл не является корректным изображением")
                val reader = readers.next()
                try {
                    reader.input = stream
                    val actual = reader.formatName.lowercase(Locale.ROOT)
                    val expected = if (extension in setOf("jpg", "jpeg")) "jpeg" else extension
                    if (actual != expected) packageInvalid("Расширение файла не соответствует типу изображения")
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width < minSide || height < minSide || width > 6000 || height > 6000 || width.toLong() * height > 8_000_000L) {
                        packageInvalid("Изображение: от $minSide до 6000 пикселей по стороне, не более 8000000 пикселей")
                    }
                    if (reader.read(0) == null) packageInvalid("Файл не является корректным изображением")
                } finally {
                    reader.dispose()
                }
            } ?: packageInvalid("Файл не является корректным изображением")
        } catch (error: AppException) {
            throw error
        } catch (_: Exception) {
            packageInvalid("Файл не является корректным изображением")
        }
    }
    private companion object {
        const val MAX_UPLOAD = 60L * 1024 * 1024
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val ATTACHMENT_TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "webp" to "image/webp", "gif" to "image/gif", "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel", "xlsx" to XLSX,
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt" to "text/plain", "csv" to "text/csv", "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "ogg" to "audio/ogg",
        )
    }
}

internal class PackageMultipart(private val filename: String, private val content: ByteArray, private val mime: String) : MultipartFile {
    override fun getName() = "file"
    override fun getOriginalFilename() = filename
    override fun getContentType() = mime
    override fun isEmpty() = content.isEmpty()
    override fun getSize() = content.size.toLong()
    override fun getBytes() = content
    override fun getInputStream() = ByteArrayInputStream(content)
    override fun transferTo(dest: File) { dest.outputStream().use { it.write(content) } }
}
private fun packageInvalid(message: String): Nothing =
    throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
