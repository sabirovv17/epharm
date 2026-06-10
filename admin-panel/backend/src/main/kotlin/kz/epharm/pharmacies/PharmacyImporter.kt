package kz.epharm.pharmacies

import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Загружает РЕАЛЬНЫЙ реестр аптек на старте приложения — для ЛЮБОГО окружения, кроме
 * тестов (`@Profile("!test")`). В prod (где dev-сидер не работает) это единственный
 * источник аптек; в dev совпадает с тем, что заливает DevDataSeeder — [RealPharmacySeeder]
 * идемпотентен (пропуск при непустой таблице), так что двойного импорта не будет.
 *
 * `@Order(HIGHEST_PRECEDENCE)` — выполнить до прочих ApplicationRunner'ов (на всякий случай).
 */
@Component
@Profile("!test")
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class PharmacyImporter(
    private val chainRepository: ChainRepository,
    private val pharmacyRepository: PharmacyRepository,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        RealPharmacySeeder.seed(chainRepository, pharmacyRepository)
    }
}
