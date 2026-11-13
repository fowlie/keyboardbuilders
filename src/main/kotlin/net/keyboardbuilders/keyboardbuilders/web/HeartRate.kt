package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * How often one visitor may heart or un-heart. The whole abuse defence for an
 * unauthenticated write, and the same shape as the reader reports' `ReportRate` (removed
 * in ba92b3b) for the same reasons.
 *
 * IN MEMORY AND NOT IN THE DATABASE: nothing about a visitor is written down, so a restart
 * resets every window. That is a real weakness and an acceptable one while a heart is a
 * count on a page and not a vote with consequences; an honest one-per-person count needs
 * accounts, which is the later step this leaves room for.
 *
 * 60 an hour by default: enough to heart a whole shortlist and change your mind about all
 * of it, too few to move a count by much from one address.
 */
@Component
class HeartRate(
    @Value("\${keyboardbuilders.heart.limit:60}") private val limit: Int,
    @Value("\${keyboardbuilders.heart.window-millis:3600000}") private val windowMillis: Long,
) {
    private val seen = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** True if this visitor may change a heart now, counting the change if so. Inside
     *  `compute`, so two requests from one address cannot both slip under the limit. */
    fun allow(visitor: String): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        seen.compute(visitor) { _, existing ->
            val times = existing ?: ArrayDeque()
            while (times.isNotEmpty() && now - times.peekFirst() >= windowMillis) times.removeFirst()
            allowed = times.size < limit
            if (allowed) times.addLast(now)
            times
        }
        // A map keyed by something a stranger chooses grows without bound unless something
        // empties it; only swept when already large, so an ordinary request pays nothing.
        if (seen.size > MAX_VISITORS) {
            seen.entries.removeIf { (_, t) -> t.isEmpty() || now - t.peekLast() >= windowMillis }
        }
        return allowed
    }

    /** For tests, which share one application context and so one limiter. */
    fun reset() = seen.clear()

    private companion object {
        const val MAX_VISITORS = 10_000
    }
}
