package kz.epharm.posm.staff

import jakarta.validation.Validation
import kz.epharm.mobile.auth.dto.IinPasswordLoginRequest
import kz.epharm.mobile.auth.dto.SetInitialPasswordRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StaffPasswordPolicyTest {
    @Test fun `four characters work for POSM and existing learner login without lowering learner creation policy`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val validator=factory.validator
            val context=StaffContext("user","session","workstation","process")
            for(password in listOf("A1!b","123","    ")) {
                val expected=if(password=="A1!b") emptySet<String>() else setOf("password")
                val requests=listOf(
                    StaffLoginRequest("951212500015",password,context),
                    StaffDeviceLoginRequest("951212500015",password,StaffDeviceContext("process")),
                    StaffDeviceRegistrationRequest("challenge","1234","951212500015","+77002223344",password,"Test Person",StaffDeviceContext("process")),
                    StaffDeviceRegistrationWithoutSmsRequest("951212500015","+77002223344",password,"Test Person",StaffDeviceContext("process")),
                    StaffPasswordRequest("challenge","1234","951212500015","+77002223344",password),
                    IinPasswordLoginRequest("951212500015",password),
                )
                for(request in requests) assertEquals(expected,validator.validate(request).map { it.propertyPath.toString() }.toSet())
            }
            assertEquals(setOf("password"),validator.validate(SetInitialPasswordRequest(
                "951212500015","+77002223344","A1!b")).map { it.propertyPath.toString() }.toSet())
        }
    }
}
