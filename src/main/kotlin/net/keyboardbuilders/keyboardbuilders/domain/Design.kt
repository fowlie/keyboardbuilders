package net.keyboardbuilders.keyboardbuilders.domain

/*
 * The domain model.
 *
 * Two structural rules run through all of it:
 *
 *  1. A design record is ONE buildable configuration that people gave a name to
 *     (ticket 07 §1, §3). Sofle v1, Sofle Choc and Sofle Pico are three records,
 *     not one — because their MCU and switch support differ, and a single record
 *     carrying both would answer "Choc split with an RP2040" with a Sofle that
 *     cannot be built.
 *
 *  2. A set is only allowed where ONE configuration genuinely offers every member
 *     at the same time. Corne's 36- and 42-key arrangements come off the same PCB,
 *     so `layouts` is a set. Sofle's two MCUs come off different PCBs, so `mcu` is
 *     not. This is the whole test, and it decides every "should this be a set?"
 *     question that comes up later.
 */

// ---------------------------------------------------------------------------
// Started as a prototype with compile-fail tests for it.
//
// "At least one" is NOT a type here — collections are plain `Set` and the check
// sits in an `init` block. The reason is that this codebase has exactly one
// crossing point from untrusted text to a checked value (`DesignForm.parse`,
// architecture.md:1204) plus one row mapper, so a wrapper type would be
// re-stating a guarantee those two places already establish. If that ever
// stops being true, `arrow.core.NonEmptySet` is the off-the-shelf type — don't
// hand-roll one.
// ---------------------------------------------------------------------------

/** Ignorance, recorded. Deliberately NOT `T?` — "nobody has said" is a fact, not an absence. */
sealed interface Recorded<out T> {
    data object Unknown : Recorded<Nothing>
    data class Known<out T>(val value: T) : Recorded<T>
}

fun <T, U> Recorded<T>.map(f: (T) -> U): Recorded<U> = when (this) {
    Recorded.Unknown -> Recorded.Unknown
    is Recorded.Known -> Recorded.Known(f(value))
}

/** The one deliberate bridge to nullability, named so it is grep-able. */
fun <T> Recorded<T>.knownOrNull(): T? = (this as? Recorded.Known<T>)?.value

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

@JvmInline
value class DesignName(val value: String) {
    init { require(value.isNotBlank()) { "a design has a name" } }
}

@JvmInline
value class Slug(val value: String) {
    init { require(value.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*"))) { "not a slug: $value" } }
}

// ---------------------------------------------------------------------------
// Layout — one physical arrangement the design can be built in.
// ---------------------------------------------------------------------------

/** Zero keys is not a keyboard. */
@JvmInline
value class KeyCount(val value: Int) {
    init { require(value > 0) { "key count must be positive, was $value" } }
}

/** `3x5+3` — per hand, rows by columns plus thumbs. */
data class Shorthand(val rows: Int, val cols: Int, val thumbs: Int) {
    override fun toString() = "${rows}x$cols+$thumbs"
}

/**
 * Ticket 04: per-hand geometry is NOT derived from key coordinates. Two algorithms
 * were measured against 132 author-certified layouts; the better one scored 73.5%
 * with its confidence flag pointing the wrong way. So the shorthand is recorded only
 * when a source states it, and `Unknown` for ~82% of splits is the accepted price.
 */
/** The author's own word for one arrangement — "polydactyl", "Trimmed", ZMK's own
 *  "Dual 1U (34 keys)". A proper noun, unlike [Shorthand]: every layout a break-off
 *  design offers can share one shorthand and still need four different names. */
@JvmInline
value class LayoutName(val value: String) {
    init { require(value.isNotBlank()) { "a layout name is not blank" } }
}

/**
 * Backlog #23 (named layouts): a break-off design is ONE PCB order with SEVERAL
 * outcomes, so it is one design with several `Layout`s, never several designs — the same
 * gerber test that already governs every other per-board fact on this project. `name` is
 * the field that ticket found missing: KLOR's PCB is one purchase and four layouts
 * (*polydactyl / konrad / yubitsume / saegewerk*), and until this existed there was
 * nowhere to put the four words that tell them apart.
 *
 * `Unknown` is the common case, deliberately: only a design whose layouts differ in
 * KIND (a break-off boundary, a genuinely different physical arrangement) has anything
 * to name. `discipline`'s four key counts (66/67/68/69) differ by keycap choice on one
 * identical outline and need no name at all — naming them would invent a difference
 * that is not there.
 */
data class Layout(
    val keys: KeyCount,
    val shorthand: Recorded<Shorthand> = Recorded.Unknown,
    val name: Recorded<LayoutName> = Recorded.Unknown,
)

/** Free, because it is a projection of a stated fact rather than a guess. */
val Layout.thumbsPerHand: Recorded<Int> get() = shorthand.map { it.thumbs }

// ---------------------------------------------------------------------------
// Switch footprint — a property of the PLATE, not of the PCB (ticket 37).
// A hand-wired plate and an FR4 PCB are both plates with switch cutouts; the PCB
// is the special case that also carries the wiring. So hand-wired designs need no
// exemption from the gate.
// ---------------------------------------------------------------------------

sealed interface SwitchFootprint {
    data object Mx : SwitchFootprint
    data object ChocV1 : SwitchFootprint
    data object ChocV2 : SwitchFootprint
    data object Alps : SwitchFootprint

    /** Kailh's PG1232, smaller than Choc v1 (14.5 x 13.5 mm against 15 x 15) with its own
     *  posts and pins, so neither footprint takes the other's switch. A product line with
     *  several boards, so not a catch-all name (backlog #109). */
    data object ChocMini : SwitchFootprint

    /** Kailh's PG1425 Kailh X. One board today, and still its own variant: the owner ruled
     *  2026-10-02 that switches get no generic "Other" category, because every commercial
     *  switch has a name and the corpus is still growing. */
    data object KailhX : SwitchFootprint

    /** A one-off with no commercial equivalent. Assigned by a human, never by the importer. */
    data class Bespoke(val name: String) : SwitchFootprint
}

// ---------------------------------------------------------------------------
// Switch mount — how a switch is HELD, which `SwitchFootprint` above deliberately does
// not say. Backlog #107 step 9, for the owner's configurator: a design states the
// interfaces its switch positions accept, a part states the interface it fits, and a
// choice is valid when they meet. The same shape controllers have.
// ---------------------------------------------------------------------------

/**
 * One interface a switch position accepts, read off the PADS by `measure_switch_pads.py`
 * rather than off footprint names, so it proves absence as well as presence:
 *
 * - [Solder]: plated pin holes a switch is soldered straight into.
 * - [MxSocket], [ChocSocket]: a Kailh socket's two ~3 mm post holes (6.84 mm apart for
 *   MX, 5.44 for Choc) and pads to solder it to.
 * - [MillMax]: plated pin holes of 1.47-1.65 mm, wide enough for a Mill-Max receptacle.
 *   1.47 mm is the smaller standard MX hole; Keebio's guide puts Mill-Max on standard MX
 *   boards, while "most PCBs designed for Chocs have switch pad holes that are too small".
 *
 * A design accepts what EVERY key position on one of its boards accepts, and what ANY of
 * its boards does: Potato65 ships a hotswap PCB and two solder ones.
 */
enum class SwitchMount { Solder, MxSocket, ChocSocket, MillMax }

/**
 * The hotswap parts a builder can hold, each with the mounts it fits. The owner's rail
 * group, 2026-10-02: Any, Kailh socket, Gateron socket, Mill-Max.
 *
 * Gateron's MX socket shares Kailh's part number (CPG151101S11) and fits its footprint
 * (a 2021 comparison; no Gateron datasheet found). No Gateron Choc socket is known, so
 * Choc pads take Kailh's CPG1350 alone.
 */
enum class HotswapPart(val token: String, val fits: Set<SwitchMount>) {
    KailhSocket("kailh", setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)),
    GateronSocket("gateron", setOf(SwitchMount.MxSocket)),
    MillMax("millmax", setOf(SwitchMount.MillMax)),
}

/** The parts a board accepting these mounts takes, in [HotswapPart]'s order. */
fun Set<SwitchMount>.hotswapParts(): List<HotswapPart> =
    HotswapPart.entries.filter { part -> part.fits.any { it in this } }

// ---------------------------------------------------------------------------
// Key pitch — architecture.md §1.5, and the reason it is here rather than on
// `Layout`: *"Pitch is a property of the PCB, not of the footprint — many PCBs
// carry both MX and Choc footprints at 19.05 mm spacing, and Choc-spaced PCBs
// also exist with dual footprints."*
//
// It is the fact that answers WHICH KEYCAPS FIT, which `switches` alone cannot:
// the owner's worked case is `diepala/cantor`, whose Classic takes Choc switches
// at Choc spacing and therefore only MBK caps, while its MX sibling takes Choc
// *or* MX switches at MX spacing and therefore ordinary MX-stem caps. Recording
// only the switch families makes those two designs look like a subset relation
// when they are a different keycap ecosystem.
//
// MEASURED BEFORE MODELLED. `scripts/measure_key_pitch.py` reads it off the
// board's own switch placements for 74 of 80 sampled boards, 65 of them at
// >= 50% self-agreement. The two exotic variants are not speculative: the sample
// contains dannixon/aya at 19.50mm and vlkv/chocofly at 17.50mm.
// ---------------------------------------------------------------------------

/**
 * Hundredths of a millimetre, so a pitch is exact and comparable without floats.
 * 1905 is MX, 1800 x 1700 is Choc.
 */
@JvmInline
value class Hundredths(val value: Int) {
    init { require(value in 1..10_000) { "a key pitch in hundredths of a mm, was $value" } }

    val mm: Double get() = value / 100.0
    override fun toString() = "%.2f mm".format(mm)
}

sealed interface KeyPitch {
    /** 19.05 x 19.05 mm — standard MX spacing. */
    data object Mx : KeyPitch

    /** 18 x 17 mm — standard Choc spacing. */
    data object Choc : KeyPitch

    /**
     * Anything else the board actually measures, carried rather than rounded to the
     * nearest standard. `dannixon/aya` is 19.50 mm and `vlkv/chocofly` is 17.50; snapping
     * either to `Mx` or `Choc` would state a keycap compatibility the board does not have.
     *
     * ONE NUMBER, WHERE `architecture.md` §1.5 HAS TWO. Its `UltraTight { x, y }` and
     * `Other { x, y }` assume both axes are known. The measurement is nearest-neighbour
     * distance between switch placements, which yields the SMALLER of the two — on Choc's
     * 18 x 17 it reads 17 — so a design carrying x and y would have one of them invented.
     * `spacing` is what is actually known: keys are at least this far apart. If a later
     * extractor separates the axes, widening this variant is the change, and it breaks
     * nothing that reads the field today.
     *
     * The two variants are also collapsed into one: nothing in v0.5 asks the question
     * `UltraTight` and `Other` differ on, and a discriminator no code reads is a second
     * place for the truth to live.
     */
    data class Custom(val spacing: Hundredths) : KeyPitch
}

/**
 * What size of keycap the design's spacing allows. DERIVED, never stored —
 * `architecture.md` §1.5 makes it a function so there is no field to lie in.
 *
 * The full 19.05 mm unit is what every ordinary MX-stem keycap profile is cut for
 * (OEM, SA, XDA, Cherry). Choc spacing is 18 x 17, so a full-size cap physically
 * collides with its neighbour and only the reduced profiles fit — MBK and friends.
 */
sealed interface CapFootprint {
    /** 19.05 mm unit: OEM, SA, XDA, Cherry and every other MX-stem profile. */
    data object Full : CapFootprint

    /** 18 x 17 mm: MBK and the other choc-spaced profiles. */
    data object Reduced : CapFootprint

    /** A pitch matching no commercial cap size. Which caps fit is the designer's to state. */
    data class Unusual(val spacing: Hundredths) : CapFootprint
}

val KeyPitch.caps: CapFootprint
    get() = when (this) {
        KeyPitch.Mx -> CapFootprint.Full
        KeyPitch.Choc -> CapFootprint.Reduced
        is KeyPitch.Custom -> CapFootprint.Unusual(spacing)
    }

/**
 * The other axis of keycap compatibility, and the one `CapFootprint` cannot answer:
 * which stem the cap has to press onto. `architecture.md` §1.5 settled that keycap
 * compatibility is a function of `(mounts, pitch)` over these TWO INDEPENDENT axes, and
 * rule R2 makes it a method rather than a field so there is nowhere to write a lie.
 *
 * ALPS IS A THIRD MEMBER HERE, AND THE RECORD SAYS TWO. `architecture.md` derives
 * `Alps -> MxCross` and books the inaccuracy as `Lint::AlpsKeycapStemApproximated`,
 * explicitly "recorded as lints, not as silent lies". **That lint machinery was never
 * built** -- there is no `Lint` type in this source tree -- so shipping the fold into a
 * page that publishes keycap answers would tell four designs (`aek2-usb`, `cyclops`,
 * `m0110a-qmk`, `m0116-usb`) that any MX keycap set fits an Alps board, which takes no
 * MX cap at all, with nothing marking the approximation. Widening the enum makes that
 * sentence unconstructible instead of merely flagged, and costs one member.
 * `docs/research/keycap-profiles.md` §5 carries the full reasoning.
 */
sealed interface KeycapStem {
    /** Cherry MX and every clone of it -- and Kailh Choc **v2**, which is the
     *  counter-intuitive one: PG1353 has a "+" stem, so it takes MX-stem caps. What it
     *  cannot take is a full-size cap on a choc-spaced board, and that is the
     *  `CapFootprint` axis's job to refuse, not this one's. */
    data object MxCross : KeycapStem

    /** Kailh Choc v1 (PG1350)'s two-prong rails. Takes no MX cap and no Choc v2 cap. */
    data object ChocV1 : KeycapStem

    /** Alps SKCM/SKCL and Matias. See this interface's own doc comment for why this
     *  member exists where the decision record has no equivalent. */
    data object Alps : KeycapStem
}

/**
 * Null for `Bespoke`, and that is a real answer rather than a gap: it is a per-design
 * statement, so the stem is the designer's to state and this build has no field to read
 * it from. Null for Mini Choc and Kailh X too, because no source we have states theirs.
 *
 * `architecture.md` §1.5 resolves `Bespoke` to `MxCross` by requiring an explicit `stem`
 * on construction ("a design whose own source never states its keycap stem simply cannot
 * be built as `Bespoke`"). This build's `Bespoke(name)` carries NO such field, so that
 * requirement is unimplementable here and assuming `MxCross` would invent the fact the
 * record was careful to demand. It does not appear in the corpus today, so nothing is
 * lost by answering honestly.
 *
 * The `when` is exhaustive, so a new `SwitchFootprint` member is a compile error here
 * rather than a silently wrong keycap answer.
 */
val SwitchFootprint.stem: KeycapStem?
    get() = when (this) {
        SwitchFootprint.Mx -> KeycapStem.MxCross
        // NOT a guess, and the one fact in this domain most people get backwards:
        // PG1353's stem is an MX cross. Two vendor sources and the decision record agree
        // (docs/research/keycap-profiles.md §2.1).
        SwitchFootprint.ChocV2 -> KeycapStem.MxCross
        SwitchFootprint.ChocV1 -> KeycapStem.ChocV1
        SwitchFootprint.Alps -> KeycapStem.Alps
        // Kailh's PG1232 page names no keycap, and Choc v1 caps are not known to fit.
        SwitchFootprint.ChocMini -> null
        SwitchFootprint.KailhX -> null
        is SwitchFootprint.Bespoke -> null
    }

/** One keycap answer: a stem to press onto, and the largest cap the board's spacing
 *  allows. Both axes or neither -- a `KeycapFit` with one half missing would be the
 *  half-answer that makes "Choc v1 takes MBK" look true for a 19.05 mm board. */
data class KeycapFit(val stem: KeycapStem, val maxFootprint: CapFootprint)

/**
 * Every keycap answer this design has, or null when it has none to give.
 *
 * `architecture.md` §1.5's `keycap_fits()`, with one shape change forced by this build's
 * own model: pitch is `Recorded`, so it can be `Unknown`, where the record's version
 * assumed a pitch was always present. **Null when the pitch was never measured** -- 89 of
 * 324 built designs -- because the cap axis then has no value and inventing one would
 * publish a keycap claim from a board nobody has measured.
 *
 * A switch whose stem is the designer's to state (`stem` == null) contributes no fit
 * rather than blocking the others: a design listing MX and a bespoke switch can still
 * answer for its MX half.
 */
fun Design.keycapFits(): Set<KeycapFit>? {
    val cap = core.pitch.knownOrNull()?.caps ?: return null
    return core.switches.mapNotNull { footprint ->
        footprint.stem?.let { KeycapFit(it, cap) }
    }.toSet()
}

// ---------------------------------------------------------------------------
// Firmware
// ---------------------------------------------------------------------------

sealed interface FirmwareRef {
    /**
     * Vial and VIA are QMK companions, not separate families.
     *
     * `targetMcu` IS A FACT ABOUT THE FIRMWARE, NOT ABOUT THE BOARD, and that is why it
     * lives here rather than on [Controller.Socket]. Ticket 50 settled that a socketed
     * design has no MCU — "the design fixes the footprint; the BUILDER picks the module"
     * — and putting a chip back on `Socket` would reverse it. What QMK's `processor`
     * states for such a design is which chip *this firmware is built for*, which is a
     * different and still useful claim: a builder holding an RP2040 Pro Micro cannot run
     * an `atmega32u4` record without porting it, and the footprint alone never says so.
     * Measured 2026-09-07: within the single `pro_micro` footprint, 38 designs target
     * atmega32u4 and 5 target RP2040.
     *
     * `Unknown` where the record does not say, and — the case that matters — where the
     * record is SHARED. 67 of 324 designs cite a QMK record another design also cites;
     * `ianklug/grooveboard` covers nine designs spanning both atmega and RP2040 hardware,
     * so its `processor` is a fact about the record and about no one of them. The
     * extractor withholds those rather than attributing one design's chip to eight
     * others, which is `sweeq`'s borrowed display in a different costume.
     *
     * ONE EXCEPTION, added 2026-10-03: a SOLDERED design whose own board (its BOM or its
     * drawing, never QMK) names the very chip the shared record is built for. Nothing a
     * sibling is built with can change what is on this board or what the record targets,
     * so the target is not in doubt -- 18 designs, the six Atmel grooveboards among them.
     * A board that names no chip stays withheld: its MCU may have come from this same
     * record, and the record agreeing with itself is not a second source.
     */
    data class Qmk(
        val keyboardPath: String? = null,
        val targetMcu: Recorded<Mcu> = Recorded.Unknown,
    ) : FirmwareRef
    data class Zmk(val shield: String? = null) : FirmwareRef
    data class Kmk(val boardFile: String? = null) : FirmwareRef
    data class Custom(val name: String) : FirmwareRef
}

// ---------------------------------------------------------------------------
// Sources. Scaffolding: ticket 07 §3 makes a design's source list DERIVED —
// `SELECT DISTINCT` over the citations that per-field provenance has to store
// anyway. Stored here because that provenance layer does not exist yet; delete
// this field when it does.
// ---------------------------------------------------------------------------

data class SourceRef(val url: String) {
    init { require(url.startsWith("http")) { "a source is a URL: $url" } }
}

// ---------------------------------------------------------------------------
// Build method — a GATE field, because every design is one or the other and the
// corpus always says which.
//
// Promoted out of `DesignExtras` on 2026-08-24. It sat in extras on the strength
// of architecture.md:3363, which lists `smd_required` among the facets "nobody
// records at all" — but that measurement is about README *prose*, and it is about
// the soldering payload, not about PCB-versus-hand-wire. The discriminator is not
// a prose fact at all: it is whether the design's hardware includes a board or
// only a plate. Ticket 48 makes it total — "a design comes from a board; a record
// without one produces nothing" — so every design that publishes has hardware by
// construction, and that hardware answers this.
//
// Read that as HARDWARE, never as "has a readable `.kicad_pcb`" — ticket 48's own
// boundary. Of the 87 rows whose repo holds no board file our scanner can read, 16
// are Altium, 12 are bare gerbers and 52 are CAD or mesh; those Altium and gerber
// designs are PCBs, and inferring `Handwire` from an unreadable board would be a
// fabrication of exactly the kind ticket 40 was bitten by. `Pcb` versus `Handwire`
// is a claim about the artifact, not about our parser's reach.
//
// What actually blocked the promotion was the NESTING, not the gate. A `Pcb`
// carrying a non-empty `Set<SolderingDifficulty>` makes soldering difficulty
// transitively mandatory, and that is the badly-covered half. So the ignorance
// moved inward rather than the field moving outward.
// ---------------------------------------------------------------------------

// Backlog #18. Replaces the old design-level `Set<SolderingDifficulty>` — `SmdPassives`
// and `SmdFinePitch`, one claim about the whole board — because #17 measured that 80%
// of designs (156/194) have a hardest component that is NOT the MCU, so a scalar keyed
// to chip package was answering the wrong question. Soldering difficulty is `max()` over
// every solderable part `scripts/measure_build_difficulty.py` finds on the design's own
// board, and the field states not just the tier but WHICH part(s) drove it: 98 of 323
// measured boards have more than one component tied for hardest, so `drivenBy` is a set,
// never a single part picked arbitrarily among ties.
//
// These four tiers are the SCANNER's own, not the ticket's original proposal
// (`ThroughHole`/`HandSolderable`/`FineWork`/`Leadless`) — that split treats a leadless
// QFN and a merely-tiny 0402/USB-C receptacle as different tiers, and the scanner does
// not yet distinguish them (both land in `smd_fine`). Naming a fourth tier the corpus
// cannot yet tell apart would assert precision nobody measured; renaming is free once
// the scanner does.
enum class SolderingDifficulty { ThroughHole, SmdPassive, SmdHand, SmdFine }

/** What KIND of part sets a design's hardest tier — the scanner's own classes. */
enum class ComponentKind { Mcu, Usb, Led, Diode, Passive, Ic, Connector, Encoder, Other }

data class HardestComponent(
    val tier: SolderingDifficulty,
    val drivenBy: Set<ComponentKind>,
) {
    init {
        // Mirrors the old `Known(emptySet())` guard: the scanner never reports a tier
        // with nothing driving it, so a value with no parts would be this layer
        // inventing a state the measurement cannot produce.
        require(drivenBy.isNotEmpty()) { "a hardest component names at least one part" }
    }
}

sealed interface BuildMethod {
    /**
     * `hardestComponent` is `Unknown` for a board the scanner found nothing solderable
     * on — either nobody's clone is in reach, or every footprint name it placed fell
     * outside the lexicon. Undocumented, not "easy": the common case, and one the
     * previous shape could not write down at all without also being forced to fabricate
     * a `Set` that discards which parts actually mattered.
     *
     * Ticket 03 §1's rule is untouched. The value still lives INSIDE `Pcb`, so
     * `Handwire` has nowhere to put it and `negative/N2_soldering_on_handwire.kt`
     * still fails to compile.
     */
    data class Pcb(
        val hardestComponent: Recorded<HardestComponent> = Recorded.Unknown,
    ) : BuildMethod

    data object Handwire : BuildMethod
}

/**
 * Derived, never stored. Three answers now, and the middle one is the point of the
 * reshape: a PCB whose hardest component nobody could measure returns `Unknown` instead
 * of silently answering `false`. `Handwire` is `Known(false)` because there is no board
 * to consult — still the whole reason `hardestComponent` nests inside `Pcb`.
 */
val BuildMethod.smdRequired: Recorded<Boolean>
    get() = when (this) {
        is BuildMethod.Pcb -> hardestComponent.map { it.tier != SolderingDifficulty.ThroughHole }
        BuildMethod.Handwire -> Recorded.Known(false)
    }

// ---------------------------------------------------------------------------
// Body — a GATE field, on the procurement reading: a split means buying a
// second controller, a second PCB and a cable, and no other field says so.
//
// Named `Chassis` until 2026-08-27.
// Renamed on the owner's objection: a chassis is what a thing is wrapped in, so the
// word reads as the CASE. This type does not describe the case at all — it answers
// how many separate pieces you hold. `case material` is a real planned facet
// (implementation.md:1347), so the old name was also squatting on the one that
// facet will want.
//
// `SplitLink` — which connector the halves use — was deliberately absent through
// ticket 51 and ticketed at 52, because three separate things had to be settled
// first and one of them was a problem this codebase created: `RadioProof`, ticket
// 12's witness for "a wireless interconnect needs radio-capable electronics",
// sourced radio capability from `Mcu`. Ticket 50 removed `Mcu` from socketed
// designs, which is 59% of the corpus, so the witness had nothing left to mint
// from — `Wireless` could never be a design-level value for the majority case.
//
// Ticket 52 §3(a), the recommended and adopted shape: the design declares which
// connector it PROVIDES, never which build a builder chooses. "None" is a value,
// readable straight off the board — a PCB either places a TRRS/TRS jack footprint
// or it does not — so nothing needs a witness, and a socketed split's wireless-ness
// stays exactly what ticket 50 already said it was: a property of the build, out
// of scope for v0.5. Priced 2026-09-02 at 98% precision / 89% recall over all 270
// then-published designs (`scripts/measure_board_count_trust.py`); see
// `split_link` in `scripts/build_seed.py` for the lexicon and its one hand-ruled
// exception (`weteor/3w6`'s rigid PCB bridge).
// ---------------------------------------------------------------------------

/** Two or more, by construction. One half is `OneHanded`; zero is not a keyboard. */
@JvmInline
value class SplitHalves(val value: Int) {
    init { require(value >= 2) { "a split has at least two halves, was $value" } }
}

enum class Hand { Left, Right, Either }

sealed interface Body {
    data object Unibody : Body

    /**
     * `reversible` is the industry term, and it replaced `Symmetry` on 2026-08-27
     * because the two are not the same fact. A reversible PCB is ONE board file,
     * populated one way for the left hand and the other way for the right.
     * `Symmetry.Mirrored` described the halves' geometry, and geometry does not
     * settle what you order: `gumorr/gummykey` ships `GummyKey_Left.kicad_pcb`
     * beside `GummyKey_Right.kicad_pcb` -- mirror images, and still two fab runs.
     * `foostan/crkbd` ships one `corne-cherry.kicad_pcb` for both hands.
     *
     * Procurement, and it bites an individual harder than a group. The usual fab
     * minimum is five boards, so reversible is ONE order of five for a whole
     * keyboard, and hand-specific is two orders -- twice the boards and twice the
     * shipping, to build a single keyboard. That is this site's reader.
     *
     * `Recorded` because almost nobody states it. Tested against all 106 splits in
     * the seed by counting board files per repo: 3 gave a clean answer and 62 were
     * ambiguous, because a repo routinely holds several unrelated designs
     * (`idank/keyboards` has 31 boards). Discriminator knowable, payload not --
     * `Pcb.soldering`'s pattern.
     *
     * It lives HERE rather than on `BuildMethod.Pcb` for one reason: `Pcb` cannot
     * see the body, so it could not forbid `Known(true)` on a unibody, and that
     * is a constructible nonsense state of exactly the kind `Pcb`'s own `init`
     * block already guards against. It would also be unfillable-forever for 244 of
     * the 321 boards in the seed. Every `Split` has halves, so the question always
     * has a referent. The one design this misplaces is the seed's single
     * hand-wired split, which has no PCB to reverse.
     */
    data class Split(
        val halves: SplitHalves,
        val reversible: Recorded<Boolean>,
        /**
         * Every connector the board places to join its halves — a SET since backlog
         * #97, because a board may offer a choice (Stront MX-38: "USB-C/TRRS") and a
         * reader filtering for USB-C must find it. EMPTY means the board places none
         * this lexicon reads: not "unbuilt" and not "wireless" — either is possible
         * underneath it, and only the build decides which. `Unknown` is a split
         * nobody could read.
         */
        val links: Recorded<Set<SplitLink>>,
    ) : Body

    /** A single half, no interconnect. Which hand is procurement — you build that one. */
    data class OneHanded(val hand: Hand) : Body
}

/**
 * A connector a split's own board places, to join its halves — ticket 52 §3(a). Never
 * what the builder ends up doing: a nice!nano and a Pro Micro share a footprint, so
 * whether a socketed split ends up wireless is the build's business, not the design's.
 * A board placing none of these the scan can name has an UNKNOWN [Body.Split.links],
 * never an empty known one: the scan matches connector names, so it proves a link and
 * never its absence. Pando (2026-09-29) plugs its halves together over USB-C and read
 * as none; so might an I/O expander over a bare header, or a wireless build. (There was
 * a `NoWiredLink` member until backlog #97 made the field a set, and then an empty set
 * printed as "none on the board" until Pando.)
 */
sealed interface SplitLink {
    /** A TRRS/TRS jack — an audio-style cable, the corpus's commonest link. */
    data object Trrs : SplitLink

    /**
     * An RJ45 jack — an Ethernet cable between the halves. Backlog #97: the Cheapino
     * and five others place one, and before this member all six read as having none.
     * Named for the cable a builder buys, not the connector's part name.
     */
    data object Ethernet : SplitLink

    /**
     * A USB-C receptacle that joins the halves rather than the computer — read only on
     * a split that sockets a dev board, whose own USB port is the host cable. Stront's
     * README says "Interconnect: USB-C"; Lotus 58 ships a "USB-C" folder beside its
     * "TRRS" one, the receptacle placed where the jack was.
     *
     * Four boards place a TRRS jack AND a USB-C link (Stront MX-38 and Choc, Pica40,
     * Lotus 58's two variants in one repo), and carry both.
     */
    data object UsbC : SplitLink

    /**
     * A 4-pin Mini-DIN jack -- the S-Video connector, joined by an S-Video cable. Forgejo
     * #132: Wave places an `AdamTech_MDJ-004-FS` and read as having no link. One design
     * in the corpus, 2026-09-29; named for the cable a builder buys, like [Ethernet].
     */
    data object MiniDin : SplitLink

    /**
     * The halves are joined by a separate rigid PCB rather than a cable —
     * `weteor/3w6`'s shape, hand-ruled because its bridge board carries no
     * switches and so is excluded from the design's own attributed board set.
     * A real physical link, and none of the cable values is true of it.
     */
    data object Bridge : SplitLink
}

/*
 * `Stagger` is NOT here, and architecture.md §1.3 puts it inside `Chassis` (this
 * type, under its old name). Under the
 * stated criterion it is an extra: ortholinear-versus-column-stagger changes nothing
 * you buy and nothing you do with a soldering iron. It is a facet you search by, the
 * same class as splay and concavity. It goes in `DesignExtras` when it arrives.
 */

// ---------------------------------------------------------------------------
// Licensing — a GATE field, and the one place the criterion reads "may I build
// this" rather than "can I". architecture.md §1.9 settles it: "no licence stated
// is a fact, not ignorance — it means all rights reserved, which is exactly what a
// builder needs to know."
//
// So this is never `Recorded`. Absence is a VALUE (`Unstated`), which is the same
// discipline as `Recorded` applied one level down, and it is total: a repository
// either contains a licence or it does not. Hardware and firmware are licensed
// separately in this hobby -- MIT firmware beside CERN-OHL-S hardware is routine --
// so they are separate fields rather than one.
// ---------------------------------------------------------------------------

sealed interface License {
    data object CernOhlS2 : License
    data object CernOhlW2 : License
    data object CernOhlP2 : License
    data object CcBySa4 : License
    data object CcBy4 : License
    data object Cc01 : License
    data object Mit : License
    data object Apache2 : License
    data object Gpl3OrLater : License
    data object Gpl2Only : License
    data object Solderpad21 : License
    data object TaprOhl1 : License

    /** The tail. An SPDX id we have not enumerated, or a bespoke name. */
    data class Other(val spdxOrName: String) : License
}

sealed interface LicenseState {
    /** The repository contains no licence. Legally: all rights reserved. */
    data object Unstated : LicenseState

    data class Stated(val licenses: Set<License>) : LicenseState {
        init { require(licenses.isNotEmpty()) { "`Stated` with no license is `Unstated`" } }
    }
}

data class Licensing(
    val hardware: LicenseState,
    val firmware: LicenseState,
    val documentation: LicenseState,
)

// ---------------------------------------------------------------------------
// The publication gate. Every field is non-null and every collection is non-empty,
// so an incomplete design is unconstructible rather than merely invalid. `firmware`
// and `pitch` sit here as `Recorded` fields, outside the gate.
// ---------------------------------------------------------------------------

data class DesignCore(
    val name: DesignName,
    val slug: Slug,
    val sources: Set<SourceRef>,
    /**
     * OUT OF THE GATE since 2026-10-02 (backlog #106). It was in ticket 10's gate because
     * every design was then built FROM a firmware record, so it cost nothing. Once the build
     * minted designs from boards, the only way to satisfy it was a key-count match, and a
     * wrong match published firmware for another chip: `unicorn-f072` showed STM32F411 on an
     * F072 board, three RP2040 grooveboards an ATmega32U4 record. Unknown is honest; that
     * was not.
     *
     * Same shape as [DesignExtras.extensions]: `Known` always holds at least one record,
     * and no record found is `Unknown`. No build can prove a keyboard has no firmware
     * anywhere, only that it found none.
     */
    val firmware: Recorded<Set<FirmwareRef>>,
    val layouts: Set<Layout>,
    /** Set-valued: Sofle v1 is genuinely MX *and* Choc on one PCB (ticket 11, amended 2026-08-18). */
    val switches: Set<SwitchFootprint>,
    /**
     * `Recorded`, unlike `switches`, and the difference is honest rather than
     * incidental: a board states its switch footprints by placing them, while its
     * pitch has to be measured off those placements and a two-key macropad has too
     * few to measure. 6 of 80 sampled boards cannot answer, so `Unknown` is a state
     * the corpus really occupies.
     */
    val pitch: Recorded<KeyPitch> = Recorded.Unknown,
    /**
     * Not `Recorded`, and no default. Every design is a PCB or a hand-wire — the
     * property is optional for no design, which is what makes it a gate field
     * rather than an extra. The soldering detail inside `Pcb` is what may be
     * unknown, and it says so there.
     */
    val buildMethod: BuildMethod,
    /**
     * Also not `Recorded`: you cannot build a keyboard without knowing what drives
     * it. Which *chip*, on a socketed design, is the builder's call and lives
     * nowhere on this record — see `Controller.Socket`.
     */
    val controller: Controller,
    /** Procurement: a split is a second controller, a second PCB and a cable. */
    val body: Body,
    /** Permission, and `Unstated` is a value rather than ignorance. */
    val licensing: Licensing,
) {
    init {
        // Each of these is empty only in a draft. An empty layout set would make the
        // design invisible to every key-count filter; an empty source set would
        // publish a claim with no evidence behind it.
        require(sources.isNotEmpty()) { "a design cites at least one source" }
        require(firmware.knownOrNull()?.isEmpty() != true) { "Known firmware holds at least one record" }
        require(layouts.isNotEmpty()) { "a design has at least one layout" }
        require(switches.isNotEmpty()) { "a design takes at least one switch footprint" }
    }
}

// ---------------------------------------------------------------------------
// Controller — a GATE field, for the owner's reason: you cannot physically build
// a keyboard without knowing what drives it.
//
// Promoted out of `DesignExtras` on 2026-08-24, and reshaped on the way, because
// the build-necessary fact is the CONTROLLER, not the chip. Ticket 27 measured the
// socket-versus-soldered verdict over 22 boards; re-run over every footprint name
// in `board-scan.json` it holds at 202 of 202 repos — 100%, zero lexicon gaps, at
// nine times the sample. Where the board is unreadable the switch gate already
// declines the design, so this costs almost no publications.
//
// `Mcu.BoardDependent` is GONE. It was a chip-shaped member standing in for "this
// design is socketed", which is the `PlateDependent` mistake ticket 42 already
// killed once: 59% of the corpus sockets its controller, and a socketed design
// genuinely does not fix the chip — Elite-C, nice!nano, Sea-Picro and Frood all
// drop into a Pro Micro footprint. "Board dependent" IS `Socket`.
// ---------------------------------------------------------------------------

/**
 * A form FACTOR, never a board — which is the only reason a closed enum is safe
 * here (ticket 27 §4). architecture.md §1.4's "the controller-board catalogue is
 * data, not variants, because new boards ship monthly" is about *boards*; these six
 * are the footprints they drop into, and the corpus places exactly these six:
 * pro_micro 106 repos, xiao 8, rp2040_zero 7, blackpill 5, rpi_pico 5, bluepill 1.
 *
 * `Feather` ADDED 2026-09-25 (#99), the seventh. Adafruit's Feather is a form factor
 * with many chips behind it, like the Pro Micro. Two published PCBs place it, both
 * beside a Pro Micro footprint (`prkl30`, `dropin-v1`), and it is how QMK's Bluetooth
 * arrives: the Feather 32u4 Bluefruit LE.
 */
enum class ControllerFootprint { ProMicro, SeeedXiao, Rp2040Zero, RpiPico, BlackPill, BluePill, Feather }

// ---------------------------------------------------------------------------
// Key wiring — an EXTRA, on the owner's ruling: "it can be published without
// knowing the wiring details."
//
// That ruling sharpened the criterion rather than bending it. **The gate holds what
// you need to DECIDE to build and to PROCURE the parts; it does not hold the
// assembly instructions.** Wiring is consumed during assembly, and at that moment
// the builder is reading the author's own build guide — which `sources`, a gate
// field, guarantees they can reach. A build guide carries a hundred facts this
// record will never hold (wire gauge, screw length, iron temperature). Wiring is one
// of them, only more structured.
//
// It arrives well populated anyway: of 406 confirmed QMK rows, 348 declare a matrix
// with a diode direction and 37 declare direct pins — 95.8%. The source is the
// FIRMWARE, not the hardware: `info.json` states it because it *is* how the firmware
// scans. Which is why the PCB-versus-hand-wire asymmetry that looked decisive for
// this field is not — knowability does not split along that line at all.
//
// TWO variants, not architecture.md §1.6's six. Across ~4,000 QMK keyboards:
// `Matrix` 3,177 and `DirectPin` 161, against `DuplexMatrix` 22, `IoExpander` 20,
// `Charlieplex` 1, and `Analog` **zero** — every `analog` hit in QMK is a thumbstick
// or audio, never key sensing. The other four are ticket 03's `NonEmpty<BuildMethod>`
// again: speculative complexity, dropped rather than modelled imprecisely. They land
// as `Unknown`, which costs nothing now that this is an extra.
// ---------------------------------------------------------------------------

enum class DiodeDirection { ColToRow, RowToCol }

/** Zero pins scans nothing. */
@JvmInline
value class PinCount(val value: Int) {
    init { require(value > 0) { "pin count must be positive, was $value" } }
}

sealed interface KeyWiring {
    /** One GPIO per key. No diodes, therefore no diode direction — ticket 03 §1's shape. */
    data class DirectPin(val pins: PinCount) : KeyWiring

    /**
     * `diodes` is required, not `Recorded`. The four corpus rows that declare rows and
     * columns without a `diode_direction` are all tzarc (`djinn` rev1/rev2, `ghoul`
     * rp2040/stm32), all carry `CUSTOM_MATRIX` in `rules.mk`, and `djinn` ships
     * `djinn_portscan_matrix.c`. They are not matrices missing a field — they are a
     * different scanning scheme, so they are `Unknown` wiring rather than a matrix
     * with unknown diodes. Nesting a `Recorded` here would record ignorance about a
     * value that does not apply, which is `soldering`-on-a-hand-wire in a new hat.
     */
    data class Matrix(val rows: PinCount, val cols: PinCount, val diodes: DiodeDirection) : KeyWiring

    /**
     * Hall-effect, optical or capacitive: a press reaches the MCU as an analog signal, not
     * a digital contact closure — neither a matrix scan nor one GPIO per key. Added
     * resolving ticket 11; kept a bare unit variant on purpose (`architecture.md` §1.6) —
     * per-key ADC pin vs. shared analog mux is a real structural difference (see
     * `docs/research/controller-catalogue.md`'s ADC-channel scarcity: 3-9 usable pins per
     * MCU, so anything past a macropad needs some kind of scan), but ticket 12 ruled the
     * pin-budget question out of v0.5 scope. Splitting this variant would build exactly the
     * structure that decision removed.
     */
    data object Analog : KeyWiring
}

/**
 * Sensing lives here, not on `SwitchFootprint` — `architecture.md` §1.5's own doc comment:
 * a Hall-effect or optical switch shares a footprint with a mechanical one (same socket,
 * same keycap) but is electrically incompatible with it. Four values: `Capacitive` was
 * added resolving ticket 11's follow-up, on `cipulot/ec_prox`, a Topre-style EC board whose
 * QMK metadata declares `matrix_pins: false`.
 */
enum class SwitchSensing { Mechanical, Hall, Optical, Capacitive }

/**
 * `switch_sensing`, ticket 03's facet inventory: "derived, sourced from `KeyWiring`." That
 * is only cleanly true for the contact-closure schemes — `Matrix` and `DirectPin` sense a
 * keypress by closing a circuit, which is mechanical sensing by construction, no further
 * fact required.
 *
 * `Analog` cannot be resolved the same way. It is deliberately a bare unit variant (see its
 * doc comment above) precisely because the wiring topology does not distinguish Hall from
 * optical from capacitive — that fact lives in the switch, not in how the MCU reads it, and
 * nothing on `KeyWiring` can supply it. So an analog-wired design's specific sensing family
 * is `Unknown` here unless some OTHER source states it (an explicit statement, a firmware
 * driver name) — guessing "probably Hall, it's the common case" would be exactly the
 * wrong-value-that-looks-right failure this project exists to avoid. Door B (ticket 16)
 * collapses its SWITCHES section to one sentence whenever this comes back `Unknown`, rather
 * than rendering rows it cannot honestly mark ✓ or ✕.
 */
val Recorded<KeyWiring>.sensing: Recorded<SwitchSensing>
    get() = when (this) {
        Recorded.Unknown -> Recorded.Unknown
        is Recorded.Known -> when (value) {
            is KeyWiring.DirectPin, is KeyWiring.Matrix -> Recorded.Known(SwitchSensing.Mechanical)
            KeyWiring.Analog -> Recorded.Unknown
        }
    }

/*
 * No `rows * cols >= keys` invariant, and it was measured rather than waved off: the
 * naive check FAILS on 85 of 352 corpus rows (24%), because QMK declares the matrix
 * per CONTROLLER, not per design. Multiplying by halves rescues 66 and still leaves
 * 19 — the whole Charybdis family, whose split flag is not in the merged `info.json`.
 * So the check needs `Body` and *still* mis-detects. A plausible-looking invariant
 * that rejects a quarter of the corpus is worse than no invariant.
 */

/**
 * The chip, as some source NAMES it — never as a package implies it.
 *
 * WIDENED FROM THREE TO FOURTEEN on 2026-09-07. The three were not a judgement about the
 * hobby; they were what `Design.kt` happened to enumerate while the extractor's own
 * comments accumulated a list of what it kept having to throw away — *"51 name a chip the
 * enum has no member for"*, deferred as *"widening the enum is the owner's, and a
 * nearest-member fit is the default-fill this project refuses."* Both halves of that were
 * right: the fit is still refused, and the list is now the corpus's.
 *
 * A CLOSED LIST, deliberately, and `SeedDesignCatalog` throws on a name that is not here.
 * A chip nobody enumerated is a seed written against a newer QMK, and guessing at it —
 * "an STM32 of some kind" — would put a fact on a page that no source states.
 *
 * `Nrf52840` has no QMK declaration anywhere in this corpus and stays: QMK barely
 * supports it, ZMK designs are built on it, and board files name it directly.
 */
enum class Mcu {
    Atmega16u2, Atmega328p, Atmega32a, Atmega32u2, Atmega32u4,
    Rp2040, Nrf52840,
    Stm32f042, Stm32f072, Stm32f103, Stm32f303, Stm32f401, Stm32f411, Stm32g0b1, Stm32g431,
}

sealed interface Controller {
    /**
     * The design fixes the footprint; the BUILDER picks the module. So no `Mcu`
     * lives here — not as `Unknown`, but because the design does not have one.
     * Exactly ticket 45's reading of a plate: it constrains without determining.
     * Which module a given build used is a property of the *build*, and map.md
     * puts that entity out of scope for v0.5.
     *
     * A SET since 2026-09-25 (#99), single-valued before, and the old ruling still
     * holds for what it was about: two PCBs with different footprints are two
     * records under ticket 07's identity rule, which is why Sofle v1 and Sofle Pico
     * are two rows. What it did not cover is ONE PCB offering a choice. `prkl30`
     * places a Pro Micro footprint and a Feather footprint on the same board, and
     * recording only the first dropped a real wireless route from its page.
     *
     * So the set is the footprints on ONE board file, never a union across a repo's
     * boards. Five repos reach two form factors across SEVERAL boards each; those are
     * revisions, and joining them would invent a PCB that offers both.
     *
     * Non-empty: a socket with no footprint is not a socket.
     */
    data class Socket(val footprints: Set<ControllerFootprint>) : Controller {
        init {
            require(footprints.isNotEmpty()) { "a socket takes at least one footprint" }
        }

        /** The common case: one footprint. */
        constructor(footprint: ControllerFootprint) : this(setOf(footprint))
    }

    /**
     * The chip is on the board, and `Unknown` is the common case: of 75 soldered
     * repos, only **9 (12%)** name their part (`Keeb_components:ATMEGA32U4`). The
     * other 88% place a mute `TQFP-44_10x10mm_P0.8mm` or `QFN-44-1EP_7x7mm_P0.5mm`,
     * which says "an MCU-sized package" and nothing whatever about which chip.
     *
     * Do NOT infer the chip from the package. `TQFP-44` is very probably an
     * ATmega32U4 and probability is not provenance; ticket 27 already measured the
     * footprint-to-chip inference wrong at least 31 times going the other way
     * (`elite_c` is not always an atmega32u4). QMK's `processor` field is the honest
     * second source, and it is a separate pass.
     */
    data class Soldered(val mcu: Recorded<Mcu> = Recorded.Unknown) : Controller
}

/*
 * Deliberately NO `Design.mcu` projection. It would have to flatten two different
 * facts into one `Unknown` — "socketed, so the design does not determine the chip"
 * and "soldered, and nobody recorded which" — which is the very collapse this
 * reshape undid. A query like "designs that run an RP2040" is a COMPATIBILITY
 * question over ticket 12's connector graph, not a field on this record, and ticket
 * 27 §3 already named that graph edge as the fact's single home.
 */

/**
 * Everything the community fills in over time. `DesignExtras()` is all-Unknown,
 * which is the right state for a fresh import.
 *
 * `Recorded` used to appear here and NOWHERE else — architecture.md §1.2's
 * discipline against the model becoming a field of nullables. There is now exactly
 * two exceptions, both the same move and both taken deliberately:
 * `BuildMethod.Pcb.hardestComponent` and `Controller.Soldered.mcu`. In each the facet's
 * discriminator is always known while its payload usually is not, and splitting
 * them was the only shape that kept both facts honest.
 *
 * That is now a pattern rather than an exception, so state it as one: **`Recorded`
 * may nest inside a core sum when the discriminator is knowable and the payload is
 * not.** What the rule still forbids is a bare `Recorded` field sitting in
 * `DesignCore` — that would be a gate field nobody has to fill in, which is the
 * Option-soup §1.2 was written against.
 *
 * The stated line, the owner's, 2026-08-24: **core is identity plus whatever you
 * must know to physically build the thing; everything else is an extra.** Splay and
 * concavity are extras under that rule — the case files already encode the geometry,
 * so nobody needs to be *told* a board is concave in order to print and build it.
 * They are facets you search by, not inputs you build from.
 *
 * One amendment the rule needs, learned twice in one day: necessity makes a field a
 * *candidate* for the gate, and knowability decides whether it can be *required*.
 * Where the fact is necessary but its payload is usually unrecorded, split it —
 * discriminator in core, `Recorded` payload nested inside. `BuildMethod.Pcb` and
 * `Controller.Soldered` are both that move.
 *
 * Still to model, one at a time: concavity, wireless host, battery, `based_on` credits,
 * and supply (can you buy the PCB and plate). All of them are extras under the
 * criterion — none changes what you buy or what you do with an iron. `SplitLink` is the
 * one deferred *core* field, and ticket 52 owns it.
 *
 * **`display` came off that list on 2026-09-07** and did not arrive as a field of its
 * own: it is one member of [Extension], because an encoder, a display and a speaker are
 * the same class of fact read off the same scan, and one field for all of them is the
 * shape the measurement already had.
 *
 * **`splay` came off the list on 2026-09-23**, the owner's own request, and it is [splay]
 * below.
 *
 * **`stagger` came off the list on 2026-09-24**, read off the same board geometry splay
 * is, and it is [stagger] below.
 */
/**
 * Optional hardware a design makes ROOM FOR — `ux.md` §1.3's "Extensions & ports",
 * backlog #66.
 *
 * THE VERB IS "SUPPORTS", NOT "HAS". A footprint is a place a part CAN go: an OLED
 * footprint with no display soldered is one of the most common builds there is, and
 * encoders are routinely left off. So every reader-facing word for this has to carry
 * "room for" rather than "fitted with".
 *
 * A SET, NEVER A COUNT, and #66 measured why. `lotus58` stacks 8 encoder footprints on
 * the positions of its one-per-half; `chameleon-v1-11` draws the same six optional
 * positions twice, once as a key and once as an encoder. A footprint is a hole, not a
 * part — the rule this codebase already applies to switches and controllers.
 */
// Joystick added 2026-11-25 (owner, on Lime), read from QMK's JOYSTICK_ENABLE.
enum class Extension { Encoder, Display, Speaker, Haptic, Trackball, Trackpad, Joystick }

/** A USB receptacle's shape — what decides which cable a builder needs. Backlog #97. */
enum class UsbPort { UsbC, MicroB, MiniB }

/**
 * Where a design's OWN board takes the computer's cable — backlog #97. Read off the
 * receptacle footprint on the board; a socketed design's cable usually plugs into the dev
 * board instead, which is `usbPortFor`'s catalogue join, not this field.
 */
sealed interface HostPort {
    data class OnBoard(val port: UsbPort) : HostPort

    /**
     * The board carries no receptacle of its own, only the 4-pin JST-SH cable to a
     * separate USB daughterboard (the Unified Daughterboard's connector). The cable's
     * shape is then the daughterboard's, which the design does not pick.
     */
    data object Daughterboard : HostPort
}

/**
 * A design's key-position pattern. `Ortho`'s rows and columns both line up; `Row`
 * shifts each row sideways, as on a typewriter; `Column` shifts each column instead, as
 * on most ergonomic splits. See [DesignExtras.stagger] for how it is read.
 */
enum class Stagger { Ortho, Row, Column }

// ---------------------------------------------------------------------------
// based_on — crediting an ancestor design. Backlog #24, resolved by ticket 22
// (ticket 22).
//
// A SET, because one design can credit several: KLOR's README names Sofle as
// `based_on` and five more as inspirations in a single sentence.
//
// RECORDED ONLY FROM AN EXPLICIT STATEMENT, never derived. GitHub's own fork flag looks
// like a shortcut and is not one: only 11 of 367 repos in this corpus carry it (people
// routinely re-upload rather than fork), and even where it is set, `url` is not an
// identity key -- a repo hosting SEVERAL designs makes "the parent" ambiguous at the
// design level, which is exactly the failure a credit must not repeat. So the fork flag
// is corroborating evidence a human weighs, never a value this field is populated from
// automatically for an ambiguous repo.
//
// TWO VARIANTS, because a credited ancestor may not be a design this corpus holds at
// all -- ticket 22's own finding, on `dactyl-manuform`'s README crediting a Clojure
// generator with no board file, which can never publish here. Dropping an unheld credit
// would discard a true, sourced statement; `External` keeps it without minting a slug
// for a design this site does not have.
sealed interface Ancestor {
    /** An ancestor this corpus also publishes. Links internally. */
    data class Held(val slug: Slug) : Ancestor

    /**
     * An ancestor this corpus does not hold. `name` is the word the credit itself used
     * ("Dactyl"), never a guess at what we would have called it had we held it --
     * ticket 22: "nothing infers what the target would have been called."
     */
    data class External(val name: String, val url: String) : Ancestor

    /**
     * A keyboard as its source credits it -- "Based on Lily58, Corne, Helix and Sofle" names
     * projects, not PCBs (owner, 2026-11-25). Before this kind, such a credit was fanned
     * out to every member of the family, and Lime read "derived from Corne Cherry v4,
     * Corne Chocolate v4, ..., Lily58, Lily58 Lite Rev3, Lily58 Pro, ...".
     */
    data class Family(val name: FamilyName) : Ancestor
}

/**
 * The keyboard a design is one PCB of -- "Sweep" for Sweep 2, Sweep Bling LP and the rest.
 * Backlog #107. A NAME OF ITS OWN, because the owner's model has no design called just
 * "Corne": Corne Cherry v4 and Corne Chocolate v4 are the variants, and "Corne" is the
 * family. Read from `seed/families.toml`, where a person names each family and its members.
 */
@JvmInline
value class FamilyName(val value: String) {
    /**
     * The family's address, `/designs/families/{slug}`: the name lowercased, every run of
     * anything but a letter or digit one hyphen. The NAME, because it is what people search for --
     * "corne keyboard", "lily58" -- and a family has no other identity to key a URL by.
     *
     * Accents are folded to their base letter rather than dropped, so a name stays the
     * same word. A name with nothing left to spell a URL from fails [Slug]'s own check,
     * which makes it a load-time error rather than a page at "/designs/families/". Two
     * families that fold to ONE slug are refused where the families are read
     * ([net.keyboardbuilders.keyboardbuilders.catalog.FamilyCatalog]), since only there are
     * both names in hand.
     */
    val slug: Slug get() = Slug(
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    )
}

data class DesignExtras(
    val summary: Recorded<String> = Recorded.Unknown,
    val basedOn: Recorded<Set<Ancestor>> = Recorded.Unknown,
    /** Unknown until a person rules: most of the corpus has no family row yet, and that is
     *  a gap in the ruling, not a claim that the board is a keyboard of its own. */
    val family: Recorded<FamilyName> = Recorded.Unknown,
    val wiring: Recorded<KeyWiring> = Recorded.Unknown,
    /**
     * WHAT WE FOUND EVIDENCE OF — never a census, and the distinction cost a day to
     * learn, so it is written down here rather than left to be rediscovered.
     *
     * `Known` always carries a NON-EMPTY set. There is no `Known(emptySet())` and the
     * seed cannot construct one, because nothing this build reads can establish that a
     * design has room for nothing. `measure_extras.py` is a regex over KiCad footprint
     * NAMES: a name proves the part has a place, and no matching name proves only that
     * we did not recognise one. A Cirque trackpad wired to a generic `PinHeader_1x04`
     * matches nothing at all.
     *
     * That was shipped the wrong way round for one afternoon on 2026-09-07, as
     * `Known(emptySet())` meaning "the boards were read and place none", rendered as
     * "Room for: nothing" on 223 pages. Measured against QMK's own records — which this
     * build already reads for other facts — **11 of those 223 were contradicted by their
     * own firmware**: `bastardkb/dilemma` declares `POINTING_DEVICE_DRIVER =
     * cirque_pinnacle_spi` and a 35 mm curved overlay while our page said it had room
     * for nothing. Corpus-wide the board scan found 1 trackpad; QMK declared 10 more.
     *
     * So absence is `Unknown`, which is what the rest of this file already means by it.
     *
     * **Widened 2026-09-09 (backlog #21's follow-on), and it did not close the gap by
     * itself.** `measure_extras.py` now also reads a footprint's LIBRARY name (the
     * `socket_of` fallback, `molecule`'s only recovery) and QMK's own
     * `POINTING_DEVICE_DRIVER` from a CONFIRMED, deliberately incomplete map of driver
     * values to a kind — `custom` stays unmapped rather than guessed. Corpus-wide:
     * trackpad 1 → 12, trackball 2 → 4. This still cannot prove absence — a ZMK-only
     * design, an unmapped driver value, or a generic connector defeats it the same way —
     * so this shape stays right independent of the widening.
     */
    val extensions: Recorded<Set<Extension>> = Recorded.Unknown,

    /**
     * What this design's switch positions accept ([SwitchMount]). `Known` always carries a
     * NON-EMPTY set: every switch position accepts something, so an empty one would be a
     * scan bug, not a fact. Unknown where no board was read, or no key position was found.
     *
     * Replaced `switchSockets` (2026-10-02), a regex over footprint NAMES that could prove
     * presence and never absence, and that read Hillside 46's `pg1350-R_hotswap` as a
     * Kailh socket where its README says "Mill-Max hotswap sockets, but not the Kailh
     * sockets". The pads tell solder-only boards apart, so `{Solder}` is a real answer.
     */
    val switchMounts: Recorded<Set<SwitchMount>> = Recorded.Unknown,

    /**
     * Does a hand's finger columns fan out from one another, ignoring the thumb cluster?
     * The owner's own request, and his own condition on it: "ignore thumb clusters for
     * this detection, otherwise 90% will have splay" — nearly every ergonomic board
     * rotates its thumb arc, so counting it answers yes everywhere. `measure_splay.py`
     * drops a hand's lowest three keys before asking the question, over the WHOLE
     * corpus's own board files.
     *
     * A GENUINE `Known(false)`, unlike [extensions] above. Those
     * two are regexes over footprint NAMES and can only ever prove presence, never
     * absence, so their silence has to mean `Unknown`. Splay is read off where a design's
     * own board actually places every switch, over the whole board rather than a lexicon
     * that can always be one name short — so a board that was measured and found not to
     * fan is a real "no", not a gap this build happens not to fill. `Unknown` still covers
     * a design with no attributed board file, or a board whose key count matches no
     * layout it publishes: nobody has looked at those at all.
     */
    val splay: Recorded<Boolean> = Recorded.Unknown,

    /**
     * Ortholinear, row-staggered (as on a typewriter) or column-staggered (as on most
     * ergonomic splits) — [Stagger] read off the same board geometry [splay] is, by
     * `build_silhouettes.stagger_kind` (`measure_stagger.py`'s caller). Two signals,
     * fitted against 38 designs the owner ruled by eye against a plot of each board's
     * own key positions (2026-09-24): whether a board's rows sit stacked directly on top
     * of one another — an ortholinear grid scores near 1, either kind of stagger scores
     * near 0, since shifting a row even a quarter unit moves every key past its
     * neighbour's tolerance window — and only once that fails, whether rows exist at
     * all (`row_share`, at a stricter 0.8 than the splay veto's own 0.6).
     *
     * A GENUINE `Known`, the same case [splay] makes for itself: this is read off where
     * a design's own board actually places every switch, not a name lexicon that can
     * only prove presence. `Unknown` still covers a design with no attributed board
     * file, a board whose key count matches no layout it publishes, or — rare — a
     * design whose several board files disagree with one another.
     */
    val stagger: Recorded<Stagger> = Recorded.Unknown,

    /**
     * Whether the board lays its parts out on top, where the finished keyboard shows
     * them -- the owner's request, 2026-10-04, "like Num Discipline, Un Split or
     * mysterium". `measure_exposed_components.py` reads it off where the board places its
     * switch diodes: `true` when most of them sit outside every keycap.
     *
     * A GENUINE `Known`, the same case [splay] makes: read off where the parts actually
     * sit. `Unknown` is a design with no readable board, or one whose switch diodes the
     * scan could not recognise by name -- that proves nothing about where they are.
     */
    val exposedComponents: Recorded<Boolean> = Recorded.Unknown,

    /**
     * Whether the board carries an on/off switch -- the slide switch a wireless board turns
     * its battery off with (owner, 2026-10-04). `measure_power_switch.py` reads it by
     * footprint name, against a lexicon read off every switch-like name in the corpus,
     * which is why a `false` is a measured answer here and not a name scan's silence.
     * `Unknown` is a design with no readable board, or one whose only slide switch is a
     * DIP switch that may pick a boot mode instead.
     */
    val powerSwitch: Recorded<Boolean> = Recorded.Unknown,

    /**
     * The receptacle on this design's own board that the computer's cable plugs into —
     * [HostPort], `build_seed.host_port`. Set on a soldered board, and on the few one-piece
     * socketed boards that place a pass-through receptacle of their own. `Unknown` on
     * every other socketed design is not ignorance about the design: its cable plugs into
     * the dev board, and `usbPortFor` answers that from the controller catalogue.
     */
    val hostPort: Recorded<HostPort> = Recorded.Unknown,

    /**
     * The size of each board a build needs, off the Edge.Cuts outline of the design's own
     * board files -- `measure_board_size.py`, the owner's call 2026-10-04 (price the fab
     * later). For a split it is ONE half's, as the per-half pins are. `Unknown` wherever
     * the outline is not surely that board: both halves joined by tabs in one outline,
     * a PCB panelled with its plates, files of different sizes under one design.
     */
    val boardSize: Recorded<BoardSize> = Recorded.Unknown,

    /** What one of the design's board files holds, read off its own switches. */
    val boardDrawn: Recorded<BoardDrawn> = Recorded.Unknown,
)

/** A board's outline extent in millimetres, at the centre of the Edge.Cuts lines. */
data class BoardOutline(val widthMm: Double, val heightMm: Double) {
    init {
        require(widthMm > 0 && heightMm > 0) { "a board has an extent, was $widthMm x $heightMm" }
    }
}

/** The size of each board one build needs. */
sealed interface BoardSize {
    /** Every board this size: a unibody's one, or a split's halves alike. */
    data class Each(val outline: BoardOutline) : BoardSize

    /** A split whose halves differ -- Fourier's right hand is wider than its left. */
    data class Halves(val left: BoardOutline, val right: BoardOutline) : BoardSize
}

/**
 * What one board file holds. [BothHalves] is a split drawn whole in one file -- Ferris,
 * Sweep and Corne v4 join the halves with snap-off tabs -- which is one order at the fab
 * where a hand-specific pair is two.
 */
enum class BoardDrawn { Board, Half, BothHalves }

data class Design(
    val core: DesignCore,
    val extras: DesignExtras = DesignExtras(),
)

// ---------------------------------------------------------------------------
// Door B, SWITCHES section (`ux.md` §2.9, ticket 16 — "Door B: the compatibility
// graph's rules, and the switch catalogue that does not exist").
//
// Ticket 16 decision 1: v0.5 ships switch CLASSES, not a switch-products catalogue — a
// products catalogue is a named v1 non-goal (`product.md` §9) and has no admissible
// source at scale (vendor pages only). So the "catalogue" this section checks against
// is exactly the four closed, well-known `SwitchFootprint` variants the ux.md mock
// names — "Kailh Choc v1", "MX-compatible", "Choc v2." `Bespoke` is an open, per-design
// self-declaration (it carries an arbitrary name) rather than a fixed class a builder
// could be checking their own spare-parts drawer against, so it is not a candidate here.
// `ChocMini` and `KailhX` are closed but rare (4 boards and 1), so they are not offered to
// every board either; a board taking one still gets its own row (`switchCandidates`).
// ---------------------------------------------------------------------------

private val DOOR_B_SWITCH_CLASSES: List<SwitchFootprint> = listOf(
    SwitchFootprint.Mx, SwitchFootprint.ChocV1, SwitchFootprint.ChocV2, SwitchFootprint.Alps,
)

/**
 * Every catalogue class this system tracks is an ordinary mechanical-contact switch
 * family — ticket 16 decision 1's accepted cost. A Hall-effect or optical switch sharing
 * one of these footprints is real (the XVX Whisper, ticket 16 §2, is dual HE/EC on an MX
 * shape) but is exactly the product-level nuance the classes-only cut trades away: "the
 * demo's parts list reads '36 × Choc v1 (low-profile, mechanical)', not '36 × Gateron
 * Sunset'," in that ticket's own words. Kept as a lookup (not a hardcoded "sensing must
 * be Mechanical" check) so a future non-mechanical class costs one line here, not a
 * rewrite of the membership rule below.
 */
private val SwitchFootprint.sensing: Set<SwitchSensing>
    get() = setOf(SwitchSensing.Mechanical)

/** Why a candidate switch class was struck from the SWITCHES section — ticket 16 decision
 *  3 rule 1: a struck-through row is a claim about the world, so it must name the
 *  recorded fact that excluded it. Typed rather than a string, per this file's own rule
 *  that every `when` over a domain fact — including how to phrase one — lives in
 *  `DesignPresentation.kt`. */
sealed interface SwitchExclusion {
    data class FootprintMismatch(val designFootprints: Set<SwitchFootprint>) : SwitchExclusion
    data class SensingMismatch(val designSensing: SwitchSensing) : SwitchExclusion
}

data class SwitchFit(val footprint: SwitchFootprint, val exclusion: SwitchExclusion?) {
    val fits: Boolean get() = exclusion == null
}

/**
 * The SWITCHES section of Door B, for one design. Ticket 16 discharging ticket 11 §5's
 * handover: the rule is `switch.footprint ∈ design.switch_footprint` **and**
 * `design.switch_sensing ∈ switch.sensing` — two independent set memberships, checked in
 * opposite directions, never fused into one predicate, because a footprint failure and a
 * sensing failure teach different lessons.
 */
sealed interface SwitchesSection {
    data class Rows(val fits: List<SwitchFit>) : SwitchesSection

    /**
     * Sensing unrecorded: the rows compare FOOTPRINTS only, and the page says sensing is
     * unrecorded. Footprint is a gate field (never `Unknown` on a published design), so
     * every row still has a real ✓ or ✕.
     *
     * Ticket 16 decision 3 rule 2 collapsed this case to one sentence and no rows. Changed
     * 2026-10-01 on the owner's report (Ferris 0.2 Bling): measured, 43 published designs
     * have no recorded wiring and so no sensing, and every one of them then said less than
     * its own design page's "Switch footprint" line. No design in the corpus records Hall or
     * optical sensing (258 matrix, 37 direct-pin), so the collapse hid known facts to guard
     * a case that does not occur here. A recorded non-mechanical sensing still excludes
     * mechanical switches, through [Rows] as before.
     */
    data class SensingUnknown(val fits: List<SwitchFit>) : SwitchesSection
}

/**
 * The two-membership rule itself, pulled out from `Design.switchesSection()` so it can be
 * exercised directly. That separation matters here specifically: today's only sensing
 * source (`Recorded<KeyWiring>.sensing`, above) can never produce a *known, specific*
 * non-mechanical value — `Analog` resolves to `Unknown`, not to `Hall`/`Optical`/
 * `Capacitive` — so `SensingMismatch` is unreachable through a `Design` alone until some
 * future source can state a sensing family outright. The rule is still specified in full
 * by ticket 16 (a `SwitchSensing` value need not come from `KeyWiring` forever), so it is
 * verified on its own terms rather than left untested until that source exists.
 */
fun switchFit(candidate: SwitchFootprint, designFootprints: Set<SwitchFootprint>, designSensing: SwitchSensing): SwitchFit {
    val exclusion = when {
        candidate !in designFootprints -> SwitchExclusion.FootprintMismatch(designFootprints)
        designSensing !in candidate.sensing -> SwitchExclusion.SensingMismatch(designSensing)
        else -> null
    }
    return SwitchFit(candidate, exclusion)
}

/** The four standard families, then any footprint of the design's own outside them -- a
 *  `Kailh Mini Choc` or a `Bespoke` switch -- so a board is never shown only crosses.
 *  Ferris 0.1 Low and 0.2 Mini were, until 2026-10-02. */
private fun Design.switchCandidates(): List<SwitchFootprint> =
    DOOR_B_SWITCH_CLASSES + core.switches.filter { it !in DOOR_B_SWITCH_CLASSES }
        .sortedBy { it.toString() }

fun Design.switchesSection(): SwitchesSection {
    val sensing = extras.wiring.sensing
    if (sensing !is Recorded.Known) return SwitchesSection.SensingUnknown(
        switchCandidates().map { candidate ->
            SwitchFit(candidate, if (candidate in core.switches) null
                                 else SwitchExclusion.FootprintMismatch(core.switches))
        }
    )
    return SwitchesSection.Rows(
        switchCandidates().map { candidate -> switchFit(candidate, core.switches, sensing.value) }
    )
}
