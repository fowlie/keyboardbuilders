package net.keyboardbuilders.keyboardbuilders.web

/** Asked for a page that does not exist. The caller turns this into a 404. */
class NoSuchPage(message: String) : IllegalArgumentException(message)

/**
 * One page of a result list. Forgejo #12's remaining lever.
 *
 * WHY PAGING, GIVEN `content-visibility` ALREADY LANDED. That rule stops the browser
 * laying out and painting rows nobody is looking at, and took the facet swap from
 * 471 ms to 164 ms at 300 designs on a 4x-throttled profile. It does not stop the
 * browser *parsing* them, and 164 ms is still 1.4x over `ux.md` §2.7's 120 ms budget.
 * Paging is what removes the parse, because the markup is never sent.
 *
 * WHY 50, AND THE HISTORY MATTERS BECAUSE THE REASON CHANGED. It was 100, chosen on the
 * measurement above: at 100 the throttled swap is 57 ms with `content-visibility` in
 * place, 200 would be about 110 ms, and 25 was rejected as "a much bigger cut to the
 * result list than the measurement asks for."
 *
 * The owner moved it to 50 on 2026-08-30 for a reason that measurement never looked at:
 * **a hundred rows is a lot to scroll.** Nothing above is contradicted — 50 is strictly
 * faster than the 57 ms figure, and further inside the 120 ms budget than 100 was. What
 * changed is which axis decides, and page size was never a performance question once
 * every candidate value sat inside the budget.
 *
 * So do NOT read the 57 ms measurement as an argument for any particular number. It rules
 * OUT 200-and-up. Between 25 and 100 it is silent, and the choice is how far someone
 * wants to scroll before the pager.
 *
 * The objection recorded against paging was that it splits the comparison set, on
 * `ux.md`:551 — *"consistent scale within a viewport"*. It does not reach: the
 * silhouette's scale is a fixed 8px per key unit, so a board renders identically
 * whatever else is on the page. What paging genuinely costs is that two designs 150
 * rows apart are never on screen together — and they never were, because a 136-design
 * list is already 15,881px tall.
 */
data class Page(
    val number: Int,
    val size: Int,
    val total: Int,
) {
    /** Pages are 1-based in the URL, so an empty corpus is page 1 of 1, not 1 of 0. */
    val count: Int get() = if (total == 0) 1 else (total + size - 1) / size

    /** Zero-based, for slicing the list. Empty when the corpus is. */
    val range: IntRange
        get() = if (total == 0) IntRange.EMPTY
        else ((number - 1) * size) until minOf(number * size, total)

    /** 1-based and inclusive, for telling a reader which designs they are looking at. */
    val first: Int get() = range.first + 1
    val last: Int get() = range.last + 1

    val hasPrevious: Boolean get() = number > 1
    val hasNext: Boolean get() = number < count

    companion object {
        /** `ux.md` §2.5's result rows. See the class comment: measurement sets the ceiling,
         *  scroll length picks the value. */
        const val DEFAULT_SIZE = 50

        /**
         * A page that exists, or [NoSuchPage].
         *
         * Deliberately not clamping. `ux.md` §2.7 makes the URL the truth of what is
         * being shown, so serving page 2 at `?page=99` would make the address bar lie
         * about the page it is on — and it is the address bar that gets shared, linked
         * and bookmarked.
         */
        fun of(number: Int, size: Int = DEFAULT_SIZE, total: Int): Page {
            require(size > 0) { "a page holds at least one row, got $size" }
            val page = Page(number, size, total)
            if (number < 1 || number > page.count) {
                throw NoSuchPage("page $number of ${page.count}")
            }
            return page
        }
    }
}
