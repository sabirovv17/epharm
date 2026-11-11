package kz.epharm.shared.validation

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode

/** One BCrypt boundary for pharmacist password writers; never trim or truncate credentials. */
object PharmacistPasswordPolicy {
    fun requireValid(value: String, minimumLength: Int) {
        if (value.isBlank() || value.length !in minimumLength..128 || value.toByteArray(Charsets.UTF_8).size > 72) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Пароль: от $minimumLength символов и не более 72 байт UTF-8. Пробелы по краям сохраняются.",
            )
        }
    }
}
