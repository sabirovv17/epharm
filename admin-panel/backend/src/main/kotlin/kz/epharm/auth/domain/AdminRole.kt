package kz.epharm.auth.domain

/**
 * Роли пользователей HQ-консоли.
 * Соответствует CHECK-constraint'у в V002__auth.sql.
 * Не путать с ролями фармацевтов — у них отдельный flow аутентификации через мобильное приложение.
 */
enum class AdminRole {
    /** Head of Trade Marketing / общий администратор HQ. */
    HQ_HEAD,

    /** Category Lead Inkar — утверждает кампании и контракты. */
    CATEGORY_LEAD,

    /** Brand Manager — управляет правилами в рамках своего контракта. */
    BRAND_MANAGER,

    /** Finance Reviewer — утверждает выплаты фармацевтам. */
    FINANCE_REVIEWER,
}

enum class AdminUserStatus {
    ACTIVE,
    PENDING,
    BLOCKED;

    fun toDbValue(): String = name.lowercase()

    companion object {
        fun fromDbValue(value: String): AdminUserStatus = valueOf(value.uppercase())
    }
}
