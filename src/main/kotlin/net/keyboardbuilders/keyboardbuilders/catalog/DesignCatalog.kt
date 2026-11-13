package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Design

/**
 * Where the site gets its designs. One interface so the pages can be built and tested
 * before there is a schema -- when the SQLite table lands, this is the seam it plugs
 * into and no controller or template changes.
 *
 * `bySlug` takes a raw `String`, not a `Slug`, because the argument comes off a URL a
 * stranger typed. `Slug("Not A Slug")` throws, and a 500 on a bad URL is worse than a
 * 404; matching the stored slug's text costs nothing and cannot throw.
 */
interface DesignCatalog {
    fun all(): List<Design>
    fun bySlug(slug: String): Design?
}
