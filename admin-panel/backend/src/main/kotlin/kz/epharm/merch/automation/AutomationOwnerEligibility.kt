package kz.epharm.merch.automation

/** Business ownership only. No source UUID is cast into a canonical person or used as an admin principal. */
object AutomationOwnerEligibility {
    fun sql(branchExpression:String)="""EXISTS(SELECT 1 FROM merch_automation_branch_owners owner
        WHERE owner.branch_id=$branchExpression AND owner.active AND length(btrim(owner.verification_reference))>0 AND
        ((owner.source_system='MERCH_CRM' AND EXISTS(SELECT 1 FROM unified_external_accounts source_owner
            WHERE source_owner.id=owner.source_owner_id AND source_owner.active AND source_owner.source_role='mass_manager'))
         OR(owner.source_system='EPHARM' AND EXISTS(SELECT 1 FROM admin_users native_owner
            JOIN unified_admin_profiles profile ON profile.account_id=native_owner.id AND profile.person_id=owner.person_id
            JOIN unified_persons person ON person.id=profile.person_id AND length(btrim(person.verification_reference))>0
            WHERE native_owner.id=owner.admin_account_id AND native_owner.status='active'
              AND native_owner.role IN ('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','REGIONAL_MANAGER')))))"""
}
