package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Facets
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forgejo #7: `contract.md` §3's shape, rendered. Four rules, and the divider is the one
 * carrying the design.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacetRailTest {

    @Autowired lateinit var mockMvc: MockMvc


    private fun page(query: String = "") =
        mockMvc.get("/designs$query").andReturn().response.contentAsString

    /** The markup of one group, so an assertion cannot accidentally match another. */
    private fun group(html: String, heading: String) =
        Regex("<summary>$heading</summary>.*?</details>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.value

    private fun rows(group: String) =
        Regex("<li[^>]*>.*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(group)
            .map { it.value }.toList()

    private fun text(row: String) =
        row.replace(Regex("<[^>]*>"), " ").replace("&#10003;", "").split(Regex("\\s+"))
            .filter { it.isNotBlank() }.joinToString(" ")

    // -- 1. not_applicable is an ordinary value, above the divider ---------------------

    @Test
    fun `not applicable sits above the divider as an ordinary selectable value`() {
        // "Controller socket" since 2026-09-10 -- every value in this group is a
        // footprint, and the old "Controller board" sent the owner looking for a
        // nice!nano in it. The param is unchanged, so the assertions below still read
        // `controller_footprint`.
        val controller = group(page(), "Controller socket")
        val above = controller.substringBefore("<hr")

        assertTrue("MCU soldered on" in above, "not_applicable is not above the divider")
        assertTrue("""href="/designs?controller_footprint=not_applicable"""" in above,
            "not_applicable must be selectable like any other value")
    }

    // -- 2. Its label is the facet's own words -----------------------------------------

    @Test
    fun `not applicable never renders as the words not applicable`() {
        val html = page()

        assertFalse("not applicable" in html.lowercase().replace("not_applicable", ""),
            "the words 'not applicable' reached the page")
        assertTrue("MCU soldered on" in html, "the facet's own wording is missing")
    }

    // -- 3. unknown sits below the divider, excluded by default ------------------------

    @Test
    fun `not recorded sits below the divider`() {
        val pitch = group(page(), "Key spacing")

        assertTrue("<hr" in pitch, "no divider")
        assertTrue("Not recorded" in pitch.substringAfter("<hr"), "unknown is not below it")
        assertFalse("Not recorded" in pitch.substringBefore("<hr"), "unknown leaked above it")
    }

    @Test
    fun `not recorded is excluded by default and one click includes it`() {
        // THE PRINTED TOTAL, NOT THE CARDS ON THE PAGE. Counting design links stops at a
        // page's 50 cards, so once `pitch=choc` alone fills a page (it went 27 -> 50 when
        // the pitch scan was re-run, backlog #104) widening the filter could not show up.
        val before = total(page("?pitch=choc"))
        val link = Regex("""href="([^"]*)"[^>]*>\s*Not recorded""")
            .find(group(page("?pitch=choc"), "Key spacing"))!!.groupValues[1]
        val after = total(mockMvc.get(link).andReturn().response.contentAsString)

        assertTrue(after > before, "including the unrecorded ones did not widen the result")
    }

    /** The result count the page prints, as `FilteringTest` reads it. */
    private fun total(html: String) =
        Regex("""(\d+) designs""").find(html)!!.groupValues[1].toInt()

    // -- 4. Zero-count options are disabled, not hidden --------------------------------

    /**
     * Hiding them leaves headed cards with nothing inside, which teach nothing and read as
     * a rendering failure. A disabled row says "this exists and your other filters exclude
     * it", which is a fact worth having.
     */
    @Test
    fun `a zero-count option is rendered, disabled, and carries the reason`() {
        val body = group(page(), "Body")
        val zero = rows(body).single { "Not recorded" in it }

        assertTrue("class=\"off\"" in zero, "a zero-count option was not disabled: $zero")
        assertFalse("<a " in zero, "a disabled option must not be a link")
        assertTrue("title=" in zero, "a disabled option must say why")
    }

    /** However small its count, the reader's own filter stays clickable or they cannot undo it. */
    @Test
    fun `a selected option is never disabled`() {
        val pitch = group(page("?pitch=choc"), "Key spacing")
        val chosen = rows(pitch).single { "aria-current" in it }

        assertFalse("class=\"off\"" in chosen)
        assertTrue("<a " in chosen, "the reader could not click their own filter off")
    }

    // -- The rail is a rail, not a ratchet ---------------------------------------------

    @Test
    fun `a selected group keeps its siblings at their unfiltered counts`() {
        val unfiltered = text(rows(group(page(), "Key spacing")).single { "MX" in it })
        val filtered = text(rows(group(page("?pitch=choc"), "Key spacing")).single { "MX" in it })

        assertEquals(unfiltered, filtered, "self-exclusion failed; the group is a dead end")
    }

    @Test
    fun `another group is narrowed by the selection`() {
        val all = text(rows(group(page(), "Body")).single { "Split" in it })
        val narrowed = text(rows(group(page("?pitch=choc"), "Body")).single { "Split" in it })

        assertTrue(all != narrowed, "an unselected group ignored the filter")
    }

    @Test
    fun `clicking a selected option removes it`() {
        val pitch = group(page("?pitch=choc"), "Key spacing")
        val off = Regex("""href="([^"]*)"[^>]*aria-current""").find(pitch)!!.groupValues[1]

        assertEquals("/designs", off, "the checked option did not link to its own removal")
    }

    // -- extensions & ports: a presence facet, and its gap is ordinary ignorance -------

    /**
     * Backlog #66. The owner's ruling, 2026-09-07: *"I don't think users will search for
     * 'no encoder'. but yes, add a 'has encoder' checkbox to the rails."*
     */
    @Test
    fun `optional hardware offers encoder and display as things to include`() {
        val ext = group(page(), "Extensions &amp; ports")

        assertTrue("Encoder" in ext, "no encoder checkbox")
        assertTrue("Display" in ext, "no display checkbox")
        assertTrue("""href="/designs?ext=encoder"""" in ext,
            "the encoder option does not select itself")
    }

    /**
     * #41's sparsity finding, applied. Speaker (6 designs), haptic (5), trackball (2) and
     * trackpad (1) get no CHECKBOX — but see the next test: they are named and linked.
     */
    @Test
    fun `the four sparse extensions get no checkbox`() {
        val checkboxes = rows(group(page(), "Extensions &amp; ports")).joinToString(" ")

        listOf("Speaker", "Haptic", "Trackball", "Trackpad").forEach {
            assertFalse(it in checkboxes, "$it took a rail row it does not earn")
        }
    }

    /**
     * `contract.md` §3: *"A truncated value list says so. Top-8-by-count, chosen values
     * never cut, and the remainder stated."* The first version of this group showed two
     * of seven values and stated nothing, so four extensions were
     * reachable only by hand-typing a URL nothing on the site mentions.
     */
    @Test
    fun `what the group leaves out is named, counted and linked`() {
        val more = Regex("<p class=\"facet-more\">.*?</p>", RegexOption.DOT_MATCHES_ALL)
            .find(group(page(), "Extensions &amp; ports"))
            ?.value

        assertNotNull(more, "the group truncates its value list and says nothing")
        listOf("Speaker", "Haptic", "Trackball", "Trackpad")
            .forEach { assertTrue(it in more, "$it is left out without being stated") }
        assertTrue("""href="/designs?ext=trackball"""" in more,
            "the remainder is stated but not reachable")
    }

    /**
     * THE PARTS A BUILDER HOLDS, in the owner's order (2026-10-02): Any, Kailh socket,
     * Gateron socket, Mill-Max. Solder-only boards are a measured answer, so they are
     * reachable on the remainder line rather than lumped into "Not recorded".
     */
    @Test
    fun `hotswap offers any and each part, and names solder-only boards on the remainder line`() {
        val hotswap = group(page(), "Hotswap")
        val more = Regex("<p class=\"facet-more\">.*?</p>", RegexOption.DOT_MATCHES_ALL)
            .find(hotswap)?.value

        assertEquals(listOf("Any", "Kailh socket", "Gateron socket", "Mill-Max"),
            rows(hotswap.substringBefore("<hr")).map { text(it).replace(Regex(" \\d+$"), "") })
        assertNotNull(more, "solder-only boards exist and the group does not say so")
        assertTrue("No hotswap" in more)
        assertTrue("""href="/designs?hotswap=no"""" in more)
    }

    @Test
    fun `the retired switch socket param redirects permanently to hotswap`() {
        for ((old, now) in listOf("hotswap" to "any", "millmax" to "millmax")) {
            val response = mockMvc.get("/designs?switch_socket=$old&q=sweep").andReturn().response

            assertEquals(301, response.status, old)
            assertEquals("/designs?hotswap=$now&q=sweep", response.getHeader("Location"))
        }
    }

    /**
     * BACKLOG #107, against the real seed: the six Sweep PCBs read as six unrelated
     * keyboards until a page said they are one family. Sweep 2's page names the family
     * beside its title and links the family page that lists them all -- owner, 2026-10-04:
     * "**Corne Chocolate v4** from the Corne family", no count.
     */
    @Test
    fun `a Sweep page names its family and links the family page`() {
        val html = mockMvc.get("/designs/sweep-2").andReturn().response.contentAsString
        val family = Regex("<p class=\"family\">.*?</p>", RegexOption.DOT_MATCHES_ALL).find(html)?.value

        assertNotNull(family, "the Sweep 2 page does not name its family")
        assertEquals(
            """<p class="family">from the <a href="/designs/families/sweep">Sweep family</a></p>""",
            family.replace(Regex("""\s+"""), " "),
        )
    }

    /** Never withdraw the control representing the reader's own filter — `contract.md` §3. */
    @Test
    fun `a sparse fitting selected by URL is promoted to a checkbox`() {
        val ext = group(page("?ext=trackball"), "Extensions &amp; ports")

        assertTrue("Trackball" in rows(ext).joinToString(" "),
            "a chosen value was left on the remainder line instead of becoming a checkbox")
    }

    /**
     * AND IT COUNTS THE DESIGNS IT ACTUALLY COVERS. The point of moving these back below
     * the divider is that "Not recorded" is now TRUE of them: we have not established
     * whether the Dilemma has room for a trackpad, and its own firmware says we are
     * wrong to imply otherwise.
     */
    @Test
    fun `the gap row counts every design we have no evidence for`() {
        val row = rows(group(page(), "Extensions &amp; ports")).single { "Not recorded" in it }

        assertTrue(
            Regex("Not recorded\\D*(\\d+)").find(text(row))!!.groupValues[1].toInt() > 100,
            "the gap row does not count the designs it covers: ${text(row)}",
        )
    }

    /**
     * A HAND-CURATED LIST IS AN ORDER, like a band's. `railValues` names encoder first
     * because 84 beats 38 over the whole corpus; re-sorting by the CURRENT count made the
     * pair swap places as a reader narrowed — `?license=beerware` rendered
     * `Display 1, Encoder 0`.
     */
    @Test
    fun `the curated pair keeps its order when a filter reverses the counts`() {
        val narrowed = rows(group(page("?license=beerware"), "Extensions &amp; ports"))
            .map { text(it) }.filter { "Encoder" in it || "Display" in it }

        assertTrue(narrowed.size == 2, "expected both rows, got $narrowed")
        assertTrue("Encoder" in narrowed[0] && "Display" in narrowed[1],
            "the curated order re-sorted itself by count: $narrowed")
    }

    /**
     * THE ROW THAT SHIPPED WRONG TWICE, and this is the shape that survived measurement.
     * It was suppressed (dishonest at `?ext=unknown`), then made an ordinary
     * `not_applicable` value reading "No optional hardware" — which asserted a fact the
     * instrument cannot establish, and 11 designs' own QMK records contradicted. A regex
     * over footprint names proves presence only, so the 223 are ignorance and belong
     * exactly where every other facet's ignorance goes: below the divider.
     */
    @Test
    fun `designs we found no evidence for sit below the divider as not recorded`() {
        val ext = group(page(), "Extensions &amp; ports")

        assertTrue("<hr" in ext, "no divider")
        assertTrue("Not recorded" in ext.substringAfter("<hr"), "the gap is not below it")
        assertFalse("No optional hardware" in ext,
            "an absence the scan cannot prove is being stated as an answer")
    }

    // -- splay: a genuine yes/no, unlike extensions and switch sockets above -----------

    /**
     * The shape that sets splay apart from `Extensions & ports` and `Hotswap`
     * just above: `no` is a real measured verdict (`measure_splay.py`), so it belongs
     * ABOVE the divider as an ordinary answer, not folded into "Not recorded".
     */
    @Test
    fun `splay offers both a measured yes and a measured no, above the divider`() {
        val splay = group(page(), "Splay")
        val above = splay.substringBefore("<hr")

        assertTrue("Splayed" in above, "the splayed checkbox is missing")
        assertTrue("Not splayed" in above, "a measured no was not given its own row")
        assertTrue("""href="/designs?splay=yes"""" in above, "splayed does not select itself")
        assertTrue("""href="/designs?splay=no"""" in above, "not-splayed does not select itself")
    }

    @Test
    fun `designs nobody measured still sit below splay's own divider`() {
        val splay = group(page(), "Splay")

        assertTrue("<hr" in splay, "no divider")
        assertTrue("Not recorded" in splay.substringAfter("<hr"), "the gap is not below it")
    }

    @Test
    fun `filtering by splayed actually narrows the result set`() {
        val before = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page()).count()
        val after = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page("?splay=yes")).count()

        assertTrue(after in 1..<before, "splay=yes did not narrow the corpus: $after of $before")
    }

    // -- power switch: a measured yes/no, same shape as splay -------------------------

    @Test
    fun `power switch offers yes before no, above the divider, and narrows the list`() {
        val group = group(page(), "Power switch")
        val above = group.substringBefore("<hr")

        assertTrue(above.indexOf("power_switch=yes") in 0..<above.indexOf("power_switch=no"),
            "yes is missing, or buried under no")
        assertTrue("No power switch" in above, above)
        val before = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page()).count()
        val after = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page("?power_switch=yes")).count()
        assertTrue(after in 1..<before, "power_switch=yes did not narrow the corpus: $after of $before")
    }

    // -- exposed components: a measured yes/no, same shape as splay ------------------

    @Test
    fun `exposed components offers a measured yes and no, above the divider`() {
        val group = group(page(), "Exposed components")
        val above = group.substringBefore("<hr")

        assertTrue("""href="/designs?exposed_components=yes"""" in above, "exposed does not select itself")
        assertTrue("""href="/designs?exposed_components=no"""" in above, "not exposed does not select itself")
        assertTrue("Not recorded" in group.substringAfter("<hr"), "the unmeasured are not below the divider")
        assertTrue(above.indexOf("exposed_components=yes") < above.indexOf("exposed_components=no"),
            "count-sorting buried the answer a reader came for under 'Not exposed'")
    }

    @Test
    fun `filtering by exposed components actually narrows the result set`() {
        val before = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page()).count()
        val after = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page("?exposed_components=yes")).count()

        assertTrue(after in 1..<before, "exposed_components=yes did not narrow the corpus: $after of $before")
    }

    // -- stagger: same shape as splay just above, a genuine three-way verdict ----------

    /**
     * Same case as `splay`'s own test just above, for all three kinds: each is a real
     * measured verdict (`measure_stagger.py`), so all three belong ABOVE the divider as
     * ordinary answers, not folded into "Not recorded".
     */
    @Test
    fun `stagger offers ortho, row and column, above the divider`() {
        val stagger = group(page(), "Stagger")
        val above = stagger.substringBefore("<hr")

        assertTrue("Ortholinear" in above, "the ortho checkbox is missing")
        assertTrue("Row-staggered" in above, "the row checkbox is missing")
        assertTrue("Column-staggered" in above, "the column checkbox is missing")
        assertTrue("""href="/designs?stagger=ortho"""" in above, "ortho does not select itself")
        assertTrue("""href="/designs?stagger=row"""" in above, "row does not select itself")
        assertTrue("""href="/designs?stagger=column"""" in above, "column does not select itself")
    }

    @Test
    fun `designs nobody measured still sit below stagger's own divider`() {
        val stagger = group(page(), "Stagger")

        assertTrue("<hr" in stagger, "no divider")
        assertTrue("Not recorded" in stagger.substringAfter("<hr"), "the gap is not below it")
    }

    @Test
    fun `filtering by row-staggered actually narrows the result set`() {
        // `row`, not `column`: column-stagger is 114 designs, over the results page's own
        // 50-row cap, so both counts would read 50 and prove nothing about narrowing.
        val before = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page()).count()
        val after = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page("?stagger=row")).count()

        assertTrue(after in 1..<before, "stagger=row did not narrow the corpus: $after of $before")
    }

    // -- split link: backlog #97, so a reader can leave TRRS out ------------------------

    /**
     * The owner's own case: some builders avoid TRRS because unplugging it while powered
     * can damage a controller. They tick every option but TRRS, and a one-piece board is
     * one of those options -- it has no cable to unplug at all.
     */
    @Test
    fun `split link offers every connector and one piece, and no none`() {
        val link = group(page(), "Split link")
        val flat = link.replace(Regex("\\s+<"), "<").replace(Regex(">\\s+"), ">")

        for (label in listOf("TRRS", "USB-C", "Ethernet", "Mini-DIN (S-Video)", "No cable: one piece")) {
            assertTrue(">$label<" in flat, "the $label checkbox is missing")
        }
        // A name-matching scan's silence is ignorance, so those splits sit under "Not
        // recorded" with every other split nobody read -- 2026-09-29, Pando.
        assertFalse("None on the board" in flat, "an empty scan is still offered as a value")
        assertTrue("""href="/designs?link=ethernet"""" in link, "ethernet does not select itself")
    }

    @Test
    fun `the retired none link value redirects permanently to not recorded`() {
        val response = mockMvc.get("/designs?link=none").andReturn().response

        assertEquals(301, response.status)
        assertEquals("/designs?link=unknown", response.getHeader("Location"))
    }

    /**
     * US ENGLISH, owner 2026-10-01: the site now says "License", so the URL does too -- the
     * param and the label are one vocabulary. `?license=` was live and indexed, so it moves
     * with a 301 and keeps every other parameter as it was.
     */
    @Test
    fun `the British licence param redirects permanently to license`() {
        val response = mockMvc.get("/designs?licence=mit&q=sweep").andReturn().response

        assertEquals(301, response.status)
        assertEquals("/designs?license=mit&q=sweep", response.getHeader("Location"))
    }

    @Test
    fun `filtering by an Ethernet link narrows to the splits that place an RJ45 jack`() {
        val after = Regex("""href="/designs/[a-z0-9-]+"""").findAll(page("?link=ethernet")).count()

        assertTrue(after in 1..20, "link=ethernet did not narrow the corpus: $after")
    }

    @Test
    fun `a board offering TRRS or USB-C is found by either`() {
        val trrs = page("?link=trrs")
        val usbC = page("?link=usb_c")

        assertTrue("""href="/designs/stront-mx-38"""" in trrs, "stront-mx-38 is missing under TRRS")
        assertTrue("""href="/designs/stront-mx-38"""" in usbC, "stront-mx-38 is missing under USB-C")
    }
}
