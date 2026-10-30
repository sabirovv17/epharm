package kz.epharm.reporting.training

import java.sql.ResultSet
import java.time.Instant
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.domain.TrainingProgramStatus
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class TrainingAuthoringExport(val kind:String,val statusFilter:String?,val generatedAt:Instant,val records:Int,val sheets:List<ReportSheet>)

/** Authoring metadata only: no learner records, answer keys, content, snapshot JSON or media URLs are selected. */
@Service
class TrainingAuthoringExportReader(private val jdbc:JdbcTemplate) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun courses(status:CourseStatus?):TrainingAuthoringExport {
        val generated=Instant.now()
        val args=status?.let {arrayOf<Any>(it.name)}?:emptyArray()
        val where=if(status==null) "" else "WHERE c.status=?"
        val courses=jdbc.query("""WITH lesson_counts AS (SELECT course_id,count(*) AS actual_lessons,
                count(*) FILTER(WHERE kind IN ('quiz','test')) AS test_lessons,
                CASE WHEN count(*) FILTER(WHERE jsonb_typeof(quiz_questions)<>'array')>0 THEN NULL
                    ELSE sum(CASE WHEN jsonb_typeof(quiz_questions)='array' THEN jsonb_array_length(quiz_questions) END) END AS structured_questions,
                string_agg(DISTINCT kind,', ' ORDER BY kind) AS formats FROM course_lessons GROUP BY course_id),
            attachment_counts AS (SELECT l.course_id,count(*) AS attachments FROM course_lesson_attachments a
                JOIN course_lessons l ON l.id=a.lesson_id GROUP BY l.course_id)
            SELECT c.id,c.title,c.status,c.category,c.duration_min,c.lessons,c.enrolled,c.completed,c.created_at,c.updated_at,
                coalesce(l.actual_lessons,0) AS actual_lessons,coalesce(l.test_lessons,0) AS test_lessons,
                CASE WHEN l.course_id IS NULL THEN 0 ELSE l.structured_questions END AS structured_questions,l.formats,coalesce(a.attachments,0) AS attachments
            FROM courses c LEFT JOIN lesson_counts l ON l.course_id=c.id LEFT JOIN attachment_counts a ON a.course_id=c.id
            $where ORDER BY c.updated_at DESC,c.id LIMIT ${rowBudget(15)+1}""",{rs,_->listOf<Any?>(rs.getString("id"),rs.getString("title"),rs.getString("status"),rs.getString("category"),
                rs.getInt("duration_min"),rs.getInt("lessons"),rs.getLong("actual_lessons"),rs.getString("formats"),rs.getLong("test_lessons"),
                rs.nullableLong("structured_questions"),rs.getLong("attachments"),rs.getInt("enrolled"),rs.getInt("completed"),
                rs.instant("created_at"),rs.instant("updated_at"))},*args)
        ensureRows(courses,15)
        val lessons=jdbc.query("""SELECT l.id,l.course_id,l.title,l.kind,l.order_no,l.required_lesson,l.duration_min,l.minimum_watch_pct,
                l.quiz_passing_score,CASE WHEN jsonb_typeof(l.quiz_questions)='array' THEN jsonb_array_length(l.quiz_questions) END AS structured_questions,l.live_provider,l.live_status,
                l.created_at,l.updated_at,(SELECT count(*) FROM course_lesson_attachments a WHERE a.lesson_id=l.id) AS attachments
            FROM course_lessons l JOIN courses c ON c.id=l.course_id $where ORDER BY l.course_id,l.order_no,l.created_at,l.id LIMIT ${rowBudget(15)+1}""",
            {rs,_->listOf<Any?>(rs.getString("id"),rs.getString("course_id"),rs.getString("title"),rs.getString("kind"),rs.getInt("order_no"),
                rs.getBoolean("required_lesson"),rs.getInt("duration_min"),rs.nullableInt("minimum_watch_pct"),
                if(rs.getString("kind") in setOf("quiz","test")) rs.getInt("quiz_passing_score") else null,
                rs.nullableInt("structured_questions"),rs.getLong("attachments"),rs.getString("live_provider"),rs.getString("live_status"),
                rs.instant("created_at"),rs.instant("updated_at"))},*args)
        ensureRows(lessons,15)
        return export("courses",status?.name,generated,courses,listOf(
            ReportSheet("Курсы",listOf("ID курса","Название","Статус","Категория","Длительность, мин (источник)","Уроков (счётчик источника)",
                "Структурированных уроков","Форматы уроков","Уроков quiz/test","Структурированных вопросов","Вложений","Записавшихся (счётчик)",
                "Завершивших (счётчик)","Создан","Обновлён"),courses),
            ReportSheet("Уроки",listOf("ID урока","ID курса","Название","Формат","Порядок","Обязательный","Длительность, мин","Минимальный просмотр, %",
                "Проходной порог quiz/test","Структурированных вопросов","Вложений","Live-провайдер","Live-статус","Создан","Обновлён"),lessons)),
            listOf("courses","course_lessons","course_lesson_attachments"),listOf(
                "Фильтр status совпадает с каталогом редактирования курсов; без фильтра включены все сохранённые статусы.",
                "Счётчики lessons/enrolled/completed сохранены отдельно от количества структурированных уроков. Их сумма не является числом уникальных людей или доказательством фактического прохождения.",
                "Число структурированных вопросов — длина массива quiz_questions, без выгрузки вопросов, вариантов, правильных ответов и разбора. Если контейнер не массив, счётчик оставлен пустым; старое текстовое содержимое не интерпретируется.",
                "Форматы сохранены по фактическим kind, включая video и live; отсутствие структурированных уроков не превращено в выдуманный формат.",
                "Контент уроков, MP4/медиа, вложения, приватные пути и URL не копируются и не изменяются; в отчёте только их счётчики/метаданные. Оценки и прогресс не пересчитываются."))
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun programs(status:TrainingProgramStatus?):TrainingAuthoringExport {
        val generated=Instant.now()
        val args=status?.let {arrayOf<Any>(it.name)}?:emptyArray()
        val where=if(status==null) "" else "WHERE p.status=?"
        val programs=jdbc.query("""SELECT p.id,p.name,p.status,p.category,p.manufacturer,p.brand,p.product,p.language,p.allowed_formats,p.current_version,
                p.starts_at,p.ends_at,p.normative_days,p.tags,p.published_at,p.archived_at,p.created_at,p.updated_at,
                v.id AS version_id,v.allowed_formats AS version_formats,v.online_course_id,v.passing_score,v.max_attempts,
                (SELECT count(*) FROM training_program_versions all_versions WHERE all_versions.program_id=p.id) AS versions,
                CASE WHEN v.id IS NULL THEN NULL ELSE (SELECT count(*) FROM training_program_stages s WHERE s.version_id=v.id) END AS stages,
                CASE WHEN v.id IS NULL THEN NULL ELSE (SELECT count(*) FROM training_program_stages s WHERE s.version_id=v.id AND s.type IN ('test','ai_exam')) END AS test_stages
            FROM training_programs p LEFT JOIN training_program_versions v ON v.program_id=p.id AND v.version_no=p.current_version
            $where ORDER BY p.updated_at DESC,p.id LIMIT ${rowBudget(26)+1}""",{rs,_->listOf<Any?>(rs.getObject("id"),rs.getString("name"),rs.getString("status"),
                rs.getString("category"),rs.getString("manufacturer"),rs.getString("brand"),rs.getString("product"),rs.getString("language"),
                rs.getString("allowed_formats"),rs.getInt("current_version"),rs.getObject("version_id"),rs.getString("version_formats"),rs.getString("online_course_id"),
                rs.nullableInt("passing_score"),rs.nullableInt("max_attempts"),rs.getLong("versions"),rs.nullableLong("stages"),rs.nullableLong("test_stages"),
                rs.instant("starts_at"),rs.instant("ends_at"),rs.getInt("normative_days"),rs.getString("tags"),rs.instant("published_at"),rs.instant("archived_at"),
                rs.instant("created_at"),rs.instant("updated_at"))},*args)
        ensureRows(programs,26)
        val stages=jdbc.query("""SELECT p.id AS program_id,s.id AS stage_id,s.version_id,s.stage_key,s.type,s.title,s.order_no,s.required,
                s.unlock_after_key,s.deadline_days,s.passing_score,s.max_attempts,s.manual_review,s.applicable_formats
            FROM training_programs p JOIN training_program_versions v ON v.program_id=p.id AND v.version_no=p.current_version
            JOIN training_program_stages s ON s.version_id=v.id $where ORDER BY p.id,s.order_no,s.id LIMIT ${rowBudget(14)+1}""",
            {rs,_->listOf<Any?>(rs.getObject("program_id"),rs.getObject("stage_id"),rs.getObject("version_id"),rs.getString("stage_key"),rs.getString("type"),rs.getString("title"),
                rs.getInt("order_no"),rs.getBoolean("required"),rs.getString("unlock_after_key"),rs.nullableInt("deadline_days"),rs.nullableInt("passing_score"),
                rs.nullableInt("max_attempts"),rs.getBoolean("manual_review"),rs.getString("applicable_formats"))},*args)
        ensureRows(stages,14)
        return export("programs",status?.name,generated,programs,listOf(
            ReportSheet("Программы",listOf("ID программы","Название","Статус","Категория","Производитель (подпись)","Бренд","Продукт","Язык","Форматы программы",
                "Текущая версия (номер)","ID текущей версии","Форматы текущей версии","ID онлайн-курса","Проходной порог","Попыток максимум","Сохранённых версий",
                "Этапов текущей версии","Этапов test/ai_exam","Начало","Окончание","Норматив, дней","Теги","Опубликована","Архивирована","Создана","Обновлена"),programs),
            ReportSheet("Этапы текущих версий",listOf("ID программы","ID этапа","ID версии","Ключ этапа (ID)","Тип","Название","Порядок","Обязательный",
                "Зависит от этапа (ID)","Срок, дней","Проходной порог","Попыток максимум","Ручная проверка","Применимые форматы"),stages)),
            listOf("training_programs","training_program_versions","training_program_stages"),listOf(
                "Фильтр status совпадает с каталогом редактирования программ. Этапы относятся только к версии с version_no=current_version на одном снимке БД.",
                "Сохранённых версий — фактическое число строк истории. Если заявленная текущая версия отсутствует, её ID, настройки и счётчики этапов оставлены пустыми, а не заменены нулём.",
                "Порог и лимит попыток — настройки программы/этапа, а не оценки, правильные ответы или доказательство прохождения. Содержимое snapshot_json и ключи ответов исключены.",
                "Подпись производителя в программе не доказывает каноническую связь CUSTOMER или принадлежность мотивационной кампании.",
                "Начисления/бонусы, участники/менеджеры, ФИО, ИИН, телефоны, логины, пароли, медиа/сертификатные URL и приватные пути исключены. Исходные файлы и версия программы не изменяются."))
    }

    private fun export(kind:String,status:String?,generated:Instant,records:List<List<Any?>>,data:List<ReportSheet>,sources:List<String>,methods:List<String>):TrainingAuthoringExport {
        val statusColumn=2
        val summary=records.groupingBy {it[statusColumn] as String}.eachCount().toSortedMap().map {(state,count)->listOf<Any?>(state,count)}
        val sheets=listOf(ReportSheet("Параметры",listOf("Параметр","Значение"),listOf(listOf("Отчёт",kind),listOf("Фильтр status",status?:"ALL"),
            listOf("Область","ALL"),listOf("Сформирован",generated),listOf("Снимок БД","REPEATABLE READ"),listOf("Исходные таблицы",sources.joinToString(", ")),
            listOf("Записей каталога",records.size))+methods.map {listOf("Методика",it)}),
            ReportSheet("Итоги",listOf("Статус","Количество записей"),listOf(listOf<Any?>("Всего",records.size))+summary))+data
        if(sheets.sumOf {(it.rows.size.toLong()+1)*it.columns.size}>ReportWorkbook.MAX_CELLS) tooLarge()
        return TrainingAuthoringExport(kind,status,generated,records.size,sheets)
    }
    private fun rowBudget(columns:Int)=minOf(ReportWorkbook.MAX_ROWS,ReportWorkbook.MAX_CELLS/columns)
    private fun ensureRows(rows:List<*>,columns:Int) {if(rows.size>rowBudget(columns)) tooLarge()}
    private fun tooLarge():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Учебный XLSX превышает лимит: уточните статус; результат не усечён",HttpStatus.PAYLOAD_TOO_LARGE)
    private fun ResultSet.instant(name:String)=getTimestamp(name)?.toInstant()
    private fun ResultSet.nullableInt(name:String)=getObject(name,Int::class.javaObjectType)
    private fun ResultSet.nullableLong(name:String)=getObject(name,Long::class.javaObjectType)
}
