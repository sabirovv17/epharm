package kz.epharm.posm

import io.mockk.mockk
import kz.epharm.posm.service.PosmPharmacistMappingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Exercises the discovery SQL in a disposable schema; never creates a financial mapping. */
@Testcontainers
class PosmPharmacistMappingDiscoveryIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mapping_discovery_test")
    }

    private val jdbc by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }
    private val service by lazy { PosmPharmacistMappingService(mockk(), mockk(), mockk(), jdbc) }

    @BeforeEach
    fun schema() {
        jdbc.execute("DROP TABLE IF EXISTS pos_sales, pharmacies, pharmacists, posm_pharmacist_mappings, posm_source_staff_bindings")
        jdbc.execute("CREATE TABLE pharmacies(id text PRIMARY KEY, name text)")
        jdbc.execute("CREATE TABLE pharmacists(id text PRIMARY KEY, name text, pharmacy_id text, status text)")
        jdbc.execute("""CREATE TABLE pos_sales(id bigserial PRIMARY KEY, pharmacy_id text, reported_pharmacist_id text,
            reported_pharmacist_name text, pharmacist_source text, capture_source text,
            source_document_id bigint, device_id text, printed_at timestamptz DEFAULT now())""")
        jdbc.execute("""CREATE TABLE posm_pharmacist_mappings(pharmacy_id text,
            external_user_id text, active boolean, id bigserial, pharmacist_id text DEFAULT 'p-a', updated_at timestamptz DEFAULT now())""")
        jdbc.execute("""CREATE TABLE posm_source_staff_bindings(pharmacy_id text,
            native_user_id text, state text, conflict_at timestamptz)""")
        jdbc.update("INSERT INTO pharmacies VALUES ('ph-a','Synthetic pharmacy A'),('ph-b','Synthetic pharmacy B')")
        jdbc.update("INSERT INTO pharmacists VALUES ('p-a','Synthetic seller','ph-a','active')")
    }

    @Test
    fun `native name matched and internal sellers remain discoverable until HQ explicitly maps them`() {
        sale("17", "standardn_name_match")
        sale("-1", "posm_internal")
        sale("17", "standardn_name_match")

        val rows = service.unmapped().associateBy { it.externalUserId }
        assertEquals(setOf("17", "-1"), rows.keys)
        assertEquals(2L, rows.getValue("17").salesCount)
        assertEquals("Synthetic seller", rows.getValue("-1").externalUserName)
        assertEquals("Synthetic pharmacy A", rows.getValue("17").pharmacyName)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM posm_pharmacist_mappings", Long::class.java))
    }

    @Test
    fun `new discovery sources require every native witness and never promote legacy profile ids`() {
        for (source in listOf("standardn_name_match", "posm_internal")) {
            sale("11", source, capture = null)
            sale("12", source, capture = "legacy-log")
            sale("13", source, document = null)
            sale("14", source, document = 0)
            sale("15", source, document = -1)
            sale("16", source, device = null)
            sale("17", source, device = "   ")
            sale("legacy-profile-id", source)
            sale("18.0", source)
        }
        sale("20", "standardn_explicit_mapping")
        assertEquals(emptyList<Any>(), service.unmapped())
    }

    @Test
    fun `active explicit maps exclude only their own pharmacy while revoked maps can be repaired`() {
        sale("21", "standardn_name_match")
        sale("21", "standardn_name_match", pharmacy = "ph-b")
        sale("22", "posm_internal")
        jdbc.update("INSERT INTO posm_pharmacist_mappings(pharmacy_id,external_user_id,active) VALUES ('ph-a','21',true),('ph-a','22',false)")

        assertEquals(setOf("ph-b" to "21", "ph-a" to "22"),
            service.unmapped().map { it.pharmacyId to it.externalUserId }.toSet())
    }

    @Test
    fun `missing user ids are visible diagnostics but never fabricated mappings`() {
        sale("old-external-id", "standardn_unmapped", capture = null, document = null, device = null)
        sale("", "standardn_unmapped")
        sale(" ", "unresolved")
        val rows = service.unmapped().associateBy { it.externalUserId }
        assertEquals(setOf("", "old-external-id"), rows.keys)
        assertEquals("missing_user_id", rows.getValue("").reason)
        assertEquals(2L, rows.getValue("").salesCount)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM posm_pharmacist_mappings", Long::class.java))
    }

    @Test
    fun `active mapping with later source conflict stays visible until explicit HQ review`() {
        sale("21", "standardn_unmapped")
        jdbc.update("INSERT INTO posm_pharmacist_mappings(pharmacy_id,external_user_id,active,updated_at) VALUES ('ph-a','21',true,now()-interval '1 hour')")
        jdbc.update("INSERT INTO posm_source_staff_bindings VALUES ('ph-a','21','conflict',now())")
        assertEquals("source_conflict", service.unmapped().single().reason)
        jdbc.update("UPDATE posm_pharmacist_mappings SET updated_at=now()+interval '1 second'")
        assertEquals(emptyList<Any>(), service.unmapped())
    }

    @Test
    fun `blocked or transferred recipient cannot hide seller from repair queue`() {
        sale("21", "standardn_unmapped")
        jdbc.update("INSERT INTO posm_pharmacist_mappings(pharmacy_id,external_user_id,active) VALUES ('ph-a','21',true)")
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id='p-a'")
        assertEquals("inactive_recipient", service.unmapped().single().reason)
        jdbc.update("UPDATE pharmacists SET status='active',pharmacy_id='ph-b' WHERE id='p-a'")
        assertEquals("inactive_recipient", service.unmapped().single().reason)
    }

    @Test
    fun `latest observed name replaces alphabetical maximum and repeated names are warned`() {
        sale("21", "standardn_unmapped")
        jdbc.update("UPDATE pos_sales SET reported_pharmacist_name='Zed Seller',printed_at=now()-interval '1 hour'")
        sale("21", "standardn_unmapped")
        jdbc.update("UPDATE pos_sales SET reported_pharmacist_name='Amy Seller' WHERE id=(SELECT max(id) FROM pos_sales)")
        val row = service.unmapped().single()
        assertEquals("Amy Seller", row.externalUserName)
        assertEquals(2L, row.observedNameCount)
        assertEquals("name_not_matched", row.reason)
    }

    @Test
    fun `no active accounts and missing names have separate diagnostic instructions`() {
        sale("21", "standardn_unmapped", pharmacy="ph-b")
        sale("22", "standardn_unmapped")
        jdbc.update("UPDATE pos_sales SET reported_pharmacist_name=NULL WHERE reported_pharmacist_id='22'")
        val rows = service.unmapped().associateBy { it.externalUserId }
        assertEquals("no_active_profiles", rows.getValue("21").reason)
        assertEquals(0L, rows.getValue("21").activePharmacistCount)
        assertEquals("missing_name", rows.getValue("22").reason)
        assertEquals(1L, rows.getValue("22").activePharmacistCount)
    }

    private fun sale(id: String, source: String, capture: String? = "standardn-print-log",
        document: Long? = 123, device: String? = "synthetic-device", pharmacy: String = "ph-a") {
        jdbc.update("""INSERT INTO pos_sales(pharmacy_id,reported_pharmacist_id,reported_pharmacist_name,
            pharmacist_source,capture_source,source_document_id,device_id) VALUES (?,?,?,?,?,?,?)""",
            pharmacy, id, "Synthetic seller", source, capture, document, device)
    }
}
