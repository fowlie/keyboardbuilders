# ZMK corpus: what the metadata gives us, and what has to be sourced by hand

Research resolving ticket 06, the ZMK corpus facts.
Run 2026-08-01.

> **Headline.** Key counts are **not** absent — they are derivable for **77 of 77** keyed records by
> reimplementing ZMK's own `ZMK_KEYMAP_LEN` macro, and all 77 independently agree with the `.keymap`
> binding count. This is an afternoon, not a week. The interconnect graph must be modelled as a
> **directed multi-level DAG over connector ids**, not a footprint string, because `nice!view`
> compatibility is a three-hop chain. ZMK carries **no** switch footprint, **no** thumb count, and
> **no** buildability signal; it does carry a URL for **100%** of records.

## Sources

All primary, all read verbatim. Nothing below is described from memory.

| Source | Identity |
|---|---|
| Published aggregate | `https://zmk.dev/hardware-metadata.json` — 31,778 bytes, 110 records |
| Schema | `zmkfirmware/zmk` `schema/hardware-metadata.schema.json` |
| Aggregator | `docs/src/hardware-metadata-static-plugin/index.js` |
| Key-count rule | `app/include/zmk/matrix.h`, `app/src/physical_layouts.c`, `app/src/matrix_transform.c` |
| Layout binding | `app/dts/bindings/zmk,physical-layout.yaml` |
| Repo | `github.com/zmkfirmware/zmk`, shallow clone at `aed236f53fc2e6377293b3c6bd4a2eb3d97dc763` |
| License | MIT |

Reproduce the derivation with [`scripts/derive_zmk_key_counts.py`](../../scripts/derive_zmk_key_counts.py).

---

## 1. Traps found (read this first)

Four, all of the same family as QMK's layered-format trap.

### 1.1 `directory` is not in the schema

Every published record carries a `directory` field. The schema sets `additionalProperties: false` and
does **not** define `directory` — so a record from the published JSON will **fail** validation against
the published schema. The field is injected at aggregation time:

```js
// docs/src/hardware-metadata-static-plugin/index.js:32-38
({
  ...metadata,
  // External tools need a way to locate this hardware within the repo.
  // Append each item's relative path.
  directory: path.posix.dirname(path.posix.relative(BASE_DIR, file)),
})
```

Strip `directory` before validating, or validation will reject all 110 records. The field is still the
right way to locate hardware in the tree and should be kept.

### 1.2 The tree has 112 `*.zmk.yml` but only 110 are real

The aggregator globs `app/boards/**/*.zmk.yml` only. Two further files live under `app/module/boards/`:

- `app/module/boards/qmk/proton_c/proton_c.zmk.yml` → `id: proton_c`
- `app/module/boards/keycapsss/puchi_ble/puchi_ble.zmk.yml` → `id: puchi_ble`

These are the **legacy Zephyr hardware-model-v1 definitions of boards that also exist in `app/boards/`
under hwmv2 ids** (`proton_c//zmk`, `puchi_ble//zmk`). ZMK is mid-migration. A naive
`find . -name '*.zmk.yml'` yields 112 records with two duplicate physical boards under two ids.
**Use the published JSON, not the tree glob** — same conclusion QMK forced.

The published JSON was current with HEAD at the time of this research: regenerating the id set from
`app/boards/**/*.zmk.yml` at `aed236f5` gives 110 ids with an empty symmetric difference. That is a
happy accident, not a guarantee — the JSON is a docs-site build artifact and can lag the repo.

### 1.3 Devicetree node-reference merge

Properties are routinely assigned to a node **from outside its definition**, via a reference block.
`corne.dtsi` does exactly this:

```dts
#include <layouts/foostan/corne/5column.dtsi>
#include <layouts/foostan/corne/6column.dtsi>

&foostan_corne_6col_layout {
    transform = <&default_transform>;
};
```

The layout node itself is defined in `app/dts/layouts/foostan/corne/6column.dtsi` and has **no**
`transform` property there. Any parser that reads only node definitions sees corne as having no usable
layout and silently falls through to a wrong answer. A first pass of the extractor written for this
research did exactly that and reported corne as `4 rows × 0 cols`. **You must merge `&label { … }`
override blocks into the labelled node before reading properties.**

### 1.4 Dangling ids in `requires` / `exposes`

Three connector ids are referenced but have **no record of any type**:

| id | required by | exposed by | status |
|---|---:|---:|---|
| `i2c_oled` | 1 | 27 | Real de-facto connector, never declared as `type: interconnect` |
| `nice_view_header` | 1 | 1 | Same |
| `rpi_pico` | 1 | 0 | **Broken reference.** `tester_rpi_pico` requires `rpi_pico`; the board's id is `rpi_pico//zmk` — an hwmv2 rename that this shield was not updated for |

A foreign-key constraint against the 5 declared interconnects would reject 29 edges. Model connector
ids as **free-standing nodes** created on reference, with the 5 declared interconnects carrying extra
attributes (`description`, `manufacturer`, `node_labels`, `design_guideline`) and the rest being bare.

Note also that `blackpill` is exposed by 4 boards and required by **zero** shields — a live interconnect
with no consumers.

---

## 2. Key counts: derivable, by ZMK's own rule

**Verdict: derivable for 77 of 77 keyed records. Verified.**

### 2.1 The rule

Do not invent one. ZMK computes the keymap length itself, and the precedence is stated in
`app/include/zmk/matrix.h` verbatim:

```c
#define ZMK_MATRIX_NODE_ID DT_CHOSEN(zmk_kscan)
#define ZMK_MATRIX_HAS_TRANSFORM DT_HAS_CHOSEN(zmk_matrix_transform)

#if DT_HAS_COMPAT_STATUS_OKAY(zmk_physical_layout)
#define ZMK_PHYSICAL_LAYOUT_BYTE_ARRAY(node_id)                                     \
    uint8_t _CONCAT(prop_, node_id)[DT_PROP_LEN(DT_PHANDLE(node_id, transform), map)];
#define ZMK_KEYMAP_LEN                                                              \
    sizeof(union {DT_FOREACH_STATUS_OKAY(zmk_physical_layout, ZMK_PHYSICAL_LAYOUT_BYTE_ARRAY)})

#elif ZMK_MATRIX_HAS_TRANSFORM
#define ZMK_KEYMAP_TRANSFORM_NODE DT_CHOSEN(zmk_matrix_transform)
#define ZMK_KEYMAP_LEN DT_PROP_LEN(ZMK_KEYMAP_TRANSFORM_NODE, map)

#else
#if DT_NODE_HAS_PROP(ZMK_MATRIX_NODE_ID, row_gpios)
#define ZMK_MATRIX_ROWS DT_PROP_LEN(ZMK_MATRIX_NODE_ID, row_gpios)
#define ZMK_MATRIX_COLS DT_PROP_LEN(ZMK_MATRIX_NODE_ID, col_gpios)
#elif DT_NODE_HAS_PROP(ZMK_MATRIX_NODE_ID, input_gpios)
#define ZMK_MATRIX_ROWS 1
#define ZMK_MATRIX_COLS DT_PROP_LEN(ZMK_MATRIX_NODE_ID, input_gpios)
#else
#define ZMK_MATRIX_ROWS DT_PROP(ZMK_MATRIX_NODE_ID, rows)
#define ZMK_MATRIX_COLS DT_PROP(ZMK_MATRIX_NODE_ID, columns)
#endif
#define ZMK_KEYMAP_LEN (ZMK_MATRIX_COLS * ZMK_MATRIX_ROWS)
#endif
```

Restated as an import rule, in strict precedence order:

1. **Physical layout** — if any `compatible = "zmk,physical-layout"` node exists *and* no
   `chosen { zmk,matrix-transform }` is set: count `RC(…)` entries in the `map` of the transform
   phandled by the layout named in `chosen { zmk,physical-layout }`. If no layout is chosen, ZMK takes
   the maximum across all layouts (that is what the `sizeof(union{…})` does).
2. **Chosen transform** — count `RC(…)` entries in the `map` of `chosen { zmk,matrix-transform }`.
3. **Synthesised from the kscan** — `zmk,kscan-gpio-matrix` → `len(row-gpios) × len(col-gpios)`;
   `zmk,kscan-gpio-direct` → `len(input-gpios)`; else the node's own `rows × columns`.

The precedence in step 1 is not cosmetic. `app/src/physical_layouts.c:42` gates the physical-layout
branch on `!DT_HAS_CHOSEN(zmk_matrix_transform)`, and the `#elif` arm carries the warning
*"Ignoring the physical layouts and using the chosen matrix transform."* A chosen matrix transform
**beats** physical layouts.

Two things that look like the answer and are not:

- **`rows × columns` on the transform node.** Corne declares `columns = <12>; rows = <4>;` — 48 — but
  its map has 42 entries. The matrix is sparse; the product is the scan grid, not the key count.
- **The length of the `keys` array on a physical layout.** `keys` is **optional** in
  `app/dts/bindings/zmk,physical-layout.yaml` (only `display-name` and `transform` are required) and
  is present for just 41 of 77 records. The `transform`'s `map` length is the load-bearing value.

### 2.2 Worked example — corne

`app/boards/shields/corne/corne.dtsi`:

```dts
    default_transform: keymap_transform_0 {
        compatible = "zmk,matrix-transform";
        columns = <12>;
        rows = <4>;
        map = <
RC(0,0) RC(0,1) RC(0,2) RC(0,3) RC(0,4) RC(0,5)  RC(0,6) RC(0,7) RC(0,8) RC(0,9) RC(0,10) RC(0,11)
RC(1,0) RC(1,1) RC(1,2) RC(1,3) RC(1,4) RC(1,5)  RC(1,6) RC(1,7) RC(1,8) RC(1,9) RC(1,10) RC(1,11)
RC(2,0) RC(2,1) RC(2,2) RC(2,3) RC(2,4) RC(2,5)  RC(2,6) RC(2,7) RC(2,8) RC(2,9) RC(2,10) RC(2,11)
                        RC(3,3) RC(3,4) RC(3,5)  RC(3,6) RC(3,7) RC(3,8)
        >;
    };
```

`chosen { zmk,physical-layout = &foostan_corne_6col_layout; }`; that layout's `transform` is assigned
by the `&foostan_corne_6col_layout { transform = <&default_transform>; }` override (trap 1.3) to
`default_transform`; the map has 12+12+12+6 = **42**. Corne is a 42-key board. Correct.

The 36-key `five_column_transform` in the same file is the alternate 5-column layout — a *variant*, not
a competing answer. See §2.5.

### 2.3 Worked example — the rule-3 fallback

Five keyed records have neither a physical layout nor a transform. `app/boards/shields/naked60/naked60.overlay`
is the whole file's substance:

```dts
    kscan0: kscan_0 {
        compatible = "zmk,kscan-gpio-matrix";
        diode-direction = "col2row";
        col-gpios = <&pro_micro 21 …>, … ;   // 12 entries
        row-gpios = <&pro_micro 10 …>, … ;   // 5 entries
    };
```

5 × 12 = **60**, and the design is called *naked60*. `app/boards/shields/two_percent_milk/two_percent_milk.overlay`
uses `zmk,kscan-gpio-direct` with 2 `input-gpios` → **2**, and the design is called *2%*. `romac` →
4 × 3 = **12**; `pancake` → 4 × 12 = **48**; `knob_goblin` → 5 × 5 = **25**.

### 2.4 Validation

The rule was tested against an **independent** source: the count of `&`-prefixed bindings in each layer
of the shield's `.keymap` file. That number is the firmware's key-position count and is produced by a
different file and a different author from the devicetree.

```
keyed records 77 | derived 77 | keymap agrees 77 | disagrees 0 | no keymap 0 | no count 0
```

Sample of the output, with the rule branch that fired:

| id | rule | derived | `.keymap` layers |
|---|---|---:|---|
| `corne` | 1 physical-layout | 42 | 42 |
| `kyria_rev3` | 1 physical-layout | 50 | 50 |
| `glove80` | 1 physical-layout | 80 | 80 |
| `splitkb_aurora_sweep` | 1 physical-layout | 34 | 34 |
| `adv360pro` | 1 physical-layout | 76 | 76 |
| `bfo9000` | 2 chosen-transform | 108 | 108 |
| `sofle` | 1 physical-layout (max) | 60 | 60 |
| `iris` | 2 chosen-transform | 56 | 56 |
| `naked60` | 3 kscan | 60 | 60 |
| `two_percent_milk` | 3 kscan | 2 | 2 |

Every one of those matches the design's real key count as the community knows it. 11 records show the
derived count as *one of several* layer sizes (`bt60` derives 62, layers 60/61/62/64/66) — these are
boards with several alternate layouts, and the derived value is the chosen one. No record produced a
count absent from its keymap.

Name-based sanity checking has one honest false alarm worth recording: `bat43` derives **48**, not 43.
Its `map` is five lines, not four — 12+13+12+6+5 = 48. The derivation is right and the name is not a
key count.

### 2.5 Alternate layouts are real and must be modelled

20 of 77 records declare more than one layout or transform. Corne is 42-key or 36-key; `planck` is
`all1u` (48), `1x2u` (47) or `2x2u` (46); `bt65` offers ANSI/ISO/HHKB/all-1u. `zmk,physical-layout`
nodes carry a `display-name` (`"5 Column"`, `"60% ANSI"`) which is a ready-made variant label.

The chosen layout is the design's headline key count. The others are a **`layouts: Vec<(name, count)>`**
on the record. Flattening to a single integer discards a genuine product fact — a Corne buyer choosing
5-column vs 6-column is making the same kind of decision the filter rail exists to serve.

---

## 3. The interconnect graph

### 3.1 Shape

It is **not** bipartite boards→shields, and it is **not** reducible to a footprint string. It is a
directed acyclic graph over connector ids in which **shields both consume and produce** connectors:

```
board  nice_nano//zmk       ──exposes──▶ pro_micro
shield corne                ──requires─▶ pro_micro
shield corne                ──exposes──▶ i2c_oled
shield nice_view_adapter    ──requires─▶ i2c_oled
shield nice_view_adapter    ──exposes──▶ nice_view_header
shield nice_view            ──requires─▶ nice_view_header
```

That chain is the flagship query's "nice!view" leg, and it is **three hops of shield stacking**. From
the real files:

```yaml
# app/boards/shields/nice_view/nice_view.zmk.yml
id: nice_view
name: nice!view
type: shield
requires: [nice_view_header]
features:
  - display
```
```yaml
# app/boards/shields/nice_view_adapter/nice_view_adapter.zmk.yml
id: nice_view_adapter
name: nice!view adapter
type: shield
requires: [i2c_oled]
exposes: [nice_view_header]
```

A footprint string on the keyboard record cannot express this. `corne` is not "nice!view compatible"
as an attribute; it is nice!view compatible *because* it exposes `i2c_oled` and a path exists through
`nice_view_adapter`. Collapse that and you lose both the reason and the ability to answer the query
when a new adapter appears.

### 3.2 Recommended model

Three tables, mirroring the schema rather than reinterpreting it:

```
Hardware(id, kind: Board|Shield|Interconnect, name, url, …)
Connector(id, declared: bool, description?, manufacturer?, node_labels?, design_guideline?)
Edge(hardware_id, connector_id, direction: Requires|Exposes)
```

`Connector` rows are created on first reference, with `declared = false` for `i2c_oled`,
`nice_view_header` and `rpi_pico` (§1.4). Compatibility between two pieces of hardware is then a
reachability query over `Edge`, not a stored field. Keep `direction` as an ADT — the asymmetry is the
entire content of the graph.

### 3.3 Full edge census

Declared interconnects (`type: interconnect`, 5): `arduino_uno`, `blackpill`,
`makerdiary_nrf52840_m2`, `pro_micro`, `seeed_xiao`. Undeclared but referenced (3): `i2c_oled`,
`nice_view_header`, `rpi_pico`.

| Connector | Exposed by | Required by |
|---|---:|---:|
| `pro_micro` | 11 boards | 61 shields |
| `i2c_oled` | 27 shields | 1 shield (`nice_view_adapter`) |
| `seeed_xiao` | 4 boards | 2 shields |
| `blackpill` | 4 boards | **0** |
| `arduino_uno` | 2 boards | 1 shield (`zmk_uno`) |
| `makerdiary_nrf52840_m2` | 1 board | 1 shield (`m60`) |
| `nice_view_header` | 1 shield (`nice_view_adapter`) | 1 shield (`nice_view`) |
| `rpi_pico` | 0 (**dangling**) | 1 shield (`tester_rpi_pico`) |

The 11 boards exposing `pro_micro`, of which **7 are BLE-capable**:

| Board | outputs |
|---|---|
| `nice_nano//zmk` | usb, ble |
| `nrfmicro/nrf52840/zmk` | usb, ble |
| `nrfmicro/nrf52840/flipped_zmk` | usb, ble |
| `nrfmicro/nrf52833/zmk` | usb, ble |
| `bluemicro840//zmk` | usb, ble |
| `puchi_ble//zmk` | usb, ble |
| `mikoto//zmk` | usb, ble |
| `adafruit_kb2040//zmk` | usb |
| `boardsource_blok//zmk` | usb |
| `sparkfun_pro_micro_rp2040//zmk` | usb |
| `proton_c//zmk` | usb |

### 3.4 Wireless is a property of the *pairing*, not the design

This is the single most important consequence of modelling the graph honestly. A shield has no radio;
the board does. So `wireless` for a shield design means *"a BLE board exists that exposes an
interconnect this shield requires."* Evaluated over the graph:

**72 of 77 keyed designs are wireless-capable.** The 5 that are not are all integrated USB-only boards
(`planck//zmk`, `preonic//zmk`, `tofu65//zmk`, `bdn9//zmk`, `ferris//zmk`) — every shield in the corpus
can be built wireless.

A boolean `wireless` column would therefore be true for effectively the whole ZMK import, which is both
correct and useless as a filter. The useful facet is closer to *"wireless without extra work"* or
*"which controllers this pairs with"*. **Flag for the spec: `wireless` needs a product definition
before the ZMK import lands, or it will merge with QMK's 0.6% into a facet that means two different
things on either side of the corpus.**

### 3.5 The flagship query, answered

`DECISIONS.md` §8 measured the flagship query at **0 results from QMK at depth 2**. Against ZMK:

| Filter | Remaining |
|---|---:|
| keyboards (records with `keys`) | 77 |
| + split | 38 |
| + wireless-capable | 38 |
| + nice!view-capable (`exposes: i2c_oled` → adapter → nice!view) | **25** |

The 25: `corne`, `cradio`, `elephant42`, `helix`, `iris`, `jorne`, `kyria`, `kyria_rev2`, `kyria_rev3`,
`leeloo`, `leeloo_micro`, `leeloo_rev2`, `lily58`, `lotus58`, `microdox`, `microdox_v2`, `snap`,
`sofle`, `splitkb_aurora_corne`, `splitkb_aurora_helix`, `splitkb_aurora_lily58`,
`splitkb_aurora_sofle`, `splitkb_aurora_sweep`, `waterfowl`, `zodiark`.

The remaining leg — "3 thumb keys" — is **not** answerable from ZMK. See §5.2. Also note that the
38 splits break down as 35 shields plus 3 integrated wireless split boards (`adv360pro`,
`corneish_zen`, `glove80`).

---

## 4. `siblings`, and the Design/Variant split

**`siblings` is the split-halves marker, and it never points at a record.**

38 records carry `siblings`. Every one has **exactly 2** entries. **Not one** of the 76 sibling ids is
itself a record in the 110:

```
corne         shield  ['corne_left', 'corne_right']            both DANGLING
kyria_rev3    shield  ['kyria_rev3_left', 'kyria_rev3_right']  both DANGLING
glove80       board   ['glove80_lh', 'glove80_rh']             both DANGLING
adv360pro     board   ['adv360pro_left//zmk', 'adv360pro_right//zmk'] both DANGLING
…
```

They are **build targets** — the `-DSHIELD=corne_left` / `corne_right` arguments you pass to `west
build`, backed by `corne_left.overlay` / `corne_right.overlay` and the `SHIELD_CORNE_LEFT` /
`SHIELD_CORNE_RIGHT` symbols in `Kconfig.shield`. They are not hardware and not designs.

**So: the halves do not become two designs, and they do not become variants.** The record *is* the
design. `siblings` is metadata about how the firmware is built for it.

**Does it generalise? Yes, and tightly.** `siblings` is present iff the design is a split. Verified by
cross-checking against a completely different file — `ZMK_SPLIT default y` in each record's
`Kconfig.defconfig`:

```
siblings vs derived ZMK_SPLIT: 0 mismatches of 77
```

So `split` is derivable two independent ways that agree perfectly. Import `siblings` as
`split: bool` plus a `build_targets: Vec<String>` for provenance, and do **not** create rows for the
sibling ids.

The real Variant axis in ZMK is elsewhere: `revisions` / `default_revision` (10 records, e.g.
`mikoto//zmk` has `['5.20','6.1','6.3','7.2']` default `5.20`) and the alternate physical layouts of
§2.5. The schema also defines a `variants` property on boards and shields — **used by zero records**.

---

## 5. What ZMK does not carry

State these as `Unknown`, per the map's "honest ignorance beats false precision".

### 5.1 Switch footprint (Choc / MX) — absent

Not a field, not an enum, not a convention. A case-insensitive grep for `choc` across all of
`app/boards` and `app/dts` returns **4 files**, all `README.md` free prose:
`leeloo/README.md`, `leeloo_micro/README.md`, `clog/README.md`, `bfo9000/README.md`. `cherry` / `kailh`
returns 3, a subset of the same. That is 4 of 68 shields, in unstructured English.

Nothing in the devicetree distinguishes footprints — a Choc and an MX build of the same PCB have
identical firmware. **ZMK cannot supply this facet at any confidence.** It must come from the linked
URL (100% coverage, §6) or from the AI-verification path.

### 5.2 Thumb count — present as geometry, not as fact; do not derive it

`zmk,physical-layout` nodes carry exact per-key geometry:

```dts
        keys  //                     w   h    x    y     rot    rx    ry
            = <&key_physical_attrs 100 100    0   37       0     0     0>
            …
            , <&key_physical_attrs 100 150  448  283    2400   448   433>
            , <&key_physical_attrs 100 150  652  283  (-2400)  752   433>
```

That is real data, and it tempts. It should be resisted, for two reasons.

**Coverage.** `keys` is optional; only **41 of 77** records have it.

**The heuristic is wrong, and wrong in the expensive way.** A bottom-y-band rule was tested across all
41. It gives contradictory answers for the *same physical design*:

| id | keys | bottom-band count | implied thumbs/hand |
|---|---:|---:|---:|
| `a_dux` | 34 | 4 | 2 |
| `cradio` | 34 | 6 | 3 |
| `ferris//zmk` | 34 | 6 | 3 |
| `splitkb_aurora_sweep` | 34 | 6 | 3 |

These are all the same 34-key Ferris-derivative geometry. The band rule cannot separate a
column-staggered bottom row key from a thumb key. A rotation-based rule fails harder: `reviung41` has
`rot != 0` on **38 of 41** keys because the whole board is angled, and `reviung34` on 32 of 34.

**"Which keys are thumb keys" is a human judgement that ZMK does not encode.** Given the map's
"wrongness costs more than emptiness", record `thumb_count: Unknown` for all 77 and let the
`/wanted` pre-seed and AI-verification path fill it. This is the one place where a plausible-looking
derivation would have quietly poisoned the corpus's most distinctive facet.

**But the geometry is still valuable elsewhere.** `x, y, w, h, rot, rx, ry` for 41 records is exactly
what the deferred **SVG layout thumbnail** item in `map.md` needs, at higher fidelity than QMK's
`layouts[*].layout[]` (ZMK carries rotation; QMK does not). Worth noting against that fog item.

### 5.3 Buildability — absent

No PCB availability, no vendor stock, no gerbers, no BOM, no assembly difficulty, no SMD/through-hole
signal, no 3D-print requirement. ZMK is a firmware repo; a `*.zmk.yml` says a design *can be flashed*,
not that it *can be built*. Consistent with `cost_band` / `skill_band` being out of scope.

The nearest proxy is the URL host: `github.com` (41 of 110) reads as open-source-repo, while
`splitkb.com` (8), `boardsource.xyz` (5), `nicekeyboards.com` (4), `polarityworks.com` (4), `keeb.io`
(3), `mechwild.com` (3) read as purchasable products. That is a hint for a human, not a field.

### 5.4 Also absent

- **`description` and `manufacturer`** exist in the schema for boards and shields but are populated on
  **interconnects only** (5 of 5). Zero boards, zero shields. No prose for any design.
- **`version`** — defined for all three types, used by **zero** records.
- **`variants`** — defined for boards and shields, used by **zero** records.
- **`pointer`** — a valid `features` enum value, used by **zero** records.
- **`arch`** is `"arm"` for all 37 boards. No discriminating power.

---

## 6. Does `features` map onto our facets?

The enum is closed and short (`app/dts/…/hardware-metadata.schema.json`):

```json
"enum": ["keys", "display", "encoder", "underglow", "backlight", "pointer", "studio"]
```

Usage across 110 records: `keys` 77, `studio` 31, `encoder` 29, `display` 28, `underglow` 26,
`backlight` 5, `pointer` 0. 32 records have no `features` key at all — all bare controller boards and
the adapter/tester shields.

| Our facet | ZMK source | Verdict |
|---|---|---|
| `encoders` | `features: encoder` | Clean boolean. **No count** — `knob_goblin` has 2 encoders in its devicetree and one flag. Count is derivable from `alps,ec11` node count if wanted. |
| `addons` → RGB | `underglow` (26) vs `backlight` (5) | Cleaner than QMK's single `rgb`; keep both, don't merge |
| `display` | `features: display` | **Lossy — see below** |
| `keys` | presence marker | Use as the keyboard/not-keyboard discriminator, §7 |
| — | `studio` (31) | ZMK Studio (live keymap editing) support. No facet of ours. Genuinely interesting to buyers; worth considering. |

### `display` does not distinguish OLED from nice!view — and is inconsistent

The flagship query names nice!view specifically, so this matters.

`display` is a single boolean over "there is some display". Worse, it disagrees with the hardware in
9 of 32 cases:

- **4 records expose an OLED header but have no `display` feature**: `cradio`,
  `splitkb_aurora_corne`, `splitkb_aurora_lily58`, `splitkb_aurora_sweep`.
- **5 records have `display` but no OLED header**: integrated or non-I2C displays — `corneish_zen`,
  `romac_plus`, `tidbit`, `zmk_uno`, and `nice_view` itself.
- 23 have both.

**Do not use `features: display` for display facets.** Use the graph:

- *has an I2C OLED header* ⟸ `exposes: i2c_oled` (27 keyboards)
- *nice!view compatible* ⟸ a path exists to the `nice_view` shield, i.e. `exposes: i2c_oled` plus the
  `nice_view_adapter` hop (27 keyboards)
- *ships an integrated display* ⟸ `features: display` and **not** `exposes: i2c_oled`

And note the modelling consequence: **nice!view is a separate purchasable shield, not an attribute of
the keyboard.** The honest product answer to "nice!view?" is "this design accepts one", which is a
different sentence from "this design has one". Worth resolving in the spec before the filter rail
copy is written.

---

## 7. Corpus shape after import

Not all 68 shields are keyboards. Filter on the `keys` feature:

| | count |
|---|---:|
| Records | 110 |
| — interconnects | 5 |
| — shields | 68 → **63 keyboards** + 5 non-keyboards |
| — boards | 37 → **14 keyboards** + 23 bare controllers |
| **Designs to import** | **77** |
| of which split | 38 |
| of which wireless-capable | 72 |
| of which wireless **and** split | 38 |
| with a URL | 110 / 110 (**100%**) |
| with derivable key count | **77 / 77** |
| with key geometry | 41 / 77 |

The 5 non-keyboard shields (`nice_view`, `nice_view_adapter`, `tester_pro_micro`, `tester_rpi_pico`,
`tester_xiao`) are accessories and hardware-test fixtures. They belong in the `Hardware` /
`Connector` graph — `nice_view` is load-bearing for the flagship query — but must **not** appear as
designs in the catalogue. The 23 bare controller boards likewise: they are the compatibility graph's
other half, not products to browse.

Two numbers worth putting next to `DECISIONS.md` §5:

- **URL coverage is 100%** against QMK's 48%. The eligibility rule that disqualifies 52% of QMK
  records disqualifies **zero** ZMK records.
- **77 designs, zero identity work.** QMK's 3,748 upstream records collapse to ~1,100–1,500 designs
  via a per-family human judgement. ZMK's 110 records are already one-row-per-design: `siblings`
  handles the split halves, `revisions` handles board revisions, and the only near-duplicates are
  deliberate separate designs (`kyria` / `kyria_rev2` / `kyria_rev3`, `microdox` / `microdox_v2`,
  `leeloo` / `leeloo_rev2` / `leeloo_micro`). The expensive part of the QMK import does not exist here.

---

## 8. Answers to the ticket, in one line each

- **Interconnect graph** — modelled in full in §3. Directed DAG over 8 connector ids (5 declared,
  3 dangling); shields both require and expose; nice!view is a 3-hop chain. Do not flatten.
- **Key counts** — **derivable, 77/77, validated 77/77.** Reimplement `ZMK_KEYMAP_LEN` (§2.1). An
  afternoon, not a week. The dev's estimate of ~68 hand-entered counts can be dropped.
- **`siblings`** — build targets, never records, always exactly 2, present iff split. The record is
  the design; halves are neither designs nor variants. Generalises perfectly (0/77 mismatches).
- **Facets ZMK cannot supply** — switch footprint (§5.1), thumb count (§5.2), buildability (§5.3).
  All three must be `Unknown`.
- **`features` enum** — `encoder` / `underglow` / `backlight` map cleanly; **`display` is lossy and
  internally inconsistent** and must be replaced by graph reachability (§6); `studio` is a facet we
  don't have and might want.

## 9. Follow-ups for the spec

1. **Define `wireless` before the import lands** (§3.4). 72/77 in ZMK vs 23/3,748 in QMK is not one
   facet; it is two, and merging them silently is a wrongness bug of exactly the kind the map warns
   against.
2. **Decide how "nice!view compatible" renders** (§6). It is a compatibility path, not an attribute.
3. **Alternate physical layouts need a home** (§2.5) — 20 of 77 records have more than one, with
   ready-made `display-name` labels.
4. **ZMK key geometry is a better SVG-thumbnail source than QMK's** (§5.2) — carries rotation. Note
   against that fog item in `map.md`.
5. **`studio` (31 records)** is a real buyer-facing capability with no facet. Cheap to add, ZMK-only.
