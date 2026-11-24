package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.domain.Connectivity
import net.keyboardbuilders.keyboardbuilders.domain.completeness
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.isOneHalfOf
import net.keyboardbuilders.keyboardbuilders.domain.largestKeyCount
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Ancestor
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.CapFootprint
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DiodeDirection
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.familyKey
import net.keyboardbuilders.keyboardbuilders.domain.Hand
import net.keyboardbuilders.keyboardbuilders.domain.KeyPitch
import net.keyboardbuilders.keyboardbuilders.domain.KeycapStem
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.ComponentKind
import net.keyboardbuilders.keyboardbuilders.domain.SolderingDifficulty
import net.keyboardbuilders.keyboardbuilders.domain.SplitLink
import net.keyboardbuilders.keyboardbuilders.domain.UsbPortAnswer
import net.keyboardbuilders.keyboardbuilders.domain.UsbPort
import net.keyboardbuilders.keyboardbuilders.domain.Stagger
import net.keyboardbuilders.keyboardbuilders.domain.SwitchExclusion
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchSensing
import net.keyboardbuilders.keyboardbuilders.domain.HotswapPart
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.hotswapParts
import net.keyboardbuilders.keyboardbuilders.domain.SwitchesSection
import net.keyboardbuilders.keyboardbuilders.domain.Term
import net.keyboardbuilders.keyboardbuilders.domain.keycapFits
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.domain.switchesSection
import net.keyboardbuilders.keyboardbuilders.domain.smdRequired

/*
 * Every `when` over a domain type lives in this file and nowhere else.
 *
 * The reason is the templates: jte can branch on a sealed interface, but then the
 * exhaustiveness check that makes these ADTs worth having moves into a file the
 * Kotlin compiler only sees after code generation, and adding a `Licence` variant
 * would fail at render time instead of at compile time. Here, adding one breaks the
 * build. So the templates receive strings and print them.
 */

/** ux.md §5.3: "not recorded" is a rendered state, never a blank cell. */
const val NOT_RECORDED = "not recorded"

/**
 * Key pitch: the grid the switches sit on, centre to centre, horizontal first.
 *
 * The owner's case is why this is worth a row at all: `diepala/cantor` ships a Classic
 * at Choc spacing and an MX sibling at 19.05. Both take Choc switches, so `switches`
 * alone reads as a subset relation — while in the shop they are different keycap
 * ecosystems, MBK against every ordinary MX profile.
 *
 * It names no keycaps. This line once said "MX-stem keycaps" for every 19.05 mm board,
 * which is wrong for Choc switches at MX spacing (Ferris 0.1 Base, Helix): the caps that
 * fit depend on the stem too, and the keycap section answers from both.
 */
private fun DesignCore.pitchLine(): SpecLine {
    val learn = "/learn/${LearnTopic.KeySpacing.slug}"
    val measured = pitch.knownOrNull()
        ?: return SpecLine("Key spacing", NOT_RECORDED, recorded = false, learnHref = learn)
    return SpecLine("Key spacing", when (measured) {
        KeyPitch.Mx -> "MX spacing · 19.05 × 19.05 mm"
        KeyPitch.Choc -> "Choc spacing · 18 × 17 mm"
        is KeyPitch.Custom -> "Non-standard · ${measured.spacing}"
    }, learnHref = learn)
}

/**
 * The star count as the DETAIL page shows it: exact, and stating its own age.
 *
 * Ticket 54's three questions land here together. The number is exact because there is
 * room for it and no scanning to disturb. It states when it was checked because the build
 * is rebuild-only on a cron, so a bare number would look live and be a week old — and the
 * map's standing rule is that *"a wrong value indistinguishable from a right one is the
 * expensive failure mode"*. And an unknown renders as `not recorded`, in the same style as
 * every other unrecorded field, because that is the family this belongs to: NOT `0 stars`,
 * which would claim GitHub told us nobody had starred it.
 */
private fun Design.starsLine(activity: RepoActivity?): SpecLine {
    val count = activity?.starsFor(this)
        ?: return SpecLine("Stars", NOT_RECORDED, recorded = false)
    val checked = activity.checkedPhrase()
    // "%,d" so five figures stay readable. The row rounds; this one does not.
    return SpecLine("Stars", "%,d".format(count) + (checked?.let { " · $it" } ?: ""))
}

/**
 * One row of the spec table. `recorded = false` is what lets the template render
 * ignorance in its own style instead of passing it off as a value.
 */
/** One value of a spec line, and where asking "what else?" leads. */
/** One term of a spec line: a link, or plain text when [href] is null. */
data class SpecTerm(val text: String, val href: String?)

data class SpecLine(
    val label: String,
    val value: String,
    val recorded: Boolean = true,
    /**
     * The value broken into linked terms, or empty to print [value] as plain text.
     *
     * `ux.md` §3.1 asks for exactly this and gives the reason: linking a design's facts
     * back to the search "turn[s] every detail page into a lateral-exploration surface and
     * an internal-link farm for crawlers". It is filled for `ext` first because that
     * facet needs it most — its four sparse values get no rail checkbox, so a design's own
     * page is where a reader meets "trackpad" and the only place they can act on it.
     *
     * Every other line still prints plain text. That is a gap rather than a decision, and
     * the reason this is a list of terms rather than one URL on the line: `switch_footprint`
     * and `firmware` are multi-valued too and want the same treatment when someone gets to
     * them.
     */
    val terms: List<SpecTerm> = emptyList(),
    /**
     * The long form, shown on hover, or null when the value says everything it needs to.
     *
     * OWNER-REQUESTED 2026-09-16: *"way too long. make it short and put the rest of the
     * text in a tooltip hint"*. The Connectivity line was the trigger -- a 170-character
     * sentence in a `<dl>` whose other values are two or three words.
     *
     * **THE VISIBLE TEXT STILL CARRIES THE CLAIM**, and the hint only elaborates. Ticket
     * 16 decision 3 rule 1 -- *"a struck row is a claim about the world and must survive
     * being screenshotted into a Discord thread"* -- applies here too, and a `title` that
     * held the caveat would fail it: tooltips do not survive a screenshot, a touch screen,
     * or a reader who never hovers. So "needs a ZMK config" is on the line, and what moves
     * into the hint is which other boards fit and why we say "nobody has recorded".
     */
    val hint: String? = null,
    /**
     * The learn page that explains this line's LABEL, or null where none does (#108).
     *
     * ON THE LABEL, NOT THE VALUE, because the value's terms already link somewhere else --
     * to the catalog filter for that value, `ux.md` §3.1's lateral exploration -- and one
     * word cannot lead to two places. "What does Hotswap mean?" is a question about the
     * label; "what else takes Mill-Max?" is one about the value.
     *
     * Set only where the page explains what THIS line says: the Choc page for a board that
     * takes a Choc-family switch, the reversible page for a Body line that states the
     * reversible answer. A link from an MX-only board to a Choc explainer would be noise.
     */
    val learnHref: String? = null,
)

/**
 * The learn page explaining a design's switch line, or null when it takes no switch the
 * Choc page covers. Kailh X is listed there too, so it counts.
 */
private fun Set<SwitchFootprint>.learnHref(): String? =
    if (any { it == SwitchFootprint.ChocV1 || it == SwitchFootprint.ChocV2 || it == SwitchFootprint.ChocMini || it == SwitchFootprint.KailhX })
        "/learn/${LearnTopic.ChocSwitches.slug}" else null

/** ux.md §2.3: the result row is name, then the shape, then the electronics. */
data class DesignRow(
    val name: String,
    val slug: String,
    val shape: List<String>,
    val electronics: List<String>,
    /** Raw inline SVG. Written into the page with `$unsafe`, so it is built here
     *  and never assembled in a template. */
    val silhouette: String,
    /**
     * Rounded, and null when the repo has no known count. Ticket 54: the default list is
     * ALREADY sorted by this number, so leaving it undrawn does not remove the
     * leaderboard, it only makes the order inexplicable — a reader cannot see why one
     * board sits above another. Null renders as nothing rather than as "not recorded":
     * the row is the scanning surface and its chips are facts about the KEYBOARD, while
     * the spec table below is where this site advertises its own gaps.
     */
    val stars: String? = null,
    /**
     * Our own hearts, drawn wherever [stars] is (owner, 2026-10-01), zero included: "since
     * the reddit posting, I've had 500 visitors in 3 days", so the counts fill in fast,
     * and a zero says the design can be hearted. Null only where a caller fetched no
     * counts (the og image, the home tiles), and then nothing is drawn.
     */
    val hearts: Int? = null,
    /** Backlog #46: the same percentage the detail page shows, as a bare number for the
     *  card's chip -- a list of dozens stays scannable only if this is one word, never
     *  the field-by-field breakdown the detail page prints. */
    val completeness: Int,
)

/**
 * A design as a card: its row, its build photo at card width, and who made it. Chosen
 * 2026-09-26 from the theme prototypes (branch `prototype/theme`), where the owner
 * picked the photo with the board's own drawing laid over its corner.
 *
 * [photo] null is ordinary -- most designs have none -- and the card then draws the
 * silhouette large in the photo's place, so every card in a grid has one shape.
 */
data class DesignCard(
    val row: DesignRow,
    val photo: String?,
    val by: String?,
    /** False where the silhouette is only the "not drawn" placeholder: the photo's corner
     *  badge is then left off rather than framing an empty chip. */
    val drawn: Boolean,
    /** True where [photo] is the board's PCB render rather than a build photo: a reader who
     *  chose "render first" on /settings gets it in the photo's place, and the alt text must
     *  not call a bare circuit board "built". */
    val rendered: Boolean = false,
)

fun Design.toCard(row: DesignRow, photos: Photos?, silhouettes: Silhouettes, render: Renders.Render? = null): DesignCard =
    if (render != null) DesignCard(row, "/renders/${render.png}.png", core.sources.credit()?.handle, silhouettes.isDrawn(core.slug.value), rendered = true)
    else DesignCard(
        row,
        photos?.of(core.slug.value)?.let(Photos::cardUrl),
        core.sources.credit()?.handle,
        silhouettes.isDrawn(core.slug.value),
    )

/**
 * Who published the design, as a handle and a link to them.
 *
 * THE REPO OWNER, WHICH IS NOT QUITE "THE DESIGNER" AND IS THE HONEST ANSWER ANYWAY.
 * Measured over the corpus: all 234 designs cite a GitHub repo, across 141 owners, and 5
 * of those repos are forks of another. It is tempting to read those 5 as miscredited --
 * `adelheid` is floookay's fork of FateNozomi's Arisu -- but the design record IS the
 * fork: `adelheid` is floookay's keyboard, derived from someone else's. So the owner is
 * the right credit for all 234, and what is missing on those 5 is the ANCESTOR, which is
 * `based_on` and belongs to Forgejo #24 rather than here.
 *
 * Deliberately NOT a domain field. `DesignCore.sources` already carries this; adding an
 * author to the record would either duplicate it or invite someone to fill it in by hand,
 * and #24 is explicit that the designer question is undecided. This is a reading of data
 * already present, so it commits nothing.
 */
data class Credit(val handle: String, val url: String)

private val GITHUB_OWNER = Regex("""https://github\.com/([^/]+)/""")

/** The first GitHub owner among the sources, or null where none is readable. */
internal fun Set<SourceRef>.credit(): Credit? =
    firstNotNullOfOrNull { GITHUB_OWNER.find(it.url)?.groupValues?.get(1) }
        ?.let { Credit(it, "https://github.com/$it") }

/**
 * The Spec table's headings, owner-agreed 2026-09-25, in the rail's own order so a
 * design page and the filters use one vocabulary. Each lists its rows by label, in the
 * order they print. A row built in `toPage` that no section names is a loud failure
 * there, not a row silently dropped -- see [sectioned].
 */
enum class SpecSection(val heading: String, val labels: List<String>) {
    Shape("Shape", listOf("Body", "Split link", "Splay", "Stagger", "Key spacing")),
    Switches("Switches", listOf("Switch footprint", "Hotswap")),
    Electronics("Electronics", listOf(
        "Controller", "Connectivity", "USB port", "Firmware", "Firmware targets", "Key wiring", "Room for",
        "Power switch", "Exposed components",
    )),
    Building("Building it", listOf("Build method", "Soldering", "SMD required")),
}

data class SpecSectionView(val heading: String, val lines: List<SpecLine>)

/**
 * [lines] under their [SpecSection]s. A section with no line on this design (a unibody's
 * Shape still has Body) is left out rather than printed as an empty heading.
 */
private fun sectioned(lines: List<SpecLine>): List<SpecSectionView> {
    val placed = SpecSection.entries.map { section ->
        SpecSectionView(section.heading, section.labels.mapNotNull { l -> lines.find { it.label == l } })
    }
    val unplaced = lines.map { it.label } - placed.flatMap { s -> s.lines.map { it.label } }.toSet()
    check(unplaced.isEmpty()) { "spec rows with no section: $unplaced -- add them to SpecSection" }
    return placed.filter { it.lines.isNotEmpty() }
}

/** Every spec row, flat and in section order -- what the compare page lines up. */
val DesignPage.spec: List<SpecLine> get() = specSections.flatMap { it.lines }

data class DesignPage(
    val name: String,
    val slug: String,
    val shape: List<String>,
    val electronics: List<String>,
    val summary: String?,
    val layouts: List<LayoutView>,
    /** What the page title calls the design -- "42-key split keyboard". See [headline]. */
    val headline: String = "",
    /**
     * One sentence of recorded facts, for the search snippet of a design with no
     * [summary]. Ours, not the author's, and written so it cannot be mistaken for theirs.
     * See [factSentence].
     */
    val facts: String = "",
    /** The Spec table under its headings, in the rail's own order. */
    val specSections: List<SpecSectionView>,
    /** The star count, in the byline since 2026-09-25 -- not a spec of the keyboard. */
    val stars: SpecLine,
    val licenses: List<SpecLine>,
    val sources: List<String>,
    val author: Credit? = null,
    /**
     * The 3D board render, where one exists -- backlog #60. Null is the ordinary case
     * and means "not rendered yet", never "this design has no board", so the page simply
     * says nothing rather than apologising for an absence.
     */
    val render: RenderView? = null,
    /**
     * A real photo of the finished board, backlog #101 -- reviewed and approved, never
     * fetched live from the design's own repo. Null is the ordinary case (most designs
     * have not been reviewed yet), and the page simply shows nothing extra.
     */
    val photo: PhotoView? = null,
    /**
     * Credited ancestors, backlog #24. Empty means no explicit statement was found, and
     * the page says nothing rather than "not recorded" -- an absent credit is the
     * common case (163 of 234 designs have no confirmed row for the adjacent `summary`
     * field), not a gap to apologise for on every page.
     */
    val basedOn: List<AncestorView> = emptyList(),
    /**
     * The reverse of [basedOn], backlog #110 step 4: the held designs whose own confirmed
     * credit names this one. Read off their records, never stored here, so it can't
     * disagree with the credit it mirrors. Shown as cards above "Similar designs",
     * the same card; empty, and no section, for the common case.
     */
    val derivedFromThis: List<DesignCard> = emptyList(),
    /** Backlog #107: the keyboard this board is one PCB of, with every board in it. Null
     *  for a design in no recorded family -- the page says nothing, like [basedOn]. */
    val family: FamilyView? = null,
    /** "Similar designs" (was "Boards like this") -- ux.md §3.6. Empty when nothing shares a weighted facet,
     *  which a design with an unusual combination of Unknowns can genuinely reach. */
    val related: List<RelatedDesignView> = emptyList(),
    /** Backlog #46: "how well is this design represented" -- ux.md §5.3's single stat
     *  line, never the per-group breakdown that section also describes (a bigger,
     *  unbuilt contribution flow this ticket does not ask for). */
    val completeness: CompletenessView,
)

/**
 * One row under "Layouts" -- `label` is the same text the row always showed
 * (`Layout.describe()`: key count, shorthand, and now its own name if the ticket #23
 * pipeline found one). `silhouette` is backlog #23's addition: a second picture, drawn
 * from the design's own firmware where it states this exact count as a complete shape
 * of its own (`Silhouettes.renderLayout`), or from the page's own main picture for
 * whichever row is the design's biggest layout -- null only for a smaller count
 * nothing separately draws, never a placeholder standing in for one.
 */
data class LayoutView(val label: String, val silhouette: String?)

/** A photo as the page needs it: the object-store URL and enough of its own dimensions
 *  to reserve the right box before it loads (`ux.md` 7.7, no layout shift). */
/** [creditUrl] and [creditName] are null for a photo from the design's own README. */
data class PhotoView(
    val url: String,
    val width: Int,
    val height: Int,
    val creditUrl: String? = null,
    val creditName: String? = null,
    /** The login of whoever added it here; the caption then reads "Photo added by @x". */
    val addedBy: String? = null,
)

/** `percent` is derived, never stored -- see `DesignCompleteness.percent` for the rounding rule.
 *  `fields` is the whole accounting, owner-requested 2026-09-16: a total nobody can check
 *  against a printed list is a number to distrust, not a fact to invite help around. */
data class CompletenessView(val percent: Int, val known: Int, val total: Int, val fields: List<CompletenessFieldView>) {
    /** Ten-segment bar, `ux.md` §5.3's own glyphs. Decoration for the sentence beside it,
     *  which already carries the number -- so the template marks this `aria-hidden`
     *  rather than making a screen reader hear the same fact twice. */
    val bar: String
        get() {
            val filled = (percent * 10 + 50) / 100
            return "▰".repeat(filled) + "▱".repeat(10 - filled)
        }
}

data class CompletenessFieldView(val label: String, val known: Boolean)

/** One "boards like this" card: the photo card it already has everywhere else, plus why. */
data class RelatedDesignView(val card: DesignCard, val reason: String)

/**
 * One credited ancestor as the page shows it. `href` is null for an [Ancestor.External]
 * credit -- ticket 22 decision 8: an unheld ancestor mints no internal slug, so its link
 * goes straight to the URL the credit itself named rather than to a page this site does
 * not have.
 */
data class AncestorView(val name: String, val href: String, val external: Boolean)

/**
 * A render as the page needs it: two same-origin URLs, and the honesty about which board.
 *
 * The URLs are built HERE rather than in the template, because a template that
 * concatenates a path is a template that can be made to concatenate a different one.
 * `boardName` is the file's own last segment -- enough for a reader to tell the hotswap
 * board from the soldered one without printing a whole repo path at them.
 */
data class RenderView(
    val model: String,
    val poster: String,
    val boardName: String,
    val alternates: Int,
    val modelBytes: Long = 0,
) {
    val hasAlternates: Boolean get() = alternates > 0

    /**
     * "13 MB", or empty when the seed has no size for this entry.
     *
     * MB and not MiB: this is a download size shown to a reader, and every browser's own
     * download UI counts in millions. Rounded to whole MB above 1 MB because a tenth of
     * a megabyte is below the resolution of the decision being made -- and these models
     * are never small enough for the tenth to matter.
     */
    val modelSize: String
        get() = when {
            modelBytes <= 0L -> ""
            modelBytes < 1_000_000L -> "${modelBytes / 1_000} KB"
            else -> "${(modelBytes + 500_000L) / 1_000_000} MB"
        }
}

// ---------------------------------------------------------------------------
// The two view models
// ---------------------------------------------------------------------------

/**
 * `keysFilter` is the request's own `keys` selection (empty for an unfiltered list, or a
 * related-designs row, or the OG image) -- the card the user is scanning should picture
 * the layout that put it in these results, not always the design's biggest one. A design
 * carrying `46 / 45 / 39 keys` filtered to `?keys=39` used to always show the 46-key
 * picture: correctly matched, correctly listed, and visually the wrong board, at a size
 * ux.md §3.5's common scale renders conspicuously larger than everything around it.
 */
fun Design.toRow(
    silhouettes: Silhouettes,
    activity: RepoActivity? = null,
    keysFilter: Set<Term> = emptySet(),
    hearts: Int? = null,
) = DesignRow(
    name = core.name.value,
    slug = core.slug.value,
    shape = shapeChips(brief = true),
    electronics = electronicsChips(),
    silhouette = matchedSilhouette(silhouettes, keysFilter),
    stars = activity?.starsFor(this)?.let(::formatStars),
    hearts = hearts,
    completeness = completeness().percent,
)

/** The card's picture with no filter applied: the design's largest layout. For lists
 *  outside /designs, such as "Your designs" (#116). */
fun Design.rowSilhouette(silhouettes: Silhouettes): String = matchedSilhouette(silhouettes, emptySet())

/**
 * The card's picture: the layout `keysFilter` singled out, when one did and a distinct
 * geometry was ever recorded for it -- otherwise the design's largest layout, unchanged.
 *
 * `renderLayout` returns null for the common case of no separately-recorded geometry at
 * that count (most designs; see its own doc comment), and that null is exactly the signal
 * to fall back rather than draw a placeholder standing in for a layout we cannot show.
 */
private fun Design.matchedSilhouette(silhouettes: Silhouettes, keysFilter: Set<Term>): String {
    layoutMatching(keysFilter)
        ?.takeIf { it.keys.value != largestKeyCount() }
        ?.let { layout ->
            silhouettes.renderLayout(
                slug = core.slug.value,
                keys = layout.keys.value,
                label = layoutLabel(layout),
                unit = Silhouettes.ROW_UNIT,
                cap = Silhouettes.ROW_CAP,
            )?.let { return it }
        }
    return silhouettes.render(
        slug = core.slug.value,
        keyCount = largestKeyCount(),
        label = silhouetteLabel(),
        unit = Silhouettes.ROW_UNIT,
        cap = Silhouettes.ROW_CAP,
        halves = halvesOrOne(),
    )
}

/** The layout a `keys` filter picked out, largest among ties -- the same tie-break
 *  [largestKeyCount] already makes when nothing filters at all. Null for an empty filter
 *  and for a filter this design's own layouts happen not to satisfy (should not reach here:
 *  the SQL projection is what decided this design belongs in the result set). */
private fun Design.layoutMatching(keysFilter: Set<Term>): Layout? =
    keysFilter.takeIf { it.isNotEmpty() }?.let { terms ->
        core.layouts.filter { layout -> terms.any { it.matches(layout.keys.value) } }
            .maxByOrNull { it.keys.value }
    }

private fun Term.matches(value: Int): Boolean = when (this) {
    is Term.Exact -> token == value.toString()
    is Term.Span -> (lo == null || value >= lo) && (hi == null || value <= hi)
}

/**
 * The label states the facts, not "layout diagram" (ux.md §3.5). It reads the same
 * facts the row prints, so a screen reader gets what a sighted scan gets.
 */
private fun Design.silhouetteLabel(): String =
    (listOf("${core.name.value}, ${largestKeyCount()} keys") + shapeChips().drop(0).takeLast(1))
        .joinToString(", ")

/** Same voice as [silhouetteLabel], for one layout among several rather than the
 *  design's largest. */
private fun Design.layoutLabel(layout: Layout): String =
    "${core.name.value}, ${layout.describe()}, ${core.body.describe()}"

/**
 * `catalog` is optional and a `Held` reference that fails to resolve is DROPPED, not
 * thrown — a stale credit is a display extra, and the design page is not worth a 500
 * over one. This should not happen if the seed's own join is honest, and if it does,
 * a missing credit is the failure that costs the reader least.
 */
private fun Set<Ancestor>.toAncestorViews(catalog: DesignCatalog?): List<AncestorView> =
    mapNotNull { ancestor ->
        when (ancestor) {
            is Ancestor.Held -> catalog?.bySlug(ancestor.slug.value)?.let {
                AncestorView(name = it.core.name.value, href = "/designs/${ancestor.slug.value}", external = false)
            }
            is Ancestor.External -> AncestorView(name = ancestor.name, href = ancestor.url, external = true)
        }
    }.sortedBy { it.name.lowercase() }

/** Every held design whose confirmed `basedOn` names this one, sorted by name. */
private fun Design.derivedFromThis(catalog: DesignCatalog?): List<Design> {
    val me = Ancestor.Held(core.slug)
    return catalog?.all().orEmpty()
        .filter { me in it.extras.basedOn.knownOrNull().orEmpty() }
        .sortedBy { it.core.name.value.lowercase() }
}

/** Shapes the placeholder when geometry is missing but splitness is known. */
private fun Design.halvesOrOne() =
    (core.body as? Body.Split)?.halves?.value ?: 1

fun Design.toPage(
    silhouettes: Silhouettes,
    activity: RepoActivity? = null,
    renders: Renders? = null,
    catalog: DesignCatalog? = null,
    related: RelatedDesigns? = null,
    /**
     * Computed by the caller, which is where the two catalogues already are -- one
     * nullable parameter rather than passing both down. Null omits the row, which is what
     * the many fixture-based tests of this function want.
     */
    connectivity: Connectivity? = null,
    photos: Photos? = null,
    hearts: net.keyboardbuilders.keyboardbuilders.persistence.Hearts? = null,
    /** Backlog #97, computed by the caller for the same reason as [connectivity]; null
     *  omits the row. */
    usbPort: UsbPortAnswer? = null,
    /** The slugs that have a `/parts/dev-board/:slug` page, so the Controller line links
     *  only to a page that exists. Empty links nothing, which fixture tests want. */
    devBoards: Set<String> = emptySet(),
): DesignPage {
    // The design's one picture, drawn once here and used for the Layouts list's own
    // biggest row -- `renderLayout` only has geometry for the minority of designs that
    // declare more than one complete shape (backlog #23), so most layouts have no
    // picture of their own to fall back on. Layouts is the only place it now appears;
    // an earlier version also showed it above the fold, which made every design with a
    // single layout carry the identical picture twice.
    //
    // LABELLED AS ITS LAYOUT, not as the design. The label is the lightbox caption, and
    // with `silhouetteLabel()` KLOR's biggest picture opened as "KLOR, 42 keys" while its
    // three smaller ones named themselves -- a reader could not tell it was Polydactyl.
    val mainSilhouette = silhouettes.render(
        slug = core.slug.value,
        keyCount = largestKeyCount(),
        label = layoutLabel(core.layouts.maxBy { it.keys.value }),
        unit = Silhouettes.DETAIL_UNIT,
        cap = Silhouettes.DETAIL_CAP,
        halves = halvesOrOne(),
    )
    val derived = derivedFromThis(catalog)
    return DesignPage(
        name = core.name.value,
        slug = core.slug.value,
        basedOn = extras.basedOn.knownOrNull().orEmpty().toAncestorViews(catalog),
        derivedFromThis = derived.let { children ->
            val counts = hearts?.forDesigns(children).orEmpty()
            children.map { it.toCard(it.toRow(silhouettes, activity, hearts = counts[it.core.slug.value]), photos, silhouettes) }
        },
        family = catalog?.let { familyOf(this, it) },
        related = related?.similarTo(this, exclude = derived.map { it.core.slug }.toSet())?.let { similar ->
            val counts = hearts?.forDesigns(similar.map { it.design }).orEmpty()
            similar.map {
                RelatedDesignView(it.design.toCard(it.design.toRow(silhouettes, activity, hearts = counts[it.design.core.slug.value]), photos, silhouettes), it.reason)
            }
        } ?: emptyList(),
        // BRIEF ON THE PAGE TOO since 2026-09-26, owner: "remove the '2 halves, TRRS cable
        // between halves' since it's the same text on like 98% of them". The Spec table's
        // Body and Split link rows still say both, so the chip loses nothing but repetition.
        shape = shapeChips(brief = true),
        headline = headline(),
        facts = factSentence(connectivity),
        electronics = electronicsChips(),
        summary = extras.summary.knownOrNull(),
        // Sorted so the page reads the same on every request; `Set` gives no order worth
        // showing a user, and the biggest arrangement is the one people recognise. Its own
        // row gets `mainSilhouette` rather than a second call to `renderLayout`: that only
        // has geometry for the minority of designs recorded under backlog #23, so falling
        // back to the design's own picture is what makes every design's biggest row look
        // the same as a multi-layout design's does, instead of only the ones with recorded
        // per-layout geometry.
        layouts = core.layouts.sortedByDescending { it.keys.value }.mapIndexed { i, layout ->
            LayoutView(
                label = layout.describe(),
                // `renderLayout`'s own geometry wins where it exists -- it is the design's
                // recorded shape for this exact count, `mainSilhouette` is a stand-in drawn
                // from the largest layout's geometry regardless of which row it lands on.
                silhouette = silhouettes.renderLayout(
                    slug = core.slug.value,
                    keys = layout.keys.value,
                    label = layoutLabel(layout),
                    unit = Silhouettes.DETAIL_UNIT,
                    cap = Silhouettes.DETAIL_CAP,
                ) ?: if (i == 0) mainSilhouette else null,
            )
        },
        // `listOfNotNull` since 2026-09-10: the `Connectivity` row is absent when the caller
        // passes no capability (every fixture-based test of this function), and an absent row
        // is not the same as a row saying nothing.
        stars = starsLine(activity),
        specSections = sectioned(listOfNotNull(
            core.pitchLine(),
            SpecLine("Build method", core.buildMethod.describe()),
            core.buildMethod.solderingLine(),
            core.buildMethod.smdRequired.let { smd ->
                SpecLine(
                    "SMD required",
                    when (smd) {
                        Recorded.Unknown -> NOT_RECORDED
                        is Recorded.Known -> if (smd.value) "yes" else "no"
                    },
                    recorded = smd is Recorded.Known,
                )
            },
            core.controller.describe().let { value ->
                SpecLine(
                    "Controller",
                    value,
                    terms = core.controller.namesake()?.takeIf { it in devBoards }
                        ?.let { listOf(SpecTerm(value, "/parts/dev-board/$it")) }.orEmpty(),
                )
            },
            /*
             * NEXT TO `Controller`, BECAUSE IT IS A FACT ABOUT THE PAIR. "Can this be
             * wireless" is not a property of the design -- for a socketed one it belongs to
             * the (design, controller) pair, and the builder picks the controller. Sitting it
             * beside the socket is what makes that readable rather than surprising.
             *
             * ALWAYS `recorded = true`, INCLUDING THE `Unrecorded` STATES, which reads like a
             * contradiction and is not. `show.kte` renders an unrecorded line as
             * `&mdash; value &mdash;`, so a sentence that already contains an em-dash came out
             * as "— Maybe — nobody has recorded which chip is on this board —". Three dashes.
             *
             * The punctuation was the smaller half. `recorded = false` renders the site's
             * "we hold nothing here" treatment, and these rows DO hold something: a specific
             * account of what is missing and why. `Connectivity.Unrecorded` is about the
             * FACET's value being unestablished -- which the rail expresses by having no row
             * at all, below its divider -- while this line's job is to say which of four
             * reasons applies. The uncertainty is in the sentence, not in the styling.
             */
            connectivity?.let { SpecLine("Connectivity", it.describe(), hint = it.hint()) },
            usbPort?.let { it.line() },
            // THE SHAPE ALONE, since backlog #97 gave the link a row of its own below. The
            // header's facts line and the page title still say both, through `describe()`.
            SpecLine(
                "Body",
                core.body.describeShape(),
                // Only where the line states the reversible answer -- `describeShape` prints
                // it for a Known split and nothing for an Unknown one.
                learnHref = "/learn/${LearnTopic.ReversiblePcb.slug}"
                    .takeIf { (core.body as? Body.Split)?.reversible is Recorded.Known },
            ),
            // Splits only: a one-piece board has no link to record, and a row reading
            // "not recorded" there would look like a gap in our data rather than in the
            // keyboard. A known set is never empty (the catalog reads `[]` as Unknown --
            // a name-matching scan proves presence, not absence), so this names a cable
            // or a bridge; the fallback is for a set holding neither, which none does.
            (core.body as? Body.Split)?.let { split ->
                val named = split.links.knownOrNull()?.let { it.cables() ?: it.bridge() }
                named?.let { SpecLine("Split link", it) }
                    ?: SpecLine("Split link", NOT_RECORDED, recorded = false)
            },
            // Same two-states-plus-ignorance shape as `SMD required` above, and for the
            // same reason it is a genuine yes/no: `DesignExtras.splay`'s own doc comment.
            extras.splay.let { splay ->
                SpecLine(
                    "Splay",
                    when (splay) {
                        Recorded.Unknown -> NOT_RECORDED
                        is Recorded.Known -> if (splay.value) "yes" else "no"
                    },
                    recorded = splay is Recorded.Known,
                )
            },
            // Same shape as `Splay` just above, and for the same reason: a genuine
            // measured verdict, not evidence-only, so `Unknown` gets the ignorance
            // style and every other value renders as its own answer.
            extras.stagger.let { stagger ->
                SpecLine(
                    "Stagger",
                    when (stagger) {
                        Recorded.Unknown -> NOT_RECORDED
                        is Recorded.Known -> when (stagger.value) {
                            Stagger.Ortho -> "ortholinear"
                            Stagger.Row -> "row-staggered"
                            Stagger.Column -> "column-staggered"
                        }
                    },
                    recorded = stagger is Recorded.Known,
                    // Whatever the value, as Key spacing does: the label is itself the term
                    // a newcomer asks about, and the page says what "not recorded" means.
                    learnHref = "/learn/${LearnTopic.Stagger.slug}",
                )
            },
            SpecLine(
                "Switch footprint",
                core.switches.map { it.describe() }.sorted().joinToString(", "),
                learnHref = core.switches.learnHref(),
            ),
            // THE PARTS THAT FIT, each linking to its rail value, so a builder reads off the
            // sockets in their drawer (#107 step 9). Read off the pads, so a solder-only
            // board is a recorded "no" and not the gap.
            extras.switchMounts.let { mounts ->
                val known = mounts.knownOrNull()
                val parts = known?.hotswapParts().orEmpty()
                val value = when {
                    known == null -> NOT_RECORDED
                    parts.isEmpty() -> "No, the switches solder in"
                    SwitchMount.Solder in known ->
                        parts.joinToString(", ") { it.word() } + ", or solder the switches in"
                    else -> parts.joinToString(", ") { it.word() }
                }
                SpecLine(
                    "Hotswap",
                    value,
                    recorded = known != null,
                    learnHref = "/learn/${LearnTopic.Hotswap.slug}",
                    terms = parts.map { SpecTerm(it.word(), "/designs?${FacetKey.Hotswap.param}=${it.token}") } +
                        listOfNotNull(SpecTerm("or solder the switches in", null)
                            .takeIf { parts.isNotEmpty() && SwitchMount.Solder in known.orEmpty() }),
                )
            },
            when (val firmware = core.firmware) {
                is Recorded.Known -> SpecLine("Firmware", firmware.value.map { it.describe() }.sorted().joinToString(", "))
                Recorded.Unknown -> SpecLine("Firmware", NOT_RECORDED, recorded = false)
            },
            // WHAT THE FIRMWARE IS BUILT FOR, which is a different fact from what is on the
            // board and is the only chip fact a SOCKETED design has at all — its board
            // carries no MCU, by ticket 50's ruling. It is the answer to "will the Pro Micro
            // in my drawer run this", and the footprint alone never gives it: 91 socketed
            // designs here target atmega32u4 and 4 target RP2040 on the same `pro_micro`
            // footprint, which is a board that physically fits and firmware that will not run.
            //
            // A SET, because a design may cite several records. One design's two records
            // disagree today, and that is a fact about the design worth printing rather than
            // a conflict to resolve behind the reader's back.
            // Absent with no record at all (#106): it is a fact about a record.
            core.firmware.knownOrNull()?.map { (it as? FirmwareRef.Qmk)?.targetMcu ?: Recorded.Unknown }
                ?.mapNotNull { it.knownOrNull() }
                ?.toSortedSet()
                ?.let { chips ->
                    SpecLine(
                        "Firmware targets",
                        chips.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { it.describe() } ?: NOT_RECORDED,
                        recorded = chips.isNotEmpty(),
                    )
                },
            extras.wiring.let { wiring ->
                SpecLine(
                    "Key wiring",
                    wiring.knownOrNull()?.let { it.describe(perHalf = isOneHalfOf(it)) } ?: NOT_RECORDED,
                    recorded = wiring is Recorded.Known,
                )
            },
            // "ROOM FOR", NEVER "HAS". A footprint is a place a part can go, and an OLED
            // footprint with no display soldered is one of the most common builds there is,
            // so the label carries the claim the data actually supports. `measure_extras.py`
            // says the same at its own top and asks that every reader-facing word obey it.
            //
            // THE ONLY PLACE ALL THREE STATES ARE VISIBLE. The rail shows presence and the
            // projection writes no row for either kind of absence, so "read, and bare" —
            // 223 designs — exists for a reader here and nowhere else. It is `recorded`,
            // because it is a measurement: rendering it in the ignorance style would say we
            // never looked, which is the fabrication this field was reshaped to avoid.
            // "ROOM FOR", NEVER "HAS". A footprint is a place a part can go, and an OLED
            // footprint with no display soldered is one of the most common builds there is,
            // so the label carries the claim the data actually supports.
            //
            // NO "nothing" ROW. This read `Room for: nothing` for 223 designs until the
            // measurement behind it was checked: the scan proves presence and cannot prove
            // absence, and 11 of the 223 were contradicted by their own QMK record. What we
            // have no evidence for is not recorded, and says so in the ignorance style like
            // every other unrecorded fact on this page.
            extras.extensions.let { ext ->
                val room = ext.knownOrNull()?.map { it.name.lowercase() }?.sorted()
                SpecLine(
                    "Room for",
                    room?.joinToString(", ") ?: NOT_RECORDED,
                    recorded = ext is Recorded.Known,
                    // `ux.md` §3.1 — every detail page a lateral-exploration surface, and the
                    // only place a reader meets "trackpad", which earns no rail checkbox.
                    terms = room.orEmpty().map {
                        SpecTerm(it, "/designs?${FacetKey.Ext.param}=$it")
                    },
                )
            },
            // Same shape as `Splay`: a measured yes/no, and the gap in the ignorance style.
            extras.powerSwitch.let { power ->
                SpecLine(
                    "Power switch",
                    when (power) {
                        Recorded.Unknown -> NOT_RECORDED
                        is Recorded.Known -> if (power.value) "yes" else "no"
                    },
                    recorded = power is Recorded.Known,
                )
            },
            extras.exposedComponents.let { exposed ->
                SpecLine(
                    "Exposed components",
                    when (exposed) {
                        Recorded.Unknown -> NOT_RECORDED
                        is Recorded.Known -> if (exposed.value) "yes" else "no"
                    },
                    recorded = exposed is Recorded.Known,
                )
            },
        )),
        // Three separate rows because the hobby licenses them separately -- MIT firmware
        // beside CERN-OHL-S hardware is routine, and collapsing them would hide the one
        // a builder actually needs.
        licenses = listOf(
            SpecLine("Hardware", core.licensing.hardware.describe()),
            SpecLine("Firmware", core.licensing.firmware.describe()),
            SpecLine("Documentation", core.licensing.documentation.describe()),
        ),
        sources = core.sources.map { it.url }.sorted(),
        author = core.sources.credit(),
        render = renders?.of(core.slug.value)?.let {
            RenderView(
                model = "/renders/${it.glb}.glb",
                poster = "/renders/${it.png}.png",
                boardName = it.path.substringAfterLast('/'),
                alternates = it.alternates,
                modelBytes = it.glbBytes,
            )
        },
        photo = photos?.of(core.slug.value)?.let {
            PhotoView(
                url = photos.url(it), width = it.width, height = it.height,
                creditUrl = it.credit,
                creditName = it.credit?.let { c -> java.net.URI(c).host?.removePrefix("www.") ?: c },
                addedBy = it.addedBy,
            )
        },
        completeness = completeness().let {
            CompletenessView(it.percent, it.known, it.total, it.fields.map { f -> CompletenessFieldView(f.label, f.known) })
        },
    )
}

/** [brief] for a card: the body in one word ([Body.brief]); the design page keeps it all. */
private fun Design.shapeChips(brief: Boolean = false): List<String> = buildList {
    // The shorthand leads, per ux.md §2.3 -- it is how users learn the query grammar.
    // Most designs have none, and then the key count carries the line on its own.
    core.layouts.mapNotNull { it.shorthand.knownOrNull()?.toString() }
        .distinct()
        .sorted()
        .forEach { add(it) }
    add(core.layouts.map { it.keys.value }.sortedDescending().joinToString(" / ") + " keys")
    add(if (brief) core.body.brief() else core.body.describe())
}

/**
 * The design as the page title names it: key count, body, and the noun.
 *
 * "KEYBOARD" BECAUSE THAT IS THE WORD IN THE QUERY. People search "corne keyboard" and
 * "sofle keyboard"; until 2026-10-03 the title read "Corne — 42 keys, split", which has
 * the shape and not the noun, on every one of 338 design pages.
 *
 * THE KLOR RULING STILL HOLDS. Owner, 2026-09-26: the title stops at "split" -- halves,
 * PCB ordering and the cable are build detail for the Spec table. So the body is
 * [Body.brief]'s one word or nothing: a unibody is just a keyboard, because "unibody" is
 * what a reader assumes and no one types it. A one-handed board does say so, because
 * building one half of a keyboard is not what "keyboard" would lead anyone to expect.
 *
 * NO MACROPAD. The corpus has no notion of one, and calling every board under some key
 * count a macropad would be a classification nobody recorded. A 4-key pad is a "4-key
 * keyboard", which is true.
 *
 * SEVERAL LAYOUTS KEEP EVERY COUNT, in the "42 / 36" form the rest of the site uses,
 * rather than a range: KLOR's 36 to 42 would suggest a 37 it does not have, and the hobby
 * names its genres by exact number. "42 / 36-key" reads as a fraction, so they follow
 * the noun instead.
 */
private fun Design.headline(): String {
    val noun = when (core.body) {
        Body.Unibody -> "keyboard"
        is Body.Split -> "split keyboard"
        is Body.OneHanded -> "one-handed keyboard"
    }
    val counts = core.layouts.map { it.keys.value }.distinct().sortedDescending()
    return counts.singleOrNull()?.let { "$it-key $noun" }
        ?: "$noun with ${counts.joinToString(" / ")} keys"
}

/**
 * The search snippet for a design with no summary: one sentence, every word of it a
 * recorded fact.
 *
 * 166 OF 338 DESIGNS HAVE NO SUMMARY. Their description was the page's chips run
 * together -- "Corne: 42 keys, split. MX, Pro Micro socket, QMK." -- which is a list,
 * and in a result page a list reads as a fragment of something else.
 *
 * IN THE SITE'S VOICE, NEVER THE AUTHOR'S. A summary is a quotation from the project's
 * README, and the page prints it as one. The authors' own sentences open "The 3w6 is a
 * low profile, split ortholinear keyboard...", so a fallback opening "Corne is a..." would
 * read as their words under their name. "Corne: a 42-key split keyboard..." is a
 * catalogue entry, which is what it is.
 *
 * NOT "Specs and sources for Corne, a ...", which was the first draft and cost 21 of 155
 * characters. Measured over the 166 on 2026-10-03, it pushed the firmware out of 40
 * snippets and the wireless board out of all 31 that had one.
 *
 * NOTHING UNKNOWN IS SAID, AND NOTHING UNKNOWN IS SAID AS "NO". Every clause is dropped
 * when its fact is unrecorded rather than turned into a denial: no hotswap clause for an
 * unread board, no firmware for a design with no record. "Open-source" is not said
 * either, which the old fallback did: a design with no stated license is all rights
 * reserved, and the page says so.
 *
 * SOLDER STAYS A CHOICE WHERE THE PADS OFFER IT. [SwitchMount.MillMax] is a hole size, not
 * a hotswap PCB: every design that takes Mill-Max also takes soldered switches, and the
 * first draft's "with Mill-Max hotswap sockets" made 75 solder boards read as hotswap ones
 * -- the solder Lily58 among them. "(soldered in, or hotswap with Mill-Max sockets)" is
 * what the Hotswap spec line says, in a snippet's words.
 *
 * WIRELESS ONLY FOR [Connectivity.WirelessWithController], the one state where a
 * catalogued wireless board both fits the socket and runs a firmware the design ships.
 * "Needs a ZMK config" is true and worth its spec line, but it is not "wireless" in a
 * snippet, which has no room for the caveat.
 *
 * FITTED, NOT CUT. `describe` elides past 155 characters, mid-list. Here the least
 * searched clause goes first until the sentence fits: the firmware, then the wireless
 * board, then the controller, then the hotswap parts. "Wireless" and "hotswap" are words
 * people put in the query; "QMK" rarely is. The body, key count and switches always stay
 * -- they are what the title promised -- and only past that does `describe` cut.
 */
private fun Design.factSentence(connectivity: Connectivity?): String {
    val switchWords = core.switches.map { it.describe() }.sorted().orList()
    val opener = "${core.name.value}: ${indefinite(headline())} ${headline()} " +
        "for $switchWords switches"
    // How the switches are held, in brackets after them: it is a choice ("or"), and the
    // rest of the sentence is a list of what ships ("and"), so the two do not share one.
    val hotswap = extras.switchMounts.knownOrNull()?.let { mounts ->
        val sockets = mounts.hotswapParts().takeIf { it.isNotEmpty() }
            ?.let { "hotswap with " + it.map { part -> part.maker() }.orList() + " sockets" }
        val solder = "soldered in".takeIf { SwitchMount.Solder in mounts }
        listOfNotNull(solder, sockets).takeIf { it.isNotEmpty() }?.joinToString(", or ", " (", ")")
    }
    val controller = when (val c = core.controller) {
        is Controller.Socket -> c.footprints.describe().let { "${indefinite(it)} $it socket" }
        is Controller.Soldered -> when (val chip = c.mcu) {
            Recorded.Unknown -> "a soldered-on controller chip"
            is Recorded.Known -> "a soldered ${chip.value.describe()}"
        }
    }
    val wireless = (connectivity as? Connectivity.WirelessWithController)?.boards?.firstOrNull()
        ?.let { " (wireless with ${indefinite(it)} $it)" }
    val firmware = core.firmware.knownOrNull()?.map { it.family() }?.distinct()?.sorted()
        ?.takeIf { it.isNotEmpty() }
        ?.let { it.andList() + " firmware" }

    fun sentence(wireless: String?, firmware: String?, controller: String?, hotswap: String?): String {
        val with = listOfNotNull(controller?.plus(wireless.orEmpty()), firmware)
        return opener + hotswap.orEmpty() + (if (with.isEmpty()) "" else ", with " + with.andList()) + "."
    }
    return listOf(
        sentence(wireless, firmware, controller, hotswap),
        sentence(wireless, null, controller, hotswap),
        sentence(null, null, controller, hotswap),
        sentence(null, null, null, hotswap),
    ).firstOrNull { it.length <= 155 } ?: sentence(null, null, null, null)
}

/** "A, B and C". */
private fun List<String>.andList(): String = joined("and")

/** "A, B or C": the choices a builder has, where [andList] is everything a design ships. */
private fun List<String>.orList(): String = joined("or")

private fun List<String>.joined(conjunction: String): String =
    if (size <= 1) joinToString("") else dropLast(1).joinToString(", ") + " $conjunction " + last()

/** The maker a builder asks the shop for: "Kailh hotswap sockets". */
private fun HotswapPart.maker(): String = when (this) {
    HotswapPart.KailhSocket -> "Kailh"
    HotswapPart.GateronSocket -> "Gateron"
    HotswapPart.MillMax -> "Mill-Max"
}

/**
 * "a" or "an" by how the phrase is SAID, which for this site's phrases is mostly numbers
 * and part names: "an 8-key keyboard", "an 18-key", "an RP2040-Zero", "an nRFMicro", but
 * "a 36-key" and "a 0xCB Helios". [withArticle] goes by the first letter, which is right
 * for most board names and wrong for every one of those.
 */
internal fun indefinite(phrase: String): String {
    val number = phrase.takeWhile { it.isDigit() }.toIntOrNull()
    val an = when {
        number != null -> number == 8 || number == 11 || number == 18 ||
            number in 80..89 || number in 800..899
        // Initialisms said letter by letter, as the catalogue's boards begin: "ar-pee",
        // "en-ar-ef".
        phrase.startsWith("RP") || phrase.startsWith("nRF") -> true
        // A vowel said as a consonant: "a one-handed keyboard with 5 / 4 keys", "wun".
        phrase.startsWith("one") -> false
        else -> phrase.firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true
    }
    return if (an) "an" else "a"
}

private fun Design.electronicsChips(): List<String> = listOf(
    core.switches.map { it.describe() }.sorted().joinToString(" / "),
    // `summarise`, not `describe`: the row states what the keyboard IS, and our gaps
    // belong in the spec table. See the doc on `Controller.summarise`.
    core.controller.summarise(),
    // Family only on the row; the keyboard path is detail-page material.
    // An unrecorded firmware says nothing on the row; the spec table owns the gap.
    core.firmware.knownOrNull().orEmpty().map { it.family() }.distinct().sorted().joinToString(" / "),
).filter { it.isNotEmpty() }

// ---------------------------------------------------------------------------
// One `when` per domain type, all exhaustive, none of them anywhere else.
// ---------------------------------------------------------------------------

fun Layout.describe(): String {
    val base = when (val recorded = shorthand) {
        Recorded.Unknown -> "${keys.value} keys"
        is Recorded.Known -> "${keys.value} keys — ${recorded.value}"
    }
    // ALWAYS KEEPS THE NUMBER, per this file's own rule for `shapeChips` above --
    // "the hobby names genres by exact number", so a name never replaces the count that
    // is how a reader recognises what they are looking at. Appended, same voice as the
    // shorthand branch above it, never swapped in for it.
    return name.knownOrNull()?.let { "$base — ${it.value}" } ?: base
}

fun SwitchFootprint.describe(): String = when (this) {
    SwitchFootprint.Mx -> "MX"
    SwitchFootprint.ChocV1 -> "Choc v1"
    SwitchFootprint.ChocV2 -> "Choc v2"
    SwitchFootprint.Alps -> "Alps"
    SwitchFootprint.ChocMini -> "Kailh Mini Choc"
    SwitchFootprint.KailhX -> "Kailh X"
    is SwitchFootprint.Bespoke -> name
}

/** The family alone, for the result row. */
fun FirmwareRef.family(): String = when (this) {
    is FirmwareRef.Qmk -> "QMK"
    is FirmwareRef.Zmk -> "ZMK"
    is FirmwareRef.Kmk -> "KMK"
    is FirmwareRef.Custom -> name
}

/** Family plus the path a builder types to flash it. */
fun FirmwareRef.describe(): String = when (this) {
    is FirmwareRef.Qmk -> keyboardPath?.let { "QMK ($it)" } ?: "QMK"
    is FirmwareRef.Zmk -> shield?.let { "ZMK ($it)" } ?: "ZMK"
    is FirmwareRef.Kmk -> boardFile?.let { "KMK ($it)" } ?: "KMK"
    is FirmwareRef.Custom -> name
}

/**
 * The "USB port" row, backlog #97: which cable reaches the computer. The visible text
 * carries the whole claim -- "depends on the dev board" -- and the hint only names which
 * board gives which, the rule `SpecLine.hint`'s own comment sets.
 */
private fun UsbPortAnswer.line(): SpecLine = when (this) {
    is UsbPortAnswer.OnBoard -> SpecLine("USB port", port.describe())
    UsbPortAnswer.Daughterboard -> SpecLine("USB port", "on a separate USB daughterboard")
    UsbPortAnswer.Unrecorded -> SpecLine("USB port", NOT_RECORDED, recorded = false)
    is UsbPortAnswer.OnDevBoard -> {
        val ports = UsbPort.entries.filter { it in choices }
        if (ports.size == 1) {
            SpecLine("USB port", "${ports.single().describe()}, on the dev board")
        } else {
            SpecLine(
                "USB port",
                ports.joinToString(" or ") { it.describe() } + ", depends on the dev board",
                hint = ports.joinToString(" ") { port ->
                    port.describe().replaceFirstChar(Char::uppercase) + ": " +
                        choices.getValue(port).joinToString(", ") + "."
                },
            )
        }
    }
}

/** The name a builder shops by, not the connector spec's ("micro-B"). */
private fun UsbPort.describe(): String = when (this) {
    UsbPort.UsbC -> "USB-C"
    UsbPort.MicroB -> "micro-USB"
    UsbPort.MiniB -> "mini-USB"
}

fun Body.describe(): String = describeShape() + when (this) {
    is Body.Split -> when (val l = links) {
        // `Unknown` says nothing: it is not a thing to buy, and not the same claim as
        // "wireless" either. (A known set is never empty -- see `SeedBody.toDomain`.)
        Recorded.Unknown -> ""
        is Recorded.Known -> l.value.cables()?.let { ", $it between halves" }
            ?: l.value.bridge()?.let { ", $it" }
            ?: ""
    }
    else -> ""
}

/**
 * The cable a builder buys to join the halves, or null where the board places none.
 * Two on one board are the builder's choice, so they read "TRRS or USB-C cable". Fixed
 * order, commonest first, so the same set always reads the same way.
 */
private fun Set<SplitLink>.cables(): String? =
    listOf(SplitLink.Trrs to "TRRS", SplitLink.UsbC to "USB-C", SplitLink.Ethernet to "Ethernet",
           SplitLink.MiniDin to "Mini-DIN (S-Video)")
        .filter { it.first in this }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" or ", postfix = " cable") { it.second }

private fun Set<SplitLink>.bridge(): String? =
    if (SplitLink.Bridge in this) "joined by a rigid PCB bridge" else null

/**
 * The body in a word, for a card. Owner's call, 2026-09-26: the half count, the PCB
 * ordering note and the link cable are build detail for the design page -- on a card
 * they turned "split" into a sentence. [describe] keeps all of it for the page.
 */
fun Body.brief(): String = when (this) {
    Body.Unibody -> "unibody"
    is Body.Split -> "split"
    is Body.OneHanded -> describeShape()
}

fun Body.describeShape(): String = when (this) {
    Body.Unibody -> "unibody"
    is Body.Split -> "split, ${halves.value} halves" + when (val r = reversible) {
        Recorded.Unknown -> ""
        // Spelled out rather than shown as the word "reversible", which means
        // nothing to someone building their first keyboard. What they need to know
        // is how many different boards they have to order.
        is Recorded.Known ->
            if (r.value) " (one reversible PCB, order it once)"
            else " (left and right PCBs differ, order both)"
    }
    is Body.OneHanded -> when (hand) {
        Hand.Left -> "one-handed, left"
        Hand.Right -> "one-handed, right"
        Hand.Either -> "one-handed, either hand"
    }
}

fun Controller.describe(): String = when (this) {
    // "socket" and not a board name on purpose: the design fixes the footprint, and
    // which module drops into it is the builder's call.
    is Controller.Socket -> "${footprints.describe()} socket"
    is Controller.Soldered -> when (val chip = mcu) {
        Recorded.Unknown -> "soldered MCU, chip $NOT_RECORDED"
        is Recorded.Known -> "soldered ${chip.value.describe()}"
    }
}

/**
 * The ROW's controller, which stops where our knowledge does instead of narrating it.
 *
 * `DesignRow.stars` already states the rule this follows: the row is the scanning
 * surface and its chips are facts about the KEYBOARD, while the spec table below is
 * where this site advertises its own gaps. `describe()` broke it — `MX · soldered MCU,
 * chip not recorded · QMK` was the most repeated string on the index, 29 rows of it,
 * spending the row's one discriminating line on an admission that discriminates nothing.
 *
 * "soldered MCU" is not a softening of that admission, it is the part that is a fact:
 * the chip is on the board rather than in a socket, which is what a builder scanning the
 * list actually needs. The page still says the rest.
 */
fun Controller.summarise(): String = when (this) {
    is Controller.Socket -> "${footprints.describe()} socket"
    is Controller.Soldered -> when (val chip = mcu) {
        Recorded.Unknown -> "soldered MCU"
        is Recorded.Known -> "soldered ${chip.value.describe()}"
    }
}

fun ControllerFootprint.describe(): String = when (this) {
    ControllerFootprint.ProMicro -> "Pro Micro"
    ControllerFootprint.SeeedXiao -> "Seeed XIAO"
    ControllerFootprint.Rp2040Zero -> "RP2040-Zero"
    ControllerFootprint.RpiPico -> "Raspberry Pi Pico"
    ControllerFootprint.BlackPill -> "BlackPill"
    ControllerFootprint.BluePill -> "BluePill"
    ControllerFootprint.Feather -> "Feather"
}

/**
 * The dev board a socket is NAMED FOR, by its catalogue slug, or null.
 *
 * A footprint is not a board, and this file says so in several places: nothing records
 * which board a design's author recommends (backlog #59). But "Pro Micro socket" is the
 * Pro Micro's own pinout, and the Pro Micro has a page listing every design whose socket
 * it fits, this one included, in whichever of its three lists the firmware puts it. So
 * linking the line to that page sends a reader to the board the line already names, and
 * that page -- not the link -- says whether it runs this design.
 *
 * ONLY WHERE ONE BOARD IS THE NAMESAKE. A Seeed XIAO socket takes four catalogued boards,
 * Seeed's own among them, and none of them is "the" XIAO; a Feather socket's only
 * catalogued board is one Bluefruit variant of a whole family. Those stay plain text
 * rather than promote one board over its equals. A socket offering a choice (#99) stays
 * plain too: the line is one link or none, and two footprints are two boards.
 *
 * A soldered chip is not a dev board, and has no page.
 */
private fun Controller.namesake(): String? = when (this) {
    is Controller.Socket -> footprints.singleOrNull()?.let {
        when (it) {
            ControllerFootprint.ProMicro -> "pro-micro"
            ControllerFootprint.RpiPico -> "raspberry-pi-pico"
            ControllerFootprint.Rp2040Zero -> "rp2040-zero"
            ControllerFootprint.BluePill -> "bluepill"
            ControllerFootprint.BlackPill -> "weact-blackpill"
            ControllerFootprint.SeeedXiao, ControllerFootprint.Feather -> null
        }
    }
    is Controller.Soldered -> null
}

/**
 * "Pro Micro or Feather" -- a PCB offering a choice (#99). Enum order, not set order, so
 * one design reads the same way on every page.
 */
fun Set<ControllerFootprint>.describe(): String =
    sortedBy { it.ordinal }.joinToString(" or ") { it.describe() }

/** The vendor's own capitalisation, which is how a builder will see it on the part. */
/**
 * ONE SENTENCE PER STATE, and each names the recorded fact rather than a verdict --
 * `SwitchExclusion`'s rule ("a struck-through row is a claim about the world") applied to
 * a spec line. "Incompatible" would not be checkable by a reader who doubts us.
 *
 * The two `No` members deliberately read differently, because they are different strengths
 * of claim: a radio-less soldered chip is absolute, while a socket with no wireless option
 * is bounded by a catalogue that is incomplete by construction. The rail's single label
 * takes the weaker of the two; here there is room to say which applies.
 */
fun Connectivity.describe(): String = when (this) {
    is Connectivity.WirelessWithController -> when (boards.size) {
        1 -> "Wireless with ${boards.single().withArticle()}"
        else -> "Wireless with ${boards.first().withArticle()}, or ${boards.size - 1} other " +
            (if (boards.size == 2) "controller" else "controllers")
    }
    /*
     * THE FIT FIRST, THE GAP SECOND, and the order is the whole correction (#98). The
     * old sentence led with a denial -- "no wireless controller we know of fits this
     * socket" -- which was false on 135 pages, seven controllers over. What is true is
     * that the controllers fit and the FIRMWARE record is missing.
     *
     * "DROPS STRAIGHT IN" AND "NO REWIRING", because the first rewrite fixed the denial
     * and kept the ambiguity. Owner, 2026-09-16: *"for a newcomer this might not be clear
     * that it's 'only' a matter of creating a ZMK configuration? I mean, it's not a
     * hardware limitation."* Right -- and "we have no record of wireless support" is
     * exactly what a newcomer reads as a hardware verdict. The socket question is closed
     * and the sentence has to SAY it is closed, or the reader supplies the pessimistic
     * reading for free.
     *
     * THE FIRMWARE IS NAMED, from [Connectivity.WirelessNeedsFirmware.firmwares] rather
     * than from a literal -- "needs ZMK or KMK firmware" tells a newcomer what to go and
     * search for, where "needs firmware" tells them nothing they did not know.
     *
     * "NOBODY HAS RECORDED" RATHER THAN "YOU WILL HAVE TO WRITE ONE", and the distinction
     * is not pedantry. Membership here is completeness-dependent: backlog #89 is 26
     * keyboards whose ZMK shields are absent from this corpus, so a config may well exist
     * and be missing from our records. Telling a builder to write one we simply have not
     * looked hard enough for would be a new false sentence in place of the old one.
     */
    is Connectivity.WirelessNeedsFirmware ->
        "Wireless with ${boards.first().withArticle()} — needs a ${configLabel()}"
    is Connectivity.Unrecorded.ChipNotRecorded ->
        "Maybe — nobody has recorded which chip is on this board"
    is Connectivity.Unrecorded.RadioNotConfirmed ->
        "Maybe — this board's ${mcu.describe()} can do Bluetooth, but nothing records " +
            "whether the board wires up an antenna"
    is Connectivity.Unrecorded.NoBoardsCataloged ->
        "Maybe — we have not cataloged any ${footprints.describe()} controller yet"
    is Connectivity.Unrecorded.FirmwareWirelessUnknown ->
        "Maybe — a wireless controller fits, but we have not confirmed that " +
            "${firmware.firmwareLabel()} supports wireless"
    is Connectivity.Unrecorded.FirmwareNotRecorded ->
        "Maybe — ${boards.first().withArticle()} fits the socket, but nobody has recorded " +
            "this design's firmware"
    is Connectivity.WiredOnly.ChipHasNoRadio ->
        "Wired — the soldered ${mcu.describe()} has no radio"
    is Connectivity.WiredOnly.NoWirelessBoardFits ->
        "No wireless controller we know of fits this socket"
}

/**
 * "a nice!nano v2", "an Adafruit Feather". By the first letter, which is right for every
 * board in the catalogue; a name read letter by letter ("an RP2040") would need more.
 */
private fun String.withArticle(): String =
    (if (firstOrNull()?.lowercaseChar()?.let { it in "aeiou" } == true) "an " else "a ") + this

/**
 * The long form of [describe], or null when the line needs none.
 *
 * ONLY ONE STATE HAS ONE, and that is the test of whether this field is earning its
 * keep rather than becoming a place to put text nobody edited down. The other six
 * `Connectivity` states already say everything in a handful of words.
 *
 * WHAT IS IN HERE IS ELABORATION, NEVER THE CLAIM. The visible line already says
 * wireless-yes and config-needed; this adds the count of other boards that fit, the
 * "drops straight into" that settles the hardware question for a newcomer, and the
 * "nobody has recorded" that keeps the completeness-dependence honest (#89: 26 ZMK
 * shields missing from this corpus, any of which would move a design out of this state).
 */
fun Connectivity.hint(): String? = when (this) {
    is Connectivity.WirelessNeedsFirmware -> {
        val others = when (boards.size) {
            1 -> "It drops"
            2 -> "It and 1 other wireless controller drop"
            else -> "It and ${boards.size - 1} other wireless controllers drop"
        }
        "$others straight into this socket, so the hardware swap is all the board needs. " +
            "Going wireless also needs ${firmwares.firmwareRoute()}, and nobody has " +
            "recorded one for this design — it may exist and simply not be in our records."
    }
    else -> null
}

/**
 * "ZMK config", from the catalogue. The FIRST firmware, which is the most widely
 * supported among the fitting boards rather than the alphabetically luckiest -- see
 * `Connectivity.connectivityFor`. The hint names them all; the line names the one a
 * reader should try.
 */
private fun Connectivity.WirelessNeedsFirmware.configLabel(): String =
    firmwares.firstOrNull()?.let { "${it.firmwareLabel()} config" } ?: "firmware config"

fun Mcu.describe(): String = when (this) {
    Mcu.Atmega16u2 -> "ATmega16U2"
    Mcu.Atmega328p -> "ATmega328P"
    Mcu.Atmega32a -> "ATmega32A"
    Mcu.Atmega32u2 -> "ATmega32U2"
    Mcu.Atmega32u4 -> "ATmega32U4"
    Mcu.Rp2040 -> "RP2040"
    Mcu.Nrf52840 -> "nRF52840"
    Mcu.Stm32f042 -> "STM32F042"
    Mcu.Stm32f072 -> "STM32F072"
    Mcu.Stm32f103 -> "STM32F103"
    Mcu.Stm32f303 -> "STM32F303"
    Mcu.Stm32f401 -> "STM32F401"
    Mcu.Stm32f411 -> "STM32F411"
    Mcu.Stm32g0b1 -> "STM32G0B1"
    Mcu.Stm32g431 -> "STM32G431"
}

fun BuildMethod.describe(): String = when (this) {
    is BuildMethod.Pcb -> "PCB"
    BuildMethod.Handwire -> "hand-wired"
}

/**
 * Three outcomes, and the third is the one worth the extra state (ux.md §5.3): a
 * hand-wire has no board to solder components onto, so "not recorded" would be a
 * lie and "none" would read as a documented finding.
 */
fun BuildMethod.solderingLine(): SpecLine = when (this) {
    BuildMethod.Handwire -> SpecLine("Soldering", "n/a — no board to solder", recorded = true)
    is BuildMethod.Pcb -> when (val hardest = hardestComponent) {
        Recorded.Unknown -> SpecLine("Soldering", NOT_RECORDED, recorded = false)
        is Recorded.Known -> SpecLine(
            "Soldering",
            "${hardest.value.tier.describe()} (${
                hardest.value.drivenBy.sortedBy { it.ordinal }.joinToString(", ") { it.describe() }
            })",
        )
    }
}

fun SolderingDifficulty.describe(): String = when (this) {
    SolderingDifficulty.ThroughHole -> "through-hole"
    SolderingDifficulty.SmdPassive -> "SMD passives"
    SolderingDifficulty.SmdHand -> "SMD, hand-solderable"
    SolderingDifficulty.SmdFine -> "SMD, fine-pitch"
}

fun ComponentKind.describe(): String = when (this) {
    ComponentKind.Mcu -> "MCU"
    ComponentKind.Usb -> "USB"
    ComponentKind.Led -> "LED"
    ComponentKind.Diode -> "diode"
    ComponentKind.Passive -> "passive"
    ComponentKind.Ic -> "IC"
    ComponentKind.Connector -> "connector"
    ComponentKind.Encoder -> "rotary encoder"
    ComponentKind.Other -> "other"
}

fun KeyWiring.describe(perHalf: Boolean = false): String {
    val half = if (perHalf) " per half" else ""
    return when (this) {
        is KeyWiring.DirectPin -> "${pins.value} direct pins$half"
        is KeyWiring.Matrix -> "${rows.value}×${cols.value} matrix$half, ${diodes.describe()} diodes"
        KeyWiring.Analog -> "analog (Hall-effect, optical or capacitive)"
    }
}

fun DiodeDirection.describe(): String = when (this) {
    DiodeDirection.ColToRow -> "column-to-row"
    DiodeDirection.RowToCol -> "row-to-column"
}

/**
 * `Unstated` is spelled out rather than left blank. architecture.md §1.9: no licence
 * stated means all rights reserved, and that is the fact a builder needs -- so it is
 * a value, and `recorded` stays true for it.
 */
fun LicenseState.describe(): String = when (this) {
    LicenseState.Unstated -> "all rights reserved — no license stated"
    is LicenseState.Stated -> licenses.map { it.describe() }.sorted().joinToString(", ")
}

fun License.describe(): String = when (this) {
    License.CernOhlS2 -> "CERN-OHL-S 2.0"
    License.CernOhlW2 -> "CERN-OHL-W 2.0"
    License.CernOhlP2 -> "CERN-OHL-P 2.0"
    License.CcBySa4 -> "CC BY-SA 4.0"
    License.CcBy4 -> "CC BY 4.0"
    License.Cc01 -> "CC0 1.0"
    License.Mit -> "MIT"
    License.Apache2 -> "Apache-2.0"
    License.Gpl3OrLater -> "GPL-3.0-or-later"
    License.Gpl2Only -> "GPL-2.0-only"
    License.Solderpad21 -> "Solderpad 2.1"
    License.TaprOhl1 -> "TAPR OHL 1.0"
    is License.Other -> spdxOrName
}

// ---------------------------------------------------------------------------
// Door B (`ux.md` §2.9, ticket 16, backlog #58). PARTIAL ON PURPOSE: only the
// SWITCHES section ships here. CONTROLLER waits on `seed/controllers.toml`
// (backlog #58's other half); KEYCAPS is unbuilt. `noindex` on the route below
// reflects that this page does not yet say what ticket 16 decided it should.
// ---------------------------------------------------------------------------

fun SwitchSensing.describe(): String = when (this) {
    SwitchSensing.Mechanical -> "mechanical contact"
    SwitchSensing.Hall -> "Hall effect (magnetic)"
    SwitchSensing.Optical -> "optical"
    SwitchSensing.Capacitive -> "capacitive"
}

/** Ticket 16 decision 3 rule 1: a struck-through row is a claim about the world, so it
 *  states the recorded fact that excluded it, never just "no". */
fun SwitchExclusion.describe(): String = when (this) {
    is SwitchExclusion.FootprintMismatch ->
        "this design's own footprint is " +
            designFootprints.map { it.describe() }.sorted().joinToString(", ") + ", not this"
    is SwitchExclusion.SensingMismatch ->
        "this board senses a keypress via ${designSensing.describe()}, not mechanical contact"
}

data class SwitchRow(val footprint: String, val fits: Boolean, val exclusion: String?)

/**
 * `wireless` is inline rather than a second column: the CONTROLLER section is a plain
 * list, not a table, and ux.md's own mock reads it as one fact per row ("nice!nano v2 —
 * wireless"). `slug` links each row to its own `/parts/dev-board/:slug` page -- owner-
 * raised 2026-09-03: a bare name teaches a newcomer nothing, and ux.md's own answer is
 * a real page per board, not a longer list or an external link off-site.
 */
data class ControllerRow(val name: String, val slug: String, val wireless: Boolean)

/**
 * `url` rather than an internal link, deliberately: `ux.md`'s sitemap names
 * `/parts/keycap-profile/:slug` but `PartsController` wires only `dev-board`, and linking a
 * route that does not exist is worse than linking the profile's own source. The catalogue
 * keeps the slug for the day that route ships.
 */
data class KeycapRow(val name: String, val url: String?)

/**
 * Flattened to a nullable message plus a (possibly empty) list, not the sealed
 * `SwitchesSection` itself — this file's own rule is that every `when` over a domain type
 * lives here, not in a `.kte` file. `switchRows` is never null (`List<SwitchRow>`, same
 * shape `design.sources` already uses) so the template needs no smart-cast dance, only
 * `@if(switchesNote != null)`, same as `design.summary`.
 *
 * `switchesNote != null` implies `switchRows.isEmpty()`, per ticket 16 decision 3
 * rule 2: a section that either shows rows or shows one sentence, never both. Same
 * pattern for `controllerMessage`/the controller lists, one level up: a soldered MCU and
 * a socketed footprint with nothing catalogued are two different true sentences, never a
 * row list pretending to answer either.
 *
 * `controllerWireless` is shown open, `controllerWired` behind a disclosure --
 * `pro_micro` alone has 26 catalogue rows, and a flat dump of all of them defeats a site
 * whose whole other half is about narrowing choices. This is NOT ux.md §2.9's own
 * mechanism (a corroboration count ranks the mock's shown rows: "14 other designs use
 * this"); nothing today records which specific board a design's own author recommends,
 * so ranking by that would be inventing a number. Splitting on `wireless` uses a fact we
 * actually have and is honest about being a narrower fix than the mock's — filed as its
 * own backlog item rather than faked here.
 */
data class CompatiblePage(
    val name: String,
    val slug: String,
    val controllerMessage: String?,
    val controllerWireless: List<ControllerRow>,
    /** Wireless boards that fit the socket but run none of this design's firmware (#98).
     *  Kept apart from [controllerWireless] because the 2026-09-03 ruling stands: these
     *  are not options a builder can buy and flash, and a merged list would say they are. */
    val controllerNeedsFirmware: List<ControllerRow>,
    /** The caveat, in the caption. Null exactly when [controllerNeedsFirmware] is empty. */
    val controllerNeedsFirmwareSummary: String?,
    val controllerWired: List<ControllerRow>,
    /** Null exactly when `controllerWired` is empty. Precomputed so the template never
     *  has to decide between "N wired options" and "N more wired options" itself --
     *  "more" is only true when something was already shown above it. */
    val controllerWiredSummary: String?,
    val switchesNote: String?,
    val switchRows: List<SwitchRow>,
    /** Same flattening as the two sections above, and the same rule: rows or one sentence,
     *  never both (ticket 16 decision 3 rule 2). `keycapMessage != null` implies
     *  `keycapRows.isEmpty()`, and the section has six distinct sentences because the
     *  reasons a keycap answer is unavailable are six different facts. */
    val keycapMessage: String?,
    val keycapRows: List<KeycapRow>,
)

/**
 * Ticket 16 decision 4's CONTROLLER section: a set intersection on footprint IDs,
 * against the catalogue `ControllerCatalog` reads from `seed/controllers.toml`.
 *
 * A soldered MCU states the fact this ticket's own §5 named as the honest one — no
 * controller to choose, not "unknown". A socketed footprint gets a message instead of
 * rows in TWO distinct cases, kept separate rather than folded together because they
 * are different facts: nothing catalogued for the footprint at all
 * (`ControllerFootprint.Rp2040Zero` today), or the catalogue has rows for the footprint
 * but none of them run this design's firmware.
 *
 * NO CORPUS DESIGN IS IN THAT SECOND STATE TODAY, and it used to be 16 of them. Until
 * 2026-09-04 this comment cited the Seeed XIAO variants and the Raspberry Pi Pico as
 * ZMK-only, which was read off QMK's `development_board` enum -- the wrong question. QMK
 * documents RP2040 as a supported microcontroller and ships `BOARD = GENERIC_RP_RP2040`
 * for boards that declare their own pins, so a converter is a pin-NAMING convenience and
 * not the gate on whether QMK runs. Correcting the three RP2040 rows emptied this state
 * (`seed/controllers.toml`'s header carries the sources). It is kept, and covered only by
 * a fixture test, because the shape recurs the moment a ZMK-only board is catalogued for a
 * footprint some QMK-only design sockets.
 *
 * FOOTPRINT ALONE IS NOT COMPATIBILITY, and treating it as such was a real bug
 * (owner-caught, 2026-09-03): `BlueMicro840 v1` (ZMK-only) was shown as fitting a
 * QMK-only design because both share the `pro_micro` footprint. The firmware check is what
 * refuses that, and the fact behind it is at the CHIP level rather than the board level:
 * `docs.qmk.fm/compatible_microcontrollers` lists AVR, STM32, RP2040, Kinetis, WB32,
 * AT32 and GD32, and **no nRF52 and no Bluetooth at all** -- so no nRF52840 board can run
 * QMK however it is wired. Conversely ZMK cannot run on AVR, because Zephyr supports no
 * 8-bit platform.
 *
 * **THAT RULING AND #98 BOTH HOLD, AND [needsFirmware] IS HOW.** Owner, 2026-09-16, on
 * `/designs/rhymestone/compatible`: *"nice!nano v2 isn't even listed"*. Both complaints
 * are correct about the same seven boards, and they only look contradictory because
 * "fits" was doing two jobs. A nice!nano **fits the socket** -- that is what a footprint
 * is -- and it **will not run this design's firmware**. The 2026-09-03 bug was showing it
 * as a plain option, indistinguishable from a board you can buy and flash today; #98's bug
 * was the opposite, denying it exists. So it is listed, and never in the same list: its
 * own group, with the caveat in the caption rather than in small print under it.
 *
 * A DESIGN PAGE AND THIS PAGE MUST NOT DISAGREE, which is what made this a bug rather than
 * a gap. `/designs/rhymestone` already said *"A nice!nano v2 or 6 other wireless
 * controllers fit this socket"* while this page said *"19 wired options"* and named none
 * of them -- one corpus, one socket, two answers. [Connectivity.WirelessNeedsFirmware] is
 * the same fact for the same reason, and `CompatiblePageTest` pins the two together.
 *
 * WIRELESS ONLY, NOT EVERY BOARD THAT CANNOT RUN THE FIRMWARE. A wired board that fails
 * the firmware check is a board a builder has no reason to want: swapping to it buys
 * nothing and it would re-break the 2026-09-03 ruling for no gain. What makes these seven
 * worth listing is that the swap buys something a reader came looking for.
 */
private fun Design.controllerFacts(catalog: ControllerCatalog, rules: FirmwareRules): ControllerSection =
    when (val controller = core.controller) {
        is Controller.Soldered -> ControllerSection(
            when (val chip = controller.mcu) {
                is Recorded.Known -> "Soldered onto this board: ${chip.value.describe()}, so there's no controller to choose."
                Recorded.Unknown -> "The MCU is soldered onto this board (which chip is not recorded), so there's no controller to choose."
            },
        )
        is Controller.Socket -> {
            val cataloged = controller.footprints.sortedBy { it.ordinal }.flatMap { catalog.fitting(it) }
            // No firmware record (#106): which boards RUN it is unknown, so the section says
            // that rather than "none of them run it".
            val myFirmware = core.firmware.knownOrNull()?.map { it.familyKey() }?.toSet()
                ?: return ControllerSection(
                    "This board sockets a ${controller.footprints.describe()} controller. Its firmware isn't " +
                        "recorded, so we can't say which of the ${cataloged.size} we've cataloged run it.",
                )
            val (runs, cannotRun) = cataloged.partition { board ->
                board.firmware.any { it in myFirmware }
            }
            val rows = { boards: List<ControllerBoard> -> boards.map { ControllerRow(it.name, it.slug, it.wireless) } }
            // Fits the socket, cannot run what this design ships, and wireless -- so the
            // swap is worth a reader's attention. #98.
            val wirelessMisfits = cannotRun.filter { it.wireless }
            val needsFirmware = rows(wirelessMisfits)
            // The route out, named from the catalogue. `Known(true)` only -- `bluemicro`'s
            // support is `unknown` and an unknown is not a route to send a newcomer down.
            // Same ordering rule as `Connectivity.connectivityFor`, and it must stay the
            // same: the two surfaces name one route or they disagree again.
            val wirelessFirmwares = wirelessMisfits.flatMap { it.firmware }
                .filter { rules.wireless(it).knownOrNull() == true }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key })
                .map { it.key }

            when {
                cataloged.isEmpty() -> ControllerSection(
                    "This board sockets a ${controller.footprints.describe()} controller, but we don't have " +
                        "any of those cataloged yet.",
                )
                /*
                 * NOTHING RUNS *AND* NOTHING WOULD, which is the only case left that is a
                 * flat "no". Before #98 this branch also swallowed designs with wireless
                 * boards fitting their socket and told them nothing was catalogued for
                 * them -- the same denial the design page was making.
                 */
                runs.isEmpty() && needsFirmware.isEmpty() -> ControllerSection(
                    "This board sockets a ${controller.footprints.describe()} controller, but none of the " +
                        "${cataloged.size} we've cataloged run ${myFirmware.joinToString(" or ") { it.uppercase() }}.",
                )
                else -> {
                    val (wireless, wired) = rows(runs).partition { it.wireless }
                    ControllerSection(null, wireless, needsFirmware, wired, wirelessFirmwares)
                }
            }
        }
    }

/**
 * The CONTROLLER section, flattened the way [CompatiblePage] wants it: a message XOR rows,
 * and the rows in three groups that never merge.
 *
 * A NAMED TYPE RATHER THAN THE `Triple` THIS WAS. #98 added a third list and a
 * `Quadruple` does not exist for a good reason -- at three positional lists, `first`
 * and `second` stop meaning anything at the call site, and the two that must never be
 * confused (boards that RUN this firmware, boards that merely FIT) would have been
 * adjacent anonymous `List<ControllerRow>`s.
 */
private data class ControllerSection(
    val message: String?,
    val wireless: List<ControllerRow> = emptyList(),
    val needsFirmware: List<ControllerRow> = emptyList(),
    val wired: List<ControllerRow> = emptyList(),
    /** Mirrors [Connectivity.WirelessNeedsFirmware.firmwares] -- the two surfaces name the
     *  same route or they are back to disagreeing. `ControllerSurfacesAgreeTest` pins it. */
    val wirelessFirmwares: List<String> = emptyList(),
)

/**
 * Ticket 16's KEYCAPS section (backlog #61): a set intersection on `(stem, cap footprint)`
 * against `KeycapCatalog`, which reads `seed/keycaps.toml` off
 * `docs/research/keycap-profiles.md`.
 *
 * TWO INDEPENDENT AXES, and getting that wrong is the whole risk here.
 * `architecture.md` §1.5 computes keycap compatibility from `(mounts, pitch)` over the stem
 * and the largest cap footprint, never from the switch alone -- and the research doc's §2.3
 * measured what "switch alone" would cost: 23 corpus designs take Choc switches at 19.05 mm
 * spacing, where choc-spaced sets physically leave gaps and Choc-stem caps cut for MX
 * spacing are the answer. This is the same shape as the CONTROLLER section's own bug
 * (footprint alone is not compatibility) arriving one section later.
 *
 * SPACING IS CHECKED BEFORE THE SWITCH DEPENDENCY, which inverts `ux.md` §2.9's emphasis on
 * purpose. Without a measured pitch there is no keycap answer for *any* switch a builder
 * could pick, so that sentence is unconditional and complete, where "pick a switch above"
 * would promise an answer resolving the switch cannot give. It is also why the dependent
 * state is 108-and-26 rather than 44: the 18 multi-switch designs with no measured pitch get
 * the stronger sentence.
 *
 * THE DEPENDENCY IS ON STEMS, NOT SWITCH KINDS. Choc v2 has an MX cross stem, so a board
 * taking MX *and* Choc v2 has one keycap answer and must resolve rather than ask. No corpus
 * design is that shape today, so this changes no page now -- it stops one from asking a
 * question with a single answer the day one is added.
 */
private fun Design.keycapFacts(catalog: KeycapCatalog): Pair<String?, List<KeycapRow>> {
    val fits = keycapFits()
        ?: return "Nobody has measured this board's key spacing yet, so we can't say which keycaps fit." to emptyList()
    if (fits.isEmpty()) {
        // Mini Choc has no stem only because no source states one (backlog #109).
        if (SwitchFootprint.ChocMini in core.switches) {
            return "Kailh doesn't say which keycaps fit its Mini Choc, and we haven't found a source that does." to emptyList()
        }
        if (SwitchFootprint.KailhX in core.switches) {
            return "We haven't found a source that says which keycaps fit the Kailh X." to emptyList()
        }
        // Every switch it lists is an open per-design declaration, so the stem is the
        // designer's to state and this build has no field to read it from.
        return "This board's switch is a one-off, so which keycaps fit is the designer's to state." to emptyList()
    }
    val stems = fits.map { it.stem }.toSet()
    if (stems.size > 1) {
        val switches = core.switches.sortedBy { it.describe() }.joinToString(" or ") { it.describe() }
        return "Which keycaps fit depends on which switch you fit \u2014 this board takes $switches." to emptyList()
    }
    val fit = fits.single()
    // THE MARKET SENTENCE COMES BEFORE THE CATALOGUE LOOKUP, and stays even if a row for
    // this quadrant is ever added: for an MX cross stem at 19.05 mm the answer is every MX
    // keycap set ever made, and a list of five would read as a restriction that does not
    // exist. `seed/keycaps.toml` says the same in its own header.
    if (fit.stem == KeycapStem.MxCross && fit.maxFootprint == CapFootprint.Full) {
        return "Any MX keycap set fits \u2014 an MX-stem cap on 19.05 mm spacing." to emptyList()
    }
    val rows = catalog.fitting(fit).map { KeycapRow(it.name, it.url) }
    if (rows.isNotEmpty()) return null to rows
    // Empty, and the reason decides the sentence. `Unusual` is checked first because it is a
    // statement about the BOARD (no commercial cap size matches its spacing) rather than
    // about our coverage, and `CapFootprint`'s own doc comment already rules on it.
    val footprint = fit.maxFootprint
    return when {
        footprint is CapFootprint.Unusual ->
            "This board's keys are ${footprint.spacing} apart, which matches no commercial cap size " +
                "\u2014 which keycaps fit is the designer's to state."
        fit.stem == KeycapStem.MxCross ->
            "MX-stem caps fit this board's switches, but at 18 \u00d7 17 mm spacing only " +
                "reduced-footprint ones clear their neighbours, and we haven't cataloged any yet."
        else ->
            "We haven't cataloged any keycaps for ${fit.stem.describe()} switches yet."
    } to emptyList()
}

/** The stem as a reader knows it, which is by the switch rather than by the mount: nobody
 *  shops for "an MX cross stem", they shop for caps that fit their Choc v1 board. */
fun KeycapStem.describe(): String = when (this) {
    KeycapStem.MxCross -> "MX-stem"
    KeycapStem.ChocV1 -> "Choc v1"
    KeycapStem.Alps -> "Alps"
}

/** A hotswap part as the page and the rail say it. Exhaustive, so a new part has to
 *  choose its word. */
private fun HotswapPart.word(): String = when (this) {
    HotswapPart.KailhSocket -> "Kailh socket"
    HotswapPart.GateronSocket -> "Gateron socket"
    HotswapPart.MillMax -> "Mill-Max"
}

private fun wiredSummary(shownAbove: Boolean, wired: List<ControllerRow>): String? {
    if (wired.isEmpty()) return null
    val noun = if (wired.size == 1) "wired option" else "wired options"
    // "more" counts ANY group above it since #98, not just the wireless one -- with seven
    // needs-firmware rows on screen and no wireless ones, "19 wired options" read as
    // though nothing preceded it.
    return if (shownAbove) "${wired.size} more $noun" else "${wired.size} $noun"
}

/**
 * THE CAVEAT GOES IN THE CAPTION, which is the whole reason this string is precomputed
 * rather than left to the template. These rows sit behind a disclosure, so the summary
 * line is the only text a reader is guaranteed to see -- put "fits the socket" there
 * without "needs firmware", and the disclosure becomes the 2026-09-03 bug with extra
 * steps.
 *
 * WORDED AS THE DESIGN PAGE WORDS IT (`Connectivity.describe`): we have no RECORD, not
 * "there is no support". Membership is completeness-dependent -- backlog #89 has 26
 * keyboards whose ZMK shields are missing from this corpus, and importing one moves its
 * design out of this group entirely.
 */
private fun needsFirmwareSummary(rows: List<ControllerRow>, firmwares: List<String>): String? {
    if (rows.isEmpty()) return null
    val noun = if (rows.size == 1) "wireless option" else "wireless options"
    val verb = if (rows.size == 1) "drops" else "drop"
    return "${rows.size} $noun — $verb straight into the socket, but ${rows.size.let {
        if (it == 1) "needs" else "need"
    }} ${firmwares.firmwareRoute()} nobody has recorded for this design"
}

/**
 * "ZMK or KMK", from the catalogue. The fallback matters: with no positively-recorded
 * wireless firmware among the fitting boards there is nothing to name, and inventing
 * "ZMK" there would be the hardcoding `FirmwareRules` exists to have removed.
 */
internal fun List<String>.firmwareRoute(): String = when (size) {
    0 -> "wireless firmware"
    1 -> "${single().firmwareLabel()} firmware"
    else -> dropLast(1).joinToString(", ") { it.firmwareLabel() } +
        " or ${last().firmwareLabel()} firmware"
}

fun Design.toCompatiblePage(
    catalog: ControllerCatalog,
    keycaps: KeycapCatalog,
    rules: FirmwareRules,
): CompatiblePage {
    val controller = controllerFacts(catalog, rules)
    val (keycapMessage, keycapRows) = keycapFacts(keycaps)
    val (unavailable, rows) = when (val section = switchesSection()) {
        // Ticket 16 decision 3 rule 2, the exact sentence it specifies.
        is SwitchesSection.SensingUnknown ->
            "These compare footprints only: we haven't recorded how this board senses a keypress." to section.fits.map { fit ->
                SwitchRow(footprint = fit.footprint.describe(), fits = fit.fits, exclusion = fit.exclusion?.describe())
            }
        is SwitchesSection.Rows -> null to section.fits.map { fit ->
            SwitchRow(footprint = fit.footprint.describe(), fits = fit.fits, exclusion = fit.exclusion?.describe())
        }
    }
    return CompatiblePage(
        core.name.value, core.slug.value,
        controller.message,
        controller.wireless,
        controller.needsFirmware,
        needsFirmwareSummary(controller.needsFirmware, controller.wirelessFirmwares),
        controller.wired,
        wiredSummary(
            shownAbove = controller.wireless.isNotEmpty() || controller.needsFirmware.isNotEmpty(),
            wired = controller.wired,
        ),
        unavailable, rows,
        keycapMessage, keycapRows,
    )
}
