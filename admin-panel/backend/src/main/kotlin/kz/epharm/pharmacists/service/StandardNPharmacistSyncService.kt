package kz.epharm.pharmacists.service

import kz.epharm.pharmacists.entity.StandardNPharmacistEntity
import kz.epharm.pharmacists.repository.StandardNPharmacistRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

data class StandardNSyncResult(
    val importedRows: Int,
    val uniqueIins: Int,
    val activeUniqueIins: Int,
    val completedAt: Instant,
)

@Service
class StandardNPharmacistSyncService(
    private val repository: StandardNPharmacistRepository,
    @Value("\${app.standardn-directory.enabled:false}") private val enabled: Boolean,
    @Value("\${app.standardn-directory.host:INKSTDN}") private val host: String,
    @Value("\${app.standardn-directory.port:3050}") private val port: Int,
    @Value("\${app.standardn-directory.database:C:\\Standart-N\\base_g\\ZTRADE_G.FDB}")
    private val database: String,
    @Value("\${app.standardn-directory.user:}") private val user: String,
    @Value("\${app.standardn-directory.password:}") private val password: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.standardn-directory.sync-delay-ms:21600000}",
        initialDelayString = "\${app.standardn-directory.initial-delay-ms:30000}",
    )
    fun scheduledSync() {
        if (!enabled) return
        runCatching { sync() }
            .onFailure { log.error("Standard-N pharmacist directory sync failed: {}", it.message) }
    }

    @Transactional
    fun sync(): StandardNSyncResult {
        check(enabled) { "Standard-N directory sync is disabled" }
        check(user.isNotBlank() && password.isNotBlank()) {
            "Standard-N read-only credentials are not configured"
        }

        val rows = readSource()
        val now = Instant.now()
        repository.deleteAllInBatch()
        repository.saveAll(rows.map { it.toEntity(now) })

        val result = StandardNSyncResult(
            importedRows = rows.size,
            uniqueIins = rows.mapTo(mutableSetOf()) { it.iin }.size,
            activeUniqueIins = rows.asSequence()
                .filter { it.status == 0 }
                .map { it.iin }
                .toSet()
                .size,
            completedAt = now,
        )
        log.info(
            "Standard-N directory synced: rows={}, uniqueIins={}, activeUniqueIins={}",
            result.importedRows,
            result.uniqueIins,
            result.activeUniqueIins,
        )
        return result
    }

    @Transactional(readOnly = true)
    fun status(): StandardNSyncResult {
        val rows = repository.findAll()
        return StandardNSyncResult(
            importedRows = rows.size,
            uniqueIins = rows.mapTo(mutableSetOf()) { it.iin }.size,
            activeUniqueIins = rows.asSequence()
                .filter { it.sourceStatus == 0 }
                .map { it.iin }
                .toSet()
                .size,
            completedAt = rows.maxOfOrNull { it.importedAt } ?: Instant.EPOCH,
        )
    }

    private fun readSource(): List<SourceRow> {
        Class.forName("org.firebirdsql.jdbc.FBDriver")
        val properties = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("encoding", "WIN1251")
            setProperty("connectionTimeout", "10")
        }
        val url = "jdbc:firebirdsql://$host:$port/$database"
        DriverManager.getConnection(url, properties).use { connection ->
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.queryTimeout = 30
                statement.executeQuery(SOURCE_SQL).use { rs ->
                    val result = ArrayList<SourceRow>(4_000)
                    while (rs.next()) {
                        val iin = rs.getString("INN")?.trim().orEmpty()
                        if (!iin.matches(IIN_REGEX)) continue
                        val profileId = rs.getLong("G_PROFILE_ID")
                        val userId = rs.getLong("USER_ID")
                        val name = firstNonBlank(
                            rs.getString("USERNAME_N"),
                            rs.getString("USERNAME"),
                            "Фармацевт Standard-N",
                        )
                        result += SourceRow(
                            profileId = profileId,
                            userId = userId,
                            iin = iin,
                            name = name,
                            status = rs.getInt("USER_STATUS"),
                            login = rs.getString("USERCODE")?.trim()?.takeIf(String::isNotBlank),
                            post = rs.getString("POST_NAME")?.trim()?.takeIf(String::isNotBlank),
                            department = rs.getString("DEPARTMENT_NAME")?.trim()?.takeIf(String::isNotBlank),
                            profileName = rs.getString("PROFILE_NAME")?.trim()?.takeIf(String::isNotBlank),
                            profileCity = rs.getString("PROFILE_CITY")?.trim()?.takeIf(String::isNotBlank),
                            profileAddress = rs.getString("PROFILE_ADDRESS")?.trim()?.takeIf(String::isNotBlank),
                        )
                    }
                    return result.distinctBy { "${it.profileId}:${it.userId}" }
                }
            }
        }
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Фармацевт Standard-N"

    private data class SourceRow(
        val profileId: Long,
        val userId: Long,
        val iin: String,
        val name: String,
        val status: Int,
        val login: String?,
        val post: String?,
        val department: String?,
        val profileName: String?,
        val profileCity: String?,
        val profileAddress: String?,
    ) {
        fun toEntity(now: Instant) = StandardNPharmacistEntity(
            directoryKey = "$profileId:$userId",
            sourceProfileId = profileId,
            sourceUserId = userId,
            iin = iin,
            fullName = name,
            sourceStatus = status,
            sourceLogin = login,
            sourcePost = post,
            sourceDepartment = department,
            profileName = profileName,
            profileCity = profileCity,
            profileAddress = profileAddress,
            importedAt = now,
        )
    }

    companion object {
        private val IIN_REGEX = Regex("^[0-9]{12}$")
        private const val SOURCE_SQL = """
            select
                u.ID as USER_ID,
                u.G${'$'}PROFILE_ID as G_PROFILE_ID,
                u.STATUS as USER_STATUS,
                u.USERNAME,
                u.USERNAME_N,
                u.USERCODE,
                u.INN,
                u.POST as POST_NAME,
                u.DEPARTAMENTNAME as DEPARTMENT_NAME,
                p.CAPTION as PROFILE_NAME,
                p.CITY as PROFILE_CITY,
                p.ADRESS as PROFILE_ADDRESS
            from USERS u
            left join G${'$'}PROFILES p on p.ID = u.G${'$'}PROFILE_ID
            where char_length(trim(coalesce(u.INN, ''))) = 12
        """
    }
}
