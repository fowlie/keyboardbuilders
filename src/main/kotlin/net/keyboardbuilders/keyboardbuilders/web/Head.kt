package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import java.security.MessageDigest
import tools.jackson.databind.json.JsonMapper
import org.springframework.stereotype.Component

/**
 * What goes in the `<head>` beyond the title: the snippet a human reads before clicking,
 * and the two directives that decide whether a crawler keeps the page.
 *
 * WHY THIS IS KOTLIN AND NOT TEMPLATE LOGIC. Same rule the silhouette follows -- the
 * markup is assembled here and the template only prints it. A canonical URL built by
 * string concatenation in three `.kte` files is three chances to disagree about whether
 * the path has a trailing slash, and a canonical that points at a 404 is worse than none.
 */
data class Head(
    val title: String,
    val description: String? = null,
    /**
     * Absolute, because a canonical URL is defined to be. A relative one is legal and is
     * resolved against the request, which makes it a tautology: it names whatever URL was
     * asked for, which is exactly what it exists to override.
     */
    val canonical: String? = null,
    /**
     * `false` renders `noindex, follow`. FOLLOW, deliberately: the facet pages are the
     * discovery path to the 234 design pages, so a crawler must keep walking them even
     * though it keeps none of them. `nofollow` here would strand every design that the
     * default list's first hundred rows do not reach.
     */
    val indexable: Boolean = true,
    /**
     * Absolute URL of the share card, or null for pages that have no picture worth
     * showing.
     *
     * TWO SOURCES, AND THEY ANSWER DIFFERENT QUESTIONS. A design page points at its own
     * `/designs/{slug}/og.png`, drawn from that board's silhouette or cropped from its
     * photo. Everything else points at the one static `/og.png`, the site's mark and
     * wordmark: the index's own picture would have to be a list, and a lockup is a
     * better answer to "what is this link" than an arbitrary one of 234 keyboards.
     */
    val image: String? = null,
    /**
     * Absolute URL of the search thumbnail, or null where no silhouette exists to draw
     * one from — Forgejo #42. Distinct from `image`: that is the 1200x630 share card,
     * this is a square crop with nothing but the silhouette in it, which is what
     * `primaryImageOfPage` schema and an image sitemap entry point at.
     */
    val thumbnail: String? = null,
) {
    /** The page's schema.org WebPage block, where it has a canonical URL and a thumbnail. */
    val webPageLd: String? get() = if (canonical == null || thumbnail == null) null else JsonLd.of(
        mapOf(
            "@context" to "https://schema.org",
            "@type" to "WebPage",
            "url" to canonical,
            "primaryImageOfPage" to mapOf("@type" to "ImageObject", "url" to thumbnail, "width" to 630, "height" to 630),
        )
    )
}

/**
 * Structured data as STRICT JSON, written by a JSON serializer and inserted unescaped.
 *
 * Templated by hand, jte escaped each value for JavaScript, since it sits in a script, and
 * JavaScript's `\-` is not JSON: every design whose slug has a hyphen (156 of 379 sitemap
 * URLs, 2026-10-03) carried a block Google could not parse, Search Console's "invalid
 * escape sequence". Every `<` is written as `<`, so no value can close the script tag.
 */
object JsonLd {
    private val mapper = JsonMapper.builder().build()

    fun of(value: Map<String, Any?>): String = mapper.writeValueAsString(value).replace("<", "\\u003c")
}

/**
 * The site's own address, for the URLs that have to be absolute — canonicals and the
 * sitemap.
 *
 * NOT DERIVED FROM THE REQUEST, and that is the point. Behind the proxy the app sees
 * whatever `Host` header arrives, so a request to the bare IP or to an old hostname
 * would mint canonicals pointing at itself and tell a crawler the duplicate is the
 * original. Configuration is the one source that cannot be spoofed by a visitor.
 */
@Component
class SiteUrl(
    @Value("\${keyboardbuilders.base-url:https://keyboardbuilders.net}")
    private val base: String,
    /**
     * Origin that RECEIVES the hits, or empty for none. `/count` hangs off it.
     *
     * NOT A CONSTANT, so that a page rendered anywhere but the deployed instance loads no
     * script whatsoever -- tests, local dev, and anyone running this from source.
     */
    @Value("\${keyboardbuilders.analytics:}")
    val analytics: String,
    /**
     * Where the counting SCRIPT is fetched from, which is a different host from where the
     * hits go and that is the whole reason this is a separate setting.
     *
     * Self-hosted GoatCounter serves `/count.js` from the same instance that receives the
     * hits, so one origin covered both and the first version of this derived them from
     * one value. **Hosted GoatCounter does not**: it serves the script from a shared host
     * and 404s `/count.js` on the per-site subdomain. Deriving it produced a script tag
     * pointing at a page that does not exist.
     *
     * The default is the hosted script host, because hosted is now the normal case. It is
     * also the third-party request Forgejo #27 was weighing when it chose self-hosting --
     * making it a named setting rather than a hidden one keeps that visible.
     */
    @Value("\${keyboardbuilders.analytics-script:https://gc.zgo.at/count.js}")
    val analyticsScript: String,
) {

    /** `base` with no trailing slash, so callers can always append a rooted path. */
    private val root = base.trimEnd('/')

    fun of(path: String): String = root + path

    val hasAnalytics: Boolean get() = analytics.isNotBlank()

    /** Trailing slash trimmed so the two derived URLs cannot double it. */
    val analyticsOrigin: String get() = analytics.trimEnd('/')

    /**
     * The stylesheet's URL with its own content hash, so a deploy that changes it is seen
     * at once. /app.css is served with no Cache-Control, which lets a browser keep a copy
     * for as long as its own heuristic says; the owner's phone kept the pre-hover one
     * (2026-10-02). Hashed once at startup, which is when the file can change.
     */
    val stylesheet: String = hashed("/app.css")

    /** The heart buttons' script (2026-11-27), hashed for the same reason. */
    val heartsScript: String = hashed("/hearts.js")

    private fun hashed(path: String): String = "$path?v=" + (javaClass.getResourceAsStream("/static$path")
        ?.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
        ?.joinToString("") { "%02x".format(it) }?.take(10) ?: "0")
}

/**
 * The description a search result shows under the link.
 *
 * NOT A RANKING FACTOR, and worth writing anyway: it is the snippet, and the snippet is
 * what decides the click. Left absent, the engine writes its own from the page — and
 * this page opens with an inline SVG and a list of chips, which makes a poor sentence.
 *
 * TRUNCATED AT 155 CHARACTERS on a word boundary. The cut-off is a rendering convention
 * rather than a rule, and over-long descriptions are elided mid-word by the engine; doing
 * it here keeps the sentence ours.
 */
internal fun describe(vararg parts: String?): String? {
    val text = parts.filterNot { it.isNullOrBlank() }.joinToString(" ") { it!!.trim() }
    if (text.isBlank()) return null
    if (text.length <= 155) return text
    return text.take(155).substringBeforeLast(' ').trimEnd(',', '.', ';') + "…"
}
