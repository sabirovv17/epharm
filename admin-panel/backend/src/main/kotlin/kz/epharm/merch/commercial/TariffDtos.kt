package kz.epharm.merch.commercial

import java.time.Instant

data class TariffValues(val label:String,val multiplier:String,val facingMonthPrice:String,
    val promotionMonthPrice:String,val trainingEventPrice:String,val motivationPersonPrice:String,val active:Boolean)
data class SaveTariff(val expectedUpdatedAt:Instant,val values:TariffValues)
data class TariffDto(val category:String,val values:TariffValues,val updatedAt:Instant,val canEdit:Boolean)
data class TariffIndex(val items:List<TariffDto>,val canEdit:Boolean)
