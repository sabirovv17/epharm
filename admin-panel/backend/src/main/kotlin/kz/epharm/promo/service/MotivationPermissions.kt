package kz.epharm.promo.service

import kz.epharm.auth.policy.AdministrationModule
import kz.epharm.auth.policy.PermissionKey

internal object MotivationPermissions {
    val campaignView = PermissionKey(AdministrationModule.MOTIVATION, "campaigns", "view")
    val recommendationView = PermissionKey(AdministrationModule.MOTIVATION, "recommendations", "view")
    val campaignEdit = PermissionKey(AdministrationModule.MOTIVATION, "campaigns", "edit")
    val recommendationEdit = PermissionKey(AdministrationModule.MOTIVATION, "recommendations", "edit")
    val campaignPublish = PermissionKey(AdministrationModule.MOTIVATION, "campaigns", "publish")
    val recommendationPublish = PermissionKey(AdministrationModule.MOTIVATION, "recommendations", "publish")
    val configureBonus = PermissionKey(AdministrationModule.MOTIVATION, "bonuses", "configure_bonus")
}
