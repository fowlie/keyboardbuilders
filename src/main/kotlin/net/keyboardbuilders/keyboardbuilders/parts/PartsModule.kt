package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.FirmwareCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Design
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The one door the design side uses into the parts module (#112): a design in, its build
 * list out. Everything behind it -- the seed, the price history, the VAT -- stays here, so
 * the web layer names no catalogue and no table.
 *
 * Owns the clock that [buildList] is handed, since staleness is the only thing that
 * depends on when the page is asked for.
 */
@Component
@ConditionalOnProperty("keyboardbuilders.parts.enabled", havingValue = "true")
class PartsModule(
    private val catalog: PartsCatalog,
    private val observations: OfferObservations,
    private val firmwares: FirmwareCatalog,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The shops a build list's prices come from, for naming them. */
    val shops: List<Shop> get() = catalog.shops

    /** [picks] are the reader's, from the URL; an unusable one is a note on the result, never an error. */
    fun buildListFor(design: Design, region: Region, picks: Map<NeedKind, Pick> = emptyMap()): BuildList =
        buildList(
            design = design,
            diodePackage = catalog.diodePackage(design.core.slug),
            products = catalog.products,
            shops = catalog.shops,
            listings = catalog.listings,
            offers = observations.offers(catalog.listings),
            region = region,
            now = clock.instant(),
            rules = firmwares.rules,
            picks = picks,
        )
}
