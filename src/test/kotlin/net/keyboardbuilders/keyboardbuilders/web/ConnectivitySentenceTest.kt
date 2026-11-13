package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Connectivity
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The article in front of a board's name. Every wireless board began with a consonant
 * until the Feather (#99), and the first render read "Wireless with a Adafruit Feather".
 */
class ConnectivitySentenceTest {

    @Test
    fun `a board whose name starts with a vowel takes an`() {
        assertEquals(
            "Wireless with an Adafruit Feather 32u4 Bluefruit LE",
            Connectivity.WirelessWithController(listOf("Adafruit Feather 32u4 Bluefruit LE")).describe(),
        )
    }

    @Test
    fun `a board whose name starts with a consonant keeps a`() {
        assertEquals(
            "Wireless with a nice!nano v2, or 1 other controller",
            Connectivity.WirelessWithController(listOf("nice!nano v2", "Mikoto")).describe(),
        )
    }

    @Test
    fun `the needs-firmware sentence takes the same article`() {
        assertEquals(
            "Wireless with an Adafruit Feather 32u4 Bluefruit LE — needs a QMK config",
            Connectivity.WirelessNeedsFirmware(
                listOf("Adafruit Feather 32u4 Bluefruit LE"), setOf(ControllerFootprint.Feather), listOf("qmk"),
            ).describe(),
        )
    }
}
