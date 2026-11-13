package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `seed/families.json`: each confirmed family's name and the source its grouping cites,
 * for the `/designs/families/{slug}` pages. Backlog #107. Membership is NOT in this file -- each
 * design carries its own `family`, and the family page reads that, so the design page and
 * the family page can never name different boards.
 */
class FamilyCatalogTest {

    private val mapper = jacksonObjectMapper()

    private fun catalogOf(json: String) = FamilyCatalog(mapper, ByteArrayResource(json.toByteArray()))

    @Test
    fun `a family is found by its slug, with the source its grouping cites`() {
        val catalog = catalogOf("""[{"name": "Bad Wings", "source": "https://github.com/hazels-garage"}]""")

        val family = catalog.bySlug("bad-wings")!!
        assertEquals(FamilyName("Bad Wings"), family.name)
        assertEquals(SourceRef("https://github.com/hazels-garage"), family.source)
        assertNull(catalog.bySlug("bad wings"))
    }

    /** Two families at one address would make one of them unreachable, and which one would
     *  depend on file order. The site refuses to start instead -- every test that boots it
     *  fails, which is the build refusing. */
    @Test
    fun `two families whose names fold to one slug are refused`() {
        val error = assertThrows<IllegalStateException> {
            catalogOf(
                """[{"name": "Bad Wings", "source": "https://github.com/a"},
                    {"name": "bad-wings", "source": "https://github.com/b"}]"""
            )
        }
        assertTrue("bad-wings" in error.message!!, error.message)
    }

    /**
     * `/designs/families/compatible` is also `/designs/{slug}/compatible` with the slug
     * "families", and Spring picks the route with the longer literal part -- the design's
     * (checked against the dev server for the singular route, 2026-10-03: "no design with
     * slug 'family'"). A family named "Compatible" would have a page nobody could reach.
     */
    @Test
    fun `a family whose slug is a word the design routes take is refused`() {
        val error = assertThrows<IllegalStateException> {
            catalogOf("""[{"name": "Compatible", "source": "https://github.com/a"}]""")
        }
        assertTrue("'compatible'" in error.message!!, error.message)
    }

    /**
     * THE SHIPPED FILES AGREE. `build_seed.py` writes both from `seed/families.toml` in one
     * run, so a mismatch is a stale file: a family with no page, or a page whose name no
     * design carries. A family of one would be a page comparing a board with nothing.
     */
    @Test
    fun `every shipped family names at least two designs, and every design's family has a record`() {
        val families = FamilyCatalog(mapper, ClassPathResource("seed/families.json")).all()
        val designs = SeedDesignCatalog(mapper, ClassPathResource("seed/designs.json")).all()
        val carried = designs.mapNotNull { it.extras.family.knownOrNull() }.groupingBy { it }.eachCount()

        assertTrue(families.isNotEmpty(), "no families shipped")
        assertEquals(carried.keys, families.map { it.name }.toSet())
        families.forEach { assertTrue((carried[it.name] ?: 0) >= 2, "${it.name.value}: fewer than two designs") }
    }
}
