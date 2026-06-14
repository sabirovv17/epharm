package kz.epharm.screens

import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Прод-инициализация рекламного экрана (T3): один активный плейлист «Promo-lite» из ДВУХ
 * лёгких аптечных видео, которые играют по очереди. Видео заранее загружены в MinIO
 * (`epharm-receipts/screens/{promo-lite,pharmacy-care}.mp4`).
 *
 * Идемпотентно: создаём только если плейлистов ещё нет (первый прод-старт). Дальше админ
 * управляет экранами из простой страницы «Экраны» (загрузка/замена видео, активация).
 * Профиль prod (dev сидит в DevDataSeeder). Флаг `app.screens.seed-promo-lite` (по умолч. true).
 */
@Component
@Profile("prod")
@Order(20)
class ScreensPromoLiteSeeder(
    private val playlistRepository: PlaylistRepository,
    private val slideRepository: SlideRepository,
    @Value("\${app.s3.public-url}") private val publicUrl: String,
    @Value("\${app.s3.bucket-receipts}") private val bucket: String,
    @Value("\${app.screens.seed-promo-lite:true}") private val enabled: Boolean,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        if (!enabled) return
        if (playlistRepository.count() > 0L) return // уже есть плейлисты — не трогаем

        val base = "${publicUrl.trimEnd('/')}/$bucket/screens"
        playlistRepository.save(
            PlaylistEntity(
                id = "pl_promo_lite", name = "Promo-lite",
                slidesCount = 2, durationSec = 16, pharmacies = 0, pharmacyId = null,
            ).also { it.status = PlaylistStatus.active },
        )
        slideRepository.saveAll(
            listOf(
                SlideEntity(
                    id = "sl_promo_lite", title = "Epharm — promo-lite", durationSec = 8,
                    mediaUrl = "$base/promo-lite.mp4", playlistId = "pl_promo_lite", position = 0,
                ).also { it.kind = SlideKind.video },
                SlideEntity(
                    id = "sl_pharm_care", title = "Аптечная программа лояльности", durationSec = 8,
                    mediaUrl = "$base/pharmacy-care.mp4", playlistId = "pl_promo_lite", position = 1,
                ).also { it.kind = SlideKind.video },
            ),
        )
        log.info("Засеян активный плейлист Promo-lite из 2 видео ({}/...)", base)
    }
}
