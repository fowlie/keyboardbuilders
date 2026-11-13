package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Design
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GitHub's star count for a design's repository, and how old the number is.
 * Ticket 54, whose scope question the owner closed in this feature's favour.
 *
 * READ FROM `activity.json`, NOT FROM SQL, and that is a scope decision rather than a
 * shortcut. The site still serves from `designs.json`; swapping the read path to SQLite
 * is its own piece of work with its own risk, and blocking a star count on it would be
 * letting an unrelated migration hold up a two-line feature. When the read path does
 * move, this class is what gets deleted, not what gets rewritten.
 */
@Component
class RepoActivity(
    mapper: ObjectMapper,
    @Value("classpath:seed/activity.json") resource: Resource,
) {

    private val root = resource.inputStream.use { mapper.readTree(it) }

    /**
     * When the fetch ran. The whole reason ticket 54's staleness question is answerable.
     *
     * Null rather than a throw if the stamp is missing or unreadable: a star count with
     * no known age is still a usable star count, and refusing to start the site over a
     * missing timestamp would be the wrong trade. The display drops the age instead.
     */
    val fetchedAt: Instant? =
        runCatching { Instant.parse(root.get("fetchedAt").asString()) }.getOrNull()

    private val stars: Map<String, Int> = root.get("repos").properties()
        .associate { (repo, facts) -> repo to facts.get("stars").asInt() }

    /**
     * The count for whichever of a design's sources is a GitHub repository, or null.
     *
     * NULL IS NOT ZERO. `architecture.md`:1847 is explicit that a missing count is not a
     * count of zero, and the sort order depends on the difference: SQLite puts NULLs last
     * under DESC, so an unknown ranks below every known count instead of alongside the
     * genuinely unstarred. A design whose repo really has no stars keeps its honest 0.
     */
    fun starsFor(design: Design): Int? = design.core.sources
        .firstNotNullOfOrNull { repoOf(it.url) }
        ?.let { stars[it] }

    /** How stale the number is, as a phrase, or null if the file carried no timestamp. */
    fun checkedPhrase(now: Instant = Instant.now()): String? =
        fetchedAt?.let { checkedPhrase(it, now) }

    private companion object {
        private val GITHUB = Regex("""https://github\.com/([^/]+/[^/#?]+)""")

        /** Keyed the way the fetch keys it: what the seed cites, not what GitHub answers. */
        fun repoOf(url: String): String? =
            GITHUB.find(url)?.groupValues?.get(1)?.removeSuffix(".git")
    }
}

/**
 * How old a fetched number is, in words.
 *
 * A free function taking both instants, so the phrasing can be tested against real dates
 * without constructing a `RepoActivity` around a file. The first version of that test
 * reimplemented this logic inside itself, which is a test that only ever agrees with its
 * own copy.
 *
 * Days rather than hours because the cron's period is days, and "checked 4 hours ago"
 * would imply a freshness the build does not have. Months past thirty days for the same
 * reason in the other direction: "checked 94 days ago" is precision nobody needs to
 * decide whether a number is stale.
 */
fun checkedPhrase(fetchedAt: Instant, now: Instant): String =
    when (val days = Duration.between(fetchedAt, now).toDays()) {
        0L -> "checked today"
        1L -> "checked yesterday"
        in 2..30 -> "checked $days days ago"
        else -> "checked ${days / 30} months ago"
    }

/**
 * A star count as a result row shows it: rounded above a thousand.
 *
 * THE ROUNDING IS THE STALENESS ANSWER, on the row at least. Ticket 54 asks how honest a
 * displayed number is allowed to be about its own age, and the build is rebuild-only on a
 * cron, so every count is as old as the last rebuild. `2,529` claims a precision it
 * cannot have; `2.5k` says "about this many", which is the true claim, and it says it
 * without spending a line of the row on a timestamp nobody scanning wants to read. The
 * detail page, where there is room to be exact, states the age instead.
 *
 * Below a thousand the number is shown as it is — rounding `78` to `80` would destroy
 * information to signal an imprecision that a two-digit count does not have.
 */
fun formatStars(count: Int): String = when {
    count < 1_000 -> "$count"
    count < 10_000 -> "${"%.1f".format(count / 1000.0).removeSuffix(".0")}k"
    else -> "${count / 1000}k"
}
