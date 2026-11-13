package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.parts.BuildList
import net.keyboardbuilders.keyboardbuilders.parts.Fitting
import net.keyboardbuilders.keyboardbuilders.parts.Need
import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.OfferFit
import net.keyboardbuilders.keyboardbuilders.parts.Part
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.PricedOffer
import net.keyboardbuilders.keyboardbuilders.parts.Region
import net.keyboardbuilders.keyboardbuilders.parts.Row
import net.keyboardbuilders.keyboardbuilders.parts.STALE_AFTER
import net.keyboardbuilders.keyboardbuilders.parts.Shop
import net.keyboardbuilders.keyboardbuilders.parts.Stock
import net.keyboardbuilders.keyboardbuilders.parts.SwitchKind
import net.keyboardbuilders.keyboardbuilders.parts.UnseenListing

/*
 * `/designs/:slug/build/switches` (#115): the switches row's own page, a filter rail like
 * /designs beside a list of switches, each a product with its offers under it. Choosing
 * one is a link back to the grid with `switches=<slug>` and every other pick kept.
 *
 * The filters are the page's own and prefixed (`f-kind`, `f-fit`, `f-stock`), so none can
 * ever be read as a pick. Their order in the URL is kept: "remove the last filter" means
 * the last one written there.
 */

/** A switch's kind as a filter value. [Unknown] is "the vendor does not say", below the rail's divider. */
enum class KindFilter(val value: String, val label: String, val kind: SwitchKind?) {
    Linear("linear", "Linear", SwitchKind.Linear),
    Tactile("tactile", "Tactile", SwitchKind.Tactile),
    SilentLinear("silent_linear", "Silent linear", SwitchKind.SilentLinear),
    SilentTactile("silent_tactile", "Silent tactile", SwitchKind.SilentTactile),
    Clicky("clicky", "Clicky", SwitchKind.Clicky),
    Unknown("unknown", "Not recorded", null),
}

sealed interface PickerFilter {
    data class Kind(val kind: KindFilter) : PickerFilter
    data object InStock : PickerFilter
    /** "Fits this board" switched off: it is on unless this is in the URL. */
    data object AnyFit : PickerFilter
}

private const val KIND = "f-kind"
private const val FIT = "f-fit"
private const val STOCK = "f-stock"
private const val ANY = "any"
private const val IN = "in"

/** Total, never throws: a value it does not know is dropped, so it is never echoed. */
fun readPickerFilters(params: Map<String, String>): List<PickerFilter> =
    params.flatMap { (key, value) ->
        when (key) {
            KIND -> value.split(',').mapNotNull { v -> KindFilter.entries.firstOrNull { it.value == v.trim() } }
                .map { PickerFilter.Kind(it) }
            STOCK -> listOfNotNull(PickerFilter.InStock.takeIf { value.trim() == IN })
            FIT -> listOfNotNull(PickerFilter.AnyFit.takeIf { value.trim() == ANY })
            else -> emptyList()
        }
    }.distinct()

/** One parameter per filter kind, in the order each first appears; kinds comma-joined as /designs does. */
private fun List<PickerFilter>.params(): List<String> {
    val groups = LinkedHashMap<String, MutableList<String>>()
    for (f in this) {
        val (key, value) = when (f) {
            is PickerFilter.Kind -> KIND to f.kind.value
            PickerFilter.InStock -> STOCK to IN
            PickerFilter.AnyFit -> FIT to ANY
        }
        groups.getOrPut(key) { mutableListOf() } += value
    }
    return groups.map { (k, vs) -> "$k=" + vs.joinToString(",") }
}

private fun PickerFilter.label(): String = when (this) {
    is PickerFilter.Kind -> kind.label
    PickerFilter.InStock -> "In stock only"
    PickerFilter.AnyFit -> "Fits this board"
}

data class SwitchPickerPage(
    val name: String,
    val slug: String,
    /** The grid, every pick kept, landing on the switches row. */
    val back: String,
    /** "58 × MX or Choc v1 switch, one per key of the 58-key layout." */
    val need: String,
    val pricing: String,
    /** Where the switches pick in the URL could not be used. */
    val notes: List<String>,
    val rail: List<RailGroup>,
    /** How many filters are applied, for the phone's "Filters" control. */
    val applied: Int,
    val count: String,
    val products: List<SwitchView>,
    /** "2 more don't fit: ...", while the fit filter hides them. */
    val dontFit: LinkLine?,
    val empty: EmptyResult?,
    /** Back to the grid with no switch picked; null when none is. */
    val clear: String?,
)

/**
 * One switch. [summary] is its cheapest countable offer for the whole board, or why there
 * is none. [choose] is null when it cannot be counted or is already the pick ([chosen]).
 */
data class SwitchView(
    val name: String,
    val kind: String,
    val fits: Boolean,
    val summary: String,
    val choose: String?,
    val chosen: Boolean,
    val offers: List<OfferRow>,
)

data class LinkLine(val text: String, val href: String)

data class EmptyResult(val text: String, val linkText: String?, val href: String?)

/** One switch and every offer and unseen listing for it, in the row's offer order. */
private class Choice(val part: Part, val offers: List<PricedOffer>, val unseen: List<UnseenListing>) {
    val fit: OfferFit = offers.firstOrNull()?.fit ?: unseen.first().fit
    val fits: Boolean get() = fit == OfferFit.Fits
    val best: PricedOffer? = offers.firstOrNull { it.countable }
    val inStock: Boolean = offers.any { it.offer.seen.stock == Stock.InStock && !it.stale }
    val kind: KindFilter = part.switch?.kind?.knownOrNull()
        ?.let { k -> KindFilter.entries.first { it.kind == k } } ?: KindFilter.Unknown

    /** In stock, then on backorder, then nothing to count; within each, cheapest for the board. */
    val rank: Int = when {
        best == null -> 2
        best.offer.seen.stock == Stock.InStock -> 0
        else -> 1
    }
}

/** Real prices only: a switch with nothing to count sorts after all that have one, by name. */
private val CHOICE_ORDER: Comparator<Choice> =
    compareBy<Choice> { it.rank }
        .thenBy { it.best?.line?.knownOrNull()?.grossMinor ?: Long.MAX_VALUE }
        .thenBy { it.part.name }

private class Filters(val list: List<PickerFilter>) {
    val kinds = list.filterIsInstance<PickerFilter.Kind>().map { it.kind }.toSet()
    val fitOn = PickerFilter.AnyFit !in list
    val stockOn = PickerFilter.InStock in list

    fun kindOk(c: Choice) = kinds.isEmpty() || c.kind in kinds
    fun fitOk(c: Choice) = !fitOn || c.fits
    fun stockOk(c: Choice) = !stockOn || c.inStock

    fun toggled(f: PickerFilter): List<PickerFilter> = if (f in list) list - f else list + f
}

fun BuildList.toSwitchPicker(design: Design, shops: List<Shop>, region: Region, filterList: List<PickerFilter>): SwitchPickerPage {
    val slug = design.core.slug.value
    val shopNames = shops.associate { it.slug to it.name }
    val row: Row? = rows.firstOrNull { it.kind == NeedKind.Switches }
    val need = row?.need
    val picked = (row?.pick as? Pick.Product)?.raw
    val filters = Filters(filterList)
    fun href(list: List<PickerFilter>) = pickerHref(slug, rows, NeedKind.Switches, list.params())

    val choices = row?.let { r ->
        val offers = r.offers.groupBy { it.product.slug }
        val unseen = r.unseen.groupBy { it.part.slug }
        (offers.keys + unseen.keys).mapNotNull { s ->
            val part = (offers[s]?.first()?.product ?: unseen[s]?.first()?.part) as? Part ?: return@mapNotNull null
            Choice(part, offers[s].orEmpty(), unseen[s].orEmpty())
        }
    }.orEmpty()

    val shown = choices.filter { filters.kindOk(it) && filters.fitOk(it) && filters.stockOk(it) }.sortedWith(CHOICE_ORDER)

    val rail = listOf(
        RailGroup(
            key = KIND, heading = "Kind",
            known = KindFilter.entries.filter { it != KindFilter.Unknown }.map { kindOption(it, choices, filters, ::href) },
            more = emptyList(),
            unrecorded = kindOption(KindFilter.Unknown, choices, filters, ::href),
            openByDefault = true,
        ),
        RailGroup(
            key = FIT, heading = "Fit",
            known = listOf(RailOption(ANY, PickerFilter.AnyFit.label(),
                choices.count { it.fits && filters.kindOk(it) && filters.stockOk(it) },
                selected = filters.fitOn, href = href(filters.toggled(PickerFilter.AnyFit)))),
            more = emptyList(), unrecorded = null, openByDefault = true,
        ),
        RailGroup(
            key = STOCK, heading = "Stock",
            known = listOf(RailOption(IN, PickerFilter.InStock.label(),
                choices.count { it.inStock && filters.kindOk(it) && filters.fitOk(it) },
                selected = filters.stockOn, href = href(filters.toggled(PickerFilter.InStock)))),
            more = emptyList(), unrecorded = null, openByDefault = true,
        ),
    )

    val hidden = if (!filters.fitOn) emptyList()
                 else choices.filter { !it.fits && filters.kindOk(it) && filters.stockOk(it) }
    val wanted = ((need as? Need.Buy)?.requirement?.fitting as? Fitting.Switch)?.footprints

    return SwitchPickerPage(
        name = design.core.name.value,
        slug = slug,
        back = pickHref(slug, rows, NeedKind.Switches, row?.pick),
        need = need?.what().orEmpty(),
        pricing = pricingNote(shops, region),
        notes = notes.filter { it.kind == NeedKind.Switches }.map { it.describe(this) },
        rail = rail,
        applied = filterList.size,
        count = if (shown.size == 1) "1 switch" else "${shown.size} switches",
        products = shown.map { c ->
            val chosen = c.part.slug.value == picked
            SwitchView(
                name = c.part.name,
                kind = if (c.kind == KindFilter.Unknown) "Kind not recorded" else c.kind.label,
                fits = c.fits,
                summary = c.summary(need, shopNames),
                choose = if (c.best == null || chosen) null
                         else pickHref(slug, rows, NeedKind.Switches, Pick.Product(c.part.slug.value)),
                chosen = chosen,
                offers = c.offers.map { it.toRow(need, shopNames, counted = false, fitNoted = false) } +
                    c.unseen.map { u -> need?.let { u.toRow(it, shopNames, fitNoted = false) } }.filterNotNull(),
            )
        },
        dontFit = hidden.takeIf { it.isNotEmpty() }?.let { h ->
            val theirs = h.flatMap { (it.part.fits as? Fitting.Switch)?.footprints.orEmpty() }.toSet()
            val verb = if (h.size == 1) "doesn't" else "don't"
            val why = if (wanted == null || theirs.isEmpty()) ""
                      else ": ${theirs.inWords()}, the board takes ${wanted.inWords()}"
            LinkLine("${h.size} more $verb fit$why", href(filters.toggled(PickerFilter.AnyFit)))
        },
        empty = if (shown.isNotEmpty()) null else {
            val restricting = filterList.filter { it != PickerFilter.AnyFit }
            when {
                choices.isEmpty() -> EmptyResult("No switches on our list yet.", null, null)
                restricting.isNotEmpty() -> EmptyResult(
                    "No switches match these filters.", "Remove “${restricting.last().label()}”",
                    href(filterList - restricting.last()),
                )
                else -> EmptyResult("No switch on our list fits this board.", null, null)
            }
        },
        clear = if (row?.pick == null) null else pickHref(slug, rows, NeedKind.Switches, null),
    )
}

private fun kindOption(k: KindFilter, choices: List<Choice>, filters: Filters, href: (List<PickerFilter>) -> String) =
    RailOption(
        value = k.value,
        label = k.label,
        count = choices.count { it.kind == k && filters.fitOk(it) && filters.stockOk(it) },
        selected = k in filters.kinds,
        href = href(filters.toggled(PickerFilter.Kind(k))),
    )

/** "58 for €36.25 at 42. Keebs, in stock, seen 4 Oct 2026", or why nothing can be counted. */
private fun Choice.summary(need: Need?, shops: Map<String, String>): String {
    best?.let { return it.toRow(need, shops, counted = false, fitNoted = false).detail }
    if (!fits) return fitNote(fit, part, need)?.replaceFirstChar { it.uppercase() } ?: "Does not fit"
    if (offers.isEmpty()) return "No price seen yet"
    val why = offers.flatMap { o ->
        listOfNotNull(
            "out of stock".takeIf { o.offer.seen.stock == Stock.OutOfStock },
            "price more than ${STALE_AFTER.toDays()} days old".takeIf { o.stale },
            "how many is not recorded".takeIf { o.line.knownOrNull() == null },
        )
    }.distinct()
    return "No offer we can count" + if (why.isEmpty()) "" else ": " + why.joinToString(", ")
}
