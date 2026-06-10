package kz.epharm.mobile.pharmacies.service

import kz.epharm.mobile.pharmacies.dto.MobilePharmacyDto
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Список аптек для мобилки. Отдаём только активные. Фильтры:
 *  - `city` — точное совпадение города (регистронезависимо);
 *  - `q` — подстрока в названии / адресе / сети.
 *
 * Цвет сети подмешиваем из chains (в pharmacies его нет). При большом числе аптек
 * (реестр сейчас десятки, потолок — сотни) выборка целиком в памяти приемлема;
 * если вырастет до тысяч — заменить на пагинируемый запрос.
 */
@Service
class MobilePharmacyService(
    private val pharmacyRepository: PharmacyRepository,
    private val chainRepository: ChainRepository,
) {

    @Transactional(readOnly = true)
    fun list(q: String?, city: String?): List<MobilePharmacyDto> {
        val colorByChain = chainRepository.findAll().associate { it.id to it.color }
        val needle = q?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val cityFilter = city?.trim()?.takeIf { it.isNotBlank() }

        return pharmacyRepository.findAllByActiveTrueOrderByNameAsc()
            .asSequence()
            .filter { cityFilter == null || it.city.equals(cityFilter, ignoreCase = true) }
            .filter { p ->
                needle == null ||
                    p.name.lowercase().contains(needle) ||
                    p.addr.lowercase().contains(needle) ||
                    p.chainName.lowercase().contains(needle)
            }
            .map { MobilePharmacyDto.of(it, colorByChain[it.chainId].orEmpty()) }
            .toList()
    }
}
