package kz.epharm.shared

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.validation.PharmacistPasswordPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PharmacistPasswordPolicyTest {
    @Test fun `all supported creation minima share the exact UTF8 BCrypt boundary`() {
        for (minimum in listOf(4, 6, 8)) {
            for (valid in listOf("x".repeat(minimum), "x".repeat(72), "я".repeat(36), "😀".repeat(18), " " + "x".repeat(70) + " ")) {
                assertDoesNotThrow { PharmacistPasswordPolicy.requireValid(valid, minimum) }
            }
            for (invalid in listOf("x".repeat(minimum - 1), " ".repeat(8), "x".repeat(73), "я".repeat(37), "😀".repeat(19))) {
                assertEquals(ErrorCode.VALIDATION_FAILED,
                    assertThrows(AppException::class.java) { PharmacistPasswordPolicy.requireValid(invalid, minimum) }.code)
            }
        }
    }
}
