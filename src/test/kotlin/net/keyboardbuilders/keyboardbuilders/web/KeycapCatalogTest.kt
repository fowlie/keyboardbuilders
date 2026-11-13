package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.CapFootprint
import net.keyboardbuilders.keyboardbuilders.domain.KeycapFit
import net.keyboardbuilders.keyboardbuilders.domain.KeycapStem
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL `seed/keycaps.json`, where `DesignPresentationTest` reads a fixture. The
 * fixture pins the lookup; this pins what the research found.
 */
class KeycapCatalogTest {

    private val keycaps = KeycapCatalog(jacksonObjectMapper(), ClassPathResource("seed/keycaps.json"))

    /**
     * A Choc v2 board at Choc spacing: an MX cross stem on an 18 x 17 mm grid. beekeeb's
     * listings give Tai-Hao's THCS-MX as 17.5 x 16.5 mm and MT165-MX as 16.5 x 16.5 mm,
     * both "+ (MX Style)" (docs/research/keycap-profiles.md §4).
     */
    @Test
    fun `MX-stem caps at Choc spacing are Tai-Hao's THCS and MT 165`() {
        val reduced = keycaps.fitting(KeycapFit(KeycapStem.MxCross, CapFootprint.Reduced))

        assertEquals(listOf("Tai-Hao THCS-MX", "Tai-Hao MT165-MX"), reduced.map { it.name })
        assertTrue(reduced.all { it.url?.startsWith("https://shop.beekeeb.com/products/") == true })
    }

    /** The whole MX market is a sentence on the page, never rows (`seed/keycaps.toml`). */
    @Test
    fun `no MX-stem row is cut for MX spacing`() {
        assertTrue(keycaps.fitting(KeycapFit(KeycapStem.MxCross, CapFootprint.Full)).isEmpty())
    }
}
