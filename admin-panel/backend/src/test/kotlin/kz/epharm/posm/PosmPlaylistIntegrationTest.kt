package kz.epharm.posm

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PosmPlaylistIntegrationTest {

    companion object {
        private const val POSM_KEY = "dev-posm-key"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var playlistRepository: PlaylistRepository
    @Autowired private lateinit var slideRepository: SlideRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository

    @BeforeEach
    fun clean() {
        slideRepository.deleteAll()
        playlistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
    }

    @Test
    fun `активный плейлист отдаёт слайды по порядку`() {
        playlistRepository.save(PlaylistEntity(id = "pl_a", name = "Промо июнь").also { it.status = PlaylistStatus.active })
        // намеренно не по порядку — должны вернуться по position
        slideRepository.save(slide("s2", "pl_a", pos = 1, url = "http://minio/v2.mp4"))
        slideRepository.save(slide("s1", "pl_a", pos = 0, url = "http://minio/v1.mp4"))

        val resp = getPlaylist()
        assertEquals("pl_a", resp.playlistId)
        assertEquals(2, resp.slides.size)
        assertEquals("http://minio/v1.mp4", resp.slides[0].url) // position 0 первым
        assertEquals("http://minio/v2.mp4", resp.slides[1].url)
    }

    @Test
    fun `нет активного плейлиста → пустой ответ (клиент откатится на promo по умолчанию)`() {
        playlistRepository.save(PlaylistEntity(id = "pl_d", name = "Черновик").also { it.status = PlaylistStatus.draft })
        val resp = getPlaylist()
        assertEquals(null, resp.playlistId)
        assertEquals(0, resp.slides.size)
    }

    @Test
    fun `без device-key → 401`() {
        mockMvc.perform(get("/api/posm/playlists/active")).andExpect(status().isUnauthorized)
    }

    // ── V016: назначение «все экраны» vs «конкретный экран» ──────────────────

    @Test
    fun `касса без своего плейлиста получает глобальный (загружено на все экраны)`() {
        seedPharmacy("ph_1")
        // глобальный активный (pharmacy_id = null)
        playlistRepository.save(PlaylistEntity(id = "pl_global", name = "Все экраны").also { it.status = PlaylistStatus.active })
        slideRepository.save(slide("g1", "pl_global", pos = 0, url = "http://minio/global.mp4"))

        val resp = getPlaylist(pharmacyId = "ph_1")
        assertEquals("pl_global", resp.playlistId)
        assertEquals("http://minio/global.mp4", resp.slides[0].url)
    }

    @Test
    fun `плейлист конкретной аптеки перекрывает глобальный для этой кассы`() {
        seedPharmacy("ph_1")
        seedPharmacy("ph_2")
        // глобальный
        playlistRepository.save(PlaylistEntity(id = "pl_global", name = "Все экраны").also { it.status = PlaylistStatus.active })
        slideRepository.save(slide("g1", "pl_global", pos = 0, url = "http://minio/global.mp4"))
        // персональный для ph_1
        playlistRepository.save(
            PlaylistEntity(id = "pl_ph1", name = "Только ph_1", pharmacyId = "ph_1")
                .also { it.status = PlaylistStatus.active },
        )
        slideRepository.save(slide("p1", "pl_ph1", pos = 0, url = "http://minio/ph1.mp4"))

        // ph_1 видит свой
        val r1 = getPlaylist(pharmacyId = "ph_1")
        assertEquals("pl_ph1", r1.playlistId)
        assertEquals("http://minio/ph1.mp4", r1.slides[0].url)

        // ph_2 (нет своего) падает на глобальный
        val r2 = getPlaylist(pharmacyId = "ph_2")
        assertEquals("pl_global", r2.playlistId)
        assertEquals("http://minio/global.mp4", r2.slides[0].url)
    }

    private fun getPlaylist(pharmacyId: String? = null): ActivePlaylistDto {
        var req = get("/api/posm/playlists/active").header("X-Posm-Key", POSM_KEY)
        if (pharmacyId != null) req = req.param("pharmacyId", pharmacyId)
        val body = mockMvc.perform(req)
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return objectMapper.readValue(body, ActivePlaylistDto::class.java)
    }

    private fun seedPharmacy(id: String) {
        if (!chainRepository.existsById("ch_t")) {
            chainRepository.save(ChainEntity(id = "ch_t", name = "Тест-сеть", color = "#16C97A", points = 1))
        }
        pharmacyRepository.save(
            PharmacyEntity(
                id = id, name = "Аптека $id", chainId = "ch_t", chainName = "Тест-сеть",
                city = "Алматы", district = "Центр", addr = "ул. Тестовая 1",
            ),
        )
    }

    private fun slide(id: String, playlistId: String, pos: Int, url: String) = SlideEntity(
        id = id, title = "Слайд $id", durationSec = 10, mediaUrl = url,
        playlistId = playlistId, position = pos,
    ).also { it.kind = SlideKind.video }
}
