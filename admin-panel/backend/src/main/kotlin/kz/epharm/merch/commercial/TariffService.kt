package kz.epharm.merch.commercial

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.merch.placements.service.PlacementPermissions
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Service
class TariffService(private val jdbc:JdbcTemplate,private val access:AdministrativeScope,private val json:ObjectMapper) {
    @Transactional(readOnly=true)
    fun list(actor:UUID):TariffIndex {
        authorize(actor,false)
        val editable=access.canAllMerchPlacementPermission(PlacementPermissions.key("edit"))
        return TariffIndex(jdbc.query("SELECT * FROM merch_supplier_pricing_tiers ORDER BY category",{rs,_->row(rs,editable)}),editable)
    }

    @Transactional
    fun save(category:String,body:SaveTariff,actor:UUID):TariffDto {
        access.lockCurrentActor()
        authorize(actor,true)
        if(category !in CommercialValidation.categories) invalid("Неизвестная тарифная категория")
        val values=validated(body.values)
        val old=jdbc.query("SELECT * FROM merch_supplier_pricing_tiers WHERE category=? FOR UPDATE",{rs,_->row(rs,true)},category)
            .singleOrNull() ?: throw AppException(ErrorCode.NOT_FOUND,"Тарифная категория не найдена",HttpStatus.NOT_FOUND)
        if(old.updatedAt!=body.expectedUpdatedAt) throw AppException(ErrorCode.CONFLICT,"Тариф изменился. Загрузите актуальную версию перед сохранением",HttpStatus.CONFLICT)
        jdbc.update("""UPDATE merch_supplier_pricing_tiers SET label=?,price_multiplier=?,facing_month_price=?,promotion_month_price=?,
            training_event_price=?,motivation_person_price=?,active=?,updated_at=GREATEST(clock_timestamp(),updated_at+interval '1 microsecond')
            WHERE category=?""",values.label,BigDecimal(values.multiplier),values.facingMonthPrice.toLong(),values.promotionMonthPrice.toLong(),
            values.trainingEventPrice.toLong(),values.motivationPersonPrice.toLong(),values.active,category)
        val result=jdbc.query("SELECT * FROM merch_supplier_pricing_tiers WHERE category=?",{rs,_->row(rs,true)},category).single()
        jdbc.update("""INSERT INTO merch_tariff_audit(id,category,actor_admin_id,previous_updated_at,updated_at,before_json,after_json)
            VALUES(?,?,?,?,?,?::jsonb,?::jsonb)""",UUID.randomUUID(),category,actor,Timestamp.from(old.updatedAt),Timestamp.from(result.updatedAt),
            json.writeValueAsString(old.values),json.writeValueAsString(result.values))
        return result
    }

    private fun authorize(actor:UUID,write:Boolean) {
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM admin_users WHERE id=? AND status='active' AND role IN ('SYSTEM_ADMIN','HQ_HEAD'))",Boolean::class.java,actor)!=true)
            throw AppException(ErrorCode.FORBIDDEN,"Тарифная матрица доступна уполномоченному HQ",HttpStatus.FORBIDDEN)
        for(action in if(write) listOf("view","edit","financial_data") else listOf("view","financial_data"))
            access.requireAllMerchPlacementPermission(PlacementPermissions.key(action))
    }
    private fun validated(v:TariffValues):TariffValues {
        val label=v.label.trim()
        if(label.length !in 1..200 || label.any {it.code<32 || it.code in 127..159}) invalid("Укажите название категории до 200 символов")
        val multiplier=v.multiplier.takeIf {it.length<=32}?.toBigDecimalOrNull()
        if(multiplier==null || multiplier<BigDecimal("0.1") || multiplier>BigDecimal.TEN || multiplier.stripTrailingZeros().scale()>3)
            invalid("Коэффициент должен быть от 0,1 до 10 и содержать не более трёх знаков после точки")
        fun money(value:String):String {
            if(!value.matches(Regex("[0-9]{1,19}"))) invalid("Стоимость должна быть целым неотрицательным числом")
            return (value.toLongOrNull() ?: invalid("Стоимость превышает допустимое целое значение")).toString()
        }
        return v.copy(label=label,multiplier=multiplier.toPlainString(),facingMonthPrice=money(v.facingMonthPrice),promotionMonthPrice=money(v.promotionMonthPrice),
            trainingEventPrice=money(v.trainingEventPrice),motivationPersonPrice=money(v.motivationPersonPrice))
    }
    private fun row(rs:ResultSet,edit:Boolean)=TariffDto(rs.getString("category"),TariffValues(rs.getString("label"),rs.getBigDecimal("price_multiplier").toPlainString(),
        rs.getLong("facing_month_price").toString(),rs.getLong("promotion_month_price").toString(),rs.getLong("training_event_price").toString(),
        rs.getLong("motivation_person_price").toString(),rs.getBoolean("active")),rs.getTimestamp("updated_at").toInstant(),edit)
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message,HttpStatus.BAD_REQUEST)
}
