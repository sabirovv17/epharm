package kz.epharm.promo.service

import kz.epharm.auth.policy.AdministrationModule
import kz.epharm.auth.policy.PermissionKey

internal object MotivationPermissions {
    val campaignPublish = PermissionKey(AdministrationModule.MOTIVATION, "campaigns", "publish")
    val recommendationPublish = PermissionKey(AdministrationModule.MOTIVATION, "recommendations", "publish")
    val configureBonus = PermissionKey(AdministrationModule.MOTIVATION, "bonuses", "configure_bonus")
}
