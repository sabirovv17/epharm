package kz.epharm.shared.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kz.epharm.shared.CardNumberUtil
import kotlin.reflect.KClass

/**
 * Bean-validation аннотация для номера карты: длина 13–19 цифр + алгоритм Луна
 * (см. [CardNumberUtil]). Как и [Iin], не ругается на null/blank — это зона
 * ответственности @NotBlank, чтобы не дублировать сообщения.
 *
 * Применение: `@field:NotBlank @field:CardNumber val card: String`.
 */
@MustBeDocumented
@Constraint(validatedBy = [CardNumberConstraintValidator::class])
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CardNumber(
    val message: String = "Некорректный номер карты: нужно 13–19 цифр с верной контрольной суммой",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class CardNumberConstraintValidator : ConstraintValidator<CardNumber, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        if (value.isNullOrBlank()) return true
        return CardNumberUtil.isValid(value)
    }
}
