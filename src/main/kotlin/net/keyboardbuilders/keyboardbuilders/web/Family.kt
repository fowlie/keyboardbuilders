package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.FamilyRecord
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull

/**
 * The family line on a design page -- backlog #107. The owner's report was "why do we have
 * so many sweep boards?": six real PCBs from one repo, which read as six unrelated
 * keyboards because no page said they belong together.
 *
 * "from the Corne family", beside the title, the name linking the family's page. It first
 * listed every member, then read "1 of 3 designs in the Corne family"; the owner,
 * 2026-10-04: "no point in saying 2 of 2 designs" -- the family page counts and compares.
 */
data class FamilyView(val name: String) {
    /** The family's own page, `/designs/families/{slug}`: the line's name links up to it. */
    val href: String get() = familyHref(FamilyName(name))
}

/**
 * Null when the design has no recorded family, AND when no other member of its family is
 * in the corpus. A family of one tells a reader nothing, and the second case means a
 * member stopped publishing while its row in `seed/families.toml` stayed -- which
 * `build_seed.py` refuses, so reaching it here is a stale seed, not a page worth a 500.
 */
fun familyOf(design: Design, catalog: DesignCatalog): FamilyView? {
    val name = design.extras.family.knownOrNull() ?: return null
    val members = familyMembers(name, catalog)
    if (members.size < 2) return null
    // Not among its own family's members: the catalog handed us a design it does not hold.
    if (members.none { it.core.slug == design.core.slug }) return null
    return FamilyView(name = name.value)
}

/**
 * Every design carrying [name] as its family, alphabetical by name. ONE reading of
 * membership, shared by the design page's family line and the family page, so the two
 * can never list different boards.
 */
fun familyMembers(name: FamilyName, catalog: DesignCatalog): List<Design> =
    catalog.all()
        .filter { it.extras.family.knownOrNull() == name }
        .sortedBy { it.core.name.value.lowercase() }

fun familyHref(name: FamilyName): String = "/designs/families/${name.slug.value}"

// ---------------------------------------------------------------------------------------
// The family page, /designs/families/{slug}.
//
// WHY IT EXISTS: people search a keyboard by its family -- "corne keyboard", "sofle",
// "lily58" -- and since #107 no design is called just "Corne", so before this page no URL
// on the site answered that search. A design page answers "Corne Cherry v4", which is
// not what anyone types.
// ---------------------------------------------------------------------------------------

/** One member as the family page needs it: its record, its own page, and its card. */
data class FamilyMember(val design: Design, val page: DesignPage, val card: DesignCard)

/** One column head of the comparison: a variant, linking to its own page. */
data class FamilyVariant(val name: String, val href: String)

/** One fact, one cell per variant, in [FamilyPage.variants]' order. */
data class FamilyCompareRow(val label: String, val cells: List<FamilyCell>)

/** `recorded = false` renders in the ignorance style, as the design page renders a gap. */
data class FamilyCell(val value: String, val recorded: Boolean, val hint: String? = null)

data class FamilyPage(
    val name: String,
    val slug: String,
    /** Built only from facts every member's record states -- see [familyLead]. */
    val lead: String,
    /** The source a person cited for this family in `seed/families.toml`. */
    val source: String,
    /** The words before the [source] link, saying what that source is -- see [sourceIntro]. */
    val sourceIntro: String,
    val variants: List<FamilyVariant>,
    val rows: List<FamilyCompareRow>,
    val cards: List<DesignCard>,
) {
    val href: String get() = "/designs/families/$slug"

    /** The source as a reader reads it, without the scheme: "github.com/foostan". */
    val sourceLabel: String get() = source.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}

/**
 * The comparison's rows, BY THE DESIGN PAGE'S OWN LABELS, so a cell reads exactly as the
 * same fact reads on that variant's page -- the compare page's rule, for the same reason:
 * a second way of describing a design is a second place for the two to drift apart.
 *
 * Chosen for what tells two variants of one keyboard apart. Body, stagger and splay are
 * left out because the family rule (#107) already requires one body kind, and a row that
 * reads the same in every cell is noise in a table built to show differences.
 */
private val COMPARED = listOf("Switch footprint", "Hotswap", "Controller", "Connectivity", "Firmware")

/**
 * The page for [record], or null when fewer than two [members] are in the corpus -- a
 * family of one compares a board with nothing, and the sitemap and the index leave it out
 * by the same rule. `FamilyCatalogTest` holds the shipped seed to two or more.
 */
fun familyPage(record: FamilyRecord, members: List<FamilyMember>): FamilyPage? {
    if (members.size < 2) return null
    return FamilyPage(
        name = record.name.value,
        slug = record.slug,
        lead = familyLead(record.name, members.map { it.design }),
        source = record.source.url,
        sourceIntro = sourceIntro(record.source.url, members.map { it.design }),
        variants = members.map { FamilyVariant(it.page.name, "/designs/${it.page.slug}") },
        rows = listOf(
            // Always recorded: every design has at least one layout, by DesignCore's gate.
            FamilyCompareRow("Keys", members.map { m ->
                FamilyCell(m.design.core.layouts.map { it.keys.value }.sortedDescending().joinToString(" / "), true)
            }),
        ) + COMPARED.map { label ->
            FamilyCompareRow(label, members.map { m ->
                // A line the page did not build at all (Connectivity, when the caller
                // computed none) is a gap like any other: "not recorded", never blank.
                m.page.spec.firstOrNull { it.label == label }
                    ?.let { FamilyCell(it.value, it.recorded, it.hint) }
                    ?: FamilyCell(NOT_RECORDED, false)
            })
        },
        cards = members.map { it.card },
    )
}

/**
 * The page's opening sentence, e.g. "Corne is a split keyboard with 2 designs in the
 * catalog: Corne Cherry v4 and Corne Chocolate v4. Between them they are built to 42 or
 * 36 keys, for Choc v1 or MX switches."
 *
 * EVERY CLAUSE IS A RECORDED FACT. Key counts and switch footprints are gate fields every
 * design has. "Split" or "unibody" is said only when every member's body agrees: the
 * family rule demands one body kind, but this sentence must not lean on a rule enforced
 * in another file -- if it ever broke, "a split keyboard" would be a claim about a board
 * whose own page says otherwise. Nothing unrecorded is mentioned, so nothing here can
 * turn an Unknown into a "no".
 */
internal fun familyLead(name: FamilyName, members: List<Design>): String {
    val bodies = members.map { it.core.body }
    val kind = when {
        bodies.all { it is Body.Split } -> "a split keyboard"
        bodies.all { it == Body.Unibody } -> "a unibody keyboard"
        else -> "a keyboard"
    }
    val names = members.map { it.core.name.value }.spokenList("and")
    // Largest first, as the design page's own key-count line and this page's Keys row
    // order them ("42 / 36 keys"), so one board's counts read the same way everywhere.
    val keys = members.flatMap { d -> d.core.layouts.map { it.keys.value } }.distinct().sortedDescending()
        .map { it.toString() }
    val switches = members.flatMap { d -> d.core.switches.map { it.describe() } }.distinct().sorted()
    // "Between them" only for counts that differ: said of one shared count it reads as if
    // the keys were divided between the boards. A footprint every variant shares gets
    // "all" only after "between them", so a shared count and a shared footprint read
    // "All are built to 34 keys, for Choc v1 switches." without "all" twice.
    val keyClause = if (keys.size == 1) "All are built to ${keys.single()} keys"
        else "Between them they are built to ${keys.spokenList("or")} keys"
    val switchClause = if (switches.size == 1 && keys.size > 1) "all for ${switches.single()} switches"
        else "for ${switches.spokenList("or")} switches"
    return "${name.value} is $kind with ${members.size} designs in the catalog: $names. $keyClause, $switchClause."
}

/** A GitHub account and nothing under it: "https://github.com/foostan", slash or not. */
private val ACCOUNT = Regex("""https://github\.com/[^/#?]+/?""", RegexOption.IGNORE_CASE)

/**
 * What the cited source IS, said before its link. For 26 of the 27 families it is the
 * author's GitHub account, and an account page never calls its boards one keyboard: the
 * grouping is this site's ruling under the #107 rule (one account, a shared name, a
 * shared key count, one body kind). "Source for this grouping: github.com/foostan" would
 * credit a ruling to a page that does not make it. So an account is shown as what it is,
 * where the variants are published -- and only when every variant's own record has a
 * source under it, since that is a fact this sentence must not assume from the rule.
 *
 * A source with more than an account (Sweep's README list of its types) is one that
 * names the variants itself, and is the grouping's source in the plain sense.
 */
private fun sourceIntro(source: String, members: List<Design>): String {
    if (!ACCOUNT.matches(source)) return "Source for this grouping:"
    val account = source.trimEnd('/').lowercase() + "/"
    val allUnder = members.all { d -> d.core.sources.any { it.url.lowercase().startsWith(account) } }
    return if (allUnder) "Grouped by this site: every design is published from"
    else "Grouped by this site; the source cited for it:"
}

/** "A", "A and B", "A, B and C" -- no serial comma, the way the rest of the site lists. */
internal fun List<String>.spokenList(conjunction: String): String = when (size) {
    0 -> ""
    1 -> single()
    else -> dropLast(1).joinToString(", ") + " $conjunction " + last()
}
