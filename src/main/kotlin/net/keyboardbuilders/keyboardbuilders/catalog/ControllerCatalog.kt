package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.ControllerFacts
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.UsbPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Which controller boards fit which footprint, for Door B's CONTROLLER section
 * (`ux.md` §2.9, ticket 16, backlog #58).
 *
 * BUILT FROM `docs/research/controller-catalogue.md` VIA `seed/controllers.toml`,
 * per ticket 5/14 -- the research doc carries a primary source per fact, the TOML
 * carries the rows the build actually reads. Independent of the design corpus, so
 * its own file rather than a field on `designs.json`: same reasoning `RepoActivity`
 * gives for `activity.json`, a different clock from the corpus's.
 *
 * Also the source for `/parts/dev-board/:slug` (`PartPresentation.kt`) -- ux.md's own
 * answer to a bare list of 20+ board names teaching a newcomer nothing (owner-raised
 * 2026-09-03), and §9 build-order item 6, "cheap, high SEO leverage".
 *
 * SIX FIELDS -- five until 2026-09-10, when `mcuFamily` arrived for the reverse
 * direction (see [ControllerBoard.mcuFamily]). Ticket 5's headline finding is that the
 * compatibility graph needs no
 * GPIO count at all -- ZMK implements the same query as a bare set intersection on
 * footprint IDs -- so GPIO/ADC/voltage stay out, per ticket 16 decision 1, and the
 * research doc's own §6 "Gaps" table marks most of that data unsourced for most boards
 * anyway. `firmware` is not optional, though, and its absence was a real bug
 * (owner-caught, 2026-09-03): footprint alone is not compatibility, so a QMK-only design
 * was shown ZMK-only boards as fitting.
 *
 * READ THE FIELD AT THE CHIP LEVEL, NOT OFF A CONVERTER LIST. `firmware` says which
 * firmware RUNS on the board. QMK's supported-microcontroller list has no nRF52 and no
 * Bluetooth, which is why no nRF52840 board carries `qmk`; ZMK supports no 8-bit
 * platform, which is why no ATmega board carries `zmk`. But QMK's `development_board`
 * enum is a pin-naming convenience and NOT that gate -- reading support off it marked
 * three RP2040 boards ZMK-only and put a false sentence on 16 pages until 2026-09-04.
 * `seed/controllers.toml`'s header carries the sources and the correction.
 */
@Component
class ControllerCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/controllers.json") resource: Resource,
) {
    private val boards: List<ControllerBoard> =
        resource.inputStream.use { mapper.readValue<List<SeedControllerBoard>>(it) }
            .map {
                ControllerBoard(
                    it.name, it.slug, it.footprint.toFootprint(), it.wireless,
                    it.mcu_family.toMcuFamily(), it.firmware.toSet(), it.url,
                    usb = it.usb?.let { ports -> Recorded.Known(ports.map(String::toUsbPort).toSet()) }
                        ?: Recorded.Unknown,
                )
            }

    /** Every catalogue board that plugs into a socket of this footprint, regardless of
     *  firmware -- filtering by the design's own firmware is the caller's job (matches
     *  this file's convention: `ControllerCatalog` is a data-access component, not
     *  where a `when` over a design's facts belongs). Empty is never a reason to
     *  suppress the CONTROLLER section, only to state it has no known boards yet
     *  (`ControllerFootprint.Rp2040Zero` is exactly this today). */
    fun fitting(footprint: ControllerFootprint): List<ControllerBoard> =
        boards.filter { it.footprint == footprint }

    /** Every catalogued board, for the sitemap. `/parts/dev-board/:slug` is indexable
     *  and self-canonical while Door B's `/compatible` -- its only inbound link -- is
     *  `noindex`, so without the sitemap these pages hang off one page a crawler has
     *  been told not to keep (`SeoController.sitemap`). */
    fun all(): List<ControllerBoard> = boards

    /** For `/parts/dev-board/:slug` -- ux.md's own answer to a bare list of board names
     *  teaching a newcomer nothing (backlog, owner-raised 2026-09-03). */
    fun bySlug(slug: String): ControllerBoard? = boards.firstOrNull { it.slug == slug }
}

data class ControllerBoard(
    override val name: String,
    /** Explicit data, not derived at runtime -- same discipline `Slug` follows for
     *  designs (ticket 07 decision 8): a URL is a promise. */
    val slug: String,
    override val footprint: ControllerFootprint,
    override val wireless: Boolean,
    /**
     * The chip family, for the two impossibility rules in `ControllerFit.kt`.
     *
     * NOT DERIVABLE FROM [firmware], which is why it is stored. `firmware` is a positive
     * list -- "known to run these" -- so its silence is Unknown. `Seeeduino XIAO` carries
     * only `zmk` because SAMD21 appears nowhere in QMK's supported list, and that is
     * absence of evidence; `nice!nano v2` carries only `zmk` because nRF52 cannot run QMK
     * at all, and that is a rule. Same field value, two different facts, and only the
     * chip tells them apart.
     *
     * `Recorded.Unknown` for five boards the research doc names no chip for -- see
     * `seed/controllers.toml`'s header. No rule fires on Unknown, which is the right
     * outcome for a chip nobody has read.
     */
    override val mcuFamily: Recorded<McuFamily>,
    /** Lowercase family names matching `FirmwareRef`'s own ("qmk", "zmk", "kmk") --
     *  one lexicon, not two that could drift. */
    override val firmware: Set<String>,
    /** The research doc's own primary-source citation, or null where it cites none
     *  clean enough to send a reader to (`seed/controllers.toml`'s own header names
     *  which boards these are). Null is a real state, never a guessed link. */
    val url: String?,
    /** Backlog #97: the receptacle(s) the board is sold with, from a quoted primary
     *  source. `Unknown` where none states it -- `seed/controllers.toml`'s header. */
    override val usb: Recorded<Set<UsbPort>> = Recorded.Unknown,
) : ControllerFacts

private data class SeedControllerBoard(
    val name: String,
    val slug: String,
    val footprint: String,
    val wireless: Boolean,
    /** Snake case to match the JSON key verbatim, as the neighbouring fields do -- the
     *  seed is a build output and this class is the only place that spelling appears.
     *  NON-NULL, so a row missing the key fails at startup rather than becoming an
     *  Unknown nobody stated; `build_controllers.py` refuses it earlier and this is the
     *  second gate for a hand-edited JSON. */
    val mcu_family: String,
    val firmware: List<String>,
    val url: String? = null,
    /** Null is Unknown -- the key is omitted where no source states the port. */
    val usb: List<String>? = null,
)

/** Throws on an unknown token, like [toMcuFamily] just below and for its reason. */
private fun String.toUsbPort(): UsbPort = when (this) {
    "usb_c" -> UsbPort.UsbC
    "micro_b" -> UsbPort.MicroB
    "mini_b" -> UsbPort.MiniB
    else -> error("unknown usb port '$this' in seed/controllers.json")
}

/** Inverts `seed/controllers.toml`'s own value list. An exhaustive `when` over strings
 *  cannot be compiler-checked, so the `else` throws rather than defaulting to Unknown:
 *  a typo that silently became Unknown would not blank a page, it would quietly return
 *  every ruled-out design to the "shares the footprint" tier, which reads as correct.
 *  `build_controllers.py` checks the same set on the way in. */
private fun String.toMcuFamily(): Recorded<McuFamily> = when (this) {
    "avr8" -> Recorded.Known(McuFamily.Avr8)
    "rp2040" -> Recorded.Known(McuFamily.Rp2040)
    "nrf52" -> Recorded.Known(McuFamily.Nrf52)
    "stm32" -> Recorded.Known(McuFamily.Stm32)
    "samd21" -> Recorded.Known(McuFamily.Samd21)
    "unknown" -> Recorded.Unknown
    else -> error("unknown mcu_family '$this' in seed/controllers.json")
}
