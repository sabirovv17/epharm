package kz.epharm.shared.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kz.epharm.shared.IinUtil
import kotlin.reflect.KClass

/**
 * Bean-validation аннотация для поля ИИН. Проверяет формат + дату рождения + контрольную сумму
 * (см. [IinUtil]). Вешать вместе с @field:NotBlank — пустоту контролирует он, эта аннотация
 * не ругается на null/blank (чтобы сообщения не дублировались).
 *
 * Применение: `@field:NotBlank @field:Iin val iin: String`.
 */
@MustBeDocumented
@Constraint(validatedBy = [IinConstraintValidator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Iin(
    val message: String = "Некорректный ИИН: нужно 12 цифр с верной датой рождения и контрольной суммой",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class IinConstraintValidator : ConstraintValidator<Iin, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        // null/blank пропускаем — это зона ответственности @NotBlank.
        if (value.isNullOrBlank()) return true
        return IinUtil.isValid(value)
    }
}
