package net.keyboardbuilders.keyboardbuilders.domain

/**
 * DOOR B RUN BACKWARDS. `/designs/:slug/compatible` asks "what fits this PCB"; this file
 * answers "what can I build with the board already in my drawer", which is the query
 * `product.md` §1 opens with and the one nothing else in the hobby answers.
 *
 * The two directions are NOT the same predicate with the arguments swapped, and that is
 * the whole reason this file exists. Forward, a controller either fits or is not shown --
 * `DesignPresentation.controllerFacts` filters on `board.firmware ∩ design.firmware` and
 * a reader who wants the rejects can read the facet rail. Backwards, the rejects are the
 * majority of the page: 153 designs socket a Pro Micro and 35 of them run ZMK, so a
 * nice!nano page that showed only the 35 would silently drop 118 boards a reader can see
 * with their own eyes on the same footprint. Owner's ruling, 2026-09-10: *"a devboard
 * should list designs that is a 1:1 fit, but also designs that only share the same
 * footprint. but some devboards use processors that we KNOW a firmware doesn't support,
 * so there are some stuff we can rule out."*
 *
 * So three tiers, and the third is the one that needed a new fact.
 */

/**
 * The MCU family a controller board carries, which is **not** [Mcu] and must not be
 * merged into it.
 *
 * `Mcu` is the list of chips the CORPUS names -- fourteen members, each one read off a
 * design's own QMK record or board file (backlog #59). This is a fact about a CATALOGUE
 * BOARD, sourced from `docs/research/controller-catalogue.md` §4, and it is deliberately
 * coarser: the two rules below are stated by the firmwares' own documentation at family
 * level, so a family is all they need, and pretending to a part number would mean
 * inventing one. `docs/research/controller-catalogue.md` §4.2 sources nRFMicro as two
 * rows, nRF52840 and nRF52833 -- a part-level field would have to widen `Mcu` with a chip
 * no design names, to answer a question no rule asks.
 *
 * `Samd21` earns a member off one board (Seeeduino XIAO) because it is the CONTROL in
 * §3.4's correction: it is the family that stayed ZMK-only when the RP2040 boards gained
 * QMK, and collapsing it into "other" would erase the reason that edit was chip-driven
 * rather than a blanket sweep.
 */
enum class McuFamily(
    /**
     * Whether the chip family carries a radio at all. **The asymmetry this exists for is
     * the load-bearing part**, and it is what keeps the wireless facet useful:
     *
     * - *has a radio* does NOT imply a board using it is wireless. An nRF52840's radio is
     *   on-die, but its ANT pin is not matched to 50 Ω, so an antenna and a matching
     *   network are the board designer's job
     *   (Nordic's own PCB guidelines). So `true` yields [Connectivity.Unrecorded], never a yes.
     * - *has no radio* DOES imply not wireless, absolutely, needing no further fact.
     *
     * Without that second half, the owner's antenna point would put all 137 soldered
     * designs into "maybe"; with it, 129 are a hard no on physics and only the
     * radio-capable ones need anything more.
     *
     * PRIOR ART, NOT INVENTION. `Mcu::radio()` existed and `V1__init.sql:53` still
     * carries `has_radio` as a generated column on the (now unused) `controller_board`
     * table. Radio was only ever wrong on the DESIGN side -- ticket 12's `RadioProof`
     * minted wirelessness from `Mcu`, and ticket 50 removed `Mcu` from socketed designs,
     * so "`Wireless` could never be a design-level value for the majority case"
     * (`Design.kt`). On the chip, which is where this lives, it was always fine.
     *
     * Hardcoded rather than seeded: five closed, hand-curated, physical members. The
     * owner's no-hardcoding ruling was about FIRMWARE, and the firmware side is now data
     * in `seed/firmwares.toml`.
     */
    val radio: Boolean,
) {
    Avr8(radio = false),
    Rp2040(radio = false),
    /** The only radio family this catalogue models. nRF52840 and nRF52833 both. */
    Nrf52(radio = true),
    Stm32(radio = false),
    Samd21(radio = false),
}

/**
 * `Mcu` -> `McuFamily`, so the design side of a chip comparison speaks the same lexicon
 * as the board side. An exhaustive `when` rather than a prefix match on `.name`: a new
 * `Mcu` member is then a compile error here, which is the only thing that stops a chip
 * being silently filed under the wrong family and losing a rule-out.
 */
fun Mcu.family(): McuFamily = when (this) {
    Mcu.Atmega16u2, Mcu.Atmega328p, Mcu.Atmega32a, Mcu.Atmega32u2, Mcu.Atmega32u4 -> McuFamily.Avr8
    Mcu.Rp2040 -> McuFamily.Rp2040
    Mcu.Nrf52840 -> McuFamily.Nrf52
    Mcu.Stm32f042, Mcu.Stm32f072, Mcu.Stm32f103, Mcu.Stm32f303,
    Mcu.Stm32f401, Mcu.Stm32f411, Mcu.Stm32g0b1, Mcu.Stm32g431,
    -> McuFamily.Stm32
}

/**
 * What one firmware family runs on, and whether it does wireless. Read from
 * `seed/firmwares.toml` by `catalog/FirmwareCatalog`.
 *
 * REPLACES `FirmwareBar`, a two-member enum that carried the same two impossibility rules
 * in code. The owner's ruling, 2026-09-10: *"plan for firmware which does not support
 * wireless chips today, might do so tomorrow. so no 'ZMK' hardcoding here, a firmware
 * class is needed with a wireless field stating the support."* The rules were in three
 * places -- that enum, `build_controllers.py`'s `IMPOSSIBLE` literal and
 * `controller-catalogue.md` §3.4's table -- and the copies HAD drifted: the §3.4 table
 * misquoted ZMK's FAQ and the misquote reached this file labelled "verbatim". One home,
 * with a source per claim, is the fix for that as much as for the hardcoding.
 *
 * TWO SETS, AND THE ASYMMETRY IS DELIBERATE. [supported] is positive, so absence from it
 * means Unknown; [excluded] is the only place a negative claim lives. Reading [supported]
 * as exhaustive would make silence prohibitive and re-break the case `ControllerFitTest`
 * pins -- SAMD21 + QMK is [ControllerFit.SocketOnly], never [ControllerFit.RuledOut],
 * because "absent from QMK's list" is absence of evidence. That collapse is the
 * 2026-09-04 regression which put a false sentence on 16 live pages. ZMK's own hardware
 * page makes the argument about itself: its list is "including but **not limited to**".
 */
data class FirmwareSupport(
    val family: String,
    val supported: Set<McuFamily>,
    val excluded: Set<McuFamily>,
    /** `Unknown` is a real state, and `bluemicro` is in it: no primary source read, and
     *  no design ships it. It must reach a reader as "we do not know" rather than as a
     *  silent "no" -- see [Connectivity.Unrecorded.FirmwareWirelessUnknown]. */
    val wireless: Recorded<Boolean>,
)

/**
 * The firmware catalogue as the rules consult it.
 *
 * A domain type over plain values, so `domain` depends on no loader -- same reasoning
 * [fitFor] gives for taking a board's facts rather than the board. `catalog` builds it;
 * a test builds one from two literals.
 */
class FirmwareRules(private val byFamily: Map<String, FirmwareSupport>) {

    /**
     * Whether [family]'s own documentation rules out running on [on]. **False for a
     * family we hold no row for** -- an unknown firmware bars nothing, which is the same
     * polarity everything else here keeps.
     */
    fun excludes(family: String, on: McuFamily): Boolean =
        byFamily[family]?.excluded?.contains(on) == true

    /** Whether [family] does wireless at all. `Unknown` for a family we hold no row for. */
    fun wireless(family: String): Recorded<Boolean> =
        byFamily[family]?.wireless ?: Recorded.Unknown

    /** Every family with a row, for the guard test that the rule set has not grown. */
    fun families(): Set<String> = byFamily.keys

    /** The excluded pairs, for the same guard. */
    fun exclusions(): Set<Pair<String, McuFamily>> =
        byFamily.values.flatMap { f -> f.excluded.map { f.family to it } }.toSet()

    companion object {
        fun of(entries: List<FirmwareSupport>) = FirmwareRules(entries.associateBy { it.family })
    }
}

/**
 * How one design stands to one catalogue board, for a design that already sockets the
 * board's footprint. Modelled on [SwitchExclusion]'s rule and for the same reason: a row
 * a reader is told not to buy is a claim about the world, so it carries the recorded fact
 * that excluded it, never just "no".
 *
 * Ordered worst-last deliberately -- [Runs] is what the page leads with.
 */
sealed interface ControllerFit {
    /**
     * The 1:1 fit. The board runs a firmware this design ships, and nothing contradicts
     * it. [families] is the overlap itself rather than a boolean, because "runs ZMK" and
     * "runs QMK or ZMK" are different sentences to a reader holding one flashing setup.
     */
    data class Runs(val families: Set<String>) : ControllerFit

    /**
     * Firmware family matches, but the design's own firmware record is BUILT FOR A
     * DIFFERENT CHIP. `FirmwareRef.Qmk.targetMcu`'s doc comment names exactly this
     * reader: *"a builder holding an RP2040 Pro Micro cannot run an `atmega32u4` record
     * without porting it, and the footprint alone never says so"*, measured at 38
     * atmega32u4 designs against 5 RP2040 within the single `pro_micro` footprint.
     *
     * NOT [Runs], because calling it a fit is the overclaim the forward page can afford
     * to ignore and this page cannot: a reader who buys on "fits" and then cannot compile
     * has been told a wrong thing by a site whose whole pitch is not doing that.
     * NOT [RuledOut] either -- porting a QMK keyboard between chips is ordinary work, so
     * the honest tier is the middle one.
     */
    data class NeedsPort(val designMcu: Mcu, val boardFamily: McuFamily) : ControllerFit

    /**
     * The socket matches and nothing else is known either way: no firmware family in
     * common, and no exclusion in [FirmwareRules] against the pairing. The tier the owner
     * asked for by name ("designs that only share the same footprint").
     *
     * [designFamilies] is carried so the row can say which firmware it would need, which
     * is the actionable half -- "this is a ZMK board and that design ships QMK" is a
     * sentence a reader can do something with.
     */
    data class SocketOnly(val designFamilies: Set<String>) : ControllerFit

    /**
     * [FirmwareRules] forbids every firmware this design ships on this chip family, so
     * the reader should not buy this board for this keyboard. Carries the family and the
     * firmwares rather than a verdict, so the row can name the documented rule.
     */
    data class RuledOut(val family: McuFamily, val firmwares: Set<String>) : ControllerFit
}

/**
 * The reverse-direction ruling for one design against one board's facts.
 *
 * TAKES THE BOARD'S FACTS RATHER THAN THE BOARD, so `domain` depends on no catalogue at
 * all. That was written when `ControllerCatalog` lived in `web`; it moved to `catalog` on
 * 2026-09-10 so `SeedImporter` could read it without `persistence` importing `web`, and
 * the argument for this signature survives the move unchanged -- `domain` still owes
 * nothing to either package. It is also the shape a test wants: the three facts are the
 * three axes, and a fixture is three literals rather than a catalogue.
 *
 * THE CALLER OWNS THE FOOTPRINT CHECK. This returns null for a design that does not
 * socket the board's footprint at all -- including every soldered design, which has no
 * socket to compare (ticket 50: *"the design fixes the footprint; the BUILDER picks the
 * module"*). Null is "not on this page", never a tier, because a footprint mismatch is
 * not a near miss a reader should scroll past: `pro_micro` and `seeed_xiao` are different
 * physical connectors and backlog #39 decided that filing one under the other states a
 * board plugs into something it does not fit.
 */
fun Design.fitFor(
    footprint: ControllerFootprint,
    boardFirmware: Set<String>,
    boardMcu: Recorded<McuFamily>,
    rules: FirmwareRules,
): ControllerFit? {
    val socket = core.controller as? Controller.Socket ?: return null
    if (footprint !in socket.footprints) return null

    // No firmware record: the socket matches and nothing else is known, which is what
    // SocketOnly means. Falling through would rule it out, since an empty set of firmwares
    // is trivially "every one barred" (#106).
    val mine = core.firmware.knownOrNull()?.map { it.familyKey() }?.toSet()
        ?: return ControllerFit.SocketOnly(emptySet())
    val shared = mine intersect boardFirmware
    if (shared.isNotEmpty()) return chipCheck(shared, boardMcu)

    // No family in common. Ruled out only if a documented bar forbids EVERY firmware this
    // design ships -- a design shipping QMK *and* ZMK is not ruled out by a rule that only
    // reaches one of them, and would in practice have matched above anyway.
    val family = boardMcu.knownOrNull()
    val barred = family?.let { f -> mine.filter { rules.excludes(it, f) }.toSet() } ?: emptySet()
    return when {
        barred == mine -> ControllerFit.RuledOut(family!!, barred)
        else -> ControllerFit.SocketOnly(mine)
    }
}

/**
 * The firmware families agree; does the chip? Only QMK records carry a target chip
 * ([FirmwareRef.Qmk.targetMcu]), so only QMK can demote a row here, and `Unknown` -- the
 * common case at 120 of 188 socketed designs -- leaves the row a [ControllerFit.Runs].
 *
 * THE UNKNOWNS STAY IN THE TOP TIER ON PURPOSE. Demoting them would turn "we did not
 * record this design's target chip" into "this design probably will not work", which is
 * the absence-read-as-a-fact failure `project_extractors_prove_presence` cost 11 false
 * pages to learn. A row we cannot fault belongs with the rows we cannot fault.
 *
 * THE `"qmk"` LITERALS BELOW SURVIVED THE 2026-09-10 MOVE TO A DATA-DRIVEN CATALOGUE, and
 * they are the one place a family name is still named in a rule. That is not an oversight
 * and moving them would not help: **QMK is structurally the only family that CAN demote a
 * row**, because `FirmwareRef.Qmk` is the only member carrying a `targetMcu` at all. The
 * asymmetry lives in `FirmwareRef`'s shape, not in this file's data, so the honest fix is
 * to widen `FirmwareRef` the day a second firmware records a target chip -- at which point
 * these two lines become a `filterIsInstance` over an interface and the literals go. Until
 * then, a `firmwares.toml` column would be a field with one possible value.
 */
private fun Design.chipCheck(shared: Set<String>, boardMcu: Recorded<McuFamily>): ControllerFit {
    val boardFamily = boardMcu.knownOrNull() ?: return ControllerFit.Runs(shared)

    // Every QMK record this design ships, where the target chip is recorded. A design
    // citing several records (variants of one keyboard) is a fit if ANY of them targets
    // the board's family -- a builder needs one buildable record, not all of them.
    val qmkTargets = core.firmware.knownOrNull().orEmpty()
        .filterIsInstance<FirmwareRef.Qmk>()
        .mapNotNull { it.targetMcu.knownOrNull() }

    if ("qmk" !in shared || qmkTargets.isEmpty()) return ControllerFit.Runs(shared)
    if (qmkTargets.any { it.family() == boardFamily }) return ControllerFit.Runs(shared)

    // QMK is the only shared family and every recorded target is another chip family. If
    // some other family is also shared (ZMK on a Blok, say), that one still runs.
    val others = shared - "qmk"
    return when {
        others.isNotEmpty() -> ControllerFit.Runs(others)
        else -> ControllerFit.NeedsPort(qmkTargets.first(), boardFamily)
    }
}

/**
 * One lexicon for a firmware family name, shared by the two directions of Door B.
 *
 * Was private to `DesignPresentation` and is here now because this file needs the same
 * strings and a second copy is how two pages start disagreeing about whether a board runs
 * a design. Deliberately still NOT `SeedImporter`'s private `familySlug()` -- a different
 * layer, projecting facet values rather than comparing catalogue rows. `FirmwareRef` is a
 * closed sealed interface, so a missed case is a compile error rather than silent drift.
 */
fun FirmwareRef.familyKey(): String = when (this) {
    is FirmwareRef.Qmk -> "qmk"
    is FirmwareRef.Zmk -> "zmk"
    is FirmwareRef.Kmk -> "kmk"
    is FirmwareRef.Custom -> name.lowercase()
}
