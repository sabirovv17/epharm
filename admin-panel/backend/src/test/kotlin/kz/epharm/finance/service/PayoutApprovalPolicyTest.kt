package kz.epharm.finance.service

import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutItemEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PayoutApprovalPolicyTest {
    private fun item(id: String, pharmacist: String, amount: Long) = PayoutItemEntity(
        id = id,
        batchId = "batch",
        pharmacistId = pharmacist,
        pharmacistName = pharmacist,
        amount = amount,
    )

    @Test
    fun `accepts only exact positive unique items with matching reservations`() {
        val items = listOf(item("one", "ph-1", 100), item("two", "ph-2", 200))
        val batch = PayoutBatchEntity(id = "batch", pharmacists = 2, items = 2, amount = 300)

        assertThat(payoutBatchApprovalConsistent(batch, items, 2)).isTrue()
        assertThat(payoutBatchApprovalConsistent(batch.apply { amount = 301 }, items, 2)).isFalse()
        batch.amount = 300
        assertThat(payoutBatchApprovalConsistent(batch, items, 1)).isFalse()
        assertThat(
            payoutBatchApprovalConsistent(
                batch,
                listOf(item("one", "ph-1", 100), item("two", "ph-1", 200)),
                2,
            ),
        ).isFalse()
        assertThat(
            payoutBatchApprovalConsistent(
                PayoutBatchEntity(id = "batch", pharmacists = 1, items = 1, amount = 0),
                listOf(item("zero", "ph-1", 0)),
                1,
            ),
        ).isFalse()
    }

    @Test
    fun `rejects overflow instead of approving a wrapped total`() {
        val items = listOf(item("one", "ph-1", Long.MAX_VALUE), item("two", "ph-2", 1))
        val batch = PayoutBatchEntity(id = "batch", pharmacists = 2, items = 2, amount = Long.MIN_VALUE)

        assertThat(payoutBatchApprovalConsistent(batch, items, 2)).isFalse()
    }

    @Test
    fun `minimum bigint amount fails the pre-query items policy without negation`() {
        val items = listOf(item("minimum", "synthetic-pharmacist", Long.MIN_VALUE))
        val batch = PayoutBatchEntity(id = "batch", pharmacists = 1, items = 1, amount = Long.MIN_VALUE)

        assertThat(payoutBatchItemsConsistent(batch, items)).isFalse()
        assertThat(payoutBatchApprovalConsistent(batch, items, 1)).isFalse()
    }
}
