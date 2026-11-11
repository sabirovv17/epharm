package kz.epharm.pharmacists.service

import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/** Isolates the external read-only source transport from the PostgreSQL snapshot transaction. */
@Component
class StandardNDirectoryConnectionFactory {
    fun open(url: String, properties: Properties): Connection {
        Class.forName("org.firebirdsql.jdbc.FBDriver")
        return DriverManager.getConnection(url, properties)
    }
}
