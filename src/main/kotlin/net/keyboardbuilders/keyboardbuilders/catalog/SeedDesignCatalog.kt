package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Ancestor
import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
import net.keyboardbuilders.keyboardbuilders.domain.BoardOutline
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.ComponentKind
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.HardestComponent
import net.keyboardbuilders.keyboardbuilders.domain.DesignExtras
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.DiodeDirection
import net.keyboardbuilders.keyboardbuilders.domain.Extension
import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.LayoutName
import net.keyboardbuilders.keyboardbuilders.domain.Hundredths
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SolderingDifficulty
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SplitLink
import net.keyboardbuilders.keyboardbuilders.domain.Stagger
import net.keyboardbuilders.keyboardbuilders.domain.HostPort
import net.keyboardbuilders.keyboardbuilders.domain.UsbPort
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * The site's designs, built by `scripts/build_seed.py` from the confirmed seed rows
 * and read at startup.
 *
 * A flat DTO stands between the file and the domain deliberately. The domain types
 * are sealed interfaces whose shapes carry meaning -- `Recorded.Unknown` is not
 * `null`, `LicenceState.Unstated` is not an empty list -- and teaching Jackson to
 * reconstruct them would mean hanging serialization annotations on `Design.kt`,
 * where every declaration currently earns its place on domain grounds. So the DTO
 * absorbs the wire format's nullability and `toDomain` states, in one readable
 * place, what each absence MEANS.
 *
 * Not a component: [DesignCatalogs] registers it, behind [PublishedDesigns] (#116).
 *
 * Loading is eager and the constructor throws on a bad file. A design that fails
 * its own `init` checks is a broken build, not a page that 500s later.
 */
class SeedDesignCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/designs.json") resource: Resource,
) : DesignCatalog {

    private val designs: List<Design> =
        resource.inputStream.use { mapper.readValue<List<SeedDesign>>(it) }
            .map { it.toDomain() }
            .sortedBy { it.core.name.value.lowercase() }
            .onEach { design ->
                check(design.core.slug.value !in TAKEN_UNDER_DESIGNS) {
                    "design '${design.core.name.value}' has the slug '${design.core.slug.value}', " +
                        "which /designs/${design.core.slug.value} already serves as its own page -- rename it in the seed"
                }
            }

    override fun all(): List<Design> = designs

    override fun bySlug(slug: String): Design? =
        designs.firstOrNull { it.core.slug.value == slug }
}

/**
 * The literal routes beside `/designs/{slug}`: `/designs/families` (the family index, #107)
 * and `/designs/compare`. Spring prefers the literal, so a design with one of these slugs
 * would have a page nobody could reach.
 */
private val TAKEN_UNDER_DESIGNS = setOf("families", "compare")

// ---------------------------------------------------------------------------
// The wire format. Nullable where the JSON says "nobody recorded this", which
// `toDomain` turns back into `Recorded.Unknown` -- the one place in the codebase
// where a null legitimately becomes recorded ignorance.
// ---------------------------------------------------------------------------

internal data class SeedDesign(
    val name: String,
    val slug: String,
    val sources: List<String>,
    val firmware: List<SeedFirmware>,
    val layouts: List<SeedLayout>,
    val switches: List<SeedSwitch>,
    val buildMethod: SeedBuildMethod,
    val controller: SeedController,
    val body: SeedBody,
    val licensing: SeedLicensing,
    /**
     * The hardest solderable part on the design's own board, per
     * `scripts/measure_build_difficulty.py` — a sibling of `buildMethod` in the wire
     * format (see `build_seed.py`'s own comment on why), nested into `BuildMethod.Pcb`
     * here because that is this codebase's stated home for "discriminator always
     * known, payload usually not" (`Design.kt`'s note above `DesignExtras`). Null for a
     * board the scanner found nothing solderable on, which stays the common case until
     * backlog #18's coverage work lands more of the lexicon.
     */
    val hardestComponent: SeedHardestComponent? = null,
    /**
     * The measured key spacing, or absent. Nullable because it is the one fact in this
     * record that is measured rather than stated -- read off where the switches
     * physically sit -- so a board with too few keys to average simply has none.
     */
    val pitch: SeedPitch? = null,
    /**
     * One sentence about the keyboard, QUOTED VERBATIM from the author's own README, or
     * absent. Null is `Recorded.Unknown` like every other nullable here, and it is the
     * common case: 163 of 234 designs have no confirmed row in `seed/summaries.toml`.
     *
     * It carries no citation of its own because it needs none — a row is only drafted
     * where the repo holds exactly one design, so `sources` has one entry and it is the
     * repo the sentence was read from.
     */
    val summary: String? = null,
    /**
     * How a keypress reaches the MCU, read from the design's own QMK metadata by
     * `qmk_metadata.wiring` (backlog #58, Door B's SWITCHES section). Null is
     * `Recorded.Unknown` like every other nullable here — the common case for a ZMK
     * design (a different parser, not read by the extractor) and for a QMK design
     * whose own revisions disagree, which the build refuses to silently resolve.
     */
    val wiring: SeedWiring? = null,
    /**
     * What we found evidence of — encoder, display, speaker, haptic, trackball,
     * trackpad. `ux.md` §1.3's "Extensions & ports".
     *
     * WRITTEN ONLY WHEN NON-EMPTY, and `[]` means the same as absent: no evidence, which
     * is `Recorded.Unknown` like every other nullable here. The build has no way to state
     * that a design has room for nothing — see [DesignExtras.extensions] for the
     * measurement that settled it.
     */
    val extensions: List<String>? = null,
    /**
     * What the design's switch positions accept: `solder`, `mx_socket`, `choc_socket`,
     * `millmax`. Written by build_seed.py from `measure_switch_pads.py`. Absent is
     * Unknown; `[]` throws, because every switch position accepts something.
     */
    val switchMounts: List<String>? = null,
    /**
     * Whether a hand's finger columns fan out from one another, thumb cluster excluded —
     * `scripts/measure_splay.py`, over the design's own attributed board files.
     *
     * NULL IS `Recorded.Unknown` LIKE EVERY OTHER NULLABLE HERE, but `true`/`false` here
     * are both genuine measured verdicts rather than "evidence found" — unlike
     * [extensions] and [switchSocket] above, this is read off where switches actually
     * sit rather than a footprint-name lexicon that can only prove presence, so a
     * measured `false` is a real answer and not a gap.
     */
    val splay: Boolean? = null,
    /**
     * Ortholinear, row-staggered or column-staggered — `scripts/measure_stagger.py`,
     * over the design's own attributed board files, same source and same discipline
     * as [splay] just above.
     *
     * NULL IS `Recorded.Unknown` LIKE EVERY OTHER NULLABLE HERE. Every written value is
     * a genuine measured verdict for the same reason [splay] is one: read off where
     * switches actually sit, not a name lexicon that can only prove presence.
     */
    val stagger: String? = null,
    /** `scripts/measure_exposed_components.py`. Null is `Recorded.Unknown`; both values are measured. */
    val exposedComponents: Boolean? = null,
    /** `scripts/measure_power_switch.py`. Null is `Recorded.Unknown`; both values are measured. */
    val powerSwitch: Boolean? = null,
    /** Backlog #97, `build_seed.host_port`. Null is `Recorded.Unknown`. */
    val hostPort: String? = null,
    /**
     * Credited ancestors, backlog #24. Absent means no explicit statement was found —
     * `Recorded.Unknown`, like every other nullable here — never "this design has no
     * ancestor": an unheld ancestor is `External` (see [SeedAncestor]), not a reason to
     * write nothing.
     */
    val basedOn: List<SeedAncestor>? = null,
    /** Backlog #107, from `seed/families.toml`. Null is `Recorded.Unknown`. */
    val family: String? = null,
    /** `scripts/measure_board_size.py` via `seed/board-sizes.toml`. Null is `Recorded.Unknown`. */
    val pcb: SeedPcb? = null,
)

/**
 * `{"drawn": "half", "widthMm": 142.52, "heightMm": 106.33, "copperLayers": 2}`, or
 * `halves` in place of the size where a split's halves differ. Every field is written
 * only where measured, so an absent one is Unknown.
 */
internal data class SeedPcb(
    val drawn: String? = null,
    val widthMm: Double? = null,
    val heightMm: Double? = null,
    val halves: List<SeedOutline>? = null,
    val copperLayers: Int? = null,
)

internal data class SeedOutline(val widthMm: Double, val heightMm: Double)

/** `{"kind": "matrix", "rows": 4, "cols": 5, "diodes": "col_to_row"}` or
 *  `{"kind": "direct_pin", "pins": 5}` — never both shapes on one instance, but the DTO
 *  stays flat rather than a sealed hierarchy, same reasoning as every other `Seed*` type
 *  in this file (comment at the top). */
internal data class SeedWiring(
    val kind: String,
    val pins: Int? = null,
    val rows: Int? = null,
    val cols: Int? = null,
    val diodes: String? = null,
)

/**
 * The wire spelling of a wiring fact, turned back into the domain's sum.
 *
 * An unrecognised `kind` is `Unknown` rather than a best-effort guess at the shape —
 * same rule `SeedPitch.toPitch` states: a label this build does not know is a seed
 * written by a newer extractor, and guessing would turn a version mismatch into a
 * fact about a keyboard.
 */
internal fun SeedWiring?.toWiring(): Recorded<KeyWiring> = when (this?.kind) {
    "direct_pin" -> Recorded.Known(
        KeyWiring.DirectPin(PinCount(requireNotNull(pins) { "direct_pin wiring needs a pin count" }))
    )
    "matrix" -> Recorded.Known(
        KeyWiring.Matrix(
            rows = PinCount(requireNotNull(rows) { "matrix wiring needs a row count" }),
            cols = PinCount(requireNotNull(cols) { "matrix wiring needs a column count" }),
            diodes = when (diodes) {
                "col_to_row" -> DiodeDirection.ColToRow
                "row_to_col" -> DiodeDirection.RowToCol
                else -> throw IllegalArgumentException("unknown diode direction '$diodes'")
            },
        )
    )
    else -> Recorded.Unknown
}

/** `{"kind": "held", "slug": "arisu"}` or `{"kind": "external", "name": "Dactyl", "url": "..."}`
 *  — the two `Ancestor` variants, flat, same reasoning as every other `Seed*` type here. */
internal data class SeedAncestor(
    val kind: String,
    val slug: String? = null,
    val name: String? = null,
    val url: String? = null,
)

/**
 * The wire spelling of credited ancestors, turned back into the domain's sum.
 *
 * `[]` and a missing key are one value, same rule as `extensions`: an empty list would
 * claim "no ancestor", which nothing this build reads can establish — the honest
 * silence is `Recorded.Unknown`.
 */
internal fun List<SeedAncestor>?.toBasedOn(): Recorded<Set<Ancestor>> =
    this?.takeIf { it.isNotEmpty() }
        ?.map {
            when (it.kind) {
                "held" -> Ancestor.Held(Slug(requireNotNull(it.slug) { "a held ancestor needs a slug" }))
                "external" -> Ancestor.External(
                    name = requireNotNull(it.name) { "an external ancestor needs a name" },
                    url = requireNotNull(it.url) { "an external ancestor needs a url" },
                )
                else -> throw IllegalArgumentException("unknown ancestor kind '${it.kind}'")
            }
        }
        ?.toSet()
        ?.let { Recorded.Known(it) }
        ?: Recorded.Unknown

/** `{"kind": "other", "mm": 19.5}` — the label AND the millimetres behind it. */
internal data class SeedPitch(val kind: String, val mm: Double)

/**
 * The wire spelling of a pitch, turned back into the domain's sum.
 *
 * An unrecognised `kind` is `Unknown` rather than a `Custom` built from the millimetres:
 * a label this build does not know is a seed written by a newer extractor, and reading
 * it as "unusual spacing" would turn a version mismatch into a fact about a keyboard.
 * `other` is a label this build DOES know, and it carries its measurement through.
 */
internal fun SeedPitch?.toPitch(): Recorded<KeyPitch> = when (this?.kind) {
    "mx_19.05" -> Recorded.Known(KeyPitch.Mx)
    "choc_18x17" -> Recorded.Known(KeyPitch.Choc)
    "other" -> Recorded.Known(KeyPitch.Custom(Hundredths((mm!! * 100).toInt())))
    else -> Recorded.Unknown
}

/** `targetMcu` is the chip QMK's own record says this firmware is built for, absent
 *  wherever the record does not say or is shared by several designs -- unless the design
 *  solders that same chip and its own board names it. See [FirmwareRef.Qmk.targetMcu] for
 *  why a shared record otherwise states nothing about any one of them. Null is
 *  `Recorded.Unknown`, like every other nullable in this file. */
internal data class SeedFirmware(
    val family: String,
    val path: String?,
    val shield: String?,
    val targetMcu: String? = null,
)
/**
 * `name` is absent for almost every design -- see [LayoutName]'s own doc comment: only a
 * break-off design or a shield with more than one stated ZMK `display-name` has anything
 * to call this. Null is `Recorded.Unknown` like every other nullable in this file.
 */
internal data class SeedLayout(val keys: Int, val name: String? = null)
internal data class SeedSwitch(val kind: String, val name: String? = null)
internal data class SeedBuildMethod(val kind: String)

/** `parts` is a list, never a single value — see `build_seed.py`'s comment on `hardestComponent`:
 *  98 of 323 measured boards have more than one component tied for hardest. */
internal data class SeedHardestComponent(val tier: String, val parts: List<String>)
internal data class SeedController(val kind: String, val footprints: List<String>? = null, val mcu: String? = null)
internal data class SeedBody(
    val kind: String,
    val halves: Int? = null,
    val reversible: Boolean? = null,
    /** Null is `Recorded.Unknown`; an empty list is a board that places no link. */
    val link: List<String>? = null,
)

/** Empty list means `Unstated`, which is a value: all rights reserved. */
internal data class SeedLicensing(
    val hardware: List<String>,
    val firmware: List<String>,
    val documentation: List<String>,
)

internal fun SeedDesign.toDomain() = Design(
    core = DesignCore(
        name = DesignName(name),
        slug = Slug(slug),
        sources = sources.map { SourceRef(it) }.toSet(),
        // `[]` is Unknown, never Known(empty): the build found no record (#106).
        firmware = firmware.map { it.toDomain() }.toSet()
            .takeIf { it.isNotEmpty() }?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        layouts = layouts.map {
            Layout(
                KeyCount(it.keys),
                name = it.name?.let { n -> Recorded.Known(LayoutName(n)) } ?: Recorded.Unknown,
            )
        }.toSet(),
        switches = switches.map { it.toDomain() }.toSet(),
        pitch = pitch.toPitch(),
        buildMethod = buildMethod.toDomain(hardestComponent),
        controller = controller.toDomain(),
        body = body.toDomain(),
        licensing = licensing.toDomain(),
    ),
    extras = DesignExtras(
        summary = summary?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        basedOn = basedOn.toBasedOn(),
        family = family?.let { Recorded.Known(FamilyName(it)) } ?: Recorded.Unknown,
        wiring = wiring.toWiring(),
        // `takeIf { it.isNotEmpty() }`, so `[]` and a missing key are one value. An
        // empty list carries no evidence, and evidence is all this field ever holds.
        extensions = extensions?.takeIf { it.isNotEmpty() }
            ?.let { Recorded.Known(it.map(String::toExtension).toSet()) }
            ?: Recorded.Unknown,
        switchMounts = switchMounts?.let {
            require(it.isNotEmpty()) { "switchMounts is empty: every switch position accepts something" }
            Recorded.Known(it.map(String::toSwitchMount).toSet())
        } ?: Recorded.Unknown,
        splay = splay?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        stagger = stagger?.let { Recorded.Known(it.toStagger()) } ?: Recorded.Unknown,
        exposedComponents = exposedComponents?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        powerSwitch = powerSwitch?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        hostPort = hostPort?.let { Recorded.Known(it.toHostPort()) } ?: Recorded.Unknown,
        boardSize = pcb.toBoardSize(),
        boardDrawn = pcb?.drawn?.let { Recorded.Known(it.toBoardDrawn()) } ?: Recorded.Unknown,
    ),
)

private fun SeedPcb?.toBoardSize(): Recorded<BoardSize> {
    if (this == null) return Recorded.Unknown
    halves?.let {
        require(it.size == 2) { "a split's halves are a left and a right, was ${it.size}" }
        return Recorded.Known(BoardSize.Halves(it[0].toOutline(), it[1].toOutline()))
    }
    if (widthMm == null || heightMm == null) return Recorded.Unknown
    return Recorded.Known(BoardSize.Each(BoardOutline(widthMm, heightMm)))
}

private fun SeedOutline.toOutline() = BoardOutline(widthMm, heightMm)

/** Throws on a token this build does not know, like every parser here. */
internal fun String.toBoardDrawn(): BoardDrawn = when (this) {
    "board" -> BoardDrawn.Board
    "half" -> BoardDrawn.Half
    "both halves" -> BoardDrawn.BothHalves
    else -> throw IllegalArgumentException("unknown board drawing '$this'")
}

/**
 * A name this build does not know is a seed written by a newer extractor, so it throws —
 * the same rule [SeedPitch.toPitch] and [SeedSwitch.toDomain] state. Guessing would turn
 * a version mismatch into a fact about a keyboard, and silently dropping it would be
 * worse: the design would read as measured-and-bare when it is neither.
 */
/** Throws on an unknown token like every other parser here: a seed the domain cannot
 *  represent must fail the build loudly, not import as silence. */
internal fun String.toSwitchMount(): SwitchMount = when (this) {
    "solder" -> SwitchMount.Solder
    "mx_socket" -> SwitchMount.MxSocket
    "choc_socket" -> SwitchMount.ChocSocket
    "millmax" -> SwitchMount.MillMax
    else -> throw IllegalArgumentException("unknown switch mount '$this'")
}

/** Throws on an unknown token like every other parser here — see [toSwitchMount]'s own
 *  comment for why silence would be worse than a loud failure. */
/** Throws on an unknown token like every other parser here. */
private fun String.toHostPort(): HostPort = when (this) {
    "usb_c" -> HostPort.OnBoard(UsbPort.UsbC)
    "micro_b" -> HostPort.OnBoard(UsbPort.MicroB)
    "mini_b" -> HostPort.OnBoard(UsbPort.MiniB)
    "daughterboard" -> HostPort.Daughterboard
    else -> throw IllegalArgumentException("unknown host port '$this'")
}

private fun String.toStagger(): Stagger = when (this) {
    "ortho" -> Stagger.Ortho
    "row" -> Stagger.Row
    "column" -> Stagger.Column
    else -> throw IllegalArgumentException("unknown stagger '$this'")
}

private fun String.toExtension(): Extension = when (this) {
    "encoder" -> Extension.Encoder
    "display" -> Extension.Display
    "speaker" -> Extension.Speaker
    "haptic" -> Extension.Haptic
    "trackball" -> Extension.Trackball
    "trackpad" -> Extension.Trackpad
    else -> throw IllegalArgumentException("unknown extension '$this'")
}

private fun SeedFirmware.toDomain(): FirmwareRef = when (family) {
    "qmk" -> FirmwareRef.Qmk(
        path,
        targetMcu?.let { Recorded.Known(it.toMcu()) } ?: Recorded.Unknown,
    )
    "zmk" -> FirmwareRef.Zmk(shield)
    "kmk" -> FirmwareRef.Kmk(path)
    else -> FirmwareRef.Custom(family)
}

private fun SeedSwitch.toDomain(): SwitchFootprint = when (kind) {
    "mx" -> SwitchFootprint.Mx
    "choc_v1" -> SwitchFootprint.ChocV1
    "choc_v2" -> SwitchFootprint.ChocV2
    "alps" -> SwitchFootprint.Alps
    "choc_mini" -> SwitchFootprint.ChocMini
    "kailh_x" -> SwitchFootprint.KailhX
    "bespoke" -> SwitchFootprint.Bespoke(requireName())
    else -> throw IllegalArgumentException("unknown switch footprint '$kind'")
}

private fun SeedSwitch.requireName() =
    requireNotNull(name) { "switch footprint '$kind' needs a name" }

private fun SeedBuildMethod.toDomain(hardest: SeedHardestComponent?): BuildMethod = when (kind) {
    "handwire" -> BuildMethod.Handwire
    // Null is "the scanner found nothing solderable on this board" -- the common case,
    // and NOT the same fact as "checked, nothing there": `HardestComponent`'s own init
    // block rejects an empty `drivenBy`, so there is nowhere to write that claim.
    "pcb" -> BuildMethod.Pcb(hardestComponent = hardest?.toDomain() ?: Recorded.Unknown)
    else -> throw IllegalArgumentException("unknown build method '$kind'")
}

private fun SeedHardestComponent.toDomain(): Recorded<HardestComponent> = Recorded.Known(
    HardestComponent(
        tier = tier.toSolderingDifficulty(),
        drivenBy = parts.map { it.toComponentKind() }.toSet(),
    )
)

private fun String.toSolderingDifficulty() = when (this) {
    "through_hole" -> SolderingDifficulty.ThroughHole
    "smd_passive" -> SolderingDifficulty.SmdPassive
    "smd_hand" -> SolderingDifficulty.SmdHand
    "smd_fine" -> SolderingDifficulty.SmdFine
    else -> throw IllegalArgumentException("unknown soldering difficulty '$this'")
}

private fun String.toComponentKind() = when (this) {
    "mcu" -> ComponentKind.Mcu
    "usb" -> ComponentKind.Usb
    "led" -> ComponentKind.Led
    "diode" -> ComponentKind.Diode
    "passive" -> ComponentKind.Passive
    "ic" -> ComponentKind.Ic
    "connector" -> ComponentKind.Connector
    "encoder" -> ComponentKind.Encoder
    "other" -> ComponentKind.Other
    else -> throw IllegalArgumentException("unknown component kind '$this'")
}

private fun SeedController.toDomain(): Controller = when (kind) {
    "socket" -> Controller.Socket(
        requireNotNull(footprints) { "a socketed controller names its footprints" }
            .map { it.toFootprint() }.toSet()
    )
    // Never inferred from the package: ticket 27 measured that inference wrong at
    // least 31 times, so an unnamed chip stays unnamed.
    "soldered" -> Controller.Soldered(
        mcu?.let { Recorded.Known(it.toMcu()) } ?: Recorded.Unknown
    )
    else -> throw IllegalArgumentException("unknown controller '$kind'")
}

/** Shared with `ControllerCatalog`, which maps the same seven wire values off its own
 *  seed file -- one lexicon for what a controller footprint IS, not two that could
 *  drift when a seventh value ever gets added. */
internal fun String.toFootprint() = when (this) {
    "pro_micro" -> ControllerFootprint.ProMicro
    "seeed_xiao" -> ControllerFootprint.SeeedXiao
    "rp2040_zero" -> ControllerFootprint.Rp2040Zero
    "rpi_pico" -> ControllerFootprint.RpiPico
    "black_pill" -> ControllerFootprint.BlackPill
    "blue_pill" -> ControllerFootprint.BluePill
    "feather" -> ControllerFootprint.Feather
    else -> throw IllegalArgumentException("unknown controller footprint '$this'")
}

/** The wire spelling is the chip's name lower-cased, which is what every source this
 *  build reads already produces -- `qmk_metadata.processor` lower-cases, and the board
 *  and BOM readers match case-insensitively and return the matched token folded down. */
private fun String.toMcu(): Mcu = when (this) {
    "atmega16u2" -> Mcu.Atmega16u2
    "atmega328p" -> Mcu.Atmega328p
    "atmega32a" -> Mcu.Atmega32a
    "atmega32u2" -> Mcu.Atmega32u2
    "atmega32u4" -> Mcu.Atmega32u4
    "rp2040" -> Mcu.Rp2040
    "nrf52840" -> Mcu.Nrf52840
    "stm32f042" -> Mcu.Stm32f042
    "stm32f072" -> Mcu.Stm32f072
    "stm32f103" -> Mcu.Stm32f103
    "stm32f303" -> Mcu.Stm32f303
    "stm32f401" -> Mcu.Stm32f401
    "stm32f411" -> Mcu.Stm32f411
    "stm32g0b1" -> Mcu.Stm32g0b1
    "stm32g431" -> Mcu.Stm32g431
    else -> throw IllegalArgumentException("unknown mcu '$this'")
}

private fun SeedBody.toDomain(): Body = when (kind) {
    "unibody" -> Body.Unibody
    "split" -> Body.Split(
        halves = SplitHalves(halves ?: 2),
        // Null is "nobody recorded it", which is 103 of the seed's 106 splits.
        reversible = reversible?.let { Recorded.Known(it) } ?: Recorded.Unknown,
        // EMPTY IS UNKNOWN TOO. The link scan matches connector names, so it can prove a
        // link and never its absence -- Pando's halves plug together over USB-C and its
        // board read `[]`. 2026-09-29; [SplitLink] has the rest.
        links = link?.takeIf { it.isNotEmpty() }
            ?.let { Recorded.Known(it.map(String::toSplitLink).toSet()) } ?: Recorded.Unknown,
    )
    "one_handed" -> throw IllegalArgumentException("one-handed needs a hand; none is in the seed yet")
    else -> throw IllegalArgumentException("unknown body '$kind'")
}

private fun String.toSplitLink() = when (this) {
    "trrs" -> SplitLink.Trrs
    "ethernet" -> SplitLink.Ethernet
    "usb_c" -> SplitLink.UsbC
    "mini_din" -> SplitLink.MiniDin
    "bridge" -> SplitLink.Bridge
    else -> throw IllegalArgumentException("unknown split link '$this'")
}

private fun SeedLicensing.toDomain() = Licensing(
    hardware = hardware.toLicenseState(),
    firmware = firmware.toLicenseState(),
    documentation = documentation.toLicenseState(),
)

/** Empty is `Unstated`: the repo carries no licence, so all rights reserved. */
private fun List<String>.toLicenseState(): LicenseState =
    if (isEmpty()) LicenseState.Unstated
    else LicenseState.Stated(map { it.toLicense() }.toSet())

private fun String.toLicense(): License = when (this) {
    "CERN-OHL-S-2.0" -> License.CernOhlS2
    "CERN-OHL-W-2.0" -> License.CernOhlW2
    "CERN-OHL-P-2.0" -> License.CernOhlP2
    "CC-BY-SA-4.0" -> License.CcBySa4
    "CC-BY-4.0" -> License.CcBy4
    "CC0-1.0" -> License.Cc01
    "MIT" -> License.Mit
    "Apache-2.0" -> License.Apache2
    "GPL-3.0" -> License.Gpl3OrLater
    "GPL-2.0" -> License.Gpl2Only
    "Solderpad-2.1" -> License.Solderpad21
    "TAPR-OHL-1.0" -> License.TaprOhl1
    // The tail is a VALUE, not a failure. AGPL-3.0, CC-BY-NC-SA-4.0, Zlib, Beerware
    // and ODbL all land here, and the model says that is what `Other` is for.
    else -> License.Other(this)
}
