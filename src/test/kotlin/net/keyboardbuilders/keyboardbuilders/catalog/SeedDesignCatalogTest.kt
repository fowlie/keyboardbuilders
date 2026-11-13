package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
import net.keyboardbuilders.keyboardbuilders.domain.BoardOutline
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.HostPort
import net.keyboardbuilders.keyboardbuilders.domain.UsbPort
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.domain.SplitLink
import net.keyboardbuilders.keyboardbuilders.domain.Stagger
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the real `seed/designs.json`. Every assertion here is about a property the
 * importer must hold whatever the data says -- never about a particular keyboard,
 * because the file is regenerated whenever a source improves.
 */
@SpringBootTest
// Unused here, but the same annotations as the web tests put this class in their cached
// Spring context instead of starting a second one (#114).
@AutoConfigureMockMvc
class SeedDesignCatalogTest {

    // By qualifier: the whole seed is not a default candidate since #116.
    @Autowired @Qualifier(DesignCatalogs.SEED)
    lateinit var catalog: SeedDesignCatalog

    /**
     * The real assertion is that the constructor did not throw. Every record ran
     * `DesignCore`'s init checks and every value class its own, so a build that
     * emits a blank name or a zero key count fails here rather than on a page.
     */
    @Test
    fun `the shipped seed loads and is not empty`() {
        assertTrue(catalog.all().size > 100, "expected the full seed, got ${catalog.all().size}")
    }

    @Test
    fun `every design is reachable by its own slug`() {
        catalog.all().forEach { design ->
            assertNotNull(catalog.bySlug(design.core.slug.value), "unreachable: ${design.core.slug}")
        }
    }

    /**
     * `/designs/families` and `/designs/compare` are pages of their own beside
     * `/designs/{slug}`, and Spring hands a literal segment to its own route. A design
     * slugged "families" or "compare" would have a page nobody could reach, so the catalog
     * refuses to load one -- the site does not start, which is the build refusing.
     */
    @Test
    fun `a design slug that names another page under designs is refused`() {
        val mapper = jacksonObjectMapper()
        val one = ClassPathResource("seed/designs.json").inputStream.use { mapper.readTree(it) }.first() as ObjectNode
        for (word in listOf("families", "compare")) {
            val json = "[" + one.deepCopy().put("slug", word).toString() + "]"
            val error = assertThrows<IllegalStateException> {
                SeedDesignCatalog(mapper, ByteArrayResource(json.toByteArray()))
            }
            assertTrue("'$word'" in error.message!!, error.message)
        }
        // The same record under its own slug loads, so the refusal is the word and nothing else.
        assertEquals(1, SeedDesignCatalog(mapper, ByteArrayResource("[$one]".toByteArray())).all().size)
    }

    @Test
    fun `slugs are unique, because a slug is the page's address`() {
        val slugs = catalog.all().map { it.core.slug.value }
        assertTrue(slugs.size == slugs.toSet().size, "duplicate slugs in the seed")
    }

    /**
     * `silhouettes.json` is keyed by design slug, so the two files are only joinable
     * if they came from the same build. Renaming designs without re-running
     * `build_silhouettes.py` left 91 of 124 drawing the dashed placeholder, and
     * nothing failed -- a missing silhouette is a legitimate state, so absence alone
     * cannot be the alarm. A key naming a design that does not exist can be.
     */
    @Test
    fun `every silhouette names a design that exists, so the two seeds cannot drift`() {
        val mapper = jacksonObjectMapper()
        val drawn: Map<String, Any> = ClassPathResource("seed/silhouettes.json")
            .inputStream.use { mapper.readValue(it) }
        val slugs = catalog.all().map { it.core.slug.value }.toSet()
        val orphans = drawn.keys - slugs

        assertTrue(
            orphans.isEmpty(),
            "silhouettes.json is stale -- ${orphans.size} of ${drawn.size} name no design " +
                "(${orphans.take(5)}). Re-run scripts/build_silhouettes.py.",
        )
    }

    @Test
    fun `a slug that is not in the seed resolves to nothing`() {
        assertNull(catalog.bySlug("definitely-not-a-keyboard"))
    }

    /**
     * The importer records ignorance rather than inventing values: reversibility was
     * measured unreadable for 103 of 106 splits, so it is `Unknown` except where a PERSON
     * ruled it in seed/bodies.toml. Those designs are named here, so a reversibility from
     * anywhere else still fails. Ferris, 2026-10-01: chip and USB on the left half, the
     * MCP23017 IO expander on the right, so two different PCBs. Architeuthis Dux, 2026-10-02:
     * one hand per board file, socket pads on both faces, so one PCB for both hands.
     */
    @Test
    fun `the importer never invents a reversibility`() {
        val ruledByAPerson = setOf(
            "ferris-0-1-base", "ferris-0-1-compact", "ferris-0-1-high", "ferris-0-1-low",
            "ferris-0-2-bling", "ferris-0-2-compact", "ferris-0-2-high", "ferris-0-2-mini",
            "architeuthis-dux",
        )
        val known = catalog.all().mapNotNull { design ->
            val body = design.core.body
            if (body is Body.Split && body.reversible !is Recorded.Unknown) design.core.slug.value else null
        }.toSet()

        assertEquals(ruledByAPerson, known, "a reversibility nobody recorded, or a ruling that no longer lands")
    }

    /**
     * Backlog #18: `hardestComponent` replaced a field the importer used to see as
     * `Unknown` on every design, by construction (`build_seed.py` wrote the old
     * `soldering` key as `null` unconditionally). This is the sanity check for the
     * wiring rather than a property that must hold "whatever the data says" like the
     * rest of this file -- if it ever again reads `Unknown` on every design, the wire
     * format and the domain have drifted apart the same way they did before #18.
     */
    @Test
    fun `hardestComponent is actually populated for most of the shipped seed`() {
        val known = catalog.all().count { design ->
            val method = design.core.buildMethod
            method is net.keyboardbuilders.keyboardbuilders.domain.BuildMethod.Pcb &&
                method.hardestComponent is Recorded.Known
        }
        assertTrue(known > catalog.all().size / 2,
            "expected most designs to carry a measured hardest component, got $known of ${catalog.all().size}")
    }

    /**
     * A SHARED QMK RECORD CARRIES A CHIP ONLY WHERE THE DESIGN SOLDERS THAT CHIP. 67 of the
     * corpus's designs cite a record another design also cites, and `ianklug/grooveboard`
     * is nine designs on one record spanning both atmega and RP2040 hardware -- so its
     * `processor` is a fact about the record and about none of them in general. This is
     * the guard, asserted against the real seed: if the extractor ever stops withholding,
     * the same chip appears on designs whose own boards contradict it.
     *
     * The one exception (2026-10-03, `build_seed.shared_record_target`): a soldered design
     * whose own board names the chip the record is built for. The build also demands the
     * board be the witness, which the catalogue cannot see; what it can see, and what this
     * asserts, is that every such target agrees with the design's own soldered MCU, and
     * that no socketed design ever carries one.
     */
    @Test
    fun `a chip is attributed from a shared QMK record only to a design soldering that chip`() {
        val owners = catalog.all()
            .flatMap { d -> d.core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>()
                .mapNotNull { it.keyboardPath }.map { it to d.core.slug.value } }
            .groupBy({ it.first }, { it.second })

        val leaked = catalog.all().flatMap { d ->
            val soldered = (d.core.controller as? Controller.Soldered)?.mcu?.knownOrNull()
            d.core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>().filter {
                val target = it.targetMcu.knownOrNull()
                target != null && (owners[it.keyboardPath]?.size ?: 0) > 1 && target != soldered
            }.map { "${d.core.slug.value} <- ${it.keyboardPath}" }
        }

        assertTrue(leaked.isEmpty(),
            "a shared record's chip was attributed to a design that does not solder it: ${leaked.take(5)}")
    }

    /**
     * THE EIGHTEEN, BY NAME. The guard above can only check that a shared record's target
     * equals the design's soldered MCU; it cannot see the build's stricter half, that the
     * design's own board (not a QMK fallback) is the witness for that MCU. That half is
     * tested only in scripts/test_build_seed.py, which nothing in this suite runs. So the
     * set it produced on 2026-10-03 is pinned here: a silent widening (a fallback MCU
     * agreeing with its own record, say) adds a slug and fails, and a narrowing drops one.
     * A new soldered design under a shared record is a legitimate reason to edit the list,
     * after checking its chip was read from its own board.
     */
    @Test
    fun `exactly the eighteen soldered designs take a target from a shared QMK record`() {
        val owners = catalog.all()
            .flatMap { d -> d.core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>()
                .mapNotNull { it.keyboardPath }.map { it to d.core.slug.value } }
            .groupBy({ it.first }, { it.second })

        val targeted = catalog.all().filter { d ->
            d.core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>().any {
                it.targetMcu is Recorded.Known && (owners[it.keyboardPath]?.size ?: 0) > 1
            }
        }.map { it.core.slug.value }.toSortedSet()

        assertEquals(
            sortedSetOf(
                "atom47-rev5", "atom47-rev5-c", "bw2-v2", "corne-cherry-v4", "corne-chocolate-v4",
                "dilemma-v2", "dilemma-v3-hotswap",
                "ferris-0-1-base", "ferris-0-1-compact", "ferris-0-1-high", "ferris-0-1-low",
                "grooveboard-atmel-au", "grooveboard-atmel-mu", "grooveboard-choc-atmel-au",
                "grooveboard-choc-atmel-mu", "grooveboard-mx-atmel-au", "grooveboard-mx-atmel-mu",
                "pisces-v2",
            ),
            targeted,
        )
    }

    /** The grooveboard case both ways round: an Atmel board under the shared record gains
     *  the record's chip, and a socketed design under a shared record (cradio) does not. */
    @Test
    fun `a soldered grooveboard names its shared record's target and a socketed cradio does not`() {
        fun targets(slug: String) = assertNotNull(catalog.all().find { it.core.slug.value == slug })
            .core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>().map { it.targetMcu }
        assertEquals(listOf<Recorded<Mcu>>(Recorded.Known(Mcu.Atmega32u4)), targets("grooveboard-atmel-au"))
        assertEquals(listOf<Recorded<Mcu>>(Recorded.Unknown), targets("cradio"))
    }

    /** The widened `Mcu` is only worth having if the seed actually uses it beyond the
     *  three it held before — otherwise the enum grew and nothing was unblocked. */
    @Test
    fun `the corpus names chips the old three-member enum could not hold`() {
        val old = setOf(Mcu.Rp2040, Mcu.Atmega32u4, Mcu.Nrf52840)
        val named = catalog.all().mapNotNull {
            (it.core.controller as? Controller.Soldered)?.mcu?.knownOrNull()
        }.toSet()

        assertTrue((named - old).isNotEmpty(),
            "every chip in the corpus fits the old enum, so the widen bought nothing")
    }

    /**
     * The sanity check for the `splay` wiring, same shape as `hardestComponent`'s above:
     * if this ever reads `Unknown` on every design, the wire format and the domain have
     * drifted apart. Measured 2026-09-23 over the shipped seed: 26 splayed, 177 measured
     * not splayed, of 328 designs -- both real values must show up, not just evidence.
     */
    @Test
    fun `splay is measured for a real share of the shipped seed, both yes and no`() {
        val verdicts = catalog.all().mapNotNull { it.extras.splay.knownOrNull() }

        assertTrue(verdicts.isNotEmpty(), "no design carries a measured splay verdict")
        assertTrue(verdicts.any { it }, "no design measured splayed")
        assertTrue(verdicts.any { !it }, "no design measured not-splayed -- looks like evidence-only")
    }

    /**
     * Same shape as the `splay` check just above, for `stagger`: all three [Stagger]
     * values must show up, not just one or two, or the wire format and the domain have
     * drifted apart. Measured 2026-09-24 over the shipped seed: 70 ortho, 33
     * row-staggered, 102 column-staggered, of 328 designs.
     */
    @Test
    fun `stagger is measured for a real share of the shipped seed, all three kinds`() {
        val verdicts = catalog.all().mapNotNull { it.extras.stagger.knownOrNull() }

        assertTrue(verdicts.isNotEmpty(), "no design carries a measured stagger verdict")
        assertTrue(verdicts.any { it == Stagger.Ortho }, "no design measured ortho")
        assertTrue(verdicts.any { it == Stagger.Row }, "no design measured row-staggered")
        assertTrue(verdicts.any { it == Stagger.Column }, "no design measured column-staggered")
    }

    /**
     * Owner, 2026-10-04: "exposed components", "like Num Discipline, Un Split or mysterium"
     * -- diodes and parts laid out on top, where the finished keyboard shows them.
     * `measure_exposed_components.py` reads it off where each board places its switch
     * diodes, so both answers are measured ones, and the owner's three examples are yes.
     */
    @Test
    fun `exposed components is measured both ways, and the owner's three examples are yes`() {
        val verdicts = catalog.all().mapNotNull { it.extras.exposedComponents.knownOrNull() }

        assertTrue(verdicts.any { it }, "no design measured with exposed components")
        assertTrue(verdicts.any { !it }, "no design measured without -- looks like evidence-only")
        for (slug in listOf("num-discipline", "un-split", "mysterium")) {
            assertEquals(true, catalog.bySlug(slug)?.extras?.exposedComponents?.knownOrNull(), slug)
        }
    }

    /**
     * Owner, 2026-10-04: Dilemma MAX and orthocode are not exposed, though the measurement
     * says yes. `seed/exposed-components-overrides.toml` carries the rulings; this holds them.
     */
    @Test
    fun `Dilemma MAX and orthocode are not exposed, as their owner-ruled overrides say`() {
        for (slug in listOf("dilemma-max", "orthocode")) {
            assertEquals(false, catalog.bySlug(slug)?.extras?.exposedComponents?.knownOrNull(), slug)
        }
    }

    /**
     * Owner, 2026-10-04: an on/off switch, read off the board, yes/no/not recorded.
     * `measure_power_switch.py` reads it by footprint name; the Sweep's MSK-12 and
     * KLOR's are yes, a wired Corne-shaped board like 3w6 is no.
     */
    @Test
    fun `the power switch is measured both ways, against boards whose answer is known`() {
        fun of(slug: String) = catalog.bySlug(slug)?.extras?.powerSwitch?.knownOrNull()

        assertEquals(true, of("sweep-2"))
        assertEquals(true, of("klor"))
        assertEquals(false, of("3w6"))
    }

    /**
     * Owner, 2026-10-04: measure the board size now. `measure_board_size.py` gives each
     * board's size where the outline is surely that board, what one file holds, and a
     * split's halves apart where they differ. Corne v4 joins its halves by tabs in one
     * outline, so no half's size is read and it stays Unknown.
     */
    @Test
    fun `board sizes come from the seed, one half's for a split, and an unread one stays unread`() {
        fun of(slug: String) = catalog.bySlug(slug)!!.extras

        assertEquals(Recorded.Known(BoardSize.Each(BoardOutline(142.52, 106.33))), of("lily58-pro").boardSize)
        assertEquals(Recorded.Known(BoardDrawn.Half), of("lily58-pro").boardDrawn)
        assertEquals(Recorded.Unknown, of("corne-chocolate-v4").boardSize)
        assertEquals(Recorded.Known(BoardDrawn.BothHalves), of("corne-chocolate-v4").boardDrawn)
        assertEquals(Recorded.Known(BoardSize.Halves(BoardOutline(124.42, 80.37), BoardOutline(129.18, 80.37))),
            of("fourier").boardSize)
        assertTrue(catalog.all().count { it.extras.boardSize is Recorded.Known } > 250)
    }

    /**
     * Owner, 2026-10-04: Lily58 Lite Rev3 showed as ortho, "but it's column-staggered". Its
     * board file is drawn a quarter turn round, so the measured "rows" were its columns;
     * `seed/stagger-overrides.toml` carries the ruling, and this holds it through rebuilds.
     */
    @Test
    fun `Lily58 Lite Rev3 is column-staggered, as its owner-corrected override says`() {
        assertEquals(Stagger.Column, catalog.bySlug("lily58-lite-rev3")?.extras?.stagger?.knownOrNull())
    }

    /**
     * Backlog #97: a split's link is read beyond the TRRS jack, and a board may offer
     * more than one. Measured 2026-09-25 over 124 splits: 6 place an RJ45 jack, 2
     * socketed splits link over USB-C alone, and 4 offer TRRS or USB-C (Stront MX-38 and
     * Choc, Pica40, Lotus 58). Every value must show up, or the wire format and the
     * domain have drifted apart.
     */
    @Test
    fun `split links are read beyond TRRS, and a board may offer two`() {
        val links = catalog.all().mapNotNull { (it.core.body as? Body.Split)?.links?.knownOrNull() }

        assertTrue(links.any { SplitLink.Trrs in it }, "no split measured with a TRRS link")
        assertTrue(links.any { SplitLink.Ethernet in it }, "no split measured with an Ethernet link")
        assertTrue(links.any { SplitLink.UsbC in it }, "no split measured with a USB-C link")
        assertTrue(links.any { it.size > 1 }, "no split offers two links -- the set is not wired through")
    }

    /**
     * 2026-09-29. The link scan matches connector NAMES, so it proves a link and never
     * its absence: Pando's board read as having none while its halves plug together over
     * USB-C. An empty reading is therefore ignorance -- `Unknown` -- and never a known
     * empty set, which the page used to print as "none on the board".
     */
    @Test
    fun `a split whose board places no connector we can name has an unrecorded link`() {
        val splits = catalog.all().mapNotNull { d -> (d.core.body as? Body.Split)?.let { d.core.slug.value to it.links } }

        assertTrue(splits.none { (_, l) -> l.knownOrNull()?.isEmpty() == true },
                   "a split still reads as a known empty link: ${splits.filter { it.second.knownOrNull()?.isEmpty() == true }.map { it.first }}")
    }

    /**
     * 2026-09-29. One chip drives both halves through an I/O expander, so the host needs
     * one USB-C socket and the rest can only be the link: Pando places two on its left
     * board and one on its right, the 3w6 three on its one panel. Both read as no link
     * until then, because on a soldered split the host port and the link are one footprint.
     */
    /**
     * 2026-09-29. Pando's own QMK record declares `"processor": "STM32G0B1"`, a chip the
     * list did not have, so its page said "chip not recorded" while its README names it.
     * Named by a source, so added -- the nearest-member fit is still refused.
     */
    @Test
    fun `pando's soldered chip is the STM32G0B1 its record declares`() {
        assertEquals(Controller.Soldered(Recorded.Known(Mcu.Stm32g0b1)), catalog.bySlug("pando")!!.core.controller)
    }

    /** Backlog #107 step 9: Hillside's README says "Mill-Max hotswap sockets, but not the
     *  Kailh sockets", and its pins are drilled 1.57 mm. The old name scan said Kailh. */
    @Test
    fun `hillside 46 takes Mill-Max, not Kailh sockets`() {
        assertEquals(Recorded.Known(setOf(SwitchMount.Solder, SwitchMount.MillMax)),
                     catalog.bySlug("hillside-46")!!.extras.switchMounts)
    }

    /** Ergoslab's 42 MX switches sit in a footprint whose name gives no family. */
    @Test
    fun `ergoslab takes MX as well as Choc v1`() {
        assertEquals(setOf(SwitchFootprint.ChocV1, SwitchFootprint.Mx), catalog.bySlug("ergoslab")!!.core.switches)
    }

    /** Forgejo #132: Wave's halves join over a 4-pin Mini-DIN, the S-Video connector. */
    @Test
    fun `wave links over a Mini-DIN, which its board places as an AdamTech MDJ-004`() {
        val body = catalog.bySlug("wave")!!.core.body as Body.Split
        assertEquals(Recorded.Known(setOf(SplitLink.MiniDin)), body.links)
    }

    @Test
    fun `a one-chip split with a USB-C socket beyond the host's links over USB-C`() {
        for (slug in listOf("pando", "3w6")) {
            val body = catalog.bySlug(slug)!!.core.body as Body.Split
            assertEquals(Recorded.Known(setOf(SplitLink.UsbC)), body.links, "$slug's link")
        }
    }

    /**
     * Backlog #97: the host port is read off the design's own board. Measured 2026-09-25:
     * 100 USB-C, 5 mini-USB, 3 micro-USB and 15 daughterboards on soldered designs, and 4
     * one-piece socketed boards with a pass-through of their own. Every kind must show up.
     */
    @Test
    fun `host ports are read off the board, every kind of them`() {
        val ports = catalog.all().mapNotNull { it.extras.hostPort.knownOrNull() }.toSet()

        assertTrue(HostPort.OnBoard(UsbPort.UsbC) in ports, "no board read as USB-C")
        assertTrue(HostPort.OnBoard(UsbPort.MicroB) in ports, "no board read as micro-USB")
        assertTrue(HostPort.OnBoard(UsbPort.MiniB) in ports, "no board read as mini-USB")
        assertTrue(HostPort.Daughterboard in ports, "no board read as using a daughterboard")
    }

    /** A repo with no license is `Unstated`, and that is a value the seed carries. */
    @Test
    fun `some designs are unstated, which is a fact rather than a gap`() {
        assertTrue(
            catalog.all().any { it.core.licensing.hardware is LicenseState.Unstated },
            "no unstated licenses at all suggests absence is being dropped",
        )
    }
}
