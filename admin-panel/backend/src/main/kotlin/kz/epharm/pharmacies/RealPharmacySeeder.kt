package kz.epharm.pharmacies

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.slf4j.LoggerFactory

/**
 * Импорт РЕАЛЬНОГО реестра аптек (≈523) витрины inkar.kz. Источник — Medusa
 * `stock_locations` (kind=pharmacy), однократно выгруженные в `seed/pharmacies.json`
 * (без рантайм-зависимости от Medusa-admin и без хранения admin-секрета).
 *
 * Идемпотентно: если в таблице уже есть аптеки — пропуск. Medusa не различает «сети»
 * (адрес и название втиснуты в `name`), поэтому все аптеки под зонтичной сетью `inkar`,
 * а распарсенное реальное название сети кладём в `chainName` (видно в админке/мобилке).
 *
 * Единый источник для dev-сидера, prod-импортёра ([PharmacyImporter]) и dev-reset.
 */
object RealPharmacySeeder {
    private val log = LoggerFactory.getLogger(RealPharmacySeeder::class.java)
    const val UMBRELLA_CHAIN_ID = "inkar"

    private data class Rec(
        val id: String = "",
        val name: String = "",
        val chain: String = "",
        val city: String = "",
        val addr: String = "",
    )

    fun seed(chainRepository: ChainRepository, pharmacyRepository: PharmacyRepository) {
        if (pharmacyRepository.count() > 0) {
            log.info("Pharmacies present — skip real-pharmacy import")
            return
        }
        if (!chainRepository.existsById(UMBRELLA_CHAIN_ID)) {
            chainRepository.save(
                ChainEntity(id = UMBRELLA_CHAIN_ID, name = "Inkar", color = "#16C97A", points = 0)
                    .also { it.group = PharmacyGroup.rolled },
            )
        }
        val recs = load()
        if (recs.isEmpty()) {
            log.warn("seed/pharmacies.json пуст/не найден — аптеки не импортированы")
            return
        }
        val entities = recs
            .filter { it.id.isNotBlank() }
            .map { r ->
                PharmacyEntity(
                    id = r.id,
                    name = r.name.ifBlank { "Аптека" }.take(255),
                    chainId = UMBRELLA_CHAIN_ID,
                    chainName = r.chain.ifBlank { "Inkar" }.take(255),
                    city = r.city.ifBlank { "—" }.take(128),
                    district = "",
                    addr = r.addr.ifBlank { r.name }.take(255),
                ).also { it.group = PharmacyGroup.rolled; it.active = true }
            }
        pharmacyRepository.saveAll(entities)
        log.info("Импортировано {} реальных аптек (витрина inkar.kz)", entities.size)
    }

    private fun load(): List<Rec> {
        val stream = javaClass.classLoader.getResourceAsStream("seed/pharmacies.json") ?: return emptyList()
        return stream.use { jacksonObjectMapper().readValue(it) }
    }
}
