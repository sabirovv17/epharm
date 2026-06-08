package kz.epharm.lift.service

import kz.epharm.lift.dto.LiftSegmentDto
import kz.epharm.lift.dto.LiftSummaryDto
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LiftService(
    private val pharmacyRepository: PharmacyRepository,
    private val chainRepository: ChainRepository,
) {

    @Transactional(readOnly = true)
    fun summary(): LiftSummaryDto {
        val pharmacies = pharmacyRepository.findAllByOrderByNameAsc()
        val pilot = pharmacies.filter { it.group == PharmacyGroup.pilot }
        val control = pharmacies.filter { it.group == PharmacyGroup.control }
        val rolled = pharmacies.filter { it.group == PharmacyGroup.rolled }

        val chainNames = chainRepository.findAll().associate { it.id to it.name }

        // Сегменты — средний lift по сетям, где есть pilot-аптеки.
        val segments = pilot
            .groupBy { it.chainId }
            .map { (chainId, list) ->
                LiftSegmentDto(
                    name = chainNames[chainId] ?: chainId,
                    liftPct = avgLift(list),
                    pharmacies = list.size,
                )
            }
            .sortedByDescending { it.liftPct }

        return LiftSummaryDto(
            networkLiftPct = avgLift(pilot),
            pValue = null,
            pilotCount = pilot.size,
            controlCount = control.size,
            rolledCount = rolled.size,
            pilotReceipts30d = pilot.sumOf { it.receipts30d },
            controlReceipts30d = control.sumOf { it.receipts30d },
            segments = segments,
        )
    }

    private fun avgLift(list: List<PharmacyEntity>): Double {
        if (list.isEmpty()) return 0.0
        val avg = list.sumOf { it.liftPct.toDouble() } / list.size
        return Math.round(avg * 10.0) / 10.0
    }
}
