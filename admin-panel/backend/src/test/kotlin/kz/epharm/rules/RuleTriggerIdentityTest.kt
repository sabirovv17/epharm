package kz.epharm.rules

import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleTriggerCondition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleTriggerIdentityTest {
    @Test fun `ACC conjunction identity ignores primary factor order and presentation labels`() {
        val groupFirst = RuleTrigger("acc_group", "g", label = "Group",
            conditions = listOf(RuleTriggerCondition("acc_mnn", "m", "MNN"), RuleTriggerCondition("acc_subgroup", "s")))
        val mnnFirst = RuleTrigger("acc_mnn", "m", label = "Different label",
            conditions = listOf(RuleTriggerCondition("acc_subgroup", "s", "Subgroup"), RuleTriggerCondition("acc_group", "g")))
        assertThat(groupFirst.identity()).isEqualTo(mnnFirst.identity())
        assertThat(groupFirst.label).isEqualTo("Group")
        assertThat(groupFirst.conditions.first().label).isEqualTo("MNN")
        assertThat(mnnFirst.identity().copy(conditions = emptyList())).isNotEqualTo(groupFirst.identity())
    }

    @Test fun `product remains primary and a different factor changes eligibility identity`() {
        val trigger = RuleTrigger("product", "p", conditions = listOf(RuleTriggerCondition("acc_mnn", "m")))
        assertThat(trigger.identity().kind).isEqualTo("product")
        assertThat(trigger.identity().value).isEqualTo("p")
        assertThat(trigger.identity()).isNotEqualTo(trigger.copy(conditions = emptyList()).identity())
        assertThat(trigger.identity()).isNotEqualTo(trigger.copy(value = "other").identity())
    }
}
