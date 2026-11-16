package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.facet.ControllerFootprintFacet
import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFit
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.domain.Facets
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.fitFor
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.persistence.DesignQueries

/**
 * `/parts/dev-board/:slug` (`ux.md`'s sitemap, §9 build order item 6). Owner-raised
 * 2026-09-03: Door B's CONTROLLER list shows 20+ bare board names, which teaches a
 * newcomer nothing -- ux.md's own answer is a real page per board, not a longer list
 * or an external link off-site.
 *
 * DELIBERATELY NOT "used by N designs" (ux.md's own worked example for
 * `nice-nano-v2`). Nothing in the corpus records which SPECIFIC board a socketed
 * design's author recommends -- backlog #59's own finding -- only which FOOTPRINT a
 * design sockets. `$designCount designs socket a Pro Micro-shaped controller` is the
 * honest claim this catalogue can make today; overstating it to "use this board" would
 * be exactly the wrong-value-that-looks-right failure this project exists to avoid.
 */
data class DevBoardPage(
    val name: String,
    val slug: String,
    val footprintLabel: String,
    val wireless: Boolean,
    /** "nRF52", or null for the five boards whose chip the research doc never names.
     *  Null prints nothing rather than "not recorded": this line is a fact about the
     *  PART, and the page's gap-advertising belongs on the design pages that own the
     *  corpus's own holes. */
    val mcuFamilyLabel: String?,
    val firmware: List<String>,
    val url: String?,
    val designCount: Int,
    val designsHref: String,
    /** The 1:1 fits: this board runs a firmware the design ships, and no recorded chip
     *  contradicts it. */
    val runs: FitTier,
    /** Same socket, no firmware in common, and nothing documented forbids the pairing. */
    val socketOnly: FitTier,
    /** Same socket, and a firmware's own documentation rules the chip out. */
    val ruledOut: FitTier,
)

/**
 * One tier: the rows the page draws, and how many designs are in the tier altogether.
 *
 * CAPPED BECAUSE THE ROWS ARE HEAVY AND THE TAIL IS NOT THE ANSWER. Measured 2026-09-10
 * on the real corpus: a `DesignRow` costs ~2.7 kB of inline silhouette SVG, `/designs`
 * page 1 draws 50 of them for 187 kB, and an uncapped nice!nano page drew all 153 for
 * **556 kB** -- three times this site's own norm, most of it a list of boards the reader
 * has just been told they cannot build.
 *
 * So the caps are asymmetric and [total] is always stated, which is the honest half: a
 * reader learns that 118 designs are ruled out and sees a dozen examples, rather than
 * scrolling 118 struck-through rows or being shown a truncated list pretending to be
 * whole. Same principle as Door B's own wired-controller disclosure -- "a flat dump
 * defeats a site whose whole other half is about narrowing choices" -- reached here by
 * measurement rather than by taste.
 */
data class FitTier(val shown: List<DevBoardDesign>, val total: Int) {
    /** How many the page is not drawing. Zero when the tier fits under its cap. */
    val hidden: Int get() = total - shown.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * One design as this page lists it: the row every other list on the site uses, plus why
 * it is in the tier it is in.
 *
 * THE ROW, NOT THE 3D RENDER. `DesignRow` carries the inline-SVG silhouette; the
 * `.kicad_pcb` render from backlog #60 stays on `/designs/:slug`, where one page loads
 * one image. A nice!nano lists 153 designs, and 153 poster PNGs at 1400x900 is a
 * multi-megabyte page for a reader who wanted a list -- the silhouette is the same
 * scanning surface at a fraction of the weight, and it is what `/designs` already shows.
 *
 * `note` is null exactly on the [ControllerFit.Runs] rows whose fit needs no explanation.
 * Everywhere else it states the recorded fact that put the row where it is, which is
 * `SwitchExclusion`'s rule ("a struck-through row is a claim about the world") applied to
 * the tier below it as well as the tier that is struck through.
 */
data class DevBoardDesign(val row: DesignRow, val note: String?)

fun ControllerBoard.toDevBoardPage(
    queries: DesignQueries,
    catalog: DesignCatalog,
    silhouettes: Silhouettes,
    activity: RepoActivity,
    rules: FirmwareRules,
    hearts: net.keyboardbuilders.keyboardbuilders.persistence.Hearts? = null,
): DevBoardPage {
    val facetValue = ControllerFootprintFacet.token(footprint)
    val designCount = queries.slugsMatching(
        Facets.parse(mapOf(FacetKey.ControllerFootprint.param to listOf(facetValue)))
    )?.size ?: queries.total()

    // RANKED BY THE SAME ORDER THE REST OF THE SITE USES, not by `catalog.all()`'s
    // alphabetical order. A reader landing here wants the boards people actually build
    // first, and `Sort.DEFAULT` is already that ranking -- reusing it means this page
    // cannot disagree with `/designs` about which board is more prominent.
    val rank = queries.allRanked().withIndex().associate { (at, slug) -> slug to at }

    val fitting = catalog.all()
        .mapNotNull { design ->
            design.fitFor(footprint, firmware, mcuFamily, rules)?.let { design to it }
        }
    val counts = hearts?.forDesigns(fitting.map { it.first }).orEmpty()
    val tiers = fitting
        .sortedBy { (design, _) -> rank[design.core.slug.value] ?: Int.MAX_VALUE }
        .groupBy({ (_, fit) -> fit.tier() }) { (design, fit) ->
            DevBoardDesign(design.toRow(silhouettes, activity, hearts = counts[design.core.slug.value]), fit.note(name))
        }

    return DevBoardPage(
        name = name,
        slug = slug,
        footprintLabel = footprint.describe(),
        wireless = wireless,
        mcuFamilyLabel = mcuFamily.knownOrNull()?.describe(),
        firmware = firmware.sorted().map { it.firmwareLabel() },
        url = url,
        designCount = designCount,
        designsHref = "/designs?${FacetKey.ControllerFootprint.param}=$facetValue",
        // THE CAPS, AND WHY THEY DIFFER. `runs` is the answer to the question the page
        // was opened with, so it gets `/designs`' own page size -- a reader scanning for
        // a keyboard to build is doing the same thing there and 50 rows is what this
        // site already considers a screenful. The other two are context: their COUNT is
        // the fact worth having and a dozen examples is enough to make it concrete.
        runs = tiers[Tier.Runs].orEmpty().tier(shown = 50),
        socketOnly = tiers[Tier.SocketOnly].orEmpty().tier(shown = 12),
        ruledOut = tiers[Tier.RuledOut].orEmpty().tier(shown = 12),
    )
}

/** Already ranked by `toDevBoardPage`, so `take` keeps the most-starred -- the examples
 *  a reader is most likely to recognise, which is what makes a capped list useful rather
 *  than arbitrary. */
private fun List<DevBoardDesign>.tier(shown: Int) = FitTier(take(shown), size)

/** Which of the page's three lists a fit belongs in. `NeedsPort` joins `SocketOnly`
 *  rather than earning a fourth list: both are "the socket is right, the firmware is not
 *  ready" and they differ only in the sentence on the row, which is exactly what
 *  [DevBoardDesign.note] is for. Four lists would be a taxonomy; three is the ruling. */
private enum class Tier { Runs, SocketOnly, RuledOut }

private fun ControllerFit.tier(): Tier = when (this) {
    is ControllerFit.Runs -> Tier.Runs
    is ControllerFit.NeedsPort, is ControllerFit.SocketOnly -> Tier.SocketOnly
    is ControllerFit.RuledOut -> Tier.RuledOut
}

/**
 * The sentence on the row. Every `when` over a domain type lives in the web layer, which
 * is `DesignPresentation.kt`'s own rule and the reason `ControllerFit` carries data rather
 * than prose.
 *
 * NAMES THE RULE, NOT JUST THE VERDICT. "nRF52 chips cannot run QMK" is checkable by a
 * reader who doubts us; "incompatible" is not, and a reader who cannot check us has to
 * trust us -- which is the position this site exists to get people out of.
 */
internal fun ControllerFit.note(board: String): String? = when (this) {
    is ControllerFit.Runs -> null
    is ControllerFit.NeedsPort ->
        "fits the socket, but this design's QMK firmware is built for " +
            "${designMcu.describe()} — you would have to port it to ${boardFamily.describe()}"
    is ControllerFit.SocketOnly ->
        "fits the socket; this design ships " +
            designFamilies.sorted().joinToString(" and ") { it.firmwareLabel() } +
            ", which $board is not recorded as running"
    is ControllerFit.RuledOut ->
        // Names the rule, not the verdict. "nRF52 chips cannot run QMK" is checkable by a
        // reader who doubts us; "incompatible" is not.
        firmwares.sorted().joinToString("; ") { fw ->
            "${family.describe()} chips cannot run ${fw.firmwareLabel()}"
        }
}

/** The family as a builder writes it, not as the enum spells it. */
fun McuFamily.describe(): String = when (this) {
    McuFamily.Avr8 -> "8-bit AVR"
    McuFamily.Rp2040 -> "RP2040"
    McuFamily.Nrf52 -> "nRF52"
    McuFamily.Stm32 -> "STM32"
    McuFamily.Samd21 -> "SAMD21"
}

/** Matches `ControllerBoard.firmware`'s own lexicon. Falls back to capitalising an
 *  unrecognised family (today: only ever "bluemicro") rather than refusing to render
 *  it -- a real, sourced fact should never disappear because it isn't one of the three
 *  families this build names specially. */
internal fun String.firmwareLabel(): String = when (this) {
    "qmk" -> "QMK"
    "zmk" -> "ZMK"
    "kmk" -> "KMK"
    else -> replaceFirstChar { it.uppercase() }
}

/**
 * SEO front door, ux.md's own phrase for this route -- indexable, unlike Door B's
 * `/compatible` page, because this page is complete for what it claims rather than
 * missing two of three decided sections.
 *
 * TITLED BY THE QUESTION, NOT THE PART, since 2026-10-03. "nice!nano v2 — dev board"
 * named the thing and stopped; the reader holding one types "keyboards for nice!nano" or
 * "what can I build with a nice nano", and the page's first list is exactly that answer.
 *
 * THE PROMISE FOLLOWS THE LIST. "Keyboards you can build with" only where [DevBoardPage.runs]
 * has a design in it -- the page's own heading for that list says the same words. A
 * board that runs nothing the designs it fits ship still has those designs, and the page
 * lists them as "This board fits the socket these N designs take"; the title says it the
 * same way round, "Keyboards with a socket for a MechWild PillBug". The board fits the
 * keyboard, so "Keyboards that fit a ..." had it backwards. On 2026-10-03 that title was
 * the MechWild PillBug's and the two Seeed XIAOs'; the BlueMicro840 v1 runs ZMK and is
 * a "you can build" page. A board with not even a socket match keeps the plain part
 * title rather than promise an empty page.
 *
 * The count in the description is that list's total, the number the page itself prints
 * under the heading, so a snippet and the page it leads to cannot disagree. It counts
 * what nothing RULES OUT (see [PartsIndexBoard]), which is what "you can build" says on
 * the page too.
 */
fun partHead(site: SiteUrl, page: DevBoardPage): Head = Head(
    title = when {
        !page.runs.isEmpty -> "Keyboards you can build with ${indefinite(page.name)} ${page.name}"
        !page.socketOnly.isEmpty || !page.ruledOut.isEmpty -> "Keyboards with a socket for ${indefinite(page.name)} ${page.name}"
        else -> "${page.name} — dev board"
    },
    description = describe(
        page.runs.total.takeIf { it > 0 }?.let {
            "$it keyboard design${if (it == 1) "" else "s"} you can build with ${indefinite(page.name)} ${page.name}:"
        } ?: "${page.name}:",
        "${indefinite(page.footprintLabel)} ${page.footprintLabel}-compatible controller board" +
            (if (page.wireless) ", wireless" else ", wired") + ".",
        page.firmware.takeIf { it.isNotEmpty() }?.let { "Runs ${it.joinToString(", ")}." },
    ),
    canonical = site.of("/parts/dev-board/${page.slug}"),
    // The site card. A board page's own subject is a controller, and this catalogue holds
    // no picture of one -- the silhouettes are of keyboards, and nothing in the corpus
    // records what a Pro Micro looks like. So the honest image is the site's, not an
    // invented drawing of a part we have never measured.
    // No `thumbnail`: Google's own guidance names a site logo as the generic image a
    // `primaryImageOfPage` should avoid, and this page's only picture is exactly that logo.
    image = site.of("/og.png"),
)

// ---------------------------------------------------------------------------
// `/parts` -- ux.md's sitemap line 56, "Part index, grouped by kind".
// ---------------------------------------------------------------------------

/**
 * THE REASON THIS PAGE EXISTS IS A LINK PROBLEM, not a missing feature.
 *
 * `SeoController.sitemap`'s own comment names it: the 34 `/parts/dev-board/:slug` pages
 * are "the only indexable pages whose sole inbound link (Door B's `/compatible`) is
 * itself `noindex`". So they hang off the sitemap alone, and a reader can only reach one
 * by already knowing its slug -- which is the opposite of Door B's premise that the
 * reader starts from the part in their drawer.
 *
 * ONE KIND, AND THAT IS STATED RATHER THAN HIDDEN. `ux.md` names six part kinds
 * (`mcu`, `dev-board`, `switch`, `keycap-profile`, `extension`, `firmware`); only
 * `dev-board` has a catalogue behind it, and `PartsController`'s own rule is that a route
 * this build cannot back is not wired. Grouping the one kind we have by FOOTPRINT rather
 * than presenting a flat 34 is the same reasoning Door B's own controller list uses: the
 * footprint is the fact that decides whether two boards are alternatives at all.
 */
data class PartsIndexPage(val footprints: List<FootprintGroup>, val total: Int)

/** Boards that fit one connector, i.e. boards that are genuine alternatives to each other. */
data class FootprintGroup(val label: String, val boards: List<PartsIndexBoard>)

/**
 * `buildable` is the [ControllerFit.Runs] count -- the same rule the board's own page
 * leads with, so the two cannot disagree.
 *
 * NOT "used by N designs", which is `ux.md` §114's own wording for this line and is a
 * claim nothing in the corpus supports (backlog #59: we know which FOOTPRINT a design
 * sockets, never which board its author picked). "Builds N" is a different and true
 * claim, derived from footprint plus firmware plus chip.
 *
 * IT IS AN UPPER BOUND, AND THE PAGE SAYS SO ONCE. The number counts designs nothing
 * RULES OUT, which is not the same as designs proven to work: a design whose QMK record
 * has no recorded target chip stays in the tier (demoting it would read absence as a
 * fact), and a board with no recorded chip cannot be checked against one at all. Two
 * boards' numbers are therefore only strictly comparable when both chips are known --
 * which is why they no longer sort the list.
 */
data class PartsIndexBoard(
    val name: String,
    val slug: String,
    val wireless: Boolean,
    val mcuFamilyLabel: String?,
    val firmware: List<String>,
    val buildable: Int,
)

fun partsIndex(
    catalog: DesignCatalog,
    boards: List<ControllerBoard>,
    rules: FirmwareRules,
): PartsIndexPage {
    val designs = catalog.all()
    val groups = boards
        .groupBy { it.footprint }
        .map { (footprint, sameSocket) ->
            FootprintGroup(
                label = footprint.describe(),
                // ALPHABETICAL, AND NOT BY `buildable`, WHICH WAS THE FIRST DRAFT AND WAS
                // WRONG. Sorting on that number ranks boards by how little we know about
                // them: a board with no recorded chip gets no chip check, so all 122 QMK
                // designs stay in its top tier, while a documented RP2040 board drops to 64
                // because we KNOW 58 of those records target AVR. The five unknown-chip
                // boards therefore sat at the very top of the hub, above every board whose
                // chip is sourced -- a leaderboard of our own ignorance, pointed at the one
                // field a builder shops from. The count is still shown, because it is true;
                // it is just not a ranking.
                boards = sameSocket
                    .map { board ->
                        PartsIndexBoard(
                            name = board.name,
                            slug = board.slug,
                            wireless = board.wireless,
                            mcuFamilyLabel = board.mcuFamily.knownOrNull()?.describe(),
                            firmware = board.firmware.sorted().map { it.firmwareLabel() },
                            buildable = designs.count {
                                it.fitFor(footprint, board.firmware, board.mcuFamily, rules) is ControllerFit.Runs
                            },
                        )
                    }
                    .sortedBy { it.name }
            )
        }
        // Biggest connector first: `pro_micro` is 26 of the 34 rows and 153 of the designs,
        // so leading with it puts the answer most readers came for at the top.
        .sortedWith(compareByDescending<FootprintGroup> { it.boards.size }.thenBy { it.label })
    return PartsIndexPage(groups, boards.size)
}

/** Indexable, and the hub the 34 board pages were missing. */
fun partsIndexHead(site: SiteUrl) = Head(
    title = "Dev boards",
    description = describe(
        "Every controller board this catalog knows, grouped by the footprint it plugs into.",
        "Pick the one in your drawer and see what you can build with it.",
    ),
    canonical = site.of("/parts"),
    // No `thumbnail`: Google's own guidance names a site logo as the generic image a
    // `primaryImageOfPage` should avoid, and this page's only picture is exactly that logo.
    image = site.of("/og.png"),
)
