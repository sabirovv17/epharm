package kz.epharm.pharmacists.service

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.pharmacists.dto.ActivatePharmacistRequest
import kz.epharm.pharmacists.dto.CreatePharmacistRequest
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.dto.UpdatePharmacistRequest
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class PharmacistService(
    private val pharmacistRepository: PharmacistRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val passwordEncoder: PasswordEncoder,
    private val wallet: PharmacistWalletService,
) {

    @Transactional(readOnly = true)
    fun list(status: PharmacistStatus? = null, pharmacyId: String? = null): List<PharmacistDto> {
        val rows = when {
            status != null -> pharmacistRepository.findAllByStatusRawOrderByNameAsc(status.name)
            pharmacyId != null -> pharmacistRepository.findAllByPharmacyIdOrderByNameAsc(pharmacyId)
            else -> pharmacistRepository.findAllByOrderByNameAsc()
        }
        return rows.map(PharmacistDto::of)
    }

    @Transactional(readOnly = true)
    fun get(id: String): PharmacistDto = PharmacistDto.of(loadOrThrow(id))

    @Transactional
    fun create(req: CreatePharmacistRequest): PharmacistDto {
        // IIN + phone уникальны — проверяем заранее для понятного сообщения
        // (constraint вернёт 500 internal иначе).
        pharmacistRepository.findByIin(req.iin)?.let {
            throw AppException(ErrorCode.CONFLICT, "IIN ${req.iin} уже зарегистрирован", HttpStatus.CONFLICT)
        }
        pharmacistRepository.findByPhone(req.phone)?.let {
            throw AppException(ErrorCode.CONFLICT, "Телефон ${req.phone} уже зарегистрирован", HttpStatus.CONFLICT)
        }
        val pharmacy = pharmacyRepository.findById(req.pharmacyId).orElseThrow {
            AppException(
                ErrorCode.VALIDATION_FAILED,
                "Pharmacy ${req.pharmacyId} does not exist",
                HttpStatus.BAD_REQUEST,
            )
        }
        val entity = PharmacistEntity(
            id = generateId(),
            name = req.name.trim(),
            iin = req.iin.trim(),
            phone = req.phone.trim(),
            passwordHash = passwordEncoder.encode(req.password),
            pharmacyId = pharmacy.id,
            pharmacyName = pharmacy.name,
            city = pharmacy.city,
            joinedAt = LocalDate.now(),
        ).also {
            it.tier = req.tier
            it.status = req.status
        }
        return PharmacistDto.of(pharmacistRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdatePharmacistRequest): PharmacistDto {
        if (req.balance != null) {
            val role = (SecurityContextHolder.getContext().authentication?.principal as? AdminPrincipal)?.role
            if (role !in setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD, AdminRole.FINANCE_REVIEWER)) {
                throw AppException(ErrorCode.FORBIDDEN, "Изменять денежный баланс может только финансовая роль", HttpStatus.FORBIDDEN)
            }
        }
        // These fields control the owner of a fresh monetary OTP, not just profile presentation.
        if (req.phone != null || req.password != null) {
            val role = (SecurityContextHolder.getContext().authentication?.principal as? AdminPrincipal)?.role
            if (role !in setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD)) {
                throw AppException(ErrorCode.FORBIDDEN, "Изменять телефон или пароль фармацевта может только HQ", HttpStatus.FORBIDDEN)
            }
        }
        val entity = loadOrThrow(id)
        if (entity.status == PharmacistStatus.blocked) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Заблокированный фармацевт — только смена статуса через /unblock",
                HttpStatus.CONFLICT,
            )
        }
        req.name?.let { entity.name = it.trim() }
        req.phone?.let { entity.phone = it.trim() }
        req.password?.let { entity.passwordHash = passwordEncoder.encode(it) }
        req.tier?.let { entity.tier = it }
        // Status переходы через update запрещены (только через /block, /unblock).
        if (req.status != null) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Use /block or /unblock endpoints to change status",
                HttpStatus.BAD_REQUEST,
            )
        }
        req.balance?.let { wallet.adjustTo(entity.id, it) }
        req.coursesDone?.let { entity.coursesDone = it }
        req.coursesTotal?.let { entity.coursesTotal = it }
        return PharmacistDto.of(pharmacistRepository.save(entity))
    }

    @Transactional
    fun activate(id: String, req: ActivatePharmacistRequest): PharmacistDto {
        val entity = loadOrThrow(id)
        if (entity.status != PharmacistStatus.pending) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Активировать можно только профиль со статусом pending",
                HttpStatus.CONFLICT,
            )
        }
        val pharmacy = loadPharmacy(req.pharmacyId)
        entity.pharmacyId = pharmacy.id
        entity.pharmacyName = pharmacy.name
        entity.city = pharmacy.city
        entity.status = PharmacistStatus.active
        return PharmacistDto.of(pharmacistRepository.save(entity))
    }

    @Transactional
    fun block(id: String): PharmacistDto {
        val entity = loadOrThrow(id)
        entity.status = PharmacistStatus.blocked
        return PharmacistDto.of(pharmacistRepository.save(entity))
    }

    @Transactional
    fun unblock(id: String): PharmacistDto {
        val entity = loadOrThrow(id)
        if (entity.status != PharmacistStatus.blocked) return PharmacistDto.of(entity)
        if (entity.pharmacyId.isNullOrBlank()) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Перед разблокировкой необходимо назначить аптеку",
                HttpStatus.CONFLICT,
            )
        }
        entity.status = PharmacistStatus.active
        return PharmacistDto.of(pharmacistRepository.save(entity))
    }

    private fun loadPharmacy(id: String) = pharmacyRepository.findById(id).orElseThrow {
        AppException(
            ErrorCode.VALIDATION_FAILED,
            "Аптека $id не найдена",
            HttpStatus.BAD_REQUEST,
        )
    }.also {
        if (!it.active) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Нельзя назначить отключённую аптеку",
                HttpStatus.CONFLICT,
            )
        }
    }

    private fun loadOrThrow(id: String): PharmacistEntity =
        pharmacistRepository.findById(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Pharmacist $id not found",
                HttpStatus.NOT_FOUND,
            )
        }

    private fun generateId(): String = "u_${UUID.randomUUID().toString().substring(0, 8)}"
}
