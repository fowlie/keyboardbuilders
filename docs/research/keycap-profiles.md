# Keycap profiles: stem, cap footprint, and what the corpus actually needs

Research note for backlog #61, Door B's KEYCAPS section.

**Date:** 2026-09-04

---

## TL;DR

**1. The model was already decided and it is right.** `architecture.md` §1.5 settled keycap
compatibility as **two independent axes — stem and maximum cap footprint — computed from
`(mounts, pitch)`, never stored** (rule R2: "Keycap compatibility is a method. You cannot lie
about it because there is nowhere to write the lie"). Kotlin already ships half of it:
`KeyPitch` → `CapFootprint` is a derived `val` in `Design.kt`. What is missing is the stem
axis and a catalogue of named profiles to intersect against. **Nothing in this note asks for
that model to change.**

**2. The research needed is much smaller than it looks, because one whole quadrant needs
none.** For an MX cross stem at 19.05 mm the answer is *the entire MX keycap market* — Cherry,
OEM, SA, MT3, KAT, DSA, XDA — which is a sentence, not a list. That is **151 of 324 corpus
designs answered with no catalogue rows at all**. A catalogue earns its keep only where the
market is small enough that naming it is the service: the Choc-stem quadrants.

**3. The finding that pays for the note.** "Choc v1 → MBK and Chicago Steno" is the obvious
rule and it is **wrong for 23 corpus designs**. Choc-stem keycaps at *MX* spacing are a
shipping product ([§2.3](#23-choc-stem-caps-at-mx-spacing-the-quadrant-the-obvious-rule-misses)),
because stem and spacing are genuinely independent. `helix`, `bfo-9000`, `ajisai74`,
`4x6-4-dilemma` and 19 more take Choc switches on a 19.05 mm board, and choc-spaced sets do
not fit them.

**4. One deviation from the record is proposed, and it is a widening.** `architecture.md`
folds Alps stems into `MxCross` "for MVP", flagged by `Lint::AlpsKeycapStemApproximated`.
**That lint machinery was never built in Kotlin** — `grep -r Lint src/main/kotlin` finds
nothing — so shipping the fold today publishes "any full-size MX keycap fits" on four Alps
designs with nothing marking it as an approximation. See [§5](#5-the-one-deviation-alps).

---

## 1. Source tiers used

| Tier | Source | Why it is primary |
|---|---|---|
| 1 | **The keycap profile's own CAD source**, and the site's own pitch measurement | For an open-source profile the parametric model *is* the specification — `Choc_Chicago_Steno.scad` names its own stem in its filename. And the spacing axis is not sourced from the web at all: `scripts/measure_key_pitch.py` reads it off each board's switch placements. |
| 2 | Vendor product specification with stated millimetres | Owns the physical facts a shop is accountable for: cap size, stem, which switch it mounts on. |
| 3 | Community and vendor cross-reference articles | Used to **discover** profiles and to cross-check tier-2 claims. Marked wherever it is the only source for a row. |

Deskthority's `Kailh_PG1350_series` and `Kailh_PG1353_series` wiki pages are the obvious
references here and **both answer HTTP 403 to this machine**, so nothing below rests on them.

> **The caution, and it is the same class of error as the controller note's pin-counting
> warning.** keeb.io states flatly that *"Keycaps made for Choc V1 are not compatible with
> standard MX stems, and vice versa"* and that no MX-spacing keycaps are recommended for
> Choc V1. The first half is a true statement about **stems**. The second half is a statement
> about **what that shop stocks**, and reading it as a statement about *pitch* is exactly how
> a catalogue ends up publishing a wrong answer for the 23 designs in §2.3. **Stem
> interchange and cap pitch are different questions and the sources routinely conflate
> them.** Every row below states its two axes separately for that reason.

---

## 2. The facts

### 2.1 Stems

| Switch | Stem | Source |
|---|---|---|
| Cherry MX and clones | MX cross | Definitional — an MX footprint *is* an MX cross stem (`architecture.md` §1.5's own note). |
| Kailh Choc **v1** (PG1350) | Two-prong Choc rails, MX-incompatible | keeb.io: *"All MBK keycaps are compatible with Kailh Choc V1 only and will not fit Choc V2, KS-33 switches, or MX switches"* (tier 3, and unanimous across every source read). |
| Kailh Choc **v2** (PG1353) | **MX cross** | beekeeb: Choc v2 *"use a '+' stem, similar to Cherry MX switches"*, and *"the stem of Choc v1 and Choc v2 key switches are different, which means that their keycaps cannot be interchanged."* keeb.io files every Choc V2 profile under "standard MX stem". |
| Alps SKCM/SKCL, Matias | Alps mount | See [§5](#5-the-one-deviation-alps). |

**`architecture.md` §1.5 got Choc v2 right, independently.** Its own text — *"Kailh Choc v2 has
an MX cross stem: it does take MX-stem keycaps, on an 18×17 PCB"* — was written in the design
phase, and two vendor sources read today agree with it. That is the single most
counter-intuitive fact in this domain and the record already holds it.

### 2.2 Cap footprint is not cap size, and neither is pitch

Three numbers get used interchangeably in vendor copy and they are not the same thing. A cap
is always smaller than the pitch it sits on; the gap is what stops adjacent caps colliding.

| Profile | 1u cap size | Pitch it is cut for | Source |
|---|---|---|---|
| Ordinary MX (Cherry, OEM, SA, MT3, …) | ~18 × 18 mm | 19.05 mm | `architecture.md` §1.5, `CapFootprint::Full19_05` |
| MBK Choc Low Profile | **17.5 × 16.5 mm** (1u), 26.5 × 16.5 (1.5u) | 18 × 17 mm | [splitkb product spec](https://splitkb.com/collections/keycaps/products/mbk-choc-low-profile-keycaps) (tier 2) |
| Chicago Stenographer (Choc) | **17.5 × 16.5 mm** — *"the standard 'choc size'"* | 18 × 17 mm | [rschenk/cfx_chicago_stenographer](https://github.com/rschenk/cfx_chicago_stenographer) README (tier 1, GPL-3.0) |
| Chosfox CFX | **16.5 × 16.5 mm** | its own, tighter — see gaps | same repo: *"resizes … to be the same size as the quirky Chosfox CFX keycaps (16.5x16.5mm) … so the keycaps in this fork are 1mm narrower"* |
| Choc V1 PBT sublimated, MX spacing | **18.25 × 18.25 mm** | 19.05 mm | [pandakb](https://pandakb.com/shop/parts/keycap/mx-spacing-compatible-keycaps-for-kailh-choc-v1/) (tier 2) |
| Tai-Hao THT | 18.1 × 18.1 mm | 19.05 mm (see gaps) | keeb.io (tier 3) |

The site's own `CapFootprint` — `Full`, `Reduced`, `Unusual(spacing)` — is the right
granularity for all of this, and its Kotlin form deliberately collapses
`architecture.md`'s `Reduced17x16_5` and `Other` into one `Unusual`. Nothing here needs
that reopened.

### 2.3 Choc-stem caps at MX spacing: the quadrant the obvious rule misses

pandakb sells PBT sublimated keycaps with the **Choc V1 (PG1350) two-prong stem** and a
*"Standard 18.25mm x 18.25mm footprint, ensuring compatibility with MX-style switch spacing
layouts"*, sold explicitly as "MX Spacing".

So `(ChocV1, Full19_05)` is a real, purchasable combination, and it is the correct answer for
**23 corpus designs** that take Choc switches on 19.05 mm boards. Of those, 5 are
Choc-only (`ajisai74`, `barracuda`, `dropin-v1`, `slimorta`, `sofle-choc`) and 18 also take
MX, which is the case `Design.kt`'s own `pitch` doc comment already describes with
`diepala/cantor`: *"its MX sibling takes Choc or MX switches at MX spacing and therefore
ordinary MX-stem caps."*

---

## 3. The catalogue

Five rows. All five are Choc-stem, because that is the only stem where the market is small
enough for a list to be the useful answer — see [§4](#4-what-is-deliberately-not-catalogued).

*2026-10-03:* seven rows. The two added are MX-stem but cut for choc spacing, where the MX
market is just as small as the Choc one; the update in §4 has their sources.

| Profile | Stem | Cap footprint | Source | Tier |
|---|---|---|---|---|
| MBK Choc Low Profile | ChocV1 | Reduced (18×17) | [splitkb](https://splitkb.com/collections/keycaps/products/mbk-choc-low-profile-keycaps) | 2 |
| Chicago Stenographer | ChocV1 | Reduced (18×17) | [pseudoku/PseudoMakeMeKeyCapProfiles](https://github.com/pseudoku/PseudoMakeMeKeyCapProfiles) | 1 |
| MCC | ChocV1 | Reduced (18×17) | [keeb.io](https://blog.keeb.io/low-profile-keycap-options-what-works-with-what/) | 3 |
| DDC | ChocV1 | Reduced (18×17) | [keeb.io](https://blog.keeb.io/low-profile-keycap-options-what-works-with-what/) | 3 |
| Choc V1 PBT sublimated (MX spacing) | ChocV1 | Full (19.05) | [pandakb](https://pandakb.com/shop/parts/keycap/mx-spacing-compatible-keycaps-for-kailh-choc-v1/) | 2 |
| Tai-Hao THCS-MX *(added 2026-10-03)* | MxCross | Reduced (18×17) | [beekeeb listing](https://shop.beekeeb.com/products/thcs-blank-black-keycaps) | 2 |
| Tai-Hao MT165-MX *(added 2026-10-03)* | MxCross | Reduced (18×17) | [beekeeb listing](https://shop.beekeeb.com/products/mt165-black-blank-keycap) | 2 |

**Chicago Stenographer's provenance, since it is the one tier-1 row.** The profile is
`Choc_Chicago_Steno.scad` in `pseudoku/PseudoMakeMeKeyCapProfiles`, GPL-3.0, parametric
OpenSCAD. Its filename carries the stem, which is why this is tier 1 rather than a vendor
claim. The fork chain read to establish that — rschenk → infused-kim → sporkus → pseudoku —
also names the creator's own shop (asymplex.xyz) for non-printed sets. The same repo holds
`MX_DES_Standard.scad` and `Philadelphia_Minimalist.scad`, MX-stem profiles by the same
author, which are **not** catalogued for the reason in §4.

---

## 4. What is deliberately not catalogued

- **Every MX-stem set at 19.05 mm.** Cherry, OEM, SA, MT3, KAT, DSA, XDA and a thousand
  group buys. The honest answer for an MX board is *"any MX keycap set fits"*, which is one
  sentence and needs no rows, and it answers 151 of 324 designs. Enumerating a market is not
  a compatibility fact, and a list of five profiles here would read as a *restriction* that
  does not exist. `MX_DES_Standard` and `Philadelphia_Minimalist` are excluded under this
  rule despite being one `gh api` call away.
- **Chosfox CFX**, despite being the set several corpus boards are spaced for. Its vendor page
  states no millimetre anywhere, and the one tier-1 measurement found says **16.5 × 16.5 mm**
  — 1 mm narrower than choc-standard, i.e. a *third* spacing class, not the 18×17 one. Filing
  it as "choc spacing" would be a guess about which pitch it is cut for, and boards exist
  spaced specifically for it (rschenk's Tern). It belongs in the catalogue once one source
  states its target pitch.
- **`(MxCross, Reduced18x17)` — MX-stem caps for a Choc-v2 board at choc spacing.** This
  quadrant is physically real and `architecture.md` §1.5 offers "LPF, Tai-Hao Thins, and
  similar" for it, but the evidence read today does not support that parenthetical: keeb.io
  gives Tai-Hao THT as **18.1 × 18.1 mm**, which cannot sit on a 17 mm axis. So the record's
  example is likely imprecise, and no sourced product fills the quadrant. **It costs nothing
  measurable today**: no corpus design is MX-stem at choc pitch (the one `choc_v2` design has
  no measured pitch at all), so the section states the gap rather than guessing, and this note
  is where the discrepancy is recorded rather than silently inherited.

  **Update, 2026-10-03: the quadrant is filled, by Tai-Hao THCS-MX and MT165-MX, not by the
  record's LPF/THT.** Read today:

  | Product | 1u cap size | Stem | Source | Tier |
  |---|---|---|---|---|
  | Tai-Hao **THCS-MX** | **17.5 × 16.5 mm**, *"compatible with choc spacing"* | *"+ (MX Style)"*; *"not compatible with Choc v1"* | [beekeeb listing](https://shop.beekeeb.com/products/thcs-blank-black-keycaps) | 2 |
  | Tai-Hao **MT165-MX** | **16.5 × 16.5 mm**, *"compatible with choc spacing"* | *"+ (MX Style)"*; *"not compatible with Choc v1"* | [beekeeb listing](https://shop.beekeeb.com/products/mt165-black-blank-keycap) | 2 |
  | Tai-Hao THT, **THCS profile** (18-key sets) | *"16.5 × 17.5 mm"* | sold in an **MX** and a **Choc V1** version | MaxGaming: [MX](https://us.maxgaming.com/us/-83/tai-hao-tht-doubleshot-pbt-shine-through-low-profile-18-key-mx-black), [Choc V1](https://www.maxgaming.com/en/keycaps/tai-hao-tht-doubleshot-pbt-shine-through-low-profile-18-key-choc-v1-black) | 2 |

  The discovery source is beekeeb's guide
  [Choc Spacing Keycaps for Choc v2 Key Switches](https://showcase.beekeeb.com/choc-spaced-chocv2-keycaps/)
  (20 Nov 2025, tier 3): the popular Choc-spaced caps (*"MBK or Chocfox"*) *"are made for
  Choc v1 switches. The stems won't fit on the Choc v2 '+' stems"*; the "+"-stem low-profile
  caps it knows (XVX, SMOLO) are standard size; and Tai-Hao's *"MT 165 and THCS"* have *"the
  '+' stem"* and *"Choc spacing dimensions"*. **beekeeb sells both products**, so the sizes
  are the seller's own statement, and the site words the claim as beekeeb's.

  Three things this does and does not settle. (1) The stem axis: THCS is a *profile* sold
  with either stem, so the catalogue rows are the **-MX** products by name, never "THCS".
  (2) MT165 is 16.5 × 16.5 mm — the same size as the Chosfox CFX caps this section still
  excludes — but here the vendor itself says *choc spacing*, which is the fact CFX lacks, and
  a smaller cap clears an 18 × 17 grid by construction. (3) **No Tai-Hao page was found**
  (the tai-hao.com front page, fetched 2026-10-03, mentions neither name, and web search
  turned up no Tai-Hao product page), so both rows rest on retailer listings, not the
  manufacturer; no web source other than beekeeb's names "MT 165" at all.
- **Alps keycaps.** No profile sourced; 4 corpus designs affected. See §5.

---

## 5. The one deviation: Alps

`architecture.md` §1.5 derives `SwitchMount::Alps → KeycapStem::MxCross`, with the comment
`// Alps caps: see note`, and its §1.10 records the compromise as
`Lint::AlpsKeycapStemApproximated` — *"Alps stems are folded into a lint rather than a third
`KeycapStem` variant for MVP … recorded as lints, not as silent lies."*

**The lint half of that bargain was never built.** There is no `Lint` type anywhere in
`src/main/kotlin`. So the fold, shipped as-is into a page that publishes keycap answers, is
the silent lie the record was careful to avoid: four designs — `aek2-usb`, `cyclops`,
`m0110a-qmk`, `m0116-usb`, all MX-pitch Alps boards — would read *"any MX keycap set fits"*,
and an Alps stem takes no MX cap at all.

**Proposed: a third `KeycapStem.Alps` member, and no catalogue rows for it**, so the section
says "we haven't catalogued keycaps for Alps switches" — a true sentence about our own
coverage. This costs one enum member, makes the false claim unconstructible rather than
merely flagged, and needs no lint machinery. It is a **widening of the domain shape rather
than an imprecise fit for an edge case**, which is the standing preference on this project.

This is a deviation from the decision record and is flagged as one: if the fold plus a real
lint is preferred, that is a bigger build (`Lint` does not exist) for a worse page.

---

## 6. How the 324 built designs land

Computed from `src/main/resources/seed/designs.json` — `switches` × `pitch`, with the stem
mapping above, in the order `keycapFacts()` actually checks them. Every design lands in
exactly one state; there is no residue.

| Designs | State | Why |
|---|---|---|
| 151 | "Any MX keycap set fits" | MX cross stem, 19.05 mm. One sentence, no rows, no research. |
| 108 | "Nobody has measured the spacing" | `pitch` is `Unknown`, so the cap axis has no value. |
| 26 | "Depends which switch you fit" | More than one **stem** among the switches recorded. |
| 24 | Rows, choc-spaced | ChocV1 at 18×17: MBK, Chicago Steno, MCC, DDC. |
| 5 | Rows, MX-spaced | ChocV1 at 19.05: the §2.3 quadrant. |
| 4 | "Alps not catalogued" | §5. |
| 3 | "No commercial cap size matches" | ChocV1 at a `Custom` pitch — `chocofly` (17.50 mm), `egg58v2`, `kobold`. |
| 3 | "No commercial cap size matches" | MX stem at a `Custom` pitch — `aya` (19.50 mm), `frankie-macropad`, `unison`. |

**The `(MxCross, Reduced18x17)` quadrant, 2026-10-03:** it now has two catalogue rows
(Tai-Hao THCS-MX and MT165-MX, §4's update) and still **0 of 338** built designs land in it:
the corpus's two Choc v2 designs (`41-1353-r41l2`, `corne-chocolate-v4`) are both measured
at 19.05 mm, and no MX-switch design is at choc pitch. The rows serve the boards beekeeb's
guide names (HSHS52, HSHS46, Toucan; intake candidates in
a review in the owner's notes) the day one is added. The
table above is still the 2026-09-04 count of 324.

**Spacing is checked before the switch dependency**, which inverts `ux.md` §2.9's own
emphasis, and the reason is that the two sentences are not equally true. Without a measured
pitch there is no keycap answer for *any* switch a builder might pick, so the spacing
sentence is unconditional and complete; "pick a switch above" would promise an answer that
resolving the switch cannot deliver. That is why the dependent state is 26 designs and not
the 44 that record more than one switch kind — the other 18 have no measured pitch and get
the stronger sentence.

**The dependency is on stems, not on switch kinds**, and the distinction is not academic:
Choc v2 has an MX cross stem, so a board taking MX *and* Choc v2 has one keycap answer
rather than two and should resolve without asking. No corpus design is that shape today
(all 44 multi-switch designs span at least two stems), so the refinement changes no page
now — but keying the dependency on switch kinds would make a Choc-v2-and-MX board ask a
question with a single answer the day one is added.

Two more things worth reading off the table. **The largest single group is a sentence, not
data** — which is why this catalogue is five rows instead of fifty. And **the second largest
is a measurement gap, not a keycap gap**: 108 designs are unanswerable because nobody has
measured their pitch, so `measure_key_pitch.py`'s coverage, not more keycap research, is
what would grow this section's usefulness most.

---

## 7. Gaps, stated as gaps

| Gap | Designs affected | What would close it |
|---|---|---|
| `pitch` unmeasured | 108 | `measure_key_pitch.py` coverage — the largest single win available here. |
| Alps profiles | 4 | One sourced Alps-stem keycap product with stated dimensions. |
| Chosfox CFX target pitch | unknown | One source stating the pitch CFX's 16.5 mm caps are cut for. |
| ~~`(MxCross, Reduced18x17)`~~ | 0 of 338 (2026-10-03) | **Closed 2026-10-03** by Tai-Hao THCS-MX (17.5 × 16.5 mm) and MT165-MX (16.5 × 16.5 mm), both "+ (MX Style)", tier 2 from beekeeb's listings, THCS corroborated by MaxGaming (§4's update). Open: a Tai-Hao primary source; `architecture.md`'s LPF/THT examples still do not survive §4's check. |
| `Bespoke` stems | 0 today | `Design.kt`'s `Bespoke(name)` carries no `stem`, where `architecture.md` requires one. Unused by the corpus, so it is a latent gap, not a live one. |
| Keycap 1u size per design | all | `ux.md` §3.2's own spec row, never backed. Out of scope for #61 by its own text. |
