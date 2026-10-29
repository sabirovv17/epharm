package kz.epharm.merch.commercial

import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.tasks.TaskEvidenceStorage
import kz.epharm.merch.tasks.TaskPhotoGate
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xddf.usermodel.chart.*
import org.apache.poi.ss.util.CellRangeAddress
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO

@Service
class CommercialMediaKit(private val commercial:CommercialService,private val jdbc:JdbcTemplate,
    private val named:NamedParameterJdbcTemplate,private val files:TaskEvidenceStorage) {
    private data class Place(val shelf:UUID,val branch:UUID,val branchName:String,val city:String,val address:String,
        val organization:String,val container:String?,val code:String,val category:String,val width:String)
    private data class Photo(val id:UUID,val branch:UUID,val shelf:UUID?,val backend:String,val key:String,val mime:String,val bytes:Long,val hash:String)
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun export(id:UUID,actor:PlacementActor,from:LocalDate?,to:LocalDate?):ByteArray {
        if(!slot.tryAcquire()) throw AppException(ErrorCode.CONFLICT,"Другой медиакит ещё формируется: повторите позже",HttpStatus.TOO_MANY_REQUESTS)
        try {
            val record=commercial.load(id)
            val branches=commercial.branches(record)
            for(action in listOf("view","export","financial_data")) commercial.requireRecord(record,actor,action)
            val view=commercial.view(id,actor)
            val start=from?:record.data.startMonth;val end=to?:record.data.endMonth
            if(start<record.data.startMonth || end>record.data.endMonth) CommercialValidation.invalid("Период выгрузки должен входить в период плана")
            val months=CommercialValidation.months(start,end)
            val rows=record.data.shelfSelection.orEmpty().filter {it.placementMonths.any {m->m in months}}
            val shelfIds=rows.map {it.shelfId}.toSet()
            val places=if(shelfIds.isEmpty()) emptyMap() else named.query("""SELECT s.id,b.id AS branch_id,b.name,b.city,b.address,o.name AS organization,
                c.code AS container,s.code,s.category,s.width_m::text AS width FROM merch_shelves s JOIN merch_branches b ON b.id=s.branch_id
                JOIN merch_organizations o ON o.id=b.organization_id LEFT JOIN merch_inventory_containers c ON c.id=s.container_id WHERE s.id IN (:ids)""",
                mapOf("ids" to shelfIds)) {rs,_->Place(rs.getObject("id",UUID::class.java),rs.getObject("branch_id",UUID::class.java),rs.getString("name"),rs.getString("city"),rs.getString("address"),rs.getString("organization"),rs.getString("container"),rs.getString("code"),rs.getString("category"),rs.getString("width"))}.associateBy {it.shelf}
            if(places.keys!=shelfIds) conflict("Места плана требуют проверки")
            val tariff=commercial.settings(record.producer).supplierCategory?.let(commercial::pricing)
            fun amount(value:BigInteger?):Any?=value?.let {if(it.bitLength()<50) it.toLong() else it.toString()}
            fun estimate(month:LocalDate):BigInteger? {
                if(record.data.items.isNotEmpty()) return record.data.items.filter {month in it.activeMonths}.fold(BigInteger.ZERO) {sum,a->sum+commercial.activityTotal(a.copy(activeMonths=listOf(month)))}
                val selected=rows.filter {month in it.placementMonths}
                if(selected.isNotEmpty() && (tariff==null || selected.any {it.price==null})) return null
                val placement=selected.fold(BigInteger.ZERO) {sum,row->sum+row.price!!.toBigInteger()+tariff!!.facingMonthPrice.toBigInteger()*row.products.filter {month in it.activeMonths}.sumOf {it.facingCount}.toBigInteger()}
                return placement+record.data.extraActivities.filter {month in it.activeMonths}.fold(BigInteger.ZERO) {sum,a->sum+commercial.activityTotal(a.copy(activeMonths=listOf(month)))}
            }
            val monthly=view.monthlyRequests.filter {it.placementMonth in months}
            val sourceRows=record.data.items.filter {it.activeMonths.any {m->m in months}}
            val sheets=listOf(
                ReportSheet("Обзор",listOf("Показатель","Значение"),listOf(
                    listOf("ID исходного/нативного плана",record.id),listOf("Название",record.data.title),listOf("ID производителя",record.producer),
                    listOf("Компания",jdbc.queryForObject("SELECT name FROM merch_producers WHERE id=?",String::class.java,record.producer)),
                    listOf("Период плана", "${record.data.startMonth} — ${record.data.endMonth}"),listOf("Период выгрузки","$start — $end"),
                    listOf("Тарифная категория",tariff?.label),listOf("Бюджет, ₸",record.data.budgetLimit),listOf("Предварительная оценка полного плана, ₸",amount(view.estimatedTotal)),
                    listOf("Города",places.values.map {it.city}.distinct().sorted().joinToString(", ")),listOf("Аптек в выгрузке",places.values.map {it.branch}.distinct().size),
                    listOf("Рекламных мест",rows.size),listOf("Целевая аудитория","Покупатели аптек"),listOf("Комментарий",record.data.notes),
                    listOf("Состояние",if(record.requests.isEmpty()) record.data.status else "submitted"),listOf("Ревизия",record.revision),listOf("Сформирован",Instant.now().toString()),
                    listOf("Исходных ссылок на заявки",view.requestIds.size),listOf("Подтверждённых связей заявок",view.monthlyRequests.size),listOf("Неразрешённых ссылок на заявки",view.unresolvedRequestIds.size),
                    listOf("Полнота фактических итогов",if(view.unresolvedRequestIds.isEmpty()) "Связи заявок подтверждены" else "Исторические заявки отсутствуют или их связь не подтверждена; фактические итоги неполны и оставлены пустыми. Исходные ID сохранены; заявки не воссозданы."),
                    listOf("Методика","Оценка не является бронью, начислением или оплатой. Сохранённые бюджетные строки уже включают дополнительные активности; они не суммируются повторно."),
                    listOf("Точность","Итоги рассчитаны сервером целыми числами. Суммы длиннее 15 цифр сохранены текстом без округления. Пустая сумма означает неизвестный тариф/цену, а не ноль."),
                    listOf("Фото","Только актуальные подтверждённые фото выбранных мест/аптек. Приватные пути, учётные данные и контакты команды не выгружаются."),
                    listOf("Оплата","not_connected"))),
                ReportSheet("Места",listOf("№","Город","Аптека","Адрес","Организация","Шкаф","Полка","Категория эффективности","Ширина, м","Товары","Категории товаров","Подкатегории товаров","SKU","Фейсинги товаров","Оценка места / месяц, ₸","Месяцы","Формат размещения","Фото","ID аптеки мерча","ID полки","ID выбора"),
                    rows.mapIndexed {index,row->val p=places.getValue(row.shelfId);listOf(index+1,p.city,p.branchName,p.address,p.organization,p.container,p.code,p.category,p.width,
                        row.products.joinToString("; ") {it.productName},row.products.joinToString("; ") {it.productCategory},row.products.joinToString("; ") {it.productSubcategory},
                        row.products.joinToString("; ") {it.sku.orEmpty()},row.products.joinToString("; ") {"${it.productName}: ${it.facingCount}"},row.price,
                        row.placementMonths.filter {it in months}.joinToString(", "),row.portion.name,"Фото не добавлено",p.branch,p.shelf,row.id)}),
                ReportSheet("Бюджет по месяцам",listOf("Месяц","Предварительная оценка, ₸","Подтверждённые позиции, ₸","Ожидают решения по статусу, ₸","Отклонено/отменено, ₸","Заявок"),
                    months.map {month->val requests=monthly.filter {it.placementMonth==month};val items=requests.flatMap {it.items}
                        listOf(month.toString(),amount(estimate(month)),if(view.unresolvedRequestIds.isNotEmpty()) null else items.filter {it.status.name=="approved"}.sumOf {it.monthlyPrice?:conflict("Финансовые поля не разрешены")},
                            if(view.unresolvedRequestIds.isNotEmpty()) null else items.filter {it.status.name=="pending"}.sumOf {it.monthlyPrice?:conflict("Финансовые поля не разрешены")},
                            if(view.unresolvedRequestIds.isNotEmpty()) null else items.filter {it.status.name in setOf("rejected","cancelled")}.sumOf {it.monthlyPrice?:conflict("Финансовые поля не разрешены")},requests.size)}),
                ReportSheet("Товары по месяцам",listOf("ID полки","Часть","ID товара в плане","Товар","SKU","Категория товара","Подкатегория товара","Фейсинги","Месяцы товара","Месяцы места","Категории эффективности"),
                    rows.flatMap {row->row.products.filter {p->p.activeMonths.any {it in months && it in row.placementMonths}}.map {p->listOf(row.shelfId,row.portion.name,p.id,p.productName,p.sku,p.productCategory,p.productSubcategory,p.facingCount,p.activeMonths.filter {it in months}.joinToString(", "),row.placementMonths.filter {it in months}.joinToString(", "),p.shelfCategories.joinToString(", "))}}),
                ReportSheet("Бюджетные строки",listOf("ID строки","Товар/активность","SKU","Тип","Месяцы","Аптек/событий","Фейсинги","Участники","Цена за единицу, ₸","Категория-снимок","Оценка периода, ₸","Комментарий"),
                    sourceRows.map {a->val clipped=a.copy(activeMonths=a.activeMonths.filter {it in months});listOf(a.id,a.productName,a.sku,a.activityType,clipped.activeMonths.joinToString(", "),a.pharmacyCount,a.facingCount,a.peopleCount,a.unitPrice,a.supplierCategory,amount(commercial.activityTotal(clipped)),a.note)}),
                ReportSheet("Дополнительные активности",listOf("Название","Тип","Месяцы","Аптек/событий","Участники","Цена за единицу, ₸","Комментарий","Учёт"),
                    record.data.extraActivities.filter {it.activeMonths.any {m->m in months}}.map {a->listOf(a.productName,a.activityType,a.activeMonths.filter {it in months}.joinToString(", "),a.pharmacyCount,a.peopleCount,a.unitPrice,a.note,if(sourceRows.isEmpty()) "Учтено в оценке" else "Снимок; учтено в бюджетных строках, повторно не суммировать")}),
                ReportSheet("Заявки и решения",listOf("ID заявки","ID позиции","Месяц","ID аптеки мерча","ID полки","Полка","Часть","Статус заявки","Статус позиции","Сумма позиции, ₸","Исходная сумма заявки, ₸","Резерв до","Решение/причина"),
                    monthly.flatMap {p->p.items.map {i->listOf(p.id,i.id,p.placementMonth.toString(),i.branchId,i.shelfId,i.shelfCode,i.portion.name,p.status.name,i.status.name,i.monthlyPrice,p.totalAmount,p.heldUntil?.toString(),p.reviewNote)}}),
                ReportSheet("Неразрешенные заявки",listOf("Исходный ID заявки","Состояние связи"),view.unresolvedRequestIds.map {listOf(it,"Заявка отсутствует или связь с этим бюджетом/производителем/кампанией не подтверждена; исходная ссылка сохранена")}),
                ReportSheet("Расчет",listOf("Месяц","Метод","Оценка, ₸"),months.map {month->listOf(month.toString(),if(record.data.items.isNotEmpty()) "Сумма исходных бюджетных строк за месяц" else "База места + тариф фейсинга × активные фейсинги; дополнительные активности один раз",amount(estimate(month)))})
            )
            if(sheets.any {it.rows.size>ReportWorkbook.MAX_ROWS} || sheets.sumOf {(it.rows.size.toLong()+1)*it.columns.size}>ReportWorkbook.MAX_CELLS) tooLarge()
            val photos=if(branches.isEmpty() || rows.isEmpty()) emptyList() else named.query("""SELECT id,branch_id,shelf_id,storage_backend,object_key,mime_type,byte_size,sha256
                FROM merch_inventory_media WHERE branch_id IN (:branches) AND archived_at IS NULL AND (shelf_id IS NULL OR shelf_id IN (:shelves))
                ORDER BY CASE WHEN shelf_id IS NOT NULL THEN 0 WHEN angle='facade' THEN 1 ELSE 2 END,created_at,id""",mapOf("branches" to branches,"shelves" to shelfIds)) {rs,_->
                Photo(rs.getObject("id",UUID::class.java),rs.getObject("branch_id",UUID::class.java),rs.getObject("shelf_id",UUID::class.java),rs.getString("storage_backend"),rs.getString("object_key"),rs.getString("mime_type"),rs.getLong("byte_size"),rs.getString("sha256"))}
            val selected=rows.map {row->photos.firstOrNull {it.shelf==row.shelfId}?:photos.firstOrNull {it.branch==places.getValue(row.shelfId).branch && it.shelf==null}}
            val unique=selected.filterNotNull().distinctBy {it.id}
            if(unique.any {it.bytes !in 1..8L*1024*1024} || unique.sumOf {it.bytes}>48L*1024*1024) tooLarge()
            val thumbnails=unique.associate {photo->
                val bytes=files.read(photo.backend,photo.key,photo.hash)
                if(bytes.size.toLong()!=photo.bytes) conflict("Размер подтверждённого фото изменился")
                photo.id to thumbnail(bytes,photo.mime)
            }
            return ReportWorkbook.render(sheets) {book->
                val placeSheet=book.getSheet("Места");val drawing=placeSheet.createDrawingPatriarch()
                val pictures=thumbnails.mapValues {(_,bytes)->book.addPicture(bytes,Workbook.PICTURE_TYPE_JPEG)}
                selected.forEachIndexed {index,photo->if(photo!=null) {
                    placeSheet.getRow(index+1).getCell(17).setCellValue("Фото ${photo.id}")
                    placeSheet.getRow(index+1).heightInPoints=80f
                    val anchor=drawing.createAnchor(0,0,0,0,17,index+1,18,index+2)
                    drawing.createPicture(anchor,pictures.getValue(photo.id))
                }}
                val budget=book.getSheet("Бюджет по месяцам")
                if(months.isNotEmpty() && months.all {estimate(it)?.bitLength()?.let {bits->bits<50}==true}) {
                    val chart=budget.createDrawingPatriarch().createChart(budget.createDrawingPatriarch().createAnchor(0,0,0,0,7,1,16,15))
                    chart.setTitleText("Предварительный бюджет по месяцам")
                    val x=chart.createCategoryAxis(AxisPosition.BOTTOM);val y=chart.createValueAxis(AxisPosition.LEFT)
                    val data=chart.createData(ChartTypes.BAR,x,y) as XDDFBarChartData
                    data.barDirection=BarDirection.COL
                    val series=data.addSeries(XDDFDataSourcesFactory.fromStringCellRange(budget,CellRangeAddress(1,months.size,0,0)),XDDFDataSourcesFactory.fromNumericCellRange(budget,CellRangeAddress(1,months.size,1,1)))
                    series.setTitle("Оценка, ₸",null);chart.plot(data)
                }
                book.setSheetHidden(book.getSheetIndex("Расчет"),true)
            }
        } finally {slot.release()}
    }
    private fun thumbnail(bytes:ByteArray,mime:String):ByteArray=TaskPhotoGate.run {
        if(mime !in setOf("image/jpeg","image/png")) conflict("Формат фото требует проверки")
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {stream->
            val readers=ImageIO.getImageReaders(stream)
            if(!readers.hasNext()) conflict("Подтверждённое фото повреждено")
            val reader=readers.next()
            try {
                reader.input=stream
                val width=reader.getWidth(0);val height=reader.getHeight(0)
                if(width<=0 || height<=0 || width.toLong()*height.toLong()>12_000_000L) tooLarge()
                val source=reader.read(0)?:conflict("Подтверждённое фото повреждено")
                try {
                    val scale=minOf(180.0/width,100.0/height,1.0)
                    val target=BufferedImage((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),BufferedImage.TYPE_INT_RGB)
                    try {
                        val graphics=target.createGraphics()
                        try {graphics.color=Color.WHITE;graphics.fillRect(0,0,target.width,target.height);graphics.drawImage(source,0,0,target.width,target.height,null)} finally {graphics.dispose()}
                        ByteArrayOutputStream().use {output->if(!ImageIO.write(target,"jpeg",output)) conflict("Не удалось сформировать фото медиакита");output.toByteArray()}
                    } finally {target.flush()}
                } finally {source.flush()}
            } finally {reader.dispose()}
        }
    }
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun tooLarge():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Медиакит превышает лимит объёма/фото: сузьте период или уменьшите фотографии; данные не усечены",HttpStatus.PAYLOAD_TOO_LARGE)
    companion object {private val slot=Semaphore(1)}
}
