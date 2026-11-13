package net.keyboardbuilders.keyboardbuilders.parts

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** #112's third layer: what a shop charged, and whether it had any, when someone looked. */
enum class Stock { InStock, Backorder, OutOfStock }

/** One look at one listing. Append-only: the rows are the price history. */
data class Observation(
    val shop: String,
    val ref: String,
    val observedAt: Instant,
    val netMinor: Long,
    val currency: String,
    val stock: Stock,
) {
    init { require(netMinor >= 0) { "a price is not negative, was $netMinor at $shop $ref" } }
}

/** A listing and the latest look at it. Joined in code, never stored as one row. */
data class Offer(val listing: Listing, val seen: Observation) {
    init {
        require(listing.shop == seen.shop && listing.ref == seen.ref) {
            "${seen.shop} ${seen.ref} is not an observation of ${listing.shop} ${listing.ref}"
        }
    }
}

/** Where the reader is, for VAT. The rate lives on [Shop.vat]. */
data class Region(val code: String)

/**
 * The looks themselves, `V7__offer_observation.sql`. Written by `scripts/observe_42keebs.py`
 * on the dev machine, and by [record] in tests. Behind the same switch as [PartsCatalog].
 */
@Component
@ConditionalOnProperty("keyboardbuilders.parts.enabled", havingValue = "true")
class OfferObservations(private val db: JdbcClient) {

    /** One more row; the history is never rewritten. Kept to the second, as V7 requires. */
    fun record(o: Observation) {
        db.sql(
            """INSERT INTO offer_observation (shop, shop_ref, observed_at, net_minor, currency, stock)
               VALUES (?, ?, ?, ?, ?, ?)"""
        ).params(o.shop, o.ref, o.observedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            o.netMinor, o.currency, o.stock.token).update()
    }

    /** The newest look at each of [refs] on [shop]. A ref nobody has looked at is absent. */
    fun latest(shop: String, refs: Collection<String>): Map<String, Observation> {
        if (refs.isEmpty()) return emptyMap()
        return db.sql(
            """SELECT o.shop, o.shop_ref, o.observed_at, o.net_minor, o.currency, o.stock
               FROM offer_observation o
               JOIN (SELECT shop_ref, MAX(observed_at) AS at FROM offer_observation
                     WHERE shop = :shop AND shop_ref IN (:refs) GROUP BY shop_ref) newest
                 ON o.shop = :shop AND o.shop_ref = newest.shop_ref AND o.observed_at = newest.at"""
        ).param("shop", shop).param("refs", refs)
            .query { rs, _ ->
                Observation(
                    rs.getString("shop"), rs.getString("shop_ref"), Instant.parse(rs.getString("observed_at")),
                    rs.getLong("net_minor"), rs.getString("currency"), rs.getString("stock").toStock(),
                )
            }
            .list().associateBy { it.ref }
    }

    /** Each listing with its latest look. A listing nobody has looked at is no offer yet. */
    fun offers(listings: List<Listing>): List<Offer> =
        listings.groupBy { it.shop }.flatMap { (shop, onShelf) ->
            val seen = latest(shop, onShelf.map { it.ref })
            onShelf.mapNotNull { listing -> seen[listing.ref]?.let { Offer(listing, it) } }
        }
}

private val Stock.token: String
    get() = when (this) {
        Stock.InStock -> "in_stock"
        Stock.Backorder -> "backorder"
        Stock.OutOfStock -> "out_of_stock"
    }

private fun String.toStock(): Stock = Stock.entries.firstOrNull { it.token == this }
    ?: throw IllegalArgumentException("unknown stock '$this' in offer_observation")
