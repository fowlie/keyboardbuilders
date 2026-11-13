package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `switchMounts`: what a design's switch positions accept, read off the pads by
 * `measure_switch_pads.py` (backlog #107 step 9). Each value is an interface a part fits:
 * a switch soldered in, a Kailh or Gateron socket on MX or Choc socket pads, or a Mill-Max
 * receptacle in a wide plated pin hole.
 */
class SeedSwitchMountsTest {

    private val mapper = jacksonObjectMapper()

    private fun parse(mounts: String?) = mapper.readValue<SeedDesign>(
        """
        {
          "name": "Test", "slug": "test",
          "sources": ["https://github.com/x/y"],
          "firmware": [{"family": "qmk", "path": "test", "shield": null}],
          "layouts": [{"keys": 34}],
          "switches": [{"kind": "choc_v1"}],
          "buildMethod": {"kind": "pcb"},
          "controller": {"kind": "socket", "footprints": ["pro_micro"]},
          "body": {"kind": "unibody"},
          "licensing": {"hardware": ["Mit"], "firmware": [], "documentation": []}
          ${mounts?.let { ""","switchMounts": $it""" } ?: ""}
        }
        """.trimIndent()
    ).toDomain()

    /** Sweep 2: Choc pins drilled 1.52 mm, for a switch soldered in or a Mill-Max sleeve. */
    @Test
    fun `a board can accept several mounts`() {
        assertEquals(
            Recorded.Known(setOf(SwitchMount.Solder, SwitchMount.MillMax)),
            parse("""["solder", "millmax"]""").extras.switchMounts,
        )
    }

    @Test
    fun `socket pads are told apart by switch family`() {
        assertEquals(
            Recorded.Known(setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)),
            parse("""["mx_socket", "choc_socket"]""").extras.switchMounts,
        )
    }

    @Test
    fun `no mounts in the seed is unknown, not a board that accepts nothing`() {
        assertEquals(Recorded.Unknown, parse(null).extras.switchMounts)
    }

    /** Every switch position accepts something, so an empty set would be a scan bug. */
    @Test
    fun `an empty mount list fails the parse`() {
        assertFailsWith<IllegalArgumentException> { parse("[]") }
    }

    @Test
    fun `a mount this build does not know fails the parse loudly`() {
        assertFailsWith<IllegalArgumentException> { parse("""["hotswap"]""") }
    }
}
