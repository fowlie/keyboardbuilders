package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import java.time.LocalDate

/**
 * A thing you can buy, independent of any shop (#112's second layer). Curated seed data
 * that changes rarely; what a shop charges for it is an [Offer], never a field here.
 */
sealed interface Product {
    val slug: Slug
    val name: String
}

/**
 * One part and the interface it fits. A dev board is a part too, and its footprint and
 * firmware come from the controller catalogue row it names, never copied: build one
 * with [Part.devBoard]. A switch carries its [switch] facts, and only a switch does.
 */
data class Part(
    override val slug: Slug,
    override val name: String,
    val fits: Fitting,
    val mpn: String? = null,
    val lcsc: String? = null,
    val devBoard: ControllerBoard? = null,
    val switch: SwitchSpec? = null,
) : Product {
    init {
        require((fits is Fitting.Switch) == (switch != null)) { "a switch part has switch facts, and only it: $slug" }
        require((fits is Fitting.ControllerSocket) == (devBoard != null)) {
            "a controller part is a catalogue dev board, and only it: $slug"
        }
        if (devBoard != null) require(fits == Fitting.ControllerSocket(setOf(devBoard.footprint))) {
            "a dev board fits its own footprint: $slug"
        }
    }

    companion object {
        fun devBoard(slug: Slug, board: ControllerBoard) =
            Part(slug, board.name, Fitting.ControllerSocket(setOf(board.footprint)), devBoard = board)
    }
}

/**
 * A vendor's kit, based on one design. A kit is usually a closed variant -- 42keebs adds
 * a power switch the open Lily58 Pro lacks -- so its facts are its own, "per the vendor"
 * and dated [readOn], and the design's facts never carry over in either direction.
 *
 * [contents] is shown, never priced. [needs] is what the vendor says the builder must
 * still buy, and is priced like any other line. [notPriced] is the rest of the vendor's
 * "not included" list, in the vendor's words.
 */
data class Kit(
    override val slug: Slug,
    override val name: String,
    val shop: String,
    val url: String,
    val basedOn: Slug,
    val readOn: LocalDate,
    val contents: List<KitLine>,
    val needs: List<Requirement>,
    val notPriced: List<String>,
) : Product

/**
 * One line of a kit's contents as the vendor lists it. [kind] where it answers a need.
 * [quantity] is `Unknown` where the vendor gives no count ("brass standoffs and screws").
 */
data class KitLine(val quantity: Recorded<Int>, val text: String, val kind: NeedKind?) {
    init { require((quantity.knownOrNull() ?: 1) >= 1) { "a kit holds at least one of a line, was $quantity" } }
}

/**
 * A shop. [vat] is percent by region code, applied when [pricesIncludeVat] is false, as
 * 42keebs' Store API prices are: net, with the visitor's VAT added only on the page.
 */
data class Shop(
    val slug: String,
    val name: String,
    val currency: String,
    val pricesIncludeVat: Boolean,
    val vat: Map<String, Int>,
)

/**
 * One product on one shop's shelf. [ref] is the shop's own id (a WooCommerce variation
 * id where there is one). [pack] is how many come for one price, read by a person off the
 * listing, since no shop API states it.
 */
data class Listing(
    val shop: String,
    val ref: String,
    val product: Slug,
    val label: String?,
    val pack: Int,
    val url: String,
) {
    init { require(pack >= 1) { "a pack holds at least one, was $pack at $shop $ref" } }
}
