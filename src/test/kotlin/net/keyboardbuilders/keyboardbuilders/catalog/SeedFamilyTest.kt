package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals

/**
 * `family` -- which keyboard a design is one PCB of. Backlog #107: "Corne" is the family,
 * Corne Cherry v4 and Corne Chocolate v4 its variants, and no design is called just
 * "Corne". So the family is a NAME of its own, carried on each member.
 */
class SeedFamilyTest {

    private val mapper = jacksonObjectMapper()

    private fun parse(family: String?) = mapper.readValue<SeedDesign>(
        """
        {
          "name": "Sweep 2", "slug": "sweep-2",
          "sources": ["https://github.com/x/y"],
          "firmware": [{"family": "zmk", "path": null, "shield": "cradio"}],
          "layouts": [{"keys": 34}],
          "switches": [{"kind": "choc_v1"}],
          "buildMethod": {"kind": "pcb"},
          "controller": {"kind": "socket", "footprints": ["pro_micro"]},
          "body": {"kind": "split", "halves": 2},
          "licensing": {"hardware": ["Mit"], "firmware": [], "documentation": []}
          ${family?.let { ""","family": "$it"""" } ?: ""}
        }
        """.trimIndent()
    ).toDomain()

    @Test
    fun `a family name comes through as the design's family`() {
        assertEquals(Recorded.Known(FamilyName("Sweep")), parse("Sweep").extras.family)
    }

    /** Not "a keyboard of its own": nobody has ruled on most designs yet, so no family
     *  key means only that none is recorded. */
    @Test
    fun `no family key is Unknown`() {
        assertEquals(Recorded.Unknown, parse(null).extras.family)
    }
}
