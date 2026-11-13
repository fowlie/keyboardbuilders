package net.keyboardbuilders.keyboardbuilders.domain

/**
 * WHAT LINK DOES THIS KEYBOARD USE? A catalogue join, never a field on the record.
 *
 * "Wireless split keyboard" is a top-intent search in this hobby and this site could not
 * answer it, for a structural reason: **for a socketed design, wirelessness is not a
 * property of the design at all** -- it is a property of the (design, controller) pair,
 * and the builder picks the controller.
 *
 * THIS HAS BEEN FOUGHT ONCE HERE AND LOST, WHICH IS WHY THIS FILE LOOKS LIKE THIS.
 * `Design.kt` records it: ticket 12's `RadioProof` was a witness for "a wireless
 * interconnect needs radio-capable electronics" and it sourced radio capability from
 * `Mcu`. Ticket 50 removed `Mcu` from socketed designs -- 59% of the corpus -- so *"the
 * witness had nothing left to mint from -- `Wireless` could never be a design-level value
 * for the majority case."*
 *
 * That ruling stands and this does not reopen it. It killed wireless as a FIELD; the home
 * it points at is the one ticket 27 §3 already named for this class of fact, the
 * compatibility graph. So: no column, no extractor, no hand-curation -- [connectivityFor]
 * derives it from the two catalogues, exactly as `/parts/dev-board/:slug` derives its
 * tiers.
 *
 * Owner's framing, 2026-09-10: *"corne was designed with pro micro which is wired only
 * BUT you CAN actually swap it out with nice!nano to get wireless"*.
 */
sealed interface Connectivity {

    /**
     * Socketed, and a catalogued wireless board both fits and runs a firmware this design
     * ships whose own documentation says it does wireless. Carries the boards so a page
     * can name them -- *"wireless with a nice!nano v2, or 6 other controllers"* is the
     * sentence, and a count alone would not let a reader act on it.
     *
     * NO SOLDERED CASE HERE, and that is measured rather than an omission: no soldered
     * design in the corpus has a radio-capable chip, because a natively-wireless keyboard
     * is a ZMK *board* (its own MCU on the PCB) and all 40 ZMK references in this corpus
     * are *shields*. When a board importer lands, such a design arrives as
     * [Unrecorded.RadioNotConfirmed] and is promoted from there.
     *
     * NAMED `Wireless`, NOT `Bluetooth`, AND THAT IS NOT A HEDGE -- see [facetValue].
     */
    data class WirelessWithController(val boards: List<String>) : Connectivity

    /**
     * Socketed; a catalogued wireless board FITS THE SOCKET, but no firmware this design
     * ships is recorded as doing wireless on one. **135 designs, every one `qmk`-only**,
     * and until 2026-09-16 every one of them read *"no wireless controller we know of
     * fits this socket"*, which was false seven times over.
     *
     * THE MEMBER EXISTS BECAUSE THE SENTENCE WAS A LIE, backlog #98. The owner:
     * *"the whole point of modeling the mcu socket is that users can search for keyboards
     * that COULD be wireless by swapping out the mcu with a nice!nano, and so having to
     * write a little ZMK config too should not be a showstopper."* The proof was a pair of
     * siblings -- `jorne-cherry-2-1` (QMK) said no wireless controller fits, while
     * `jorne-cherry-3-0` (ZMK) offered seven. One physical socket, one swap, opposite
     * answers, decided entirely by which firmware tree happened to carry a record.
     *
     * NOT MERGED INTO [WirelessWithController] AND NOT A REWORD IN PLACE. A searcher
     * filtering for wireless would get 135 boards needing firmware work, unflagged; and a
     * reword alone leaves them unfindable, which was the whole objection. So: its own
     * facet value, its own rail row.
     *
     * IT CARRIES THE BOARDS, like [WirelessWithController], because the fit is the
     * actionable half -- *"a nice!nano v2 or 6 other wireless controllers fit this
     * socket"* is a thing a builder can go and buy. The firmware gap is the caveat, not
     * the headline.
     *
     * **AND IT CARRIES [firmwares], BECAUSE "NO RECORD OF WIRELESS SUPPORT" READ AS A
     * HARDWARE VERDICT.** Owner, 2026-09-16: *"for a newcomer this might not be clear
     * that it's 'only' a matter of creating a ZMK configuration? I mean, it's not a
     * hardware limitation, and I don't think that's communicated clear enough."* He is
     * right, and the first wording made the same mistake as the sentence it replaced,
     * one step further down: the socket question is SETTLED -- the board drops in -- and
     * the only open question is software. A reader who cannot tell those apart is back to
     * believing the design cannot go wireless.
     *
     * DERIVED, NOT HARDCODED, and that is the owner's own standing rule for this file:
     * *"no 'ZMK' hardcoding here, a firmware class is needed with a wireless field
     * stating the support"* (2026-09-10). These are the firmwares the FITTING BOARDS
     * actually run whose own docs claim wireless -- `zmk` and `kmk` on a Pro Micro socket
     * today -- so catalogueing a board that runs something else names that instead.
     * `bluemicro` is excluded by the same rule that keeps it out of
     * [Unrecorded.FirmwareWirelessUnknown]'s neighbours: its support is `unknown`, and an
     * unknown must not be offered to a newcomer as a route.
     *
     * **MEMBERSHIP IS COMPLETENESS-DEPENDENT, AND THE SENTENCE MUST SAY SO.** A design
     * lands here *because no ZMK record is known for it*, and backlog #89 has 26 keyboards
     * whose ZMK shields are not in this corpus at all. Find one and that design moves to
     * [WirelessWithController]. So the honest phrasing is *"we have no record of wireless
     * support for this particular design"*, never *"this design has no ZMK support"* --
     * the count is a property of our records, not of the world. Same rule that keeps this
     * number off every sort key.
     */
    data class WirelessNeedsFirmware(
        val boards: List<String>,
        /** The design's footprints. More than one only where a PCB offers a choice (#99). */
        val footprints: Set<ControllerFootprint>,
        /** Firmwares those boards run that are documented as doing wireless, so the page
         *  can name the actual route. Empty only if a wireless board runs nothing we have
         *  a positive wireless record for -- then the sentence says "wireless firmware"
         *  rather than naming one it cannot. */
        val firmwares: List<String>,
    ) : Connectivity

    /** We can say neither yes nor no, and each member states which fact is missing. */
    sealed interface Unrecorded : Connectivity {
        /**
         * Soldered, and nobody recorded which chip. 8 designs -- 7 Ferris variants and
         * `dropin-v2`. The owner's ruling, 2026-09-10: *"you can treat 'not recorded
         * devboard' as maybe wireless"*, and it is right, because a chip we have not read
         * could be a radio chip.
         */
        data object ChipNotRecorded : Unrecorded

        /**
         * Soldered onto a radio-capable chip family, with nothing confirming the board
         * actually implements the radio. **This is the owner's antenna point, and it is
         * real**: an nRF52840's radio is on-die, but the ANT pin is not matched to 50 Ω,
         * so an antenna and a matching network are the board designer's job.
         *
         * A STATE RATHER THAN A STORED FIELD. A `wireless_native` column was designed and
         * dropped in the same session: it would have been `Unknown` for all 325 designs
         * with no route to data, which is speculative schema. This carries the same
         * meaning for free, and when a ZMK-board importer lands, `outputs: [usb, ble]` in
         * a board's own `.zmk.yml` promotes it -- a first-party declaration rather than
         * someone judging antenna traces off a PCB.
         *
         * Unreachable today (0 designs), like `Avr8`'s exclusion: written so that arriving
         * data is a data change.
         */
        data class RadioNotConfirmed(val mcu: Mcu) : Unrecorded

        /**
         * Socketed onto a footprint for which this catalogue holds **no boards at all**,
         * so we cannot know whether a wireless one exists.
         *
         * THE CORRECTION THAT MADE THIS MEMBER EXIST. The first measurement filed these
         * under "no wireless option", which read an EMPTY CATALOGUE as proof that no
         * wireless board exists -- absence of evidence as a fact, the failure
         * `project_extractors_prove_presence` names. It was 10 designs on `rp2040_zero`;
         * adding that board on 2026-09-10 took the member to 0, and it stays because the
         * next unmodelled footprint will land here rather than in a lie.
         *
         * ON A SOCKET WITH SEVERAL FOOTPRINTS (#99) it names only the ones with nothing
         * catalogued, and it is reached only when no wireless board fits any of them.
         */
        data class NoBoardsCataloged(val footprints: Set<ControllerFootprint>) : Unrecorded

        /**
         * A fitting wireless board exists, but the firmware they share does not say
         * whether it does wireless. `bluemicro` is the row: no primary source read, and
         * `BlueMicro840 v1` carries it. Inert today (no design ships it) and it exists so
         * that an `Unknown` cannot silently become a "no".
         */
        data class FirmwareWirelessUnknown(val firmware: String) : Unrecorded

        /**
         * Socketed, a wireless board fits the socket, and the design has no firmware
         * record, so nothing says whether it runs there (#106). Not
         * [WirelessNeedsFirmware], which says the design ships a firmware the board cannot
         * run.
         */
        data class FirmwareNotRecorded(val boards: List<String>) : Unrecorded
    }

    /**
     * No, and the two members are **different strengths of claim** -- which is the point
     * of splitting them. They share one facet value and never one sentence.
     */
    sealed interface WiredOnly : Connectivity {
        /**
         * Soldered onto a chip family with no radio. **Absolute, on physics**: an
         * ATmega32U4 cannot be made wireless by any board design, so this needs no
         * further fact and is the reason 129 of 137 soldered designs stay out of [Maybe].
         *
         * This is the second half of the asymmetry [McuFamily.radio] documents, and
         * without it the antenna requirement would put all 137 into "maybe" and make the
         * whole facet useless.
         */
        data class ChipHasNoRadio(val mcu: Mcu) : WiredOnly

        /**
         * Socketed; boards ARE catalogued for the footprint and **not one of them is
         * wireless at all**.
         *
         * NARROWED ON 2026-09-16 AND THAT IS THE POINT OF #98. This member used to also
         * hold every design whose socket takes a wireless board that its firmware cannot
         * run -- 135 of its 154 designs -- and told all of them "no wireless controller we
         * know of fits this socket". For 135 that was false: seven fit a Pro Micro socket.
         * They are [WirelessNeedsFirmware] now, and what is left here is 19 designs on
         * three footprints where the claim is true: `rp2040_zero` (10), `rpi_pico` (8),
         * `blue_pill` (1).
         *
         * BOUNDED BY OUR CATALOGUE, unlike [ChipHasNoRadio], and the page must still say
         * so. `rpi_pico` is the live example: 8 designs, catalogued boards, none wireless
         * -- while a Pico W exists in the world and is not in `seed/controllers.toml`. So
         * the honest sentence is *"no wireless controller we know of fits this socket"*,
         * never "wired". The narrowing did not make that sentence safe to strengthen; it
         * made it TRUE of the designs that still read it.
         *
         * ON A SOCKET WITH SEVERAL FOOTPRINTS (#99) the "no" must hold on every one of
         * them, each with boards catalogued.
         */
        data class NoWirelessBoardFits(val footprints: Set<ControllerFootprint>) : WiredOnly
    }
}

/**
 * The facts one catalogue board contributes. A domain-level view so this file needs no
 * `catalog` import -- the same move [fitFor] makes by taking a board's facts rather than
 * the board, and `catalog.ControllerBoard` implements it.
 */
interface ControllerFacts {
    val name: String
    val footprint: ControllerFootprint
    val wireless: Boolean
    val mcuFamily: Recorded<McuFamily>
    val firmware: Set<String>

    /**
     * The receptacle(s) the board is sold with — backlog #97, `usbPortFor`. Two means the
     * builder picks (the classic Pro Micro comes micro-USB or USB-C). Defaulted so the
     * connectivity fixtures, which never ask, need not state it.
     */
    val usb: Recorded<Set<UsbPort>> get() = Recorded.Unknown
}

/**
 * The ruling for one design against the whole controller catalogue.
 *
 * ORDER MATTERS AND IS THE HONESTY. The soldered branch checks *no radio* before
 * *unconfirmed radio*, so a chip that physically cannot do wireless is answered
 * absolutely rather than hedged; and the socketed branch checks *nothing catalogued*
 * before *nothing fits*, so an empty catalogue never renders as a finding.
 */
fun Design.connectivityFor(boards: List<ControllerFacts>, rules: FirmwareRules): Connectivity {
    return when (val controller = core.controller) {
        is Controller.Soldered -> when (val mcu = controller.mcu.knownOrNull()) {
            null -> Connectivity.Unrecorded.ChipNotRecorded
            else -> when {
                !mcu.family().radio -> Connectivity.WiredOnly.ChipHasNoRadio(mcu)
                else -> Connectivity.Unrecorded.RadioNotConfirmed(mcu)
            }
        }

        is Controller.Socket -> {
            val onFootprint = boards.filter { it.footprint in controller.footprints }
            // No firmware record (#106): a wireless board that fits the socket is a maybe,
            // and none fitting is still the physical "no" the branches below give.
            val wirelessFits = onFootprint.filter { it.wireless }
            if (core.firmware == Recorded.Unknown && wirelessFits.isNotEmpty()) {
                return Connectivity.Unrecorded.FirmwareNotRecorded(wirelessFits.map { it.name })
            }
            val mine = core.firmware.knownOrNull().orEmpty().map { it.familyKey() }.toSet()
            // Every wireless board that fits AND runs something this design ships.
            val fitting = onFootprint.filter { board ->
                board.wireless &&
                    fitFor(board.footprint, board.firmware, board.mcuFamily, rules) is ControllerFit.Runs
            }
            // Of those, the ones whose shared firmware is documented as doing wireless.
            val shared = { board: ControllerFacts -> mine intersect board.firmware }
            val proven = fitting.filter { b -> shared(b).any { rules.wireless(it).knownOrNull() == true } }
            val unproven = fitting.flatMap { b -> shared(b) }
                .firstOrNull { rules.wireless(it) == Recorded.Unknown }

            // Every wireless board that fits the SOCKET, firmware set aside. `fitting`
            // above is the stricter list -- it also demands the board run something this
            // design ships -- and #98 is precisely the gap between the two.
            val fitsSocket = wirelessFits

            // Footprints the catalogue holds nothing for. With one footprint this is all
            // or nothing; with several (#99) it can be some.
            val uncataloged = controller.footprints - onFootprint.map { it.footprint }.toSet()

            when {
                proven.isNotEmpty() -> Connectivity.WirelessWithController(proven.map { it.name })
                unproven != null -> Connectivity.Unrecorded.FirmwareWirelessUnknown(unproven)
                /*
                 * BEFORE the "no" and that ORDER IS THE FIX. A wireless board fitting the
                 * socket is a fact about the footprint; being unable to run it is a fact
                 * about our firmware records. Answering the second as though it were the
                 * first is what put a false sentence on 135 pages.
                 */
                fitsSocket.isNotEmpty() -> Connectivity.WirelessNeedsFirmware(
                    fitsSocket.map { it.name },
                    controller.footprints,
                    // The route out, named from the catalogue rather than from a literal.
                    // `Known(true)` only: an Unknown is not a route to recommend.
                    // ORDERED BY HOW MANY FITTING BOARDS RUN IT, not alphabetically: ZMK
                    // runs on all seven Pro Micro boards and KMK on two, so ZMK is the
                    // route to try first and "KMK or ZMK" buried it. NOT the ranking trap
                    // -- a firmware we failed to record makes this count go DOWN, never up.
                    fitsSocket.flatMap { it.firmware }
                        .filter { rules.wireless(it).knownOrNull() == true }
                        .groupingBy { it }.eachCount()
                        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                            .thenBy { it.key })
                        .map { it.key },
                )
                /*
                 * AFTER the wireless fit, and for one footprint the order changes nothing:
                 * nothing catalogued means nothing fits. With several it matters. A
                 * nice!nano on the Pro Micro position is a fact, and an empty XIAO shelf
                 * must not hide it behind a "maybe".
                 */
                uncataloged.isNotEmpty() -> Connectivity.Unrecorded.NoBoardsCataloged(uncataloged)
                else -> Connectivity.WiredOnly.NoWirelessBoardFits(controller.footprints)
            }
        }
    }
}

/**
 * The facet value, or **null for a design whose connectivity we have not established** --
 * and the nullability is the whole point of the 2026-09-10 rename.
 *
 * THE OWNER'S NAMING CRITIQUE FIXED A MODELLING ERROR, not just a string. This shipped as
 * `FacetKey.Wireless` with values `wireless` / `maybe` / `none`, and he objected:
 * *"I do not like the query param `wireless=wireless`, it smells like bad design"* --
 * correct, and the smell had a cause. The facet was named after a VERDICT ("can this be
 * wireless") rather than after the thing, so `maybe` looked like a legitimate answer and
 * was stored as a real value. That forced a special case: a facet with no "Not recorded"
 * tier, argued for in three places.
 *
 * Named after the thing -- **Connectivity** -- "maybe" is not a connectivity type at all.
 * For those designs we simply have not read the chip, so the honest projection is
 * ABSENCE, and the rail's existing `unknown` anti-join puts them below the divider with
 * every other gap on the site. The special case disappears and this becomes an ordinary
 * facet. `Unrecorded`'s four members are the reasons, and they still reach a reader as a
 * sentence on the design page, where there is room for one.
 *
 * Three values, and `wired_only` is scoped deliberately: a wireless design is ALSO wired,
 * since USB still flashes and charges it. Were both values stored for it, `wired` would be
 * 320 of 328 and dead as a filter.
 *
 * `wireless`, NOT `bluetooth`, AND THE RENAME HAS A SOURCE. This value shipped as
 * `bluetooth` on 2026-09-10 and was renamed on 2026-09-16 with the rest of #98, for two
 * reasons the owner put together:
 *
 * 1. **Nothing in this corpus records a protocol.** `seed/controllers.toml` carries
 *    `wireless = true`; `McuFamily` calls `Nrf52` *"the only radio family this catalogue
 *    models"*. "Bluetooth" was an INFERENCE from nRF52 + ZMK, and this file's own rule is
 *    to name a value after the recorded thing rather than after a verdict about it -- the
 *    same rule that turned `wireless=wireless` into `connectivity=`.
 * 2. **A real open project already falsifies it.** OpenController
 *    (https://openkeyboard.org/opencontroller/) is a WCH CH592F doing HID uplink over a
 *    proprietary **2.4 GHz** link to OpenDongle, with *"Bluetooth LE as a secondary
 *    transport"* only listed as planned work -- and OpenDongle's hardware is CERN-OHL-W
 *    v2, the same licence as `zmk-uno` and squarely inside this pipeline's import bar. So
 *    a non-Bluetooth wireless board is not a hypothetical to widen for; it is a project
 *    shipping now.
 *
 * Per-protocol values (`bluetooth`, `rf24`, ...) were the alternative and were declined:
 * making them honest needs a `radio` field on all 9 wireless boards, and no design in the
 * corpus uses OpenController, so every value but one would be a rail row nobody can click
 * -- the dead row backlog #41 exists to prevent. Widen now, split when a design arrives.
 *
 * The param and the rail label are ONE VOCABULARY, by the owner's rule 2026-09-16: never
 * `wireless` in a URL and "Bluetooth" on the checkbox that sets it, in either direction.
 * `FacetRail.label` is the other half of this and the two must be read together.
 */
fun Connectivity.facetValue(): String? = when (this) {
    is Connectivity.WirelessWithController -> "wireless"
    is Connectivity.WirelessNeedsFirmware -> "wireless_needs_firmware"
    is Connectivity.WiredOnly -> "wired_only"
    is Connectivity.Unrecorded -> null
}
