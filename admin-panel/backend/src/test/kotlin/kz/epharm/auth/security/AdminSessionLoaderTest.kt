package kz.epharm.auth.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.util.Optional
import java.util.UUID

class AdminSessionLoaderTest {
    private val users = mockk<AdminUserRepository>()
    private val loader = AdminSessionLoader(users)
    private val user = AdminUserEntity(
        id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        email = "current-admin@example.test", name = "Current administrator", company = "Current company",
        roleRaw = AdminRole.TRAINING_MANAGER.name,
    )

    @Test
    fun `active account supplies its current database identity and role`() {
        every { users.findById(user.id) } returns Optional.of(user)
        assertThat(loader.load(user.id.toString())).isEqualTo(
            AdminPrincipal(user.id, user.email, user.name, AdminRole.TRAINING_MANAGER, user.company),
        )
    }

    @Test
    fun `pending blocked deleted and unknown status accounts cannot authenticate`() {
        every { users.findById(user.id) } returns Optional.of(user)
        for (status in listOf(AdminUserStatus.PENDING.toDbValue(), AdminUserStatus.BLOCKED.toDbValue(), "unknown")) {
            user.statusRaw = status
            assertThat(loader.load(user.id.toString())).describedAs(status).isNull()
        }
        every { users.findById(user.id) } returns Optional.empty()
        assertThat(loader.load(user.id.toString())).isNull()
    }

    @Test
    fun `invalid subject is rejected before repository access`() {
        for (subject in listOf(null, "", "not-a-uuid", "1-1-1-1-1", " ${user.id}", "${user.id} ")) {
            assertThat(loader.load(subject)).describedAs("subject %s", subject).isNull()
        }
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `unknown current role is rejected without a default role`() {
        every { users.findById(user.id) } returns Optional.of(user)
        user.roleRaw = "FUTURE_OR_INVALID_ROLE"
        assertThat(loader.load(user.id.toString())).isNull()
    }

    @Test
    fun `repository failure propagates instead of returning token privileges`() {
        every { users.findById(user.id) } throws DataAccessResourceFailureException("Database unavailable")
        assertThatThrownBy { loader.load(user.id.toString()) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
    }
}
