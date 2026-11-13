package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.BoardOutline
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.parts.Basis
import net.keyboardbuilders.keyboardbuilders.parts.BoardsMade
import net.keyboardbuilders.keyboardbuilders.parts.BuildList
import net.keyboardbuilders.keyboardbuilders.parts.Cell
import net.keyboardbuilders.keyboardbuilders.parts.Column
import net.keyboardbuilders.keyboardbuilders.parts.DiodePackage
import net.keyboardbuilders.keyboardbuilders.parts.Fitting
import net.keyboardbuilders.keyboardbuilders.parts.Kit
import net.keyboardbuilders.keyboardbuilders.parts.LeftOut
import net.keyboardbuilders.keyboardbuilders.parts.Listing
import net.keyboardbuilders.keyboardbuilders.parts.Need
import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.NoneReason
import net.keyboardbuilders.keyboardbuilders.parts.OfferFit
import net.keyboardbuilders.keyboardbuilders.parts.Part
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.PickNote
import net.keyboardbuilders.keyboardbuilders.parts.PricedOffer
import net.keyboardbuilders.keyboardbuilders.parts.Product
import net.keyboardbuilders.keyboardbuilders.parts.Region
import net.keyboardbuilders.keyboardbuilders.parts.Row
import net.keyboardbuilders.keyboardbuilders.parts.STALE_AFTER
import net.keyboardbuilders.keyboardbuilders.parts.Shop
import net.keyboardbuilders.keyboardbuilders.parts.Stock
import net.keyboardbuilders.keyboardbuilders.parts.Total
import net.keyboardbuilders.keyboardbuilders.parts.UnpricedReason
import net.keyboardbuilders.keyboardbuilders.parts.UnseenListing
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * `/designs/:slug/build` (#112, #113). Every sentence on the page is built here, as the
 * rest of the web layer does it: the template prints strings and walks lists, and makes
 * no decision over a domain type.
 */

data class BuildListPage(
    val name: String,
    val slug: String,
    /** Whose prices, and what is and is not in them. */
    val pricing: String,
    /** Where a pick in the URL could not be used. Never repeats what was typed. */
    val notes: List<String>,
    val grid: GridView,
    val kits: List<KitView>,
    /** What no column covers, so a total does not read as the whole build. */
    val notPriced: String,
)

/** [heads], each row's cells and [foots] line up: one per column, "Buy it yourself" first. */
data class GridView(val heads: List<ColumnHead>, val rows: List<RowView>, val foots: List<ColumnFoot>)

/** [price] is the kit's own: "€43.75, Cherry MX-compatible, black, in stock". */
data class ColumnHead(val name: String, val url: String?, val price: String?)

/**
 * One need: its label and [what] in the pinned first column, then a cell per column.
 * [offers] is what a cell with [CellView.picker] opens on; [own] is the "I have this"
 * link, null where the row buys nothing.
 */
data class RowView(
    val id: String,
    val label: String,
    val what: String,
    val whatCss: String,
    val offers: List<OfferRow>,
    val own: OwnLink?,
    val cells: List<CellView>,
)

data class OwnLink(val text: String, val href: String)

/**
 * [css] is "unrecorded" for a gap, so it reads apart from the facts. Where [picker], [text]
 * opens the row's offers: a priced line with something else to choose, or a gap that no
 * offer could fill, which then shows the offers and why each does not count. A cell
 * never links to a shop itself; a click on a part is a choice, not a trip away.
 */
data class CellView(val text: String, val sub: String?, val css: String, val picker: Boolean = false, val href: String? = null)

/**
 * NN/g's limit for a plain list (parts-picker-ux.md): past about 15 options a row is
 * picked on a page of its own, as a taste row always is (#115). The one place the rule
 * lives. Only the switches page exists so far.
 */
const val CELL_PICKER_MAX = 15

val Row.picksOnItsOwnPage: Boolean
    get() = kind.taste || offers.size + unseen.size > CELL_PICKER_MAX

/** [total] is "€95.50 before shipping" or "Incomplete (2 lines)", the second never with a price. */
data class ColumnFoot(val total: String, val gaps: String?, val shipping: List<String>)

/** [mark] is the tick on the offer in effect. [pick] is the link that picks it, null when it cannot count or is in effect. [url] is the shop's page. */
data class OfferRow(val name: String, val url: String, val detail: String, val css: String, val mark: String, val pick: String?)

data class KitView(
    val name: String,
    val url: String,
    val basedOnSlug: String,
    val basedOnName: String,
    val licence: String,
    val offersSummary: String?,
    val offers: List<OfferRow>,
    val contentsIntro: String,
    val contents: List<String>,
    val notPriced: String?,
)

fun BuildList.toPage(design: Design, shops: List<Shop>, region: Region): BuildListPage {
    val shopNames = shops.associate { it.slug to it.name }
    val slug = design.core.slug.value
    return BuildListPage(
        name = design.core.name.value,
        slug = slug,
        pricing = pricingNote(shops, region),
        notes = notes.map { it.describe(this) },
        grid = GridView(
            heads = columns.map { it.head(shopNames) },
            rows = rows.map { it.toView(slug, rows, columns, shopNames) },
            foots = columns.map { it.foot(shopNames) },
        ),
        kits = columns.mapNotNull { c -> c.kit?.let { c.toKitView(it, design, shopNames) } },
        notPriced = "Only the " + NeedKind.entries.filter { it.pricedHere }.map { it.inSentence() }.let {
            it.dropLast(1).joinToString(", ") + " and " + it.last()
        } + " are priced here. The keycaps and everything else the board needs are not.",
    )
}

private const val SELF_SOURCED = "Buy it yourself"

private fun Column.name(): String = kit?.name ?: SELF_SOURCED

private fun Column.head(shops: Map<String, String>): ColumnHead {
    val kit = kit ?: return ColumnHead(SELF_SOURCED, null, null)
    val counted = kitOffers.firstOrNull { it.countable }
    val price = when {
        counted != null -> listOfNotNull(money(counted.packGrossMinor), counted.offer.listing.label,
                                         counted.offer.seen.stock.describe()).joinToString(", ")
        kitOffers.isEmpty() -> "No price seen yet"
        else -> "No price we can count"
    }
    return ColumnHead(kit.name, kit.url, price)
}

private fun Column.foot(shops: Map<String, String>): ColumnFoot {
    val shipping = this.shops.map { "Shipping from ${shops[it] ?: it}: not priced yet" }
    return when (val t = total) {
        is Total.Complete -> ColumnFoot(
            "${money(t.grossMinor)} before shipping" +
                (if (t.without.isEmpty()) "" else ", without " + t.without.joinToString(" and ") { it.inSentence() }),
            null, shipping,
        )
        is Total.Incomplete -> ColumnFoot(
            "Incomplete (${t.gaps.size} ${if (t.gaps.size == 1) "line" else "lines"})",
            "Not priced: " + t.gaps.joinToString(", ") { it.describe() },
            shipping,
        )
    }
}

private fun LeftOut.describe(): String = when (this) {
    LeftOut.KitPrice -> "the kit itself"
    is LeftOut.Unpriced -> kind.inSentence() + if (reason == UnpricedReason.NotChosen) " (not chosen yet)" else ""
}

internal fun PickNote.describe(grid: BuildList): String {
    // A taste row (#115) has no default to fall back on: the row is still to choose.
    val fallback = if (kind.taste) "none is picked" else "the default is shown"
    return when (this) {
        is PickNote.NotListed -> "We could not find your pick for the ${kind.inSentence()}, so $fallback."
        is PickNote.NotCounted -> {
            val usedNowhere = grid.rows.single { it.kind == kind }.pick == null
            val where = when {
                !usedNowhere -> "under " + columns.joinToString(" and ") { it?.name ?: SELF_SOURCED } +
                    ", so $fallback there"
                columns.size == 1 -> "here, so $fallback"
                columns.size == 2 -> "in either column, so $fallback"
                else -> "in any column, so $fallback"
            }
            "Your pick for the ${kind.inSentence()}, ${(product as? Part)?.name ?: product.name}, can't be counted $where."
        }
        is PickNote.ShopNotCounted ->
            "The shop you chose for ${(product as? Part)?.name ?: product.name} has no offer we can count, so the cheapest is shown."
    }
}

private fun Row.toView(slug: String, rows: List<Row>, columns: List<Column>, shops: Map<String, String>): RowView {
    // The offer in effect is the one a column priced from this row's own list.
    val inEffect = cells.filterIsInstance<Cell.Picked>().map { it.offer }.firstOrNull { o -> offers.any { it === o } }
    // A taste row has no default, so every countable offer is a pick of its own.
    val default = if (kind.taste) null else offers.firstOrNull { it.countable }
    val buys = cells.any { it is Cell.Picked || it is Cell.NotPriced || it is Cell.Owned || it is Cell.Choose }
    val choices = offers.size + unseen.size
    return RowView(
        id = "need-${kind.param}",
        label = kind.label(),
        what = need.what(),
        whatCss = if (need is Need.NotRecorded || need is Need.OutOfSlice ||
                      (need is Need.Boards && need.size == Recorded.Unknown)) "unrecorded" else "",
        offers = offers.map { o ->
            val href = when {
                !o.countable || o === inEffect -> null
                o === default -> pickHref(slug, rows, kind, null)
                else -> pickHref(slug, rows, kind, Pick.Product(o.product.slug.value))
            }
            o.toRow(need, shops, counted = o === inEffect, pick = href)
        } + unseen.map { it.toRow(need, shops) },
        own = if (!buys) null
              else if (pick == Pick.Own) OwnLink("You have this · undo", pickHref(slug, rows, kind, null))
              else OwnLink("I have this", pickHref(slug, rows, kind, Pick.Own)),
        cells = cells.mapIndexed { c, cell ->
            val view = cell.toView(columns[c].kit, choices, kind)
            if (!picksOnItsOwnPage || !(view.picker || cell is Cell.Choose || cell is Cell.Picked)) view
            else view.copy(
                text = if (cell is Cell.Choose) view.text + " →" else view.text,
                picker = false,
                href = pickerHref(slug, rows, kind),
            )
        },
    )
}

internal fun Need.what(): String = when (this) {
    is Need.NothingToBuy -> "Nothing to buy: ${why.describe()}."
    is Need.NotRecorded -> kind.notRecorded()
    is Need.OutOfSlice -> "The board takes ${mounts.describe()} only. Those are not priced here yet."
    is Need.Boards -> describe() + "."
    is Need.Buy -> what() + ", " + basisText(null) + "."
}

private fun Cell.toView(kit: Kit?, choices: Int, kind: NeedKind): CellView = when (this) {
    is Cell.Picked -> {
        val cost = offer.line.knownOrNull()
        val listing = offer.offer.listing
        val price = when {
            cost == null -> money(offer.packGrossMinor)
            listing.pack > 1 -> "${cost.packs} packs of ${listing.pack} for ${money(cost.grossMinor)}"
            cost.packs > 1 -> "${cost.packs} for ${money(cost.grossMinor)}"
            else -> money(cost.grossMinor)
        }
        val notes = listOfNotNull(
            offer.offer.seen.stock.takeIf { it != Stock.InStock }?.describe(),
            "your pick".takeIf { byReader },
        )
        val name = (offer.product as? Part)?.nameOn(listing) ?: offer.product.name
        CellView(name, (listOf(price) + notes).joinToString(", "), "", picker = choices > 1)
    }
    is Cell.InKit -> CellView("In the kit", line.quantity.knownOrNull()?.let { "$it × ${line.text}" } ?: line.text, "")
    Cell.Owned -> CellView("You have this", null, "")
    Cell.Choose -> CellView("Choose ${kind.inSentence()}", null, "", picker = choices > 0)
    is Cell.NothingToBuy -> CellView("Nothing to buy", null, "")
    is Cell.NotPriced -> CellView(
        when {
            kit != null && reason == UnpricedReason.NotRecorded -> "Not priced: the vendor does not say"
            else -> "Not priced: " + reason.describe()
        },
        null, "unrecorded",
        picker = reason == UnpricedReason.NoOfferCounted && choices > 0,
    )
}

private fun Column.toKitView(kit: Kit, design: Design, shops: Map<String, String>): KitView {
    val shop = shops[kit.shop] ?: kit.shop
    val counted = kitOffers.firstOrNull { it.countable }
    return KitView(
        name = kit.name,
        url = kit.url,
        basedOnSlug = kit.basedOn.value,
        basedOnName = design.core.name.value,
        licence = "hardware licence ${design.core.licensing.hardware.describe()}",
        offersSummary = when (kitOffers.size) {
            0 -> null
            1 -> "1 variation"
            else -> "${kitOffers.size} variations"
        },
        offers = kitOffers.map { it.toRow(null, shops, counted = it === counted) },
        contentsIntro = "In the box, per $shop, read ${day(kit.readOn)}:",
        contents = kit.contents.map { line -> line.quantity.knownOrNull()?.let { "$it × ${line.text}" } ?: line.text },
        notPriced = kit.notPriced.takeIf { it.isNotEmpty() }
            ?.joinToString("; ", prefix = "Also needed, not priced here: ", postfix = "."),
    )
}

internal fun pricingNote(shops: List<Shop>, region: Region): String {
    val names = shops.joinToString(" and ") { it.name }
    val vat = shops.mapNotNull { shop -> shop.vat[region.code].takeUnless { shop.pricesIncludeVat } }.distinct()
    val withVat = when (vat.size) {
        1 -> "with ${region.describe()}'s ${vat.single()}% VAT added"
        else -> "with VAT"
    }
    return "Prices from $names, $withVat. Shipping is not included."
}

private fun Region.describe(): String = when (code) {
    "NO" -> "Norway"
    else -> code
}

private fun UnpricedReason.describe(): String = when (this) {
    UnpricedReason.NotRecorded -> "not recorded"
    UnpricedReason.QuantityNotRecorded -> "how many is not recorded"
    UnpricedReason.OutOfSlice -> "not on our list yet"
    UnpricedReason.NoOfferCounted -> "no offer we can count"
    UnpricedReason.FabNotPriced -> "fab prices not recorded yet"
    UnpricedReason.NotChosen -> "not chosen yet"
}

private fun NeedKind.label(): String = when (this) {
    NeedKind.Controller -> "Controller"
    NeedKind.SwitchSockets -> "Switch sockets"
    NeedKind.Diodes -> "Diodes"
    NeedKind.Pcb -> "PCB"
    NeedKind.Switches -> "Switches"
}

/** The label mid-sentence: "diodes", but "PCB". */
internal fun NeedKind.inSentence(): String = if (this == NeedKind.Pcb) label() else label().lowercase()

/** "About 142.5 × 106.3 mm, 2 boards (one per half)". */
private fun Need.Boards.describe(): String {
    val size = when (val s = size) {
        Recorded.Unknown -> "Size not recorded"
        is Recorded.Known -> when (val b = s.value) {
            is BoardSize.Each -> "About ${b.outline.mm()}"
            is BoardSize.Halves -> "About ${b.left.mm()} (left) and ${b.right.mm()} (right)"
        }
    }
    val boards = if (count == 1) "1 board" else "$count boards"
    val why = when (made) {
        BoardsMade.One -> ""
        BoardsMade.OnePerHalf -> " (one per half)"
        BoardsMade.Reversible -> " (reversible)"
        BoardsMade.BothHalvesInOneFile -> " (both halves in one file)"
    }
    return "$size, $boards$why"
}

private fun BoardOutline.mm(): String =
    String.format(Locale.ROOT, "%.1f × %.1f mm", widthMm, heightMm)

private fun NoneReason.describe(): String = when (this) {
    is NoneReason.OnTheBoard -> when (val chip = mcu) {
        Recorded.Unknown -> "the chip is on the board"
        is Recorded.Known -> "the ${chip.value.describe()} is on the board"
    }
    // The design page's own phrase, so the two pages cannot drift apart on a split.
    is NoneReason.DirectPin -> "each key has its own pin (${KeyWiring.DirectPin(pins).describe(perHalf)}), so there are no diodes"
    NoneReason.SolderedSwitches -> "the switches solder straight to the board"
    NoneReason.Analog -> "the switches are analog and take no diodes"
    NoneReason.Handwired -> "the keys are wired by hand, with no PCB"
}

/** Unknown upstream, said as such: never "none". */
private fun NeedKind.notRecorded(): String = when (this) {
    NeedKind.Controller -> "Which controller this board takes is not recorded."
    NeedKind.SwitchSockets -> "Which switch sockets this board takes is not recorded."
    NeedKind.Diodes -> "How this board's keys are wired is not recorded, so neither is whether it needs diodes."
    NeedKind.Pcb -> "Whether this design has a PCB is not recorded."
    NeedKind.Switches -> "Which switches this board takes is not recorded."
}

/** "58 × MX or Choc hotswap socket"; where the count is not recorded, says so. */
private fun Need.Buy.what(): String {
    val thing = when (val f = requirement.fitting) {
        is Fitting.ControllerSocket ->
            "dev board for a " + f.footprints.sortedBy { it.ordinal }.joinToString(" or ") { it.describe() } + " socket"
        is Fitting.SwitchSocket -> f.mounts.describe() + " hotswap socket"
        is Fitting.Switch -> f.footprints.inWords() + " switch"
        is Fitting.SwitchDiode -> when (val pkg = f.pkg) {
            Recorded.Unknown -> "diode (the board's diode package is not recorded)"
            is Recorded.Known -> "${pkg.value.describe()} diode"
        }
    }
    return when (val n = requirement.quantity) {
        Recorded.Unknown -> "$thing, how many not recorded"
        is Recorded.Known -> "${n.value} × $thing"
    }
}

private fun Need.Buy.basisText(vendor: String?): String =
    basis.sortedBy { it.ordinal }.joinToString(", ") { b ->
        when (b) {
            Basis.DesignFact -> "from the design's record"
            Basis.OnePerKey -> when (val name = layoutName) {
                Recorded.Unknown -> "one per key"
                is Recorded.Known -> "one per key of the ${name.value.value} layout"
            }
            Basis.OnePerHalf -> "one per half"
            Basis.BoardFile -> "package read off the board file"
            Basis.PerVendor -> vendor ?: "per the vendor"
        }
    }

private fun Set<SwitchMount>.describe(): String =
    sortedBy { it.ordinal }.joinToString(" or ") {
        when (it) {
            SwitchMount.Solder -> "solder"
            SwitchMount.MxSocket -> "MX"
            SwitchMount.ChocSocket -> "Choc"
            SwitchMount.MillMax -> "Mill-Max"
        }
    }

/** "MX or Choc v1", MX first, then as the design page lists the families. */
internal fun Set<SwitchFootprint>.inWords(): String =
    sortedBy { FOOTPRINT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: FOOTPRINT_ORDER.size }
        .joinToString(" or ") { it.describe() }

private val FOOTPRINT_ORDER: List<SwitchFootprint> = listOf(
    SwitchFootprint.Mx, SwitchFootprint.ChocV1, SwitchFootprint.ChocV2, SwitchFootprint.Alps,
    SwitchFootprint.ChocMini, SwitchFootprint.KailhX,
)

private fun DiodePackage.describe(): String = when (this) {
    DiodePackage.Do35 -> "DO-35"
    DiodePackage.Sod123 -> "SOD-123"
    DiodePackage.Sod123F -> "SOD-123F"
    DiodePackage.Sod323 -> "SOD-323"
    DiodePackage.Sod523 -> "SOD-523"
}

/**
 * "6 packs of 10 (2 spare) for €11.25, in stock, seen 4 Oct 2026", then why it does not
 * count where it does not. [line] is null for a kit's own offers, which are one each.
 */
internal fun PricedOffer.toRow(
    need: Need?, shops: Map<String, String>, counted: Boolean, pick: String? = null,
    /** False where the reason a part does not fit is said once above its offers (the picker page). */
    fitNoted: Boolean = true,
): OfferRow {
    val listing = offer.listing
    val price = when (val cost = this.line.knownOrNull()) {
        null -> if (listing.pack == 1) "${money(packGrossMinor)} each"
                else "${money(packGrossMinor)} per pack of ${listing.pack}"
        else -> when {
            listing.pack > 1 -> "${cost.packs} packs of ${listing.pack}" +
                (if (cost.spare > 0) " (${cost.spare} spare)" else "") + " for ${money(cost.grossMinor)}"
            cost.packs > 1 -> "${cost.packs} for ${money(cost.grossMinor)}"
            else -> money(cost.grossMinor)
        }
    }
    val notes = listOfNotNull(
        fitNote(fit, product, need)?.takeIf { fitNoted },
        "price more than ${STALE_AFTER.toDays()} days old, not counted".takeIf { stale },
    )
    val shop = shops[listing.shop] ?: listing.shop
    val detail = "$price at $shop, ${offer.seen.stock.describe()}, seen ${day(offer.seen.observedAt.atZone(ZoneOffset.UTC).toLocalDate())}" +
        notes.joinToString("") { " — $it" }
    val name = when (val p = product) {
        is Part -> p.nameOn(listing)
        is Kit -> listing.label ?: p.name
    }
    return OfferRow(
        name = name,
        url = listing.url,
        detail = detail,
        css = if (countable) "fits" else "excluded",
        mark = if (counted) "✓ " else "",
        pick = pick,
    )
}

/** "listed at 42. Keebs, no price seen yet": a listing nobody has looked at, so never counted. */
internal fun UnseenListing.toRow(need: Need, shops: Map<String, String>, fitNoted: Boolean = true): OfferRow {
    val notes = listOfNotNull(fitNote(fit, part, need)?.takeIf { fitNoted })
    return OfferRow(
        name = part.nameOn(listing),
        url = listing.url,
        detail = "listed at ${shops[listing.shop] ?: listing.shop}, no price seen yet" + notes.joinToString("") { " — $it" },
        css = "excluded",
        mark = "",
        pick = null,
    )
}

/**
 * "SMD" beside "1N4148W diode, SMD" says nothing; a colour or a variant would. A dev board
 * says "wireless" or "wired" (#115), so the reader sees what the controller's default chose.
 */
private fun Part.nameOn(listing: Listing): String =
    name + (listing.label?.takeUnless { name.contains(it, ignoreCase = true) }?.let { " ($it)" } ?: "") +
        (devBoard?.let { if (it.wireless) ", wireless" else ", wired" } ?: "")

internal fun fitNote(fit: OfferFit, product: Product, need: Need?): String? = when (val f = fit) {
    OfferFit.Fits -> null
    OfferFit.DoesNotFit -> {
        val wanted = ((need as? Need.Buy)?.requirement?.fitting as? Fitting.Switch)?.footprints
        val has = ((product as? Part)?.fits as? Fitting.Switch)?.footprints
        if (wanted == null || has == null) "does not fit"
        else "does not fit: ${has.inWords()}, the board takes ${wanted.inWords()}"
    }
    is OfferFit.Controller -> f.fit.note((product as? Part)?.name ?: product.name)
    OfferFit.NotRuledAFit -> {
        val wanted = ((need as? Need.Buy)?.requirement?.fitting as? Fitting.SwitchDiode)?.pkg?.knownOrNull()
        val has = ((product as? Part)?.fits as? Fitting.SwitchDiode)?.pkg?.knownOrNull()
        when {
            wanted == null -> "not ruled a fit: the board's diode package is not recorded"
            has == null -> "not ruled a fit"
            else -> "not ruled a fit: ${has.describe()} on ${wanted.describe()} pads"
        }
    }
}

internal fun Stock.describe(): String = when (this) {
    Stock.InStock -> "in stock"
    Stock.Backorder -> "on backorder"
    Stock.OutOfStock -> "out of stock"
}

/** Minor units as a reader reads them. EUR is the only currency the slice has. */
internal fun money(minor: Long): String = String.format(Locale.ROOT, "€%d.%02d", minor / 100, minor % 100)

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun day(date: LocalDate): String = date.format(DAY)
