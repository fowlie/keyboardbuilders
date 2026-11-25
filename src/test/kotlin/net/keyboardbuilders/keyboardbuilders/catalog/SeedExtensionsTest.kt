package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `extensions` — what a design makes ROOM FOR, `ux.md` §1.3's "Extensions & ports".
 *
 * THIS FIELD ONLY EVER CARRIES EVIDENCE, which cost a day to learn. `measure_extras.py`
 * is a regex over KiCad footprint NAMES: a match proves the part has a place, and no
 * match proves nothing at all — a Cirque trackpad on a generic `PinHeader_1x04` matches
 * none of the patterns. The field shipped for one afternoon with `[]` meaning "the boards
 * were read and place none", and 11 of the 223 designs that produced were contradicted by
 * their own QMK record. So absence is `Unknown`, and `[]` on the wire means exactly what
 * a missing key means.
 */
class SeedExtensionsTest {

    private val mapper = jacksonObjectMapper()

    private fun parse(extensions: String?) = mapper.readValue<SeedDesign>(
        """
        {
          "name": "Test", "slug": "test",
          "sources": ["https://github.com/x/y"],
          "firmware": [{"family": "qmk", "path": "test", "shield": null}],
          "layouts": [{"keys": 36}],
          "switches": [{"kind": "mx"}],
          "buildMethod": {"kind": "pcb"},
          "controller": {"kind": "socket", "footprints": ["pro_micro"]},
          "body": {"kind": "unibody"},
          "licensing": {"hardware": ["Mit"], "firmware": [], "documentation": []}
          ${extensions?.let { ""","extensions": $it""" } ?: ""}
        }
        """.trimIndent()
    ).toDomain()

    @Test
    fun `no extensions key is Unknown`() {
        assertEquals(Recorded.Unknown, parse(null).extras.extensions)
    }

    /**
     * NO EVIDENCE IS NOT EVIDENCE OF NONE. `bastardkb/dilemma` scans to `[]` here and
     * declares `POINTING_DEVICE_DRIVER = cirque_pinnacle_spi` in its own QMK record,
     * while the page briefly said it had room for nothing.
     */
    @Test
    fun `an empty extensions list means exactly what a missing key means`() {
        assertEquals(Recorded.Unknown, parse("[]").extras.extensions)
        assertEquals(parse(null).extras.extensions, parse("[]").extras.extensions)
    }

    @Test
    fun `the extensions a design has evidence for come through as a set`() {
        assertEquals(
            Recorded.Known(setOf(Extension.Encoder, Extension.Display)),
            parse("""["display", "encoder"]""").extras.extensions,
        )
    }

    /**
     * Strict in BOTH directions or not strict at all. A name this build does not know is
     * a seed written by a newer extractor; guessing would turn a version mismatch into a
     * fact about a keyboard, which is `SeedPitch.toPitch`'s stated rule.
     */
    @Test
    fun `an extension name this build does not know fails the parse loudly`() {
        assertFailsWith<IllegalArgumentException> { parse("""["rgb_underglow"]""") }
    }

    /** Owner, 2026-11-25: Lime's joystick, read from its QMK record's JOYSTICK_ENABLE. */
    @Test
    fun `a joystick comes through`() {
        assertEquals(Recorded.Known(setOf(Extension.Joystick)), parse("""["joystick"]""").extras.extensions)
    }
}
