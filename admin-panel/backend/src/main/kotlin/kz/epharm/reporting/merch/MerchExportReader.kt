package kz.epharm.reporting.merch

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.merch.inventory.service.MerchInventoryService
import kz.epharm.merch.placements.dto.PlacementStatus
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class MerchExport(val report:String,val sheets:List<ReportSheet>,val dataRows:Int,val filters:Map<String,String?>)

/** Whitelisted operational facts only. A preserved source JSON, capability or private file location never enters a workbook. */
@Service
class MerchExportReader(private val jdbc:JdbcTemplate,private val named:NamedParameterJdbcTemplate,
    private val access:AdministrativeScope,private val inventory:MerchInventoryService) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun inventory(branchId:UUID,startMonth:LocalDate,monthCount:Int):MerchExport {
        requireExport("inventory")
        access.requireMerchBranchPermission(key("inventory","export"),branchId)
        access.requireMerchBranchPermission(key("inventory","view"),branchId)
        if(monthCount !in 1..12 || startMonth.dayOfMonth!=1 || startMonth.year !in 1..9999 || startMonth.plusMonths(monthCount-1L).year>9999)
            invalid("Укажите первый день месяца и от 1 до 12 месяцев")
        val columns=listOf("ID аптеки мерча","Аптека","Город","Адрес","ID полки","Код полки","Категория","Месяц","Статус календаря",
            "Активная полка","Часть размещения","Цена текущего календаря, ₸","Сохранённая цена ячейки, ₸","ID ячейки",
            "Источник полки","Исходный ID полки","Источник ячейки","Исходный ID ячейки")
        val shelves=jdbc.queryForObject("SELECT count(*) FROM merch_shelves WHERE branch_id=?",Long::class.java,branchId)!!
        ensure(shelves,columns.size*monthCount)
        val calendar=inventory.calendar(branchId,startMonth,monthCount)
        val shelfSources=jdbc.query("SELECT id,source_system,source_id FROM merch_shelves WHERE branch_id=?",{rs,_->
            rs.getObject("id",UUID::class.java) to (rs.getString("source_system") to rs.getObject("source_id",UUID::class.java))},branchId).toMap()
        val cells=jdbc.query("""SELECT i.id,i.shelf_id,i.placement_month,i.monthly_price,i.source_system,i.source_id FROM merch_monthly_inventory i
            JOIN merch_shelves s ON s.id=i.shelf_id WHERE s.branch_id=? AND i.placement_month BETWEEN ? AND ?""",{rs,_->
            (rs.getObject("shelf_id",UUID::class.java) to rs.getObject("placement_month",LocalDate::class.java)) to
                CalendarSource(rs.getObject("id",UUID::class.java),rs.getLong("monthly_price"),rs.getString("source_system"),rs.getObject("source_id",UUID::class.java))
        },branchId,startMonth,startMonth.plusMonths(monthCount-1L)).toMap()
        val money=access.canMerchBranchPermission(key("inventory","financial_data"),branchId)
        val rows=calendar.shelves.flatMap {shelf->shelf.months.map {cell->
            val original=cells[shelf.id to cell.month];val source=shelfSources.getValue(shelf.id)
            listOf(branchId,calendar.branch.name,calendar.branch.city,calendar.branch.address,shelf.id,shelf.code,shelf.category,cell.month,
                cell.status.name,shelf.active,cell.placement?.portion,shelf.price,if(money) original?.price else null,original?.id,
                source.first,source.second,original?.system,original?.sourceId)
        }}
        val summary=calendar.months.map {listOf(it.month,it.availableCount,it.blockedCount,it.heldCount,it.bookedCount,
            it.availableCount+it.blockedCount+it.heldCount+it.bookedCount)}
        val filters=mapOf("branchId" to branchId.toString(),"startMonth" to startMonth.toString(),"monthCount" to monthCount.toString())
        return export("inventory",filters,listOf("Месяц","Свободно","Заблокировано","Резерв","Занято","Ячеек"),summary,columns,rows,
            "Цена текущего календаря совпадает с экраном; цена сохранённой ячейки пустая, если записи нет. Приватные заметки не выгружаются.")
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun placements(producerId:UUID?,branchId:UUID?,status:PlacementStatus?,from:LocalDate?,to:LocalDate?):MerchExport {
        requireExport("placements");period(from,to);requestedScope(branchId,producerId)
        val branches=access.currentMerchBranchIds();val producers=access.currentProducerIds()
        val columns=listOf("ID медиаплана","ID производителя","Производитель","Исходное название компании","Месяц размещения","Статус медиаплана",
            "ID позиции","ID аптеки мерча","Аптека","ID полки","Код полки","Часть","Статус позиции","Стоимость позиции, ₸",
            "Сумма медиаплана (один раз), ₸","Создано (UTC)","Обновлено (UTC)","Источник медиаплана","Исходный ID медиаплана","Исходный ID позиции")
        val params=mutableMapOf<String,Any?>("branches" to branches,"producers" to producers,"producer" to producerId,"branch" to branchId,
            "status" to status?.name,"from" to from,"to" to to)
        val where=buildString {
            if(branches?.isEmpty()==true || producers?.isEmpty()==true) append(" AND false")
            if(producers!=null && producers.isNotEmpty()) append(" AND p.producer_id IN (:producers)")
            if(branches!=null && branches.isNotEmpty()) append(""" AND p.producer_id IS NOT NULL AND EXISTS(SELECT 1 FROM merch_media_plan_items own WHERE own.media_plan_id=p.id)
                AND NOT EXISTS(SELECT 1 FROM merch_media_plan_items outside JOIN merch_shelves os ON os.id=outside.shelf_id
                    WHERE outside.media_plan_id=p.id AND os.branch_id NOT IN (:branches))""")
            if(branches!=null || producers!=null) append(" AND p.producer_id IS NOT NULL")
            if(producerId!=null) append(" AND p.producer_id=:producer")
            if(branchId!=null) append(" AND EXISTS(SELECT 1 FROM merch_media_plan_items chosen JOIN merch_shelves cs ON cs.id=chosen.shelf_id WHERE chosen.media_plan_id=p.id AND cs.branch_id=:branch)")
            if(status!=null) append(" AND p.status=:status")
            if(from!=null) append(" AND (p.created_at AT TIME ZONE 'Asia/Almaty')::date BETWEEN :from AND :to")
        }
        val records=named.query("""SELECT p.id,p.producer_id,c.name,p.advertiser_name,p.placement_month,p.status,p.total_amount,p.created_at,p.updated_at,
            p.source_system,p.source_id,i.id AS item_id,i.source_id AS item_source_id,i.monthly_price,i.sale_portion,i.status AS item_status,
            s.id AS shelf_id,s.code,b.id AS branch_id,b.name AS branch_name
            FROM merch_media_plans p LEFT JOIN merch_producers c ON c.id=p.producer_id
            LEFT JOIN merch_media_plan_items i ON i.media_plan_id=p.id LEFT JOIN merch_shelves s ON s.id=i.shelf_id
            LEFT JOIN merch_branches b ON b.id=s.branch_id WHERE true $where
            ORDER BY p.created_at,p.id,i.id LIMIT ${budget(columns.size)+1}""",params) {rs,_->
            PlacementExportRow(rs.getObject("id",UUID::class.java),rs.getObject("producer_id",UUID::class.java),rs.getString("name"),rs.getString("advertiser_name"),
                rs.getObject("placement_month",LocalDate::class.java),rs.getString("status"),rs.getLong("total_amount"),rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),rs.getString("source_system"),rs.getObject("source_id",UUID::class.java),rs.getObject("item_id",UUID::class.java),
                rs.getObject("item_source_id",UUID::class.java),rs.getObject("monthly_price",Int::class.javaObjectType)?.toLong(),rs.getString("sale_portion"),rs.getString("item_status"),
                rs.getObject("shelf_id",UUID::class.java),rs.getString("code"),rs.getObject("branch_id",UUID::class.java),rs.getString("branch_name"))
        }
        ensure(records.size.toLong(),columns.size)
        val groups=records.groupBy {it.id}
        // The complete plan scope is checked before it contributes rows or a daily total.
        val allowed=groups.filter {(_,items)-> val first=items.first();val ids=items.mapNotNull {it.branchId}.toSet()
            placementPermission("view",ids,first.producer) && placementPermission("export",ids,first.producer)}
        val planMoney=allowed.mapValues {(_,items)->placementPermission("financial_data",items.mapNotNull {it.branchId}.toSet(),items.first().producer)}
        val rows=allowed.values.flatMap {items->items.mapIndexed {index,row->
            val money=placementPermission("financial_data",row.branchId?.let(::setOf).orEmpty(),row.producer)
            listOf(row.id,row.producer,row.producerName,row.advertiser,row.month,row.status,row.item,row.branchId,row.branchName,row.shelf,row.code,
                row.portion,row.itemStatus,if(money) row.price else null,if(index==0 && planMoney.getValue(row.id)) row.total else null,row.created,row.updated,
                row.source,row.sourceId,row.itemSource)
        }}
        val summary=allowed.values.map {it.first()}.groupBy {it.created.atZone(KZ).toLocalDate()}.toSortedMap().map { (day,plans)->
            listOf(day,plans.size,plans.sumOf {allowed.getValue(it.id).count {row->row.item!=null}},
                plans.count {it.status=="draft"},plans.count {it.status=="pending"},plans.count {it.status=="approved"},plans.count {it.status=="rejected"},plans.count {it.status=="cancelled"},
                if(plans.all {planMoney.getValue(it.id)}) plans.sumOf {it.total} else null)
        }
        return export("placements",mapOf("producerId" to producerId?.toString(),"branchId" to branchId?.toString(),"status" to status?.name,"from" to from?.toString(),"to" to to?.toString()),
            listOf("Дата создания (KZ)","Медиапланов","Позиций","Черновики","Ожидают решения","Подтверждены","Отклонены","Отменены","Сумма медиапланов, ₸"),summary,columns,rows,
            "Отбор и дневная сводка по дате создания в Asia/Almaty; статусы текущие. Сумма медиаплана указана один раз и не является платежом. Пустые деньги означают отсутствие финансового доступа.")
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun tasks(branchId:UUID?,status:String?,from:LocalDate?,to:LocalDate?):MerchExport {
        requireExport("tasks");period(from,to);requestedScope(branchId,null)
        if(status!=null && status !in TASK_STATUSES) invalid("Неизвестный статус задания")
        val branches=access.currentMerchBranchIds()
        val columns=listOf("ID пакета заданий","ID аптеки мерча","Аптека","Статус","Приоритет","Тип","Пунктов","Срок (UTC)","Создано (UTC)","Обновлено (UTC)",
            "Вознаграждение, ₸","Состояние вознаграждения","Источник","Исходный ID")
        val where=buildString {
            if(branches?.isEmpty()==true) append(" AND false") else if(branches!=null) append(" AND d.branch_id IN (:branches)")
            if(branchId!=null) append(" AND d.branch_id=:branch")
            if(status!=null) append(" AND d.status=:status")
            if(from!=null) append(" AND (d.created_at AT TIME ZONE 'Asia/Almaty')::date BETWEEN :from AND :to")
        }
        val records=named.query("""SELECT d.id,d.branch_id,b.name,d.status,d.priority,d.task_type,d.due_at,d.created_at,d.updated_at,d.reward_amount_kzt,
            r.state,d.source_system,d.source_id,(SELECT count(*) FROM merch_task_items i WHERE i.dispatch_id=d.id) AS items
            FROM merch_task_dispatches d JOIN merch_branches b ON b.id=d.branch_id LEFT JOIN merch_task_rewards r ON r.dispatch_id=d.id
            WHERE true $where ORDER BY d.created_at,d.id LIMIT ${budget(columns.size)+1}""",
            mapOf("branches" to branches,"branch" to branchId,"status" to status,"from" to from,"to" to to)) {rs,_->
            TaskExportRow(rs.getObject("id",UUID::class.java),rs.getObject("branch_id",UUID::class.java),rs.getString("name"),rs.getString("status"),rs.getString("priority"),
                rs.getString("task_type"),rs.getLong("items"),rs.getTimestamp("due_at")?.toInstant(),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("reward_amount_kzt"),rs.getString("state"),rs.getString("source_system"),rs.getObject("source_id",UUID::class.java))
        }
        ensure(records.size.toLong(),columns.size)
        val allowed=records.filter {access.canMerchBranchPermission(key("tasks","view"),it.branch) && access.canMerchBranchPermission(key("tasks","export"),it.branch)}
        val money=allowed.associate {it.id to access.canMerchBranchPermission(key("tasks","financial_data"),it.branch)}
        val rows=allowed.map {listOf(it.id,it.branch,it.branchName,it.status,it.priority,it.type,it.items,it.due,it.created,it.updated,
            if(money.getValue(it.id)) it.reward else null,if(money.getValue(it.id)) it.rewardState else null,it.source,it.sourceId)}
        val summary=allowed.groupBy {it.created.atZone(KZ).toLocalDate()}.toSortedMap().map { (day,tasks)->
            listOf(day,tasks.size,tasks.sumOf {it.items})+TASK_STATUSES.map {s->tasks.count {it.status==s}}+
                listOf(if(tasks.all {money.getValue(it.id)}) tasks.sumOf {it.reward} else null)
        }
        return export("tasks",mapOf("branchId" to branchId?.toString(),"status" to status,"from" to from?.toString(),"to" to to?.toString()),
            listOf("Дата создания (KZ)","Пакетов","Пунктов")+TASK_STATUSES+"Вознаграждения, ₸",summary,columns,rows,
            "Отбор и дневная сводка по дате создания в Asia/Almaty; статусы текущие. Вознаграждение — назначенная сумма задания, не выплата. Исполнители, телефоны, ссылки, доказательства и приватные причины не выгружаются.")
    }

    private fun placementPermission(action:String,branches:Set<UUID>,producer:UUID?):Boolean = if(producer==null || branches.isEmpty())
        access.canAllMerchPlacementPermission(key("placements",action))
        else access.canMerchPlacementPermission(key("placements",action),branches,producer)
    private fun requestedScope(branch:UUID?,producer:UUID?) {
        if(branch!=null && access.currentMerchBranchIds()?.let {branch !in it}==true) forbidden()
        if(producer!=null && access.currentProducerIds()?.let {producer !in it}==true) forbidden()
    }
    private fun requireExport(section:String) {
        access.requireExplicitPermission(key(section,"view"));access.requireExplicitPermission(key(section,"export"))
    }
    private fun export(name:String,filters:Map<String,String?>,summaryColumns:List<String>,summary:List<List<Any?>>,columns:List<String>,rows:List<List<Any?>>,note:String):MerchExport {
        val at=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp::class.java)!!.toInstant()
        val params=listOf(listOf("Отчёт",name),listOf("Снимок (UTC)",at),listOf("Изоляция","REPEATABLE READ"),listOf("Пояснение",note),
            listOf("Строк данных",rows.size))+filters.map {listOf(it.key,it.value?:"Все в разрешённой области")}
        return MerchExport(name,listOf(ReportSheet("Параметры",listOf("Параметр","Значение"),params),ReportSheet("Сводка",summaryColumns,summary),ReportSheet("Данные",columns,rows)),rows.size,filters)
    }
    private fun period(from:LocalDate?,to:LocalDate?) {
        if((from==null)!=(to==null) || from!=null && (from.year !in 1..9999 || to!!.year !in 1..9999 || from>to || ChronoUnit.DAYS.between(from,to)>365))
            invalid("Укажите обе границы периода от 1 до 366 дней")
    }
    private fun budget(columns:Int)=minOf(ReportWorkbook.MAX_ROWS,ReportWorkbook.MAX_CELLS/columns)
    private fun ensure(rows:Long,columns:Int) {if(rows>budget(columns)) throw AppException(ErrorCode.VALIDATION_FAILED,"Отчёт превышает лимит XLSX: сузьте фильтры; строки не усечены",HttpStatus.PAYLOAD_TOO_LARGE)}
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message,HttpStatus.BAD_REQUEST)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Фильтр вне разрешённой области",HttpStatus.FORBIDDEN)
    companion object {
        val KZ:ZoneId=ZoneId.of("Asia/Almaty")
        val TASK_STATUSES=listOf("ready","sent","in_progress","submitted","revision_required","completed","cancelled")
        fun key(section:String,action:String)=requireNotNull(PermissionKey.parse("merchandising",section,action))
    }
    private data class CalendarSource(val id:UUID,val price:Long,val system:String,val sourceId:UUID)
    private data class PlacementExportRow(val id:UUID,val producer:UUID?,val producerName:String?,val advertiser:String?,val month:LocalDate,val status:String,val total:Long,
        val created:Instant,val updated:Instant,val source:String,val sourceId:UUID,val item:UUID?,val itemSource:UUID?,val price:Long?,val portion:String?,val itemStatus:String?,
        val shelf:UUID?,val code:String?,val branchId:UUID?,val branchName:String?)
    private data class TaskExportRow(val id:UUID,val branch:UUID,val branchName:String,val status:String,val priority:String,val type:String,val items:Long,val due:Instant?,
        val created:Instant,val updated:Instant,val reward:Long,val rewardState:String?,val source:String,val sourceId:UUID)
}
