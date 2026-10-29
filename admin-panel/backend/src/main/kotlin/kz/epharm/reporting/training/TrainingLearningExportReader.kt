package kz.epharm.reporting.training

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.reporting.exports.ReportPeriod
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class TrainingLearningFilter(val format:TrainingFormat?,val status:TrainingAssignmentStatus?,val programId:UUID?)
data class TrainingLearningExport(val kind:String,val filters:Map<String,String?>,val dataRows:Int,val sheets:List<ReportSheet>)

/** D6 learning visibility follows the current profile; no historical money, answer key or grade calculation enters this reader. */
@Service
class TrainingLearningExportReader(private val jdbc:NamedParameterJdbcTemplate,private val access:AdministrativeScope) {
    private data class ScopeQuery(val where:String,val parameters:MutableMap<String,Any>,val description:String)
    private fun scope(actor:AdminPrincipal,filter:TrainingLearningFilter):ScopeQuery {
        val pharmacies=access.currentPharmacyIds()
        if(pharmacies?.isEmpty()==true) forbidden()
        val parameters=mutableMapOf<String,Any>("actor" to actor.userId)
        val where=buildString {
            if(pharmacies!=null) {append(" AND ph.pharmacy_id IN (:pharmacies)");parameters["pharmacies"]=pharmacies}
            when(actor.role) {
                AdminRole.REGIONAL_MANAGER->append(" AND EXISTS(SELECT 1 FROM admin_training_regions region WHERE region.admin_user_id=:actor AND region.region=ph.city)")
                AdminRole.TRAINER->append(" AND EXISTS(SELECT 1 FROM offline_events event WHERE event.id=a.event_id AND event.trainer_id=:actor)")
                AdminRole.SYSTEM_ADMIN,AdminRole.HQ_HEAD,AdminRole.TRAINING_MANAGER->Unit
                else->forbidden()
            }
            filter.format?.let {append(" AND a.format=:format");parameters["format"]=it.name}
            filter.status?.let {append(" AND a.status=:status");parameters["status"]=it.name}
            filter.programId?.let {append(" AND p.id=:program");parameters["program"]=it}
        }
        return ScopeQuery(where,parameters,if(pharmacies==null) "ALL текущих аптек, с дополнительным учебным ограничением роли" else "Только назначенные текущие аптеки и дополнительное учебное ограничение роли")
    }
    private val assignmentFrom="""FROM training_assignments a JOIN pharmacists ph ON ph.id=a.pharmacist_id
        JOIN training_program_versions v ON v.id=a.program_version_id JOIN training_programs p ON p.id=v.program_id
        LEFT JOIN pharmacies pharmacy ON pharmacy.id=ph.pharmacy_id"""

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun assignments(actor:AdminPrincipal,filter:TrainingLearningFilter):TrainingLearningExport {
        val scoped=scope(actor,filter)
        val financial=access.canAllPermission(UnifiedAdminCatalog.key("pharmacists","registry","financial_data"))
        val rewardColumns=if(financial) "reward.amount AS reward_amount,reward.status AS reward_status" else "NULL::bigint AS reward_amount,NULL::text AS reward_status"
        val rewardJoin=if(financial) "LEFT JOIN training_rewards reward ON reward.assignment_id=a.id AND reward.pharmacist_id=a.pharmacist_id" else ""
        if(financial && jdbc.queryForObject("""SELECT EXISTS(SELECT 1 $assignmentFrom JOIN training_rewards reward ON reward.assignment_id=a.id
            WHERE true ${scoped.where} AND reward.pharmacist_id<>a.pharmacist_id)""",scoped.parameters,Boolean::class.java)==true) conflict()
        val rows=jdbc.query("""SELECT a.id,p.id AS program_id,a.program_version_id,v.version_no,p.name,a.pharmacist_id,ph.pharmacy_id,
            pharmacy.name AS pharmacy_name,ph.city,a.format,a.status,a.priority,a.required,a.progress_pct,a.score,a.starts_at,a.due_at,
            a.started_at,a.completed_at,a.cancelled_at,a.repeat_no,a.created_at,a.updated_at,a.event_id,ph.name AS pharmacist_name,$rewardColumns $assignmentFrom $rewardJoin
            WHERE true ${scoped.where} ORDER BY a.created_at,a.id LIMIT ${budget(27)+1}""",scoped.parameters) {rs,_->listOf<Any?>(
            rs.getObject("id"),rs.getObject("program_id"),rs.getObject("program_version_id"),rs.getInt("version_no"),rs.getString("name"),rs.getString("pharmacist_id"),
            rs.getString("pharmacy_id"),rs.getString("pharmacy_name"),rs.getString("city"),rs.getString("format"),rs.getString("status"),rs.getString("priority"),
            rs.getBoolean("required"),rs.getInt("progress_pct"),rs.nullableInt("score"),rs.instant("starts_at"),rs.instant("due_at"),rs.instant("started_at"),
            rs.instant("completed_at"),rs.instant("cancelled_at"),rs.getInt("repeat_no"),rs.instant("created_at"),rs.instant("updated_at"),rs.getObject("event_id"),rs.getString("pharmacist_name"),
            (rs.getObject("reward_amount") as? Number)?.toLong(),rs.getString("reward_status"))}
        ensure(rows,27)
        val stages=jdbc.query("""SELECT a.id AS assignment_id,s.id AS assignment_stage_id,s.program_stage_id,t.stage_key,t.type,t.title,
            s.status,s.progress_pct,s.score,s.attempts_used,s.started_at,s.completed_at,s.updated_at $assignmentFrom
            JOIN training_assignment_stages s ON s.assignment_id=a.id JOIN training_program_stages t ON t.id=s.program_stage_id AND t.version_id=a.program_version_id
            WHERE true ${scoped.where} ORDER BY a.id,t.order_no,s.id LIMIT ${budget(13)+1}""",scoped.parameters) {rs,_->listOf<Any?>(
            rs.getObject("assignment_id"),rs.getObject("assignment_stage_id"),rs.getObject("program_stage_id"),rs.getString("stage_key"),rs.getString("type"),rs.getString("title"),
            rs.getString("status"),rs.getInt("progress_pct"),rs.nullableInt("score"),rs.getInt("attempts_used"),rs.instant("started_at"),rs.instant("completed_at"),rs.instant("updated_at"))}
        ensure(stages,13)
        val certificates=jdbc.query("""SELECT c.assignment_id,c.id,c.certificate_number,c.program_version_id,c.format,c.status,c.score,c.issued_at,c.expires_at
            $assignmentFrom JOIN training_certificates c ON c.assignment_id=a.id AND c.pharmacist_id=a.pharmacist_id AND c.program_version_id=a.program_version_id
            WHERE true ${scoped.where} ORDER BY a.id,c.id LIMIT ${budget(9)+1}""",scoped.parameters) {rs,_->listOf<Any?>(rs.getObject("assignment_id"),rs.getObject("id"),
            rs.getString("certificate_number"),rs.getObject("program_version_id"),rs.getString("format"),rs.getString("status"),rs.nullableInt("score"),rs.instant("issued_at"),rs.instant("expires_at"))}
        ensure(certificates,9)
        assertConsistentChildren(scoped)
        return report("assignments",filterMap(filter),scoped,rows.size,listOf(
            ReportSheet("Назначения",listOf("ID назначения","ID программы","ID версии программы","Номер версии","Программа","ID фармацевта",
                "Текущий ID аптеки профиля","Текущее название аптеки по ID","Текущий город профиля","Формат","Сохранённый статус","Приоритет","Обязательное",
                "Сохранённый прогресс, %","Сохранённый балл","Начало по плану","Срок","Начато","Завершено","Отменено","Повтор №","Создано","Обновлено","ID мероприятия","ФИО фармацевта (сохранено)",
                "Сохранённый бонус обучения, ₸ (отдельное финансовое право)","Сохранённый статус бонуса (отдельное финансовое право)"),rows),
            ReportSheet("Этапы назначений",listOf("ID назначения","ID этапа назначения","ID этапа программы","Ключ этапа (ID)","Тип","Название","Сохранённый статус",
                "Сохранённый прогресс, %","Сохранённый балл","Попыток использовано","Начат","Завершён","Обновлён"),stages),
            ReportSheet("Сертификаты",listOf("ID назначения","ID сертификата","Номер сертификата","ID версии программы","Формат","Сохранённый статус",
                "Сохранённый балл","Выдан","Истекает"),certificates)),rows.groupingBy {it[10] as String}.eachCount())
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun results(actor:AdminPrincipal,filter:TrainingLearningFilter,sourceType:String?,from:LocalDate?,to:LocalDate?):TrainingLearningExport {
        if(sourceType!=null && sourceType !in setOf("test","ai_exam","manual_review")) invalid("Неизвестный источник результата")
        if((from==null)!=(to==null)) invalid("Укажите обе границы периода")
        val period=from?.let {ReportPeriod(it,to!!)}
        val generated=Instant.now();val scoped=scope(actor,filter)
        val resultWhere=buildString {
            sourceType?.let {append(" AND r.source_type=:sourceType");scoped.parameters["sourceType"]=it}
            period?.let {append(" AND r.recorded_at>=:since AND r.recorded_at<:until");scoped.parameters["since"]=Timestamp.from(it.start());scoped.parameters["until"]=Timestamp.from(it.end(generated))}
        }
        val consistent="""EXISTS(SELECT 1 FROM training_assignment_stages own JOIN training_program_stages definition ON definition.id=own.program_stage_id
            WHERE own.id=r.assignment_stage_id AND own.assignment_id=a.id AND definition.version_id=a.program_version_id)"""
        val inconsistent=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 $assignmentFrom JOIN training_assessment_results r ON r.assignment_id=a.id
            WHERE true ${scoped.where} $resultWhere AND NOT ($consistent))""",scoped.parameters,Boolean::class.java)==true
        if(inconsistent) conflict()
        val rows=jdbc.query("""SELECT r.id,a.id AS assignment_id,r.assignment_stage_id,p.id AS program_id,a.program_version_id,p.name,v.version_no,
            a.pharmacist_id,ph.pharmacy_id,pharmacy.name AS pharmacy_name,ph.city,a.format,a.status,r.source_type,r.attempt_no,r.score,r.passed,r.recorded_at,
            t.id AS program_stage_id,t.stage_key,t.title,ph.name AS pharmacist_name $assignmentFrom JOIN training_assessment_results r ON r.assignment_id=a.id
            JOIN training_assignment_stages s ON s.id=r.assignment_stage_id AND s.assignment_id=a.id
            JOIN training_program_stages t ON t.id=s.program_stage_id AND t.version_id=a.program_version_id
            WHERE true ${scoped.where} $resultWhere ORDER BY r.recorded_at,r.id LIMIT ${budget(22)+1}""",scoped.parameters) {rs,_->listOf<Any?>(
            rs.getObject("id"),rs.getObject("assignment_id"),rs.getObject("assignment_stage_id"),rs.getObject("program_id"),rs.getObject("program_version_id"),rs.getString("name"),
            rs.getInt("version_no"),rs.getString("pharmacist_id"),rs.getString("pharmacy_id"),rs.getString("pharmacy_name"),rs.getString("city"),rs.getString("format"),
            rs.getString("status"),rs.getString("source_type"),rs.getInt("attempt_no"),rs.getInt("score"),rs.getBoolean("passed"),rs.instant("recorded_at"),
            rs.getObject("program_stage_id"),rs.getString("stage_key"),rs.getString("title"),rs.getString("pharmacist_name"))}
        ensure(rows,22)
        val filters=filterMap(filter)+mapOf("sourceType" to sourceType,"from" to from?.toString(),"to" to to?.toString())
        return report("results",filters,scoped,rows.size,listOf(ReportSheet("Результаты",listOf("ID результата","ID назначения","ID этапа назначения","ID программы",
            "ID версии программы","Программа","Номер версии","ID фармацевта","Текущий ID аптеки профиля","Текущее название аптеки по ID","Текущий город профиля","Формат",
            "Сохранённый статус назначения","Источник результата","Попытка №","Сохранённый балл","Сохранённый passed","Зафиксирован (UTC)","ID этапа программы","Ключ этапа (ID)","Этап","ФИО фармацевта (сохранено)"),rows)),
            rows.groupingBy {it[13] as String}.eachCount(),generated)
    }

    private fun assertConsistentChildren(scoped:ScopeQuery) {
        val invalid=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 $assignmentFrom WHERE true ${scoped.where} AND (
            EXISTS(SELECT 1 FROM training_assignment_stages s JOIN training_program_stages t ON t.id=s.program_stage_id WHERE s.assignment_id=a.id AND t.version_id<>a.program_version_id)
            OR EXISTS(SELECT 1 FROM training_certificates c WHERE c.assignment_id=a.id AND (c.pharmacist_id<>a.pharmacist_id OR c.program_version_id<>a.program_version_id))))""",scoped.parameters,Boolean::class.java)==true
        if(invalid) conflict()
    }
    private fun report(kind:String,filters:Map<String,String?>,scope:ScopeQuery,count:Int,data:List<ReportSheet>,totals:Map<String,Int>,generated:Instant=Instant.now()):TrainingLearningExport {
        val params=listOf(listOf("Отчёт",kind),listOf("Сформирован",generated),listOf("Изоляция БД","REPEATABLE READ"),listOf("Область",scope.description),
            listOf("История учебной видимости (D6)","По текущей аптеке/городу профиля; дополнительно регион менеджера или собственное мероприятие тренера. Историческая аптека денег из этого не выводится."),
            listOf("Методика","Баллы, passed, прогресс и статусы взяты из сохранённых полей источника; не пересчитаны, не выданы за исправление C1 и не заменены 100 при отсутствии результата."),
            listOf("Приватность","Сохранённое ФИО показывается только для строк разрешённой учебной области. ИИН, телефоны, логины/пароли, ответы, разбор/feedback, competency JSON, QR/URL/пути и причины денежных операций не выгружаются."),
            listOf("Деньги обучения","В назначениях сохранённая сумма и статус training_rewards доступны только при глобальном pharmacists.registry.financial_data и глобальной области отчёта. Без права ячейки пустые; нет бонуса — также пусто. Сумма не заменяется балансом, не пересчитывается и не означает банковскую выплату."),
            listOf("Сертификаты","Номер и сохранённый статус — исходные метаданные; приватный QR-токен, PDF-URL и имя подписанта исключены.")) + filters.map {listOf(it.key,it.value?:"Все в разрешённой области")}
        val sheets=listOf(ReportSheet("Параметры",listOf("Параметр","Значение"),params),ReportSheet("Итоги",listOf("Разрез","Количество записей"),
            listOf(listOf<Any?>("Всего",count))+totals.toSortedMap().map {(state,n)->listOf<Any?>(state,n)}))+data
        if(sheets.sumOf {(it.rows.size.toLong()+1)*it.columns.size}>ReportWorkbook.MAX_CELLS) tooLarge()
        return TrainingLearningExport(kind,filters,count,sheets)
    }
    private fun filterMap(filter:TrainingLearningFilter)=mapOf("format" to filter.format?.name,"status" to filter.status?.name,"programId" to filter.programId?.toString())
    private fun budget(columns:Int)=minOf(ReportWorkbook.MAX_ROWS,ReportWorkbook.MAX_CELLS/columns)
    private fun ensure(rows:List<*>,columns:Int) {if(rows.size>budget(columns)) tooLarge()}
    private fun ResultSet.instant(name:String)=getTimestamp(name)?.toInstant()
    private fun ResultSet.nullableInt(name:String)=getObject(name,Int::class.javaObjectType)
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Учебная область не определена",HttpStatus.FORBIDDEN)
    private fun conflict():Nothing=throw AppException(ErrorCode.CONFLICT,"Связь учебного результата, этапа или сертификата требует сверки; отчёт не усечён",HttpStatus.CONFLICT)
    private fun tooLarge():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Учебный XLSX превышает лимит: уточните фильтры; результат не усечён",HttpStatus.PAYLOAD_TOO_LARGE)
}
