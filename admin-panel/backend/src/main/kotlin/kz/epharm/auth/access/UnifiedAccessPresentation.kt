package kz.epharm.auth.access

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.policy.*

data class DelegationScope(val allPharmacies: Boolean, val pharmacyIds: Set<String>,
    val allMerchBranches: Boolean = false, val merchBranchIds: Set<String> = emptySet(),
    val allProducers: Boolean = false, val producerIds: Set<String> = emptySet())
data class PermissionOption(
    val module: String, val subsection: String, val action: String, val kind: PermissionKind,
    val moduleTitle: String, val subsectionTitle: String, val actionTitle: String,
    val mode: ScopeIntegration, val availableForRoles: Set<AdminRole>, val requiresPasswordReauthentication: Boolean,
    val delegatable: Boolean, val delegationScope: DelegationScope?,
    val scopeType: String = "PHARMACY",
    val requiresProducerScope: Boolean = false,
)

/** Display eligibility only; every write rechecks current grants, person, scope, password and version. */
class UnifiedAccessPresentation(private val catalog: UnifiedAdminCatalog) {
    fun permissions(access: LoadedAccess, manage: Boolean): List<PermissionOption> {
        val grouped = catalog.routes.groupBy { it.permission.key() }
        val definitions = catalog.definitions.associateBy { it.key }
        return (grouped.keys + definitions.keys).sortedWith(compareBy({ it.module.identifier }, { it.subsection }, { it.action })).map { key ->
            val routes = grouped[key].orEmpty()
            val definition = definitions[key]
            val kind = definition?.kind ?: routes.first().permission.kind
            val mode = catalog.integration(key)
            val roles = catalog.availableRoles(key)
            val scope = if (!manage || mode == ScopeIntegration.NOT_READY || access.role !in roles) null else {
                val own = if (access.actor.primaryHqOwner && kind == PermissionKind.ORDINARY) catalog.allScope(key).pharmacies
                    else access.actor.grants.singleOrNull { it.permission == key }?.scope?.pharmacies
                val branches = when {
                    own is PharmacyScope.All -> DelegationScope(true, emptySet())
                    own is PharmacyScope.Assigned && own.ids.isNotEmpty() && mode == ScopeIntegration.SCOPED ->
                        DelegationScope(false, own.ids.map { it.id }.toSet())
                    own is PharmacyScope.AllMerchBranches -> DelegationScope(false, emptySet(), true)
                    own is PharmacyScope.MerchBranches && own.ids.isNotEmpty() && mode == ScopeIntegration.SCOPED ->
                        DelegationScope(false, emptySet(), false, own.ids.map { it.id }.toSet())
                    else -> null
                }
                if (branches == null || key.module != AdministrationModule.MERCHANDISING || key.subsection !in setOf("placements", "producers")) branches
                else {
                    val owners = if (access.actor.primaryHqOwner && kind == PermissionKind.ORDINARY) OwnerScope.AllCustomers
                        else access.actor.grants.singleOrNull { it.permission == key }?.scope?.owners
                    when (owners) {
                        OwnerScope.AllCustomers -> branches.copy(allProducers = true)
                        is OwnerScope.Customers -> if (owners.ids.isEmpty()) null else branches.copy(producerIds = owners.ids.map { it.id }.toSet())
                        else -> null
                    }
                }
            }
            val delegatedScope = scope?.takeIf {
                mode != ScopeIntegration.ALL_ONLY || if (key.module == AdministrationModule.MERCHANDISING)
                    it.allMerchBranches && (key.subsection !in setOf("placements", "producers") || it.allProducers)
                else it.allPharmacies
            }
            PermissionOption(key.module.identifier, key.subsection, key.action, kind,
                MODULE_TITLES.getValue(key.module), TITLES[key.subsection] ?: key.subsection.replace('_', ' '),
                ACTION_TITLES[key.action] ?: key.action.replace('_', ' '), mode, roles,
                definition?.requiresPasswordReauthentication ?: false, delegatedScope != null, delegatedScope,
                if (key.module == AdministrationModule.MERCHANDISING) "MERCH_BRANCH" else "PHARMACY",
                key.module == AdministrationModule.MERCHANDISING && key.subsection in setOf("placements", "producers"))
        }
    }

    companion object {
        private val MODULE_TITLES = mapOf(AdministrationModule.ANALYTICS to "Аналитика",
            AdministrationModule.MOTIVATION to "Мотивация фармацевтов", AdministrationModule.TRAINING to "Обучение",
            AdministrationModule.MERCHANDISING to "Мерчендайзинг", AdministrationModule.PHARMACIES_PRODUCTS to "Аптеки и товары",
            AdministrationModule.PHARMACISTS to "Фармацевты", AdministrationModule.MANAGEMENT to "Управление")
        private val TITLES = mapOf("accounts" to "Аккаунты и права", "assignments" to "Назначения", "attendance" to "Посещаемость",
            "banners" to "Баннеры", "bonuses" to "Условия бонусов", "campaign_reports" to "Отчёты по акциям",
            "campaigns" to "Промо-кампании", "catalog_sync" to "Обновление каталога", "certificates" to "Сертификаты",
            "chains" to "Аптечные сети", "classifier" to "Классификатор АСС", "courses" to "Курсы", "events" to "Мероприятия",
            "fulfillment" to "Интернет-заказы", "live" to "Онлайн-обучение", "media" to "Экраны и материалы",
            "motivation" to "Мотивация", "motivation_effect" to "Эффективность мотивации", "overview" to "Обзор",
            "payouts" to "Выплаты", "accruals" to "Начисления", "inventory" to "Места размещения", "pharmacies" to "Аптеки", "posm_releases" to "Обновления касс",
            "preferences" to "Предпочтения обучения", "products" to "Товары", "profiles" to "Профили", "programs" to "Программы",
            "question_bank" to "Банк вопросов", "recommendations" to "Правила рекомендаций", "registers" to "Ведомости",
            "registry" to "Реестр фармацевтов", "results" to "Результаты", "roster" to "Фармацевты на обучении",
            "sales_review" to "Сверка продаж", "seller_mappings" to "Сопоставление кассиров", "standardn_directory" to "Справочник Стандарт-Н")
        private val ACTION_TITLES = mapOf("view" to "Просмотр", "create" to "Создание", "edit" to "Редактирование",
            "delete" to "Удаление", "activate" to "Активация", "approve" to "Подтверждение", "archive" to "В архив",
            "assign" to "Назначение", "block" to "Блокировка", "change_format" to "Смена формата", "duplicate" to "Копирование",
            "export" to "Выгрузка", "generate" to "Формирование", "import" to "Импорт", "map" to "Сопоставление",
            "mark" to "Отметка", "moderate" to "Проверка", "participate" to "Участие", "provision" to "Подключение",
            "publish" to "Публикация", "record_result" to "Фиксация результата", "refresh" to "Обновление", "reject" to "Отклонение",
            "reorder" to "Порядок", "restore" to "Восстановление", "revoke" to "Отзыв", "show_qr" to "Показ QR",
            "submit" to "Отправка", "sync" to "Синхронизация", "transition" to "Изменение статуса", "unblock" to "Разблокировка",
            "upload" to "Загрузка", "bind_producer" to "Подтвердить производителя старого размещения",
            "manage_producer" to "Подтвердить производителя акции",
            "manage" to "Выдача прав", "personal_data" to "ИИН и телефон",
            "financial_data" to "Финансовые данные", "configure_bonus" to "Настройка бонусов", "manage_availability" to "Доступность мест")
    }
}
