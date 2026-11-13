package net.keyboardbuilders.keyboardbuilders.domain

/**
 * Backlog #46: "how well is this design represented", as a percentage. The UX spec
 * rules out one lone badge that reads as a grade on the DESIGN; this is a grade on the
 * RECORD.
 *
 * COUNTS GATE FIELDS TOO, on the owner's ruling (2026-09-16): "it's just so obvious that a
 * design has more fields" than the handful that can ever be `Unknown`. A gate field (layout,
 * switch footprint, build method, controller, body, firmware, the three licence slots) is
 * never missing — `DesignCore` cannot be constructed without one — so it always reads as
 * known. That means it can never be the reason a percentage is below 100, only ever raises
 * the denominator alongside the numerator. The trade this accepts: two designs that differ
 * only in optional fields now read closer together than a fields-that-can-actually-be-missing
 * count would show them, because nine of the roughly fifteen fields on any page are the same
 * fixed, always-known block. Accepted because the alternative — a record that "is" its five or
 * six optional facts — undercounts a design by omitting everything that makes it buildable.
 *
 * THE FIELD LIST IS THE DISPLAY, not a hidden tally. Owner-caught on `/designs/litl`
 * (2026-09-16): a percentage nobody can check against the page is worse than no percentage
 * at all — `layout.shorthand` was counted once while rendering no visible gap, and the fix
 * was to stop counting what the page hides. This time the fix is the opposite shape: the
 * page now shows the whole list. `DesignCompleteness` carries the labelled fields themselves
 * rather than a bare count, and `show.kte` prints every label. A field that is never
 * `Unknown` still prints — the reader sees a real accounting, not a magic total.
 */
data class DesignCompleteness(val fields: List<CompletenessField>) {
    init {
        require(fields.isNotEmpty()) { "every design has at least the gate fields" }
    }

    val known: Int get() = fields.count { it.known }
    val total: Int get() = fields.size

    /** Rounds to the nearest percent rather than always down, so 17/22 (77.27%) reads 77
     *  and not the pessimistic 76 truncation would give. */
    val percent: Int get() = (known * 100 + total / 2) / total
}

/** One field on the accounting: what it's called, and whether this design states it. */
data class CompletenessField(val label: String, val known: Boolean)

private fun <T> Recorded<T>.known() = this is Recorded.Known

/**
 * Every field this design's own shape could have an answer for, paired with whether it does.
 *
 * ONLY OPTIONAL FIELDS THE PAGE ITSELF SHOWS AS A GAP are counted as possibly-`Unknown`.
 * `layout.shorthand` and `extras.basedOn` are both `Recorded`, but `show.kte` and their own
 * `describe()` functions render an `Unknown` one as nothing at all — deliberately, per their
 * doc comments ("Unknown for ~82% of splits is the accepted price", "the page says nothing
 * rather than apologising for an absence"). `Body.Split.reversible`/`link` render the same way
 * ("neither one is a thing to buy"). Counting them would make the percentage claim a gap the
 * reader has no way to see.
 *
 * The gate fields above them are never `Unknown` — see the type's own doc comment for why
 * they are counted anyway.
 *
 * MEETING THIS RULE IS NECESSARY BUT NOT SUFFICIENT. `extras.extensions` ("Room for")
 * renders a visible gap and still is not counted — see the `Design.fields()` comment
 * beside it for the "unknown known" it would otherwise be scored on.
 */
// `Layout.name` joins `layout.shorthand` here, deliberately, for the identical
// reason: `Layout.describe()` appends a known name and renders an unknown one as
// nothing at all, so it fails this admission rule the same way `shorthand` does.
private fun Design.fields(): List<CompletenessField> = buildList {
    add(CompletenessField("Layout", true))
    add(CompletenessField("Switch footprint", true))
    add(CompletenessField("Build method", true))
    add(CompletenessField("Controller", true))
    add(CompletenessField("Body", true))
    // OUT OF THE GATE since #106, and NOT COUNTED WHEN UNKNOWN, the `extensions` rule: no
    // build can prove a keyboard has no firmware anywhere, so a board nobody wrote firmware
    // for reads like one nobody has checked, an unknown known.
    if (core.firmware is Recorded.Known) add(CompletenessField("Firmware", true))
    add(CompletenessField("Hardware license", true))
    add(CompletenessField("Firmware license", true))
    add(CompletenessField("Documentation license", true))

    add(CompletenessField("Key pitch", core.pitch.known()))

    when (val method = core.buildMethod) {
        is BuildMethod.Pcb -> add(CompletenessField("Soldering", method.hardestComponent.known()))
        BuildMethod.Handwire -> {} // no soldering tier to know — there is no board
    }

    when (val controller = core.controller) {
        is Controller.Soldered -> add(CompletenessField("Soldered MCU", controller.mcu.known()))
        is Controller.Socket -> {} // the build picks the chip; the design has none to name
    }

    // ONE field, matching the Spec table's own "Firmware targets" line, which unions every
    // QMK entry's target into one line rather than printing one per record (`DesignPresentation
    // .kt`'s `toPage()`). A design citing several QMK records must not read as several
    // firmware-target fields when the page only ever shows one.
    core.firmware.knownOrNull().orEmpty().filterIsInstance<FirmwareRef.Qmk>().let { qmkEntries ->
        if (qmkEntries.isNotEmpty()) {
            add(CompletenessField("Firmware targets", qmkEntries.any { it.targetMcu.known() }))
        }
    }

    add(CompletenessField("Summary", extras.summary.known()))
    add(CompletenessField("Key wiring", extras.wiring.known()))

    // NOT COUNTED, OWNER'S RULING (2026-09-17): "it's about the unknown knowns." `show.kte`
    // renders an unrecorded "Room for" as a visible gap, so it meets this file's own
    // admission rule on procedure alone -- but `extras.extensions`' `Unknown` is not
    // honest ignorance the way most `Unknown`s here are. `Design.kt`'s own doc comment on
    // that field says why: there is no `Known(emptySet())`, because nothing this build
    // reads can PROVE a board has room for nothing -- so a plain macropad with genuinely
    // no encoder, trackball or display reads identically to one nobody has checked yet.
    // That true "no extras at all" is an UNKNOWN KNOWN: a fact the corpus really does
    // hold (this design has none), filed under ignorance because the domain has nowhere
    // else to put it. Counting it would score that macropad as an incomplete record of a
    // fancier keyboard it was never going to be, rather than a complete record of a
    // simple one.
    //
    // Contrast `extras.switchMounts` just below, which stays counted: a board is always
    // hotswap or soldered, there is no third "neither" case a design can genuinely be, so
    // an unrecorded one is ordinary ignorance about a fact that exists, not this collision.

    // QUALIFIES UNDER THIS FILE'S OWN ADMISSION RULE: `show.kte` renders an unrecorded
    // one as a visible "not recorded" gap, unlike `layout.shorthand` or `basedOn`, which
    // render as nothing and are therefore not counted. A reader can check this field
    // against the page, which is the whole test.
    //
    // Read off the pads since 2026-10-02, so only 4 designs (key positions that share no
    // mount) lower by it. It never rises when data is MISSING, which is the hazard for a
    // number that feeds `sort=complete`.
    add(CompletenessField("Hotswap", extras.switchMounts.known()))

    // COUNTED, ON EVEN SAFER GROUND THAN `Hotswap` JUST ABOVE. That field's
    // argument is about the WORLD ("a board is always hotswap or soldered, no third
    // case") because the instrument itself can only ever emit `Known` on a hotswap
    // hit — it has no way to state "soldered". `splay`'s `Known(false)` needs no such
    // argument: it is a real measured verdict from `measure_splay.py`, not an absence
    // of evidence standing in for one (`DesignExtras.splay`'s own doc comment has the
    // instrument). So its `Unknown` — no attributed board, or a board whose key count
    // matches no layout the design publishes — is ordinary ignorance with nothing of
    // `extensions`' "genuinely has none" collision to guard against. Qualifies under
    // this file's own admission rule too: the Spec table renders an unmeasured one as
    // a visible "Not recorded" line.
    add(CompletenessField("Splay", extras.splay.known()))

    // COUNTED, SAME ARGUMENT AS `Splay` JUST ABOVE AND FOR THE SAME REASON: a board is
    // always ortho, row-staggered or column-staggered -- there is no fourth "none of
    // these" case a design can genuinely be, so `extras.stagger`'s `Unknown` is ordinary
    // ignorance rather than an unknown known like `extensions`' is. Read off the same
    // instrument (`measure_stagger.py`), not a name lexicon that can only prove
    // presence, and the Spec table renders an unmeasured one as a visible "Not
    // recorded" line, which is this file's own admission rule.
    add(CompletenessField("Stagger", extras.stagger.known()))
}

fun Design.completeness(): DesignCompleteness = DesignCompleteness(fields())
