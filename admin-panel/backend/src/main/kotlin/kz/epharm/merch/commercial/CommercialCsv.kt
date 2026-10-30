package kz.epharm.merch.commercial

import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.merch.placements.service.KazakhstanReservationCalendar
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

object CommercialCsvParser {
    val aliases=mapOf("branch" to setOf("branch_code","pharmacy_code","код аптеки","код филиала","филиал"),
        "shelf" to setOf("shelf_code","placement_code","код полки","код места","место"),
        "month" to setOf("month","placement_month","месяц","месяц размещения"))
    fun parse(text:String):List<List<String>> {
        if(text.toByteArray(Charsets.UTF_8).size>500_000 || text.any {it=='\u0000'}) CommercialValidation.invalid("CSV превышает лимит или содержит недопустимые символы")
        val source=text.removePrefix("\uFEFF")
        val header=source.lineSequence().firstOrNull().orEmpty()
        val delimiter=if(header.count {it==';'}>=header.count {it==','}) ';' else ','
        val rows=mutableListOf<List<String>>();var cells=mutableListOf<String>();val value=StringBuilder()
        var quoted=false;var closed=false;var index=0
        fun cell() {cells+=value.toString().trim();value.setLength(0);closed=false}
        fun row() {cell();if(cells.any {it.isNotEmpty()}) rows+=cells;cells=mutableListOf();if(rows.size>6001) CommercialValidation.invalid("CSV содержит более 6000 позиций; строки не усечены")}
        while(index<source.length) {
            val c=source[index]
            if(quoted) {
                if(c=='"' && index+1<source.length && source[index+1]=='"') {value.append('"');index++}
                else if(c=='"') {quoted=false;closed=true} else value.append(c)
            } else when {
                c=='"' && value.isEmpty() && !closed -> quoted=true
                c==delimiter -> cell()
                c=='\r' || c=='\n' -> {if(c=='\r' && index+1<source.length && source[index+1]=='\n') index++;row()}
                c=='"' || closed && !c.isWhitespace() -> CommercialValidation.invalid("Некорректные кавычки CSV")
                !closed -> value.append(c)
            }
            index++
        }
        if(quoted) CommercialValidation.invalid("Незакрытая кавычка CSV")
        if(value.isNotEmpty() || cells.isNotEmpty() || closed) row()
        if(rows.size<2) CommercialValidation.invalid("CSV должен содержать заголовок и хотя бы одну позицию")
        if(rows.any {it.any {cell->cell.length>255 || cell.any {c->c.code<32 || c.code in 127..159}}}) CommercialValidation.invalid("Некорректные или слишком длинные ячейки CSV")
        return rows
    }
    fun normalize(value:String)=value.trim().lowercase(Locale.ROOT)
    fun month(value:String):LocalDate? {
        val match=Regex("^(\\d{4})[-./](\\d{1,2})(?:[-./]0?1)?$").matchEntire(value.trim())?:return null
        return runCatching {LocalDate.of(match.groupValues[1].toInt(),match.groupValues[2].toInt(),1)}.getOrNull()
    }
}

@Service
class CommercialCsv(private val jdbc:JdbcTemplate,private val commercial:CommercialService,private val placements:PlacementService,
    private val repository:PlacementRepository) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun preview(csv:String,producer:UUID,actor:PlacementActor):CommercialCsvPreview {
        if(repository.producer(producer)?.active!=true) throw AppException(ErrorCode.FORBIDDEN,"Производитель недоступен",HttpStatus.FORBIDDEN)
        if(actor is PlacementActor.Producer && actor.customerId!=producer) throw AppException(ErrorCode.FORBIDDEN,"Нет доступа",HttpStatus.FORBIDDEN)
        val parsed=CommercialCsvParser.parse(csv)
        val header=parsed.first().map(CommercialCsvParser::normalize)
        val columns=CommercialCsvParser.aliases.mapValues {(_,aliases)->
            val indices=header.indices.filter {header[it] in aliases}
            if(indices.size!=1) CommercialValidation.invalid("Укажите по одной колонке: код филиала, код места, месяц")
            indices.single()
        }
        data class Reference(val branch:UUID,val branchSource:UUID,val branchCode:String,val shelf:UUID,val code:String)
        val refs=jdbc.query("""SELECT b.id,b.source_id,b.code,s.id AS shelf_id,s.code AS shelf_code FROM merch_branches b
            JOIN merch_organizations o ON o.id=b.organization_id JOIN merch_shelves s ON s.branch_id=b.id WHERE b.active AND o.active AND s.active""",
            {rs,_->Reference(rs.getObject("id",UUID::class.java),rs.getObject("source_id",UUID::class.java),rs.getString("code"),rs.getObject("shelf_id",UUID::class.java),rs.getString("shelf_code"))})
        val allowedBranches=refs.map {it.branch}.distinct().filter {commercial.can(actor,"view",setOf(it),producer) && commercial.can(actor,"edit",setOf(it),producer)}.toSet()
        val refIndex=refs.filter {it.branch in allowedBranches}.flatMap {ref->
            setOf(ref.branch.toString(),ref.branchSource.toString(),CommercialCsvParser.normalize(ref.branchCode)).map {alias->
                (alias to CommercialCsvParser.normalize(ref.code)) to ref}
        }.groupBy({it.first},{it.second})
        val seen=mutableSetOf<Pair<UUID,LocalDate>>()
        val now=LocalDate.now(KazakhstanReservationCalendar.timezone).withDayOfMonth(1)
        val rows=parsed.drop(1).mapIndexed {index,cells->
            val branch=cells.getOrNull(columns.getValue("branch")).orEmpty();val shelf=cells.getOrNull(columns.getValue("shelf")).orEmpty()
            val month=CommercialCsvParser.month(cells.getOrNull(columns.getValue("month")).orEmpty())
            val matches=refIndex[CommercialCsvParser.normalize(branch) to CommercialCsvParser.normalize(shelf)].orEmpty()
            val ref=matches.singleOrNull()
            val error=when {ref==null->"Аптека или место не найдены, неоднозначны либо недоступны"
                month==null || month<now->"Укажите текущий или будущий месяц в формате ГГГГ-ММ"
                !seen.add(ref.shelf to month)->"Эта позиция уже есть в файле"
                else->null}
            CommercialCsvRow(index+2,branch,shelf,month,if(error==null) ref?.shelf else null,if(error==null) ref?.branch else null,error)
        }
        if(rows.any {it.error!=null}) return CommercialCsvPreview(rows,false,emptyList())
        val grouped=rows.groupBy {it.shelfId!!}
        if(grouped.size>500) CommercialValidation.invalid("В плане более 500 мест; позиции не усечены")
        val prices=grouped.entries.groupBy {entry->entry.value.minOf {it.month!!}}.flatMap {(month,entries)->
            placements.commercialBaseQuotes(actor,producer,month,entries.map {PlacementSelection(it.key)}).map {(selection,price)->(selection.shelfId to month) to price}
        }.toMap()
        val selections=grouped.map {(shelf,entries)->
            val months=entries.map {it.month!!}.distinct().sorted()
            val price=prices.getValue(shelf to months.first())
            CommercialShelf(shelf.toString(),shelf,entries.first().branchId,placementMonths=months,price=price,code=entries.first().shelfCode)
        }
        return CommercialCsvPreview(rows,true,selections)
    }
}
