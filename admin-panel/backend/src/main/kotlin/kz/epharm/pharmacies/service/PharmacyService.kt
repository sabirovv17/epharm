package kz.epharm.pharmacies.service

import kz.epharm.pharmacies.dto.ChainDto
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.pharmacies.dto.CreateChainRequest
import kz.epharm.pharmacies.dto.CreatePharmacyRequest
import kz.epharm.pharmacies.dto.PharmacyDto
import kz.epharm.pharmacies.dto.UpdateChainRequest
import kz.epharm.pharmacies.dto.UpdatePharmacyRequest
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PharmacyService(
    private val pharmacyRepository: PharmacyRepository,
    private val chainRepository: ChainRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val administrativeScope: AdministrativeScope,
) {

    @Transactional(readOnly = true)
    fun listChains(): List<ChainDto> =
        chainRepository.findAllByOrderByPointsDesc().map(ChainDto::of)

    // ── Chains CRUD (Блок 2) ──────────────────────────────────────────────

    @Transactional
    fun createChain(req: CreateChainRequest): ChainDto {
        if (chainRepository.existsById(req.id)) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Chain ${req.id} already exists",
                HttpStatus.CONFLICT,
            )
        }
        val entity = ChainEntity(
            id = req.id,
            name = req.name.trim(),
            color = req.color,
            points = req.points,
        ).also { it.group = req.group }
        return ChainDto.of(chainRepository.save(entity))
    }

    @Transactional
    fun updateChain(id: String, req: UpdateChainRequest): ChainDto {
        val entity = loadChainOrThrow(id)
        req.name?.let { entity.name = it.trim() }
        req.color?.let { entity.color = it }
        req.points?.let { entity.points = it }
        req.group?.let { entity.group = it }
        return ChainDto.of(chainRepository.save(entity))
    }

    /**
     * Удаление сети с guard'ом: аптеки ссылаются на сеть через `chainId`.
     * Удалить сеть с привязанными аптеками = осиротить их (chainName повиснет).
     * Поэтому при наличии аптек → 409 CONFLICT.
     */
    @Transactional
    fun deleteChain(id: String) {
        val entity = loadChainOrThrow(id)
        val pharmacies = pharmacyRepository.countByChainId(id)
        if (pharmacies > 0) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Chain $id has $pharmacies pharmacies. Reassign or delete them first.",
                HttpStatus.CONFLICT,
            )
        }
        chainRepository.delete(entity)
    }

    private fun loadChainOrThrow(id: String): ChainEntity =
        chainRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Chain $id not found", HttpStatus.NOT_FOUND)
        }

    @Transactional(readOnly = true)
    fun list(group: PharmacyGroup? = null, chainId: String? = null): List<PharmacyDto> {
        val scope = administrativeScope.currentPharmacyIds()
        val rows = when {
            scope != null && scope.isEmpty() -> emptyList()
            scope != null -> pharmacyRepository.findForAdministration(scope, group?.name, chainId)
            group != null && chainId != null ->
                pharmacyRepository.findAllByGroupRawAndChainIdOrderByNameAsc(group.name, chainId)
            group != null  -> pharmacyRepository.findAllByGroupRawOrderByNameAsc(group.name)
            chainId != null -> pharmacyRepository.findAllByChainIdOrderByNameAsc(chainId)
            else -> pharmacyRepository.findAllByOrderByNameAsc()
        }
        return rows.map { projected(it) }
    }

    @Transactional(readOnly = true)
    fun get(id: String): PharmacyDto = projected(loadOrThrow(id))

    @Transactional
    fun create(req: CreatePharmacyRequest): PharmacyDto {
        // A new ID has no verified membership in an existing selected-pharmacy scope.
        if (administrativeScope.currentPharmacyIds() != null) {
            throw AppException(ErrorCode.FORBIDDEN, "Creating a pharmacy requires unrestricted administration", HttpStatus.FORBIDDEN)
        }
        val chain = chainRepository.findById(req.chainId).orElseThrow {
            AppException(
                ErrorCode.VALIDATION_FAILED,
                "Chain ${req.chainId} does not exist",
                HttpStatus.BAD_REQUEST,
            )
        }
        val entity = PharmacyEntity(
            id = generateId(),
            name = req.name.trim(),
            chainId = chain.id,
            chainName = chain.name,
            city = req.city.trim(),
            district = req.district.trim(),
            addr = req.addr.trim(),
            active = req.active,
        ).also { it.group = req.group }
        return projected(pharmacyRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdatePharmacyRequest): PharmacyDto {
        administrativeScope.requirePharmacyUpdate(req)
        val entity = loadOrThrow(id, forUpdate = true)
        req.name?.let { entity.name = it.trim() }
        req.city?.let { entity.city = it.trim() }
        req.district?.let { entity.district = it.trim() }
        req.addr?.let { entity.addr = it.trim() }
        req.group?.let { entity.group = it }
        req.active?.let { entity.active = it }
        req.receipts30d?.let { entity.receipts30d = it }
        req.gmv30d?.let { entity.gmv30d = it }
        req.rulesAccepted?.let { entity.rulesAccepted = it }
        return projected(pharmacyRepository.save(entity))
    }

    /**
     * Удаление аптеки с guard'ом: фармацевты ссылаются на аптеку через `pharmacyId`.
     * Удалить аптеку с привязанными фармацевтами = осиротить их балансы/выплаты.
     * Поэтому при наличии фармацевтов → 409 CONFLICT.
     */
    @Transactional
    fun delete(id: String) {
        val entity = loadOrThrow(id, forUpdate = true)
        val pharmacists = pharmacistRepository.countByPharmacyId(id)
        if (pharmacists > 0) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Pharmacy $id has $pharmacists pharmacists. Reassign or delete them first.",
                HttpStatus.CONFLICT,
            )
        }
        pharmacyRepository.delete(entity)
    }

    private fun loadOrThrow(id: String, forUpdate: Boolean = false): PharmacyEntity {
        val scope = administrativeScope.currentPharmacyIds()
        val entity = when {
            scope == null -> pharmacyRepository.findById(id).orElse(null)
            scope.isEmpty() -> null
            forUpdate -> pharmacyRepository.findInAdministrativeScopeForUpdate(id, scope).orElse(null)
            else -> pharmacyRepository.findInAdministrativeScope(id, scope).orElse(null)
        }
        return entity ?: throw AppException(ErrorCode.NOT_FOUND, "Pharmacy $id not found", HttpStatus.NOT_FOUND)
    }

    private fun projected(entity: PharmacyEntity): PharmacyDto =
        administrativeScope.pharmacy(PharmacyDto.of(entity))

    private fun generateId(): String = "ph_${UUID.randomUUID().toString().substring(0, 8)}"
}
