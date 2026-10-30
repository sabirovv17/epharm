package kz.epharm.merchtasks.service

import kz.epharm.merch.delivery.NativeMerchTaskDeliveryService
import kz.epharm.merchtasks.dto.MerchTaskDeliveryResult
import kz.epharm.merchtasks.dto.MerchTaskEnvelope
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

/** One writer per delivery; rolling back issuance never reroutes an already owned capability. */
@Component
class MerchTaskDelivery(
    private val legacy: MerchTaskClient,
    private val native: NativeMerchTaskDeliveryService,
    @Value("\${app.merch.native-delivery-enabled:false}") private val nativeEnabled: Boolean,
) {
    fun activeTask(pharmacyId: String): MerchTaskEnvelope {
        if (!nativeEnabled) return legacy.activeTask(pharmacyId)
        return optional(MerchTaskEnvelope(available = false)) { native.activeTask(pharmacyId) }
    }

    fun markShown(request: MerchTaskShownRequest): MerchTaskDeliveryResult =
        optional(MerchTaskDeliveryResult(available = false)) {
            if (native.ownsNativeDeliveryToken(request.deliveryToken)) native.markShown(request)
            else legacy.markShown(request)
        }

    private fun <T> optional(unavailable: T, operation: () -> T): T = try { operation() }
    catch (_: DataAccessException) {
        log.warn("Native merchandising delivery unavailable; no alternate writer used")
        unavailable
    } catch (error: AppException) {
        if (error.code != ErrorCode.UPSTREAM_UNAVAILABLE) throw error
        log.warn("Native merchandising delivery unavailable; no alternate writer used")
        unavailable
    }

    private val log = LoggerFactory.getLogger(javaClass)
}
