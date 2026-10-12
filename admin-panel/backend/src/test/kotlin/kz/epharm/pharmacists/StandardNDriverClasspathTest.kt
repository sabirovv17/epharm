package kz.epharm.pharmacists

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardNDriverClasspathTest {
    @Test
    fun `Firebird JDBC driver is available for Standard-N sync`() {
        val driver = Class.forName("org.firebirdsql.jdbc.FBDriver")
            .getDeclaredConstructor()
            .newInstance() as java.sql.Driver

        assertTrue(driver.acceptsURL("jdbc:firebirdsql://localhost:3050/standardn"))
    }
}
