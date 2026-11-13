package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFit
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.fitFor
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import java.time.Duration
import java.time.Instant

/*
 * The build list (#112, #113): what a design needs, against each way to get it, priced
 * from what the shops charge today. Pure: the caller hands in the catalogue, the latest
 * offers and the time, so a test pins every outcome with literals.
 */

/**
 * What the design needs (rows) against each way to get it (columns): "Buy it yourself",
 * then one column per kit based on the design. [notes] say where a reader's pick from the
 * URL could not be used. [licence] is the design's, shown beside each kit's based-on link.
 */
data class BuildList(val columns: List<Column>, val rows: List<Row>, val notes: List<PickNote>, val licence: Licensing)

/**
 * [kit] null is "Buy it yourself", always `columns[0]`; then kits in seed order, never
 * sorted by total. [shops] are the shops this column buys from, the kit's first.
 */
data class Column(val kit: Kit?, val kitOffers: List<PricedOffer>, val shops: List<String>, val total: Total)

/**
 * One need. [need] is the design's own. [offers] and [unseen] feed the row's picker: the
 * self-sourced line's, or the first kit line that buys this kind when the design's need
 * is not a buy. [pick] is the reader's pick in effect, null when none was given or it was
 * not used anywhere. [cells] has one cell per column.
 */
data class Row(
    val kind: NeedKind,
    val need: Need,
    val offers: List<PricedOffer>,
    val unseen: List<UnseenListing>,
    val pick: Pick?,
    val cells: List<Cell>,
)

sealed interface Cell {
    /** [byReader] when the reader picked it; otherwise it is the line's default. */
    data class Picked(val offer: PricedOffer, val byReader: Boolean) : Cell
    data class InKit(val line: KitLine) : Cell
    data object Owned : Cell
    /** The only gap: a column with one has no total. */
    data class NotPriced(val reason: UnpricedReason) : Cell
    /**
     * A taste row (#115) the reader has not picked. Left out of the total, and said so,
     * where no column covers the row; a gap where another column does (see [totalOf]).
     */
    data object Choose : Cell
    data class NothingToBuy(val why: NoneReason) : Cell
}

/** No number exists while there is a gap: never a partial sum, and never a sort key. */
sealed interface Total {
    /** [without] is the taste rows no column covers, left out of every column alike. */
    data class Complete(val grossMinor: Long, val without: List<NeedKind> = emptyList()) : Total
    /** Row order, then the kit's own price. */
    data class Incomplete(val gaps: List<LeftOut>) : Total
}

/**
 * A reader's choice for one row, from the URL. [raw] is the typed text, never trusted as a
 * slug. [shop] is the shop the reader chose for that product (#115), typed text as well;
 * null is the cheapest countable offer.
 */
sealed interface Pick {
    data object Own : Pick
    data class Product(val raw: String, val shop: String? = null) : Pick
}

sealed interface PickNote {
    val kind: NeedKind
    /** No offer on this row is a product with that slug: a typo, or a part for another row. */
    data class NotListed(override val kind: NeedKind) : PickNote
    /** Listed, but out of stock, stale or not a fit in [columns] (null is "Buy it yourself"), which show the default. */
    data class NotCounted(override val kind: NeedKind, val product: Product, val columns: List<Kit?>) : PickNote
    /** The shop chosen for [product] has no offer for it we can count, so the cheapest that counts is used. */
    data class ShopNotCounted(override val kind: NeedKind, val product: Product) : PickNote
}

/**
 * One need and every offer that fits it, in [OFFER_ORDER]. Offers that cannot count --
 * out of stock, stale, not a fit -- stay listed so a reader sees why the line is empty.
 * [unseen] is the listings that would answer it but nobody has looked at yet: Unknown,
 * never "no shop sells one".
 */
data class Line(val need: Need, val offers: List<PricedOffer>, val unseen: List<UnseenListing> = emptyList()) {
    /** The line's default: the first offer a reader could act on today. */
    val counted: PricedOffer? get() = offers.firstOrNull { it.countable }
}

/** A listing in the seed with no look at it yet, so no price and no stock. Never counted. */
data class UnseenListing(val listing: Listing, val part: Part, val fit: OfferFit)

/** What a line of known quantity costs at one offer. */
data class LineCost(val packs: Int, val spare: Int, val grossMinor: Long)

/**
 * One offer priced for one line, VAT in. [line] is `Unknown` when the need's quantity
 * is: the pack price still shows, and nothing is counted.
 */
data class PricedOffer(
    val offer: Offer,
    val product: Product,
    val packGrossMinor: Long,
    val line: Recorded<LineCost>,
    val stale: Boolean,
    val fit: OfferFit,
) {
    val countable: Boolean
        get() = line is Recorded.Known && offer.seen.stock != Stock.OutOfStock && !stale && fit.counts
}

/** Whether a part answers the line, carrying the reason when it may not. */
sealed interface OfferFit {
    data object Fits : OfferFit

    /** A dev board in the design's socket, with `fitFor`'s firmware verdict. Only [ControllerFit.Runs] counts. */
    data class Controller(val fit: ControllerFit) : OfferFit

    /** A near match nobody has ruled on: a SOD-123F diode on SOD-123 pads, or any diode on unread pads. */
    data object NotRuledAFit : OfferFit

    /**
     * The part's interface and the line's do not meet: an Alps switch on an MX board.
     * Listed on a long row (switches) so a picker can count what does not fit and say
     * why; never counted.
     */
    data object DoesNotFit : OfferFit
}

private val OfferFit.counts: Boolean
    get() = when (this) {
        OfferFit.Fits -> true
        is OfferFit.Controller -> fit is ControllerFit.Runs
        OfferFit.NotRuledAFit -> false
        OfferFit.DoesNotFit -> false
    }

sealed interface LeftOut {
    data class Unpriced(val kind: NeedKind, val reason: UnpricedReason) : LeftOut

    /** No offer for the kit itself could be counted. */
    data object KitPrice : LeftOut
}

enum class UnpricedReason {
    NotRecorded, QuantityNotRecorded, OutOfSlice, NoOfferCounted,
    /** The PCB: measured, and nobody sells it here yet (fab prices postponed, 2026-10-04). */
    FabNotPriced,
    /** A taste row not picked yet, while another column covers it. */
    NotChosen,
}

/** How many packs a line takes, and how many parts are left over. */
data class Packs(val packs: Int, val spare: Int)

/** Rounded up: 58 sockets sold in tens is six packs, two spare. [pack] is read by a person. */
fun packsFor(quantity: Int, pack: Int): Packs {
    require(quantity >= 1) { "a line asks for at least one, was $quantity" }
    require(pack >= 1) { "a pack holds at least one, was $pack" }
    val packs = (quantity + pack - 1) / pack
    return Packs(packs, packs * pack - quantity)
}

/**
 * The price a reader pays for [packs] packs at [netMinor] each, in minor units with VAT
 * added. VAT goes on the whole line and is rounded half up once, so six packs at 1.875
 * cost 11.25 and not 6 x 1.88.
 */
fun gross(netMinor: Long, vatPercent: Int, packs: Int = 1): Long {
    require(netMinor >= 0 && vatPercent >= 0 && packs >= 1) { "net $netMinor, VAT $vatPercent%, $packs packs" }
    return (packs * netMinor * (100 + vatPercent) + 50) / 100
}

/** A price older than this is shown with its date, greyed, and kept out of the total. */
val STALE_AFTER: Duration = Duration.ofDays(7)

/**
 * In stock, then backorder, then out of stock; within each, the cheaper line first. Both
 * keys are facts about the offer, so the order does not move as the corpus fills in.
 */
private val OFFER_ORDER: Comparator<PricedOffer> =
    compareBy<PricedOffer> { it.offer.seen.stock.ordinal }
        .thenBy { it.line.knownOrNull()?.grossMinor ?: it.packGrossMinor }
        .thenBy { it.offer.listing.shop }
        .thenBy { it.offer.listing.ref }

private class Pricing(
    products: List<Product>,
    shops: List<Shop>,
    listings: List<Listing>,
    private val offers: List<Offer>,
    private val region: Region,
    private val now: Instant,
) {
    private val products = products.associateBy { it.slug }
    private val shops = shops.associateBy { it.slug }
    private val unseen = offers.map { it.listing.shop to it.listing.ref }.toSet()
        .let { seen -> listings.filter { (it.shop to it.ref) !in seen } }

    init {
        // One total adds prices from several shops, so they must share a currency.
        require(shops.map { it.currency }.distinct().size <= 1) { "shops in more than one currency" }
    }

    /** [controllerFit] is the verdict for a dev board in the socket; null leaves it off the line. */
    fun line(need: Need, controllerFit: (ControllerBoard) -> OfferFit?): Line {
        if (need !is Need.Buy) return Line(need, emptyList())
        val quantity = need.requirement.quantity.knownOrNull()
        val priced = offers.mapNotNull { offer ->
            val part = products[offer.listing.product] as? Part ?: return@mapNotNull null
            val fit = fitOf(need.requirement.fitting, part, controllerFit) ?: return@mapNotNull null
            price(offer, part, quantity, fit)
        }
        val notLookedAt = unseen.mapNotNull { listing ->
            val part = products[listing.product] as? Part ?: return@mapNotNull null
            fitOf(need.requirement.fitting, part, controllerFit)?.let { UnseenListing(listing, part, it) }
        }
        return Line(need, priced.sortedWith(OFFER_ORDER), notLookedAt)
    }

    fun kitOffers(kit: Kit): List<PricedOffer> =
        offers.filter { it.listing.product == kit.slug }
            .map { price(it, kit, 1, OfferFit.Fits) }
            .sortedWith(OFFER_ORDER)

    fun price(offer: Offer, product: Product, quantity: Int?, fit: OfferFit): PricedOffer {
        val shop = requireNotNull(shops[offer.listing.shop]) { "no shop ${offer.listing.shop}" }
        require(offer.seen.currency == shop.currency) {
            "${shop.slug} ${offer.listing.ref} priced in ${offer.seen.currency}, the shop in ${shop.currency}"
        }
        val vat = if (shop.pricesIncludeVat) 0
                  else requireNotNull(shop.vat[region.code]) { "${shop.slug} has no VAT rate for ${region.code}" }
        val line = quantity?.let { packsFor(it, offer.listing.pack) }
            ?.let { LineCost(it.packs, it.spare, gross(offer.seen.netMinor, vat, it.packs)) }
        return PricedOffer(
            offer = offer,
            product = product,
            packGrossMinor = gross(offer.seen.netMinor, vat),
            line = line?.let { Recorded.Known(it) } ?: Recorded.Unknown,
            stale = Duration.between(offer.seen.observedAt, now) > STALE_AFTER,
            fit = fit,
        )
    }
}

/** Null when the part does not plug into this interface at all, so it is not on the line. */
private fun fitOf(wanted: Fitting, part: Part, controllerFit: (ControllerBoard) -> OfferFit?): OfferFit? =
    when (wanted) {
        is Fitting.ControllerSocket -> part.devBoard
            ?.takeIf { it.footprint in wanted.footprints }
            ?.let(controllerFit)
        is Fitting.SwitchSocket -> (part.fits as? Fitting.SwitchSocket)
            ?.takeIf { (it.mounts intersect wanted.mounts).isNotEmpty() }
            ?.let { OfferFit.Fits }
        is Fitting.Switch -> (part.fits as? Fitting.Switch)?.let {
            if ((it.footprints intersect wanted.footprints).isNotEmpty()) OfferFit.Fits else OfferFit.DoesNotFit
        }
        is Fitting.SwitchDiode -> (part.fits as? Fitting.SwitchDiode)?.let { diode ->
            val exact = wanted.pkg is Recorded.Known && diode.pkg == wanted.pkg
            if (exact) OfferFit.Fits else OfferFit.NotRuledAFit
        }
    }

fun buildList(
    design: Design,
    diodePackage: Recorded<DiodePackage>,
    products: List<Product>,
    shops: List<Shop>,
    listings: List<Listing>,
    offers: List<Offer>,
    region: Region,
    now: Instant,
    rules: FirmwareRules,
    picks: Map<NeedKind, Pick> = emptyMap(),
): BuildList {
    val pricing = Pricing(products, shops, listings, offers, region, now)
    val needs = design.needs(diodePackage)
    val kits = products.filterIsInstance<Kit>().filter { it.basedOn == design.core.slug }

    val selfSlots: List<Slot> = needs.map { need ->
        when (need) {
            is Need.Buy -> Slot.Buy(pricing.line(need) { board ->
                design.fitFor(board.footprint, board.firmware, board.mcuFamily, rules)?.let { OfferFit.Controller(it) }
            })
            is Need.NothingToBuy -> Slot.Fixed(Cell.NothingToBuy(need.why))
            is Need.NotRecorded -> Slot.Fixed(Cell.NotPriced(UnpricedReason.NotRecorded))
            is Need.OutOfSlice -> Slot.Fixed(Cell.NotPriced(UnpricedReason.OutOfSlice))
            is Need.Boards -> Slot.Fixed(Cell.NotPriced(UnpricedReason.FabNotPriced))
        }
    }
    // The kit's facts are the vendor's: a dev board fits on footprint alone, with no
    // firmware verdict, and a kind the vendor says nothing about is not recorded.
    val kitSlots: List<List<Slot>> = kits.map { kit ->
        needs.map { need ->
            val buys = kit.needs.firstOrNull { it.fitting.kind == need.kind }
            val holds = kit.contents.firstOrNull { it.kind == need.kind }
            when {
                buys != null -> Slot.Buy(pricing.line(Need.Buy(buys, setOf(Basis.PerVendor))) { OfferFit.Fits })
                holds != null -> Slot.Fixed(Cell.InKit(holds))
                else -> Slot.Fixed(Cell.NotPriced(UnpricedReason.NotRecorded))
            }
        }
    }
    val columnKits: List<Kit?> = listOf(null) + kits
    val slots: List<List<Slot>> = listOf(selfSlots) + kitSlots

    val notes = mutableListOf<PickNote>()
    val rows = needs.mapIndexed { r, need ->
        val across = slots.map { it[r] }
        val resolved = resolve(need.kind, across, picks[need.kind], columnKits)
        resolved.note?.let { notes += it }
        val source = (across.first() as? Slot.Buy ?: across.firstNotNullOfOrNull { it as? Slot.Buy })?.line
        Row(need.kind, need, source?.offers.orEmpty(), source?.unseen.orEmpty(), resolved.pick, resolved.cells)
    }

    val columns = columnKits.mapIndexed { c, kit ->
        val kitOffers = kit?.let { pricing.kitOffers(it) }.orEmpty()
        Column(kit, kitOffers, shopsOf(kit, rows.map { it.cells[c] }), totalOf(kit, kitOffers, rows, c))
    }
    return BuildList(columns, rows, notes, design.core.licensing)
}

/** One column's state for one row before any pick: a line to price, or a settled cell. */
private sealed interface Slot {
    data class Buy(val line: Line) : Slot
    data class Fixed(val cell: Cell) : Slot
}

private data class Resolved(val cells: List<Cell>, val pick: Pick?, val note: PickNote?)

/** A taste row has no default: the reader chooses, so the cell says so, offers or none. */
private fun Line.default(): Cell =
    if (need.kind.taste && need is Need.Buy) Cell.Choose
    else counted?.let { Cell.Picked(it, byReader = false) }
        ?: Cell.NotPriced(
            if ((need as? Need.Buy)?.requirement?.quantity is Recorded.Known) UnpricedReason.NoOfferCounted
            else UnpricedReason.QuantityNotRecorded
        )

/**
 * A pick applies to the whole row. A product pick is only ever compared with a listed
 * product's slug, so a typed value never becomes a [Slug] and never throws.
 */
private fun resolve(kind: NeedKind, across: List<Slot>, pick: Pick?, columns: List<Kit?>): Resolved {
    val defaults = across.map { slot ->
        when (slot) {
            is Slot.Buy -> slot.line.default()
            is Slot.Fixed -> slot.cell
        }
    }
    return when (pick) {
        null -> Resolved(defaults, null, null)
        Pick.Own -> {
            // You pay for a kit either way, so what is in it stays, and so does nothing to buy.
            val owned = defaults.map { if (it is Cell.Picked || it is Cell.NotPriced || it is Cell.Choose) Cell.Owned else it }
            Resolved(owned, Pick.Own.takeIf { owned != defaults }, null)
        }
        is Pick.Product -> {
            val lines = across.map { (it as? Slot.Buy)?.line }
            val listed = lines.filterNotNull().firstNotNullOfOrNull { line ->
                line.offers.firstOrNull { it.product.slug.value == pick.raw }?.product
                    ?: line.unseen.firstOrNull { it.part.slug.value == pick.raw }?.part
            } ?: return Resolved(defaults, null, PickNote.NotListed(kind))
            val fellBack = mutableListOf<Kit?>()
            var shopUsed = false
            var shopMissed = false
            val cells = lines.mapIndexed { c, line ->
                if (line == null) return@mapIndexed defaults[c]
                val counted = line.offers.filter { it.countable && it.product.slug.value == pick.raw }
                val atShop = pick.shop?.let { s -> counted.firstOrNull { it.offer.listing.shop == s } }
                if (pick.shop != null && counted.isNotEmpty()) { if (atShop != null) shopUsed = true else shopMissed = true }
                val match = atShop ?: counted.firstOrNull()
                if (match == null) { fellBack += columns[c]; defaults[c] } else Cell.Picked(match, byReader = true)
            }
            val used = cells.any { it is Cell.Picked && it.byReader }
            val note = fellBack.takeIf { it.isNotEmpty() }?.let { PickNote.NotCounted(kind, listed, it) }
                ?: PickNote.ShopNotCounted(kind, listed).takeIf { shopMissed }
            val inEffect = if (shopUsed && !shopMissed) pick else Pick.Product(pick.raw)
            Resolved(cells, inEffect.takeIf { used }, note)
        }
    }
}

/** The kit's shop first, then each priced cell's, in row order. */
private fun shopsOf(kit: Kit?, cells: List<Cell>): List<String> =
    (listOfNotNull(kit?.shop) + cells.filterIsInstance<Cell.Picked>().map { it.offer.offer.listing.shop }).distinct()

/**
 * The owner's rule for taste rows (2026-10-05): an unpicked one is left out of every
 * column, and named, while no column covers it; once a kit includes it, or a pick or a
 * gap stands in another column, a "choose" cell is a gap, so the columns never compare
 * different things.
 */
private fun totalOf(kit: Kit?, kitOffers: List<PricedOffer>, rows: List<Row>, column: Int): Total {
    val without = mutableListOf<NeedKind>()
    val gaps = rows.mapNotNull { row ->
        when (val cell = row.cells[column]) {
            is Cell.NotPriced -> LeftOut.Unpriced(row.kind, cell.reason)
            Cell.Choose -> if (row.coveredSomewhere()) LeftOut.Unpriced(row.kind, UnpricedReason.NotChosen)
                           else { without += row.kind; null }
            else -> null
        }
    }.toMutableList<LeftOut>()
    val kitCost = kitOffers.firstOrNull { it.countable }?.line?.knownOrNull()
    if (kit != null && kitCost == null) gaps += LeftOut.KitPrice
    if (gaps.isNotEmpty()) return Total.Incomplete(gaps)
    val parts = rows.sumOf { row ->
        (row.cells[column] as? Cell.Picked)?.offer?.line?.knownOrNull()?.grossMinor ?: 0L
    }
    return Total.Complete((kitCost?.grossMinor ?: 0L) + parts, without)
}

/** Some column has it in the box, priced, or as a gap of its own. */
private fun Row.coveredSomewhere(): Boolean =
    cells.any { it is Cell.InKit || it is Cell.Picked || it is Cell.NotPriced }
