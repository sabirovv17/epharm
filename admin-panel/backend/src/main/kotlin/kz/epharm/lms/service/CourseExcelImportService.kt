package kz.epharm.lms.service

import kz.epharm.lms.dto.CourseDto
import kz.epharm.lms.dto.CourseQuizQuestionRequest
import kz.epharm.lms.dto.CreateCourseLessonRequest
import kz.epharm.lms.dto.CreateCourseRequest
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

@Service
class CourseExcelImportService(
    private val courseService: CourseService,
) {
    private val formatter = DataFormatter(Locale.forLanguageTag("ru-RU"))

    @Transactional
    fun importCourse(file: MultipartFile, createdBy: String): CourseDto {
        val imported = parse(file)
        val course = courseService.create(
            CreateCourseRequest(
                title = imported.title,
                category = imported.category,
                description = imported.description,
                bonus = imported.bonus,
                status = CourseStatus.draft,
            ),
            createdBy,
        )
        imported.lessons.sortedBy(ImportedLesson::order).forEach { lesson ->
            courseService.createLesson(course.id, lesson.request)
        }
        return courseService.get(course.id)
    }

    private fun parse(file: MultipartFile): ImportedCourse {
        if (file.isEmpty) invalid("Выберите заполненный Excel-файл")
        if (file.size > MAX_IMPORT_BYTES) invalid("Размер Excel-файла не должен превышать 2 МБ")
        if (file.originalFilename.orEmpty().substringAfterLast('.', "").lowercase() != "xlsx") {
            invalid("Поддерживается только формат .xlsx")
        }

        try {
            WorkbookFactory.create(file.inputStream).use { workbook ->
                if (workbook !is XSSFWorkbook) invalid("Поддерживается только формат .xlsx")
                val courseSheet = workbook.getSheet(COURSE_SHEET)
                    ?: invalid("Не найден лист «$COURSE_SHEET»")
                val lessonsSheet = workbook.getSheet(LESSONS_SHEET)
                    ?: invalid("Не найден лист «$LESSONS_SHEET»")
                val questionsSheet = workbook.getSheet(QUESTIONS_SHEET)
                    ?: invalid("Не найден лист «$QUESTIONS_SHEET»")

                val course = parseCourse(courseSheet)
                val lessons = parseLessons(lessonsSheet)
                val questions = parseQuestions(questionsSheet, lessons)
                val withQuestions = lessons.map { lesson ->
                    val lessonQuestions = questions[lesson.code].orEmpty()
                    if (lesson.request.kind in QUIZ_KINDS && lessonQuestions.isEmpty()) {
                        invalid("Для теста «${lesson.request.title}» добавьте вопросы на листе «$QUESTIONS_SHEET»")
                    }
                    lesson.copy(request = lesson.request.copy(quizQuestions = lessonQuestions))
                }
                return course.copy(lessons = withQuestions)
            }
        } catch (exception: AppException) {
            throw exception
        } catch (_: Exception) {
            invalid("Не удалось прочитать Excel. Скачайте шаблон и не меняйте его структуру")
        }
    }

    private fun parseCourse(sheet: Sheet): ImportedCourse {
        val columns = headerColumns(sheet, COURSE_HEADERS)
        val row = sheet.getRow(1) ?: invalid("На листе «$COURSE_SHEET» заполните строку 2")
        if (isBlank(row, columns.values)) invalid("На листе «$COURSE_SHEET» заполните строку 2")
        if ((2..sheet.lastRowNum).any { index ->
                sheet.getRow(index)?.let { !isBlank(it, columns.values) } == true
            }
        ) {
            invalid("На листе «$COURSE_SHEET» должна быть только одна строка с курсом")
        }

        val title = requiredText(sheet, row, columns.getValue("Название"), "Название")
        val category = text(sheet, row, columns.getValue("Категория"))
        val description = text(sheet, row, columns.getValue("Описание"))
        val bonus = optionalInt(sheet, row, columns.getValue("Бонус, ₸")) ?: 0
        checkLength(title, 255, COURSE_SHEET, row.rowNum, "Название")
        checkLength(category, 128, COURSE_SHEET, row.rowNum, "Категория")
        checkLength(description, 10_000, COURSE_SHEET, row.rowNum, "Описание")
        if (bonus < 0) cellInvalid(COURSE_SHEET, row.rowNum, "Бонус, ₸", "укажите число от 0")
        return ImportedCourse(title, category, description, bonus, emptyList())
    }

    private fun parseLessons(sheet: Sheet): List<ImportedLesson> {
        val columns = headerColumns(sheet, LESSON_HEADERS)
        val lessons = mutableListOf<ImportedLesson>()
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            if (isBlank(row, columns.values)) continue
            if (lessons.size >= MAX_LESSONS) invalid("В одном курсе может быть не более $MAX_LESSONS уроков")

            val code = requiredText(sheet, row, columns.getValue("Код урока"), "Код урока")
            val order = requiredInt(sheet, row, columns.getValue("Порядок"), "Порядок")
            val title = requiredText(sheet, row, columns.getValue("Название"), "Название")
            val kindLabel = requiredText(sheet, row, columns.getValue("Тип"), "Тип")
            val kind = LESSON_KIND_ALIASES[normalize(kindLabel)]
                ?: cellInvalid(LESSONS_SHEET, row.rowNum, "Тип", "неизвестный тип «$kindLabel»")
            val duration = optionalInt(sheet, row, columns.getValue("Длительность, мин")) ?: 0
            val description = text(sheet, row, columns.getValue("Краткое описание"))
            val content = text(sheet, row, columns.getValue("Содержание"))
            val externalUrl = text(sheet, row, columns.getValue("Внешняя ссылка")).ifBlank { null }
            val required = optionalBoolean(sheet, row, columns.getValue("Обязательный")) ?: true
            val minimumWatchPct = optionalInt(sheet, row, columns.getValue("Мин. просмотр, %"))
            val passingScore = optionalInt(sheet, row, columns.getValue("Проходной балл, %")) ?: 80

            checkLength(code, 64, LESSONS_SHEET, row.rowNum, "Код урока")
            checkLength(title, 255, LESSONS_SHEET, row.rowNum, "Название")
            checkLength(description, 1_000, LESSONS_SHEET, row.rowNum, "Краткое описание")
            checkLength(content, 10_000, LESSONS_SHEET, row.rowNum, "Содержание")
            externalUrl?.let { checkLength(it, 1_000, LESSONS_SHEET, row.rowNum, "Внешняя ссылка") }
            if (order !in 1..MAX_LESSONS) {
                cellInvalid(LESSONS_SHEET, row.rowNum, "Порядок", "укажите число от 1 до $MAX_LESSONS")
            }
            if (duration < 0) cellInvalid(LESSONS_SHEET, row.rowNum, "Длительность, мин", "укажите число от 0")
            if (kind == CourseLessonKind.video && duration !in 1..30) {
                cellInvalid(LESSONS_SHEET, row.rowNum, "Длительность, мин", "для видео укажите от 1 до 30 минут")
            }
            if (minimumWatchPct != null && minimumWatchPct !in 0..100) {
                cellInvalid(LESSONS_SHEET, row.rowNum, "Мин. просмотр, %", "укажите число от 0 до 100")
            }
            if (passingScore !in 1..100) {
                cellInvalid(LESSONS_SHEET, row.rowNum, "Проходной балл, %", "укажите число от 1 до 100")
            }
            if (kind in setOf(CourseLessonKind.link, CourseLessonKind.interactive) && externalUrl == null) {
                cellInvalid(LESSONS_SHEET, row.rowNum, "Внешняя ссылка", "поле обязательно для этого типа урока")
            }

            lessons += ImportedLesson(
                code = code,
                order = order,
                request = CreateCourseLessonRequest(
                    title = title,
                    description = description,
                    content = content,
                    kind = kind,
                    externalUrl = externalUrl,
                    required = required,
                    minimumWatchPct = minimumWatchPct,
                    quizPassingScore = passingScore,
                    durationMin = duration,
                ),
            )
        }
        if (lessons.isEmpty()) invalid("Добавьте хотя бы один урок на листе «$LESSONS_SHEET»")
        lessons.groupBy { normalize(it.code) }.values.firstOrNull { it.size > 1 }?.let {
            invalid("Код урока «${it.first().code}» повторяется на листе «$LESSONS_SHEET»")
        }
        lessons.groupBy(ImportedLesson::order).values.firstOrNull { it.size > 1 }?.let {
            invalid("Порядок урока ${it.first().order} повторяется на листе «$LESSONS_SHEET»")
        }
        return lessons
    }

    private fun parseQuestions(
        sheet: Sheet,
        lessons: List<ImportedLesson>,
    ): Map<String, List<CourseQuizQuestionRequest>> {
        val columns = headerColumns(sheet, QUESTION_HEADERS)
        val lessonByCode = lessons.associateBy { normalize(it.code) }
        val questions = mutableMapOf<String, MutableList<ImportedQuestion>>()
        var count = 0
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            if (isBlank(row, columns.values)) continue
            if (++count > MAX_QUESTIONS_TOTAL) invalid("В одном файле может быть не более $MAX_QUESTIONS_TOTAL вопросов")

            val code = requiredText(sheet, row, columns.getValue("Код урока"), "Код урока")
            val lesson = lessonByCode[normalize(code)]
                ?: cellInvalid(QUESTIONS_SHEET, row.rowNum, "Код урока", "урок «$code» не найден")
            if (lesson.request.kind !in QUIZ_KINDS) {
                cellInvalid(QUESTIONS_SHEET, row.rowNum, "Код урока", "урок «$code» не является тестом")
            }
            val order = requiredInt(sheet, row, columns.getValue("Порядок"), "Порядок")
            val prompt = requiredText(sheet, row, columns.getValue("Вопрос"), "Вопрос")
            val rawOptions = (1..8).map { number ->
                text(sheet, row, columns.getValue("Вариант $number"))
            }
            val lastOption = rawOptions.indexOfLast(String::isNotBlank)
            if (lastOption < 1) {
                cellInvalid(QUESTIONS_SHEET, row.rowNum, "Вариант 2", "заполните минимум два варианта")
            }
            val options = rawOptions.take(lastOption + 1)
            if (options.any(String::isBlank)) {
                cellInvalid(QUESTIONS_SHEET, row.rowNum, "Варианты", "заполняйте варианты подряд, без пропусков")
            }
            val correct = requiredInt(
                sheet,
                row,
                columns.getValue("Правильный вариант"),
                "Правильный вариант",
            )
            if (correct !in 1..options.size) {
                cellInvalid(
                    QUESTIONS_SHEET,
                    row.rowNum,
                    "Правильный вариант",
                    "укажите номер от 1 до ${options.size}",
                )
            }
            val explanation = text(sheet, row, columns.getValue("Пояснение"))
            checkLength(prompt, 1_000, QUESTIONS_SHEET, row.rowNum, "Вопрос")
            options.forEachIndexed { index, option ->
                checkLength(option, 1_000, QUESTIONS_SHEET, row.rowNum, "Вариант ${index + 1}")
            }
            checkLength(explanation, 2_000, QUESTIONS_SHEET, row.rowNum, "Пояснение")

            val key = lesson.code
            val target = questions.getOrPut(key) { mutableListOf() }
            if (target.size >= MAX_QUESTIONS_PER_TEST) {
                invalid("В тесте «${lesson.request.title}» может быть не более $MAX_QUESTIONS_PER_TEST вопросов")
            }
            target += ImportedQuestion(
                order,
                CourseQuizQuestionRequest(
                    prompt = prompt,
                    options = options,
                    correctOption = correct - 1,
                    explanation = explanation,
                ),
            )
        }

        return questions.mapValues { (code, rows) ->
            rows.groupBy(ImportedQuestion::order).values.firstOrNull { it.size > 1 }?.let {
                invalid("Порядок вопроса ${it.first().order} повторяется для урока «$code»")
            }
            rows.sortedBy(ImportedQuestion::order).map(ImportedQuestion::request)
        }
    }

    private fun headerColumns(sheet: Sheet, requiredHeaders: List<String>): Map<String, Int> {
        val row = sheet.getRow(0) ?: invalid("На листе «${sheet.sheetName}» отсутствуют заголовки")
        val found = (0 until row.lastCellNum.coerceAtLeast(0).toInt()).associateBy(
            keySelector = { index -> text(sheet, row, index) },
            valueTransform = { it },
        )
        requiredHeaders.firstOrNull { it !in found }?.let {
            invalid("На листе «${sheet.sheetName}» не найден столбец «$it»")
        }
        return requiredHeaders.associateWith(found::getValue)
    }

    private fun requiredText(sheet: Sheet, row: Row, column: Int, label: String): String =
        text(sheet, row, column).takeIf(String::isNotBlank)
            ?: cellInvalid(sheet.sheetName, row.rowNum, label, "поле обязательно")

    private fun text(sheet: Sheet, row: Row, column: Int): String {
        val cell = row.getCell(column) ?: return ""
        rejectFormula(sheet, row, cell)
        return formatter.formatCellValue(cell).trim()
    }

    private fun requiredInt(sheet: Sheet, row: Row, column: Int, label: String): Int =
        optionalInt(sheet, row, column)
            ?: cellInvalid(sheet.sheetName, row.rowNum, label, "укажите целое число")

    private fun optionalInt(sheet: Sheet, row: Row, column: Int): Int? {
        val cell = row.getCell(column) ?: return null
        rejectFormula(sheet, row, cell)
        val rendered = formatter.formatCellValue(cell).trim()
        if (cell.cellType == CellType.BLANK || rendered.isBlank()) return null
        if (cell.cellType == CellType.NUMERIC) {
            val value = cell.numericCellValue
            if (!value.isFinite() || value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                cellInvalid(sheet.sheetName, row.rowNum, header(sheet, column), "укажите целое число")
            }
            return value.toInt()
        }
        return rendered.replace(" ", "").toIntOrNull()
            ?: cellInvalid(sheet.sheetName, row.rowNum, header(sheet, column), "укажите целое число")
    }

    private fun optionalBoolean(sheet: Sheet, row: Row, column: Int): Boolean? {
        val value = text(sheet, row, column)
        if (value.isBlank()) return null
        return when (normalize(value)) {
            "да", "yes", "true", "1" -> true
            "нет", "no", "false", "0" -> false
            else -> cellInvalid(sheet.sheetName, row.rowNum, header(sheet, column), "используйте «Да» или «Нет»")
        }
    }

    private fun rejectFormula(sheet: Sheet, row: Row, cell: Cell) {
        if (cell.cellType == CellType.FORMULA) {
            cellInvalid(sheet.sheetName, row.rowNum, header(sheet, cell.columnIndex), "формулы запрещены")
        }
    }

    private fun header(sheet: Sheet, column: Int): String =
        sheet.getRow(0)?.getCell(column)?.let(formatter::formatCellValue)?.trim().orEmpty().ifBlank {
            "столбец ${column + 1}"
        }

    private fun isBlank(row: Row, columns: Collection<Int>): Boolean =
        columns.all { column ->
            val cell = row.getCell(column)
            cell == null || (cell.cellType != CellType.FORMULA && formatter.formatCellValue(cell).isBlank())
        }

    private fun checkLength(value: String, max: Int, sheet: String, row: Int, column: String) {
        if (value.length > max) cellInvalid(sheet, row, column, "не более $max символов")
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun cellInvalid(sheet: String, row: Int, column: String, details: String): Nothing =
        invalid("Лист «$sheet», строка ${row + 1}, «$column»: $details")

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    private data class ImportedCourse(
        val title: String,
        val category: String,
        val description: String,
        val bonus: Int,
        val lessons: List<ImportedLesson>,
    )

    private data class ImportedLesson(
        val code: String,
        val order: Int,
        val request: CreateCourseLessonRequest,
    )

    private data class ImportedQuestion(
        val order: Int,
        val request: CourseQuizQuestionRequest,
    )

    private companion object {
        const val COURSE_SHEET = "Курс"
        const val LESSONS_SHEET = "Уроки"
        const val QUESTIONS_SHEET = "Вопросы"
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024
        const val MAX_LESSONS = 100
        const val MAX_QUESTIONS_PER_TEST = 50
        const val MAX_QUESTIONS_TOTAL = 1_000

        val COURSE_HEADERS = listOf("Название", "Категория", "Описание", "Бонус, ₸")
        val LESSON_HEADERS = listOf(
            "Код урока",
            "Порядок",
            "Название",
            "Тип",
            "Длительность, мин",
            "Краткое описание",
            "Содержание",
            "Внешняя ссылка",
            "Обязательный",
            "Мин. просмотр, %",
            "Проходной балл, %",
        )
        val QUESTION_HEADERS = listOf(
            "Код урока",
            "Порядок",
            "Вопрос",
            "Вариант 1",
            "Вариант 2",
            "Вариант 3",
            "Вариант 4",
            "Вариант 5",
            "Вариант 6",
            "Вариант 7",
            "Вариант 8",
            "Правильный вариант",
            "Пояснение",
        )
        val QUIZ_KINDS = setOf(CourseLessonKind.quiz, CourseLessonKind.test)
        val LESSON_KIND_ALIASES = mapOf(
            "текст" to CourseLessonKind.text,
            "text" to CourseLessonKind.text,
            "видео" to CourseLessonKind.video,
            "video" to CourseLessonKind.video,
            "pdf" to CourseLessonKind.pdf,
            "презентация" to CourseLessonKind.presentation,
            "presentation" to CourseLessonKind.presentation,
            "изображение" to CourseLessonKind.image,
            "image" to CourseLessonKind.image,
            "аудио" to CourseLessonKind.audio,
            "audio" to CourseLessonKind.audio,
            "ссылка" to CourseLessonKind.link,
            "link" to CourseLessonKind.link,
            "интерактив" to CourseLessonKind.interactive,
            "interactive" to CourseLessonKind.interactive,
            "викторина" to CourseLessonKind.quiz,
            "quiz" to CourseLessonKind.quiz,
            "тест" to CourseLessonKind.test,
            "test" to CourseLessonKind.test,
            "практика" to CourseLessonKind.practice,
            "practice" to CourseLessonKind.practice,
            "задание" to CourseLessonKind.assignment,
            "assignment" to CourseLessonKind.assignment,
        )
    }
}
