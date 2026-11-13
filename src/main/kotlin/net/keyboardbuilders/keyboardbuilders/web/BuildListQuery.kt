package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.Row
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
 * The build list's picks in the URL (#113), so a build list can be shared or bookmarked
 * with no login: `?controller=nice-nano-v2&sockets=own`. One key per row; the value is a
 * product slug or `own`. The keys live here and nowhere else.
 */

val NeedKind.param: String
    get() = when (this) {
        NeedKind.Controller -> "controller"
        NeedKind.SwitchSockets -> "sockets"
        NeedKind.Diodes -> "diodes"
        NeedKind.Pcb -> "pcb"
        NeedKind.Switches -> "switches"
    }

private const val OWN = "own"

/** `switches-shop=42keebs`: the shop chosen for the row's picked product (#115). */
private const val SHOP = "-shop"

/**
 * Total, never throws. Unknown keys (`utm_source`) and blank values are skipped. A key
 * given twice takes its first value: Spring's `Map<String, String>` binding does that.
 * The value is kept as typed; only the parts module decides whether it names a product.
 */
fun readPicks(params: Map<String, String>): Map<NeedKind, Pick> =
    NeedKind.entries.mapNotNull { kind ->
        val value = params[kind.param]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val shop = params["${kind.param}$SHOP"]?.trim()?.takeIf { it.isNotEmpty() }
        kind to if (value == OWN) Pick.Own else Pick.Product(value, shop)
    }.toMap()

/**
 * The page again with [kind] set [to] (null is the row's default, so its key goes) and
 * every other row's pick in effect kept, in row order, landing on the row. Built only from
 * [Row.pick], so a pick that was not used disappears on the next click.
 */
fun pickHref(slug: String, rows: List<Row>, kind: NeedKind, to: Pick?): String =
    "/designs/$slug/build" + query(picksQuery(rows.associate { it.kind to it.pick } + (kind to to))) + "#need-${kind.param}"

/**
 * A row's picker page (#115), `/designs/lily58-pro/build/switches`, carrying every pick in
 * effect so choosing there keeps them. [filters] are the page's own, already encoded and
 * prefixed (`f-kind=linear`), after the picks.
 */
fun pickerHref(slug: String, rows: List<Row>, kind: NeedKind, filters: List<String> = emptyList()): String =
    "/designs/$slug/build/${kind.param}" + query(picksQuery(rows.associate { it.kind to it.pick }) + filters)

private fun picksQuery(picks: Map<NeedKind, Pick?>): List<String> =
    NeedKind.entries.mapNotNull { k ->
        when (val p = picks[k]) {
            null -> null
            Pick.Own -> "${k.param}=$OWN"
            is Pick.Product -> "${k.param}=${URLEncoder.encode(p.raw, StandardCharsets.UTF_8)}" +
                (p.shop?.let { "&${k.param}$SHOP=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } ?: "")
        }
    }

private fun query(params: List<String>): String = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
