package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `firmware` left the publication gate on 2026-10-02 (backlog #106). It was there because
 * every design used to be built FROM a firmware record, so it was free; once the build
 * minted designs from boards, the only way to satisfy it was a key-count match, and a wrong
 * match published firmware for another chip (unicorn-f072, the RP2040 grooveboards). An
 * unknown firmware is honest; a wrong one is the failure this site exists to avoid.
 *
 * Same shape as `extensions`: `Known` always holds at least one record, and `[]` on the
 * wire is `Unknown`. A build cannot prove a keyboard has no firmware anywhere, only that
 * it found none.
 */
class SeedFirmwareTest {

    private val mapper = jacksonObjectMapper()

    private fun parse(firmware: String) = mapper.readValue<SeedDesign>(
        """
        {
          "name": "Test", "slug": "test",
          "sources": ["https://github.com/x/y"],
          "firmware": $firmware,
          "layouts": [{"keys": 36}],
          "switches": [{"kind": "mx"}],
          "buildMethod": {"kind": "pcb"},
          "controller": {"kind": "soldered", "mcu": "stm32f072"},
          "body": {"kind": "unibody"},
          "licensing": {"hardware": ["Mit"], "firmware": [], "documentation": []}
        }
        """.trimIndent()
    ).toDomain()

    @Test
    fun `an empty firmware list is Unknown and the design still publishes`() {
        assertEquals(Recorded.Unknown, parse("[]").core.firmware)
    }

    @Test
    fun `a listed firmware is Known`() {
        val firmware = parse("""[{"family": "qmk", "path": "test", "shield": null}]""").core.firmware
        assertEquals(Recorded.Known(setOf<FirmwareRef>(FirmwareRef.Qmk(keyboardPath = "test"))), firmware)
    }

    @Test
    fun `Known never holds an empty set`() {
        assertFailsWith<IllegalArgumentException> {
            DesignCore(
                name = DesignName("Test"),
                slug = Slug("test"),
                sources = setOf(SourceRef("https://github.com/x/y")),
                firmware = Recorded.Known(emptySet()),
                layouts = setOf(Layout(KeyCount(36))),
                switches = setOf(SwitchFootprint.Mx),
                buildMethod = BuildMethod.Pcb(),
                controller = Controller.Socket(ControllerFootprint.ProMicro),
                body = Body.Unibody,
                licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
            )
        }
    }
}
