package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "USB port" row on real pages, over the shipped seed and the real controller
 * catalogue -- backlog #97. One design per kind of answer, so a wiring gap between the
 * catalogue and the page fails here rather than only in a fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UsbPortPageTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun usbRow(slug: String): String =
        mockMvc.get("/designs/$slug").andReturn().response.contentAsString
            .substringAfter("<dt>USB port</dt>", "(no USB port row)").substringBefore("</dd>")

    @Test
    fun `a soldered board with USB-C on it says USB-C`() {
        assertTrue("USB-C" in usbRow("redherring"), usbRow("redherring"))
    }

    @Test
    fun `a QMK Pro Micro socket says it depends, because the classic Pro Micro is micro-USB`() {
        val row = usbRow("8pack")
        assertTrue("USB-C or micro-USB, depends on the dev board" in row, row)
        assertTrue("Pro Micro (ATmega32U4)" in row, "the hint should name the classic board: $row")
    }

    /**
     * The classic Pro Micro runs QMK only, so a ZMK design's list must not offer it. (Lily58
     * still reads "or micro-USB": nRFMicro rev 1.1, which runs ZMK, places both footprints.)
     */
    @Test
    fun `a ZMK Pro Micro socket never offers the classic Pro Micro, which cannot run ZMK`() {
        val row = usbRow("lily58")
        assertTrue("nice!nano v2" in row, "a ZMK board should be offered: $row")
        assertFalse("Pro Micro (ATmega32U4)" in row, "the classic Pro Micro runs QMK only: $row")
    }

    @Test
    fun `a Pico socket says micro-USB, on the dev board`() {
        val row = usbRow("piantor")
        assertTrue("micro-USB, on the dev board" in row, row)
    }

    @Test
    fun `a board wired to a daughterboard says so`() {
        val row = usbRow("bakeneko-65")
        assertTrue("on a separate USB daughterboard" in row, row)
    }
}
