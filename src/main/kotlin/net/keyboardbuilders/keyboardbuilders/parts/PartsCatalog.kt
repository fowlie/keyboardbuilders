package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalogs
import org.springframework.beans.factory.annotation.Qualifier
import net.keyboardbuilders.keyboardbuilders.catalog.toFootprint
import net.keyboardbuilders.keyboardbuilders.catalog.toSwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

/**
 * The curated layer of #112: shops, the parts and kits they sell, which listing is which,
 * and the diode packages measured so far. Built from `seed/parts.toml` by
 * `scripts/build_parts.py`, which checks the same things first.
 *
 * Eager and strict, like the other catalogues: an unknown token, a dev board the
 * controller catalogue lacks, a kit or diode package naming no design, a listing of no
 * product, a pack of none or a ref listed twice is a refused start. A row that failed
 * quietly would be a line missing from a build list, which reads as "nothing to buy".
 *
 * OFF UNLESS `keyboardbuilders.parts.enabled`. The module is local-only until the owner
 * says otherwise, and the many tests that swap the controller or design catalogue for a
 * fixture would otherwise refuse to start over a dev board their fixture leaves out.
 */
@Component
@ConditionalOnProperty("keyboardbuilders.parts.enabled", havingValue = "true")
class PartsCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/parts.json") resource: Resource,
    controllers: ControllerCatalog,
    @Qualifier(DesignCatalogs.SEED) designs: DesignCatalog,
) {
    private val seed: SeedParts = resource.inputStream.use { mapper.readValue<SeedParts>(it) }

    val shops: List<Shop> = seed.shops.map {
        require(Regex("[A-Z]{3}").matches(it.currency)) { "${it.slug}: currency '${it.currency}'" }
        Shop(it.slug, it.name, it.currency, it.prices_include_vat, it.vat)
    }

    val products: List<Product> = seed.parts.map { it.toPart(controllers, shops.map { s -> s.slug }) } + seed.kits.map { kit ->
        require(kit.shop in shops.map { it.slug }) { "${kit.slug}: no shop '${kit.shop}'" }
        requireNotNull(designs.bySlug(kit.based_on)) { "${kit.slug}: based_on '${kit.based_on}' names no design" }
        Kit(
            slug = Slug(kit.slug), name = kit.name, shop = kit.shop, url = kit.url,
            basedOn = Slug(kit.based_on), readOn = LocalDate.parse(kit.read_on),
            contents = kit.contents.map {
                KitLine(it.quantity?.let { n -> Recorded.Known(n) } ?: Recorded.Unknown, it.text, it.kind?.toNeedKind())
            },
            needs = kit.needs.map { Requirement(it.fits.toFitting(kit.slug), Recorded.Known(it.quantity)) },
            notPriced = kit.not_priced,
        )
    }

    val listings: List<Listing> = seed.listings.map {
        require(it.shop in shops.map { s -> s.slug }) { "listing ${it.ref}: no shop '${it.shop}'" }
        require(products.any { p -> p.slug.value == it.product }) { "listing ${it.ref}: no product '${it.product}'" }
        Listing(it.shop, it.ref, Slug(it.product), it.label, it.pack, it.url)
    }

    private val diodePackages: Map<Slug, DiodePackage> = seed.diode_packages.associate {
        requireNotNull(designs.bySlug(it.design)) { "diode_package: '${it.design}' names no design" }
        Slug(it.design) to it.`package`.toDiodePackage()
    }

    init {
        val slugs = products.map { it.slug }
        // The build list's URL says "I have this" as `?diodes=own` (#113).
        require(Slug("own") !in slugs) { "'own' is not a product slug: the build list's URL uses it" }
        require(slugs.distinct() == slugs) { "a product slug is used twice: $slugs" }
        val refs = listings.map { it.shop to it.ref }
        refs.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()
            ?.let { (shop, ref) -> throw IllegalArgumentException("two listings for $shop $ref") }
        require(seed.diode_packages.map { it.design }.distinct().size == seed.diode_packages.size) {
            "two diode packages for one design"
        }
    }

    /** Measured from the board file where a row says so; `Unknown` otherwise, never "no diodes". */
    fun diodePackage(design: Slug): Recorded<DiodePackage> =
        diodePackages[design]?.let { Recorded.Known(it) } ?: Recorded.Unknown
}

private fun SeedPart.toPart(controllers: ControllerCatalog, shops: List<String>): Part {
    if (dev_board != null) {
        require(name == null && fits == null) { "$slug: a dev board's name and footprint come from its catalogue row" }
        val board = requireNotNull(controllers.bySlug(dev_board)) {
            "$slug: dev_board '$dev_board' is not in seed/controllers.json"
        }
        return Part.devBoard(Slug(slug), board)
    }
    val fitting = requireNotNull(fits) { "$slug: no fits" }.toFitting(slug)
    require(switch_kind == null || fitting is Fitting.Switch) { "$slug: only a switch has a switch_kind" }
    val switch = (fitting as? Fitting.Switch)?.let {
        SwitchSpec(switch_kind?.let { k -> Recorded.Known(k.toSwitchKind()) } ?: Recorded.Unknown, spec?.toStated(slug, shops))
    }
    require(spec == null || fitting is Fitting.Switch) { "$slug: only a switch has a spec" }
    return Part(Slug(slug), requireNotNull(name) { "$slug: no name" }, fitting, mpn, lcsc, switch = switch)
}

private fun SeedFits.toFitting(where: String): Fitting {
    val named = listOfNotNull(controller_socket, switch_socket, diode, switch)
    require(named.size == 1) { "$where: fits names one of controller_socket, switch_socket, diode or switch" }
    return when {
        switch != null -> Fitting.Switch(switch.map { it.toShelfSwitchFootprint(where) }.toSet())
        controller_socket != null -> Fitting.ControllerSocket(controller_socket.map { it.toFootprint() }.toSet())
        switch_socket != null -> Fitting.SwitchSocket(switch_socket.map { token ->
            token.toSwitchMount().also { require(it in SOCKET_MOUNTS) { "$where: '$token' is not a socket you buy" } }
        }.toSet())
        else -> Fitting.SwitchDiode(Recorded.Known(diode!!.toDiodePackage()))
    }
}

/** Throws on an unknown token, like every seed parser: a typo must not read as Unknown. */
private fun String.toDiodePackage(): DiodePackage = when (this) {
    "do_35" -> DiodePackage.Do35
    "sod_123" -> DiodePackage.Sod123
    "sod_123f" -> DiodePackage.Sod123F
    "sod_323" -> DiodePackage.Sod323
    "sod_523" -> DiodePackage.Sod523
    else -> throw IllegalArgumentException("unknown diode package '$this'")
}

private fun String.toNeedKind(): NeedKind = when (this) {
    "controller" -> NeedKind.Controller
    "switch_sockets" -> NeedKind.SwitchSockets
    "diodes" -> NeedKind.Diodes
    "pcb" -> NeedKind.Pcb
    "switches" -> NeedKind.Switches
    else -> throw IllegalArgumentException("unknown need kind '$this'")
}

/** The design seed's footprint tokens, less `bespoke`: one design's own switch is on no shelf. */
private fun String.toShelfSwitchFootprint(where: String): SwitchFootprint = when (this) {
    "mx" -> SwitchFootprint.Mx
    "choc_v1" -> SwitchFootprint.ChocV1
    "choc_v2" -> SwitchFootprint.ChocV2
    "alps" -> SwitchFootprint.Alps
    "choc_mini" -> SwitchFootprint.ChocMini
    "kailh_x" -> SwitchFootprint.KailhX
    else -> throw IllegalArgumentException("$where: unknown switch footprint '$this'")
}

/** The vendor's word, strictly: "silent" alone is not a kind. */
private fun String.toSwitchKind(): SwitchKind = when (this) {
    "linear" -> SwitchKind.Linear
    "tactile" -> SwitchKind.Tactile
    "silent_linear" -> SwitchKind.SilentLinear
    "silent_tactile" -> SwitchKind.SilentTactile
    "clicky" -> SwitchKind.Clicky
    else -> throw IllegalArgumentException("unknown switch kind '$this'")
}

/** Snake case to match the JSON verbatim, as the other seed readers do. */
private data class SeedParts(
    val shops: List<SeedShop>,
    val parts: List<SeedPart>,
    val kits: List<SeedKit>,
    val listings: List<SeedListing>,
    val diode_packages: List<SeedDiodePackage>,
)

private data class SeedShop(
    val slug: String,
    val name: String,
    val currency: String,
    val prices_include_vat: Boolean,
    val vat: Map<String, Int>,
)

private data class SeedPart(
    val slug: String,
    val name: String? = null,
    val fits: SeedFits? = null,
    val dev_board: String? = null,
    val mpn: String? = null,
    val lcsc: String? = null,
    val switch_kind: String? = null,
    val spec: SeedSpec? = null,
)

private fun SeedSpec.toStated(slug: String, shops: List<String>): StatedSpec {
    require(per in shops) { "$slug: spec per '$per' is no shop" }
    fun <T : Any> T?.rec(): Recorded<T> = this?.let { Recorded.Known(it) } ?: Recorded.Unknown
    return try {
        StatedSpec(
            per = per, readOn = LocalDate.parse(read_on),
            actuationForceGf = actuation_force_gf.rec(), bottomOutForceGf = bottom_out_force_gf.rec(),
            actuationMm = actuation_mm.rec(), travelMm = travel_mm.rec(),
            factoryLubed = factory_lubed.rec(), pins = pins.rec(),
            housing = housing.rec(), stem = stem.rec(), vendorSays = vendor_says.rec(),
        )
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$slug: ${e.message}", e)
    }
}

/** A switch's [StatedSpec] as `seed/parts.toml` writes it. */
private data class SeedSpec(
    val per: String,
    val read_on: String,
    val actuation_force_gf: Double? = null,
    val bottom_out_force_gf: Double? = null,
    val actuation_mm: Double? = null,
    val travel_mm: Double? = null,
    val factory_lubed: Boolean? = null,
    val pins: Int? = null,
    val housing: String? = null,
    val stem: String? = null,
    val vendor_says: String? = null,
)

private data class SeedFits(
    val controller_socket: List<String>? = null,
    val switch_socket: List<String>? = null,
    val diode: String? = null,
    val switch: List<String>? = null,
)

private data class SeedKit(
    val slug: String,
    val name: String,
    val shop: String,
    val url: String,
    val based_on: String,
    val read_on: String,
    val contents: List<SeedKitLine>,
    val needs: List<SeedNeed>,
    val not_priced: List<String>,
)

/** Null [quantity] is the vendor giving no count. */
private data class SeedKitLine(val quantity: Int? = null, val text: String, val kind: String? = null)

private data class SeedNeed(val fits: SeedFits, val quantity: Int)

private data class SeedListing(
    val shop: String,
    val ref: String,
    val product: String,
    val label: String? = null,
    val pack: Int,
    val url: String,
)

private data class SeedDiodePackage(val design: String, val `package`: String, val source: String)
