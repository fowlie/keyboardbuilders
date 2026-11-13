package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import java.sql.ResultSet

/**
 * A column held a value, and this build could not read it. Forgejo #3.
 *
 * NOT an absence. The distinction is the whole point of the issue: an unparseable value
 * turned into [Recorded.Unknown] is neither wrong nor empty, it is a wrong value wearing
 * emptiness's clothes — indistinguishable on screen from an honest gap, and it would be
 * counted in the sidebar's "Not recorded" bucket as if somebody simply had not filled it
 * in yet. That is the most expensive failure this project can have, so it is an
 * exception and never a fallback.
 *
 * The message carries the column and the offending value because a bare
 * `No enum constant Mcu.RP2040X` tells you what was wrong and not where. [row] is
 * whatever identifies the row to a human — a slug, a name — and is worth supplying:
 * finding the bad row in a 300-design corpus is the expensive half of the fix.
 */
class UnreadableColumn(
    val column: String,
    val value: String,
    val row: String? = null,
    cause: Throwable,
) : IllegalStateException(
    "column `$column`${row?.let { " of $it" } ?: ""} holds a value this build " +
        "cannot read: \"$value\"",
    cause,
)

/**
 * Read one nullable column into [Recorded]. Forgejo #3, and one of the two crossing
 * points from untrusted text into checked values that `Design.kt`'s header names.
 *
 * THREE BEHAVIOURS, AND THE THIRD IS THE DECISION:
 *
 *  - SQL NULL becomes [Recorded.Unknown]. Not null, not a default, not a sentinel.
 *  - A present, parseable value becomes [Recorded.Known].
 *  - A present, UNPARSEABLE value throws [UnreadableColumn] and propagates. It does not
 *    become `Unknown`, it is not skipped, and it is not logged-and-continued.
 *
 * That last one is the judgement the issue reserved, and it is settled the way
 * `implementation.md`:490 asks: *"a corrupt row is a loud 500, not a silently wrong
 * page."* The reasoning is that in v0.5 the database is built by our own importer from
 * our own seed, so an unreadable value means the importer wrote something the domain
 * cannot represent. Degrading the page hides our bug behind what looks like missing
 * data; failing the request puts it in front of the person who can fix it. Revisit this
 * if the database ever holds values this codebase did not write.
 *
 * `val raw: String?` is deliberate and load-bearing. `getString` returns a PLATFORM
 * type, so Kotlin enforces no null check on it statically — the prototype measured a
 * Java method typed `String` returning null, assigned to a non-null Kotlin field,
 * compiling with no warning. Naming the type nullable here is what turns that back into
 * a check the compiler can see.
 */
fun <T : Any> ResultSet.recorded(
    column: String,
    row: String? = null,
    parse: (String) -> T,
): Recorded<T> {
    val raw: String? = getString(column)
    if (raw == null) return Recorded.Unknown
    return try {
        Recorded.Known(parse(raw))
    } catch (cause: Exception) {
        // Exception and not Throwable: an OutOfMemoryError is not a bad column, and
        // wrapping it would say something false about the data.
        throw UnreadableColumn(column, raw, row, cause)
    }
}

/**
 * The same read for an INTEGER column. Split from [recorded] rather than folded in
 * because `getInt` answers 0 for SQL NULL — the single most common way a nullable
 * numeric column silently becomes a real value. `getObject` is what distinguishes them.
 *
 * `design_search.stars` is exactly this hazard: the schema's own comment says NULL means
 * "we have no star count", NOT zero stars.
 */
fun ResultSet.recordedInt(column: String, row: String? = null): Recorded<Int> {
    val raw: Any? = getObject(column)
    if (raw == null) return Recorded.Unknown
    return when (raw) {
        is Number -> Recorded.Known(raw.toInt())
        else -> throw UnreadableColumn(
            column, raw.toString(), row,
            IllegalArgumentException("expected a number, got ${raw.javaClass.simpleName}"),
        )
    }
}
