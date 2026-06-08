package kz.epharm.cdp.service

import kz.epharm.cdp.dto.CdpLookupResponse
import kz.epharm.cdp.dto.CdpProfileDto
import kz.epharm.cdp.dto.CdpRegisterRequest
import kz.epharm.cdp.entity.CdpProfileEntity
import kz.epharm.cdp.repository.CdpProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * CDP (профили клиентов лояльности, ТЗ §5.6). Поиск по телефону + регистрация на кассе.
 * Welcome-бонус и SMS со ссылкой на приложение — заглушка (реальный Mobizon — Stage 4).
 */
@Service
class CdpService(
    private val repository: CdpProfileRepository,
) {

    private val log = LoggerFactory.getLogger(CdpService::class.java)

    @Transactional(readOnly = true)
    fun lookup(phone: String): CdpLookupResponse {
        val norm = normalize(phone)
        val p = repository.findByPhone(norm)
        return if (p != null) {
            CdpLookupResponse(found = true, profileId = p.id, phone = p.phone, name = p.name, tier = p.tier)
        } else {
            CdpLookupResponse(found = false, profileId = null, phone = norm, name = null, tier = null)
        }
    }

    /** Идемпотентно по телефону: если профиль уже есть — возвращаем его с isNew=false. */
    @Transactional
    fun register(req: CdpRegisterRequest): CdpProfileDto {
        val norm = normalize(req.phone)
        repository.findByPhone(norm)?.let {
            return CdpProfileDto(it.id, it.phone, it.name, it.tier, it.createdAt, isNew = false)
        }
        val saved = repository.save(
            CdpProfileEntity(
                id = "cdp_${UUID.randomUUID().toString().substring(0, 8)}",
                phone = norm,
                name = req.name,
                registeredByPharmacist = req.pharmacistId,
                registeredAtPharmacy = req.pharmacyId,
            ),
        )
        // TODO Stage 4: welcome-бонус + SMS со ссылкой на приложение (Mobizon). Пока — заглушка.
        log.info("CDP профиль {} создан для {} (welcome-бонус/SMS — заглушка)", saved.id, norm)
        return CdpProfileDto(saved.id, saved.phone, saved.name, saved.tier, saved.createdAt, isNew = true)
    }

    /**
     * Нормализация телефона к канону `+<цифры>`, чтобы один и тот же номер в разных форматах
     * («+7 700 111 22 33», «87001112233», «77001112233») искался как одна запись.
     * КЗ-правило: 11 цифр с ведущей 8 → 7 (8700… == 7700…).
     */
    private fun normalize(phone: String): String {
        var digits = phone.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1)
        return "+$digits"
    }
}
