# Controller catalogue: GPIO, logic voltage, firmware support

Research note for ticket 05, the controller catalogue data.

**Date:** 2026-08-01

---

## TL;DR

Two answers, and the second matters more than the first.

**1. On the data.** ~33 boards are covered below with a primary source per fact. The single most useful
number in the whole catalogue is not per-board at all: the **Pro Micro interconnect exposes exactly 18
GPIO**, confirmed identically by three independent firmware sources. Every Pro-Micro-footprint board in the
hobby inherits that 18.

**2. On the model.** A single `gpio_count: u8` on `ControllerBoard` is the wrong shape, and — more
importantly — **the compatibility graph does not need it.** ZMK already implements the exact bidirectional
query this product calls its headline feature, and it does so with **zero GPIO counts**, as a set
intersection on footprint IDs. See [§3.1](#31-zmk-already-implements-this-products-headline-feature) — that
is the most actionable finding here. GPIO counts answer a *different*, narrower question, and when they are
needed they need to be a small struct, not a scalar. See [§5](#5-the-shape-the-data-supports).

**2b. Corrected 2026-09-04.** This note originally read QMK's board-level support off the
`development_board` enum, which is a pin-naming convenience rather than the gate. QMK documents RP2040 as a
supported microcontroller and ships a generic RP2040 board target, so the Pico, Seeed XIAO RP2040 and QT Py
RP2040 run QMK — a mistake that told 16 live design pages the opposite. **The rules that survive are
MCU-family-level: `nRF52 ⇒ ¬QMK` and `AVR ⇒ ¬ZMK`.** See [§3.4](#34-correction-2026-09-04--a-converter-is-not-the-gate-on-qmk-support).

**3. On ADC.** Adding analog capability didn't just add a column — it found the one place the model is
genuinely asymmetric. GPIO count is a property of the **footprint** (every Pro Micro board exposes 18);
**ADC capability is not.** The same footprint delivers **9, 4, or 3** ADC channels depending on the silicon
behind it, because the footprint's `A0`–`A10` labels were inherited from the ATmega32U4 and are wrong
everywhere else. Worst case, it inverts the ranking: nice!nano is **18 GPIO / 3 ADC**, Seeed XIAO BLE is
**11 GPIO / 6 ADC** — so a single scalar of *either* kind orders those two boards backwards for one of the
two design classes. See [§2.5](#25-adc--and-the-asymmetry-that-matters-most). This is the finding that is
expensive to retrofit, and it is cheap to get right now.

---

## 1. Source tiers used

| Tier | Source | Why it is primary |
|---|---|---|
| 1 | **Firmware board definitions** — ZMK/Zephyr devicetree, QMK converter headers and schemas | These are the files the compiler reads. If a pin is not in the `gpio-map`, no firmware can drive it. They are also *the* authority on "which firmware supports this board": the file's existence **is** the support. |
| 2 | Vendor schematic / open-hardware repo / vendor docs | Owns the electrical facts (regulator, charger, jumpers, voltage). |
| 3 | Community cross-reference lists | **Not** used as a source of record. Used only to cross-check tier-1 numbers and to discover boards. Marked explicitly wherever it appears. |

No number below comes from a photo, a product listing, or a forum post.

> **A caution learned the hard way during this research.** An LLM summary of the nRFMicro pinout wiki
> reported "approximately 24 GPIO pins via Pro Micro-compatible headers". The raw wiki table says **18**.
> The summariser had counted table rows including `GND`, `RAW`, `VCC` and `RST`. Every count in this
> document was taken from raw source text, not from a summary of it. If this catalogue is ever populated by
> an AI verification pass (per the `field_verification` model in ticket 01), **pin counting is exactly the
> task that pass will silently get wrong**, and the error is in the expensive direction.

---

## 2. Footprints (interconnects) — the load-bearing fact

### 2.1 Pro Micro — 18 GPIO

Three independent primary sources agree exactly:

- ZMK / nice!nano: [`arduino_pro_micro_pins.dtsi`](https://github.com/zmkfirmware/zmk/blob/main/app/module/boards/nicekeyboards/nice_nano/arduino_pro_micro_pins.dtsi) — `gpio-map` has 18 entries.
- Zephyr upstream / SparkFun Pro Micro RP2040: [`sparkfun_pro_micro_connector.dtsi`](https://github.com/zephyrproject-rtos/zephyr/blob/main/boards/sparkfun/pro_micro_rp2040/sparkfun_pro_micro_connector.dtsi) — 18 entries.
- QMK: [`promicro_to_kb2040/_pin_defs.h`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_kb2040/_pin_defs.h) — 10 left side, 8 right side.

Pin labels in ZMK's Arduino naming: `D0 D1 D2 D3 D4 D5 D6 D7 D8 D9 D10 D14 D15 D16 D18 D19 D20 D21`
(note the gaps — no D11/D12/D13/D17). The same 18 in QMK's AVR naming: `D3 D2 D1 D0 D4 C6 D7 E6 B4 B5`
(left) and `F4 F5 F6 F7 B1 B3 B2 B6` (right). Non-GPIO pads: `RAW`, `GND` ×3, `RESET`, `VCC`.

The footprint is an abstraction over wildly different silicon. For pin `D1`, nice!nano routes `P0.17`,
nRFMicro routes `P0.15`, SparkFun PM2040 routes `GPIO0`, Blok routes `GPIO1`. Nothing about the MCU pin
numbering survives; only the interconnect position is stable. **That is exactly why the footprint, not the
chip, is the compatibility key.**

### 2.2 Pro Micro + 2 LED pins — the first conditional case

Every QMK Pro Micro converter defines **two pins beyond the 18**, named `D5` and `B0`. On a genuine
ATmega32U4 Pro Micro these are the onboard TX and RX LEDs. On the clones the converters remap them, and
QMK's own comments state the price:

| Converter | `D5` → | `B0` → | QMK's comment (verbatim) |
|---|---|---|---|
| [`promicro_to_kb2040`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_kb2040/_pin_defs.h) | `12U` | `13U` | `// LEDs (Mapped to QT connector to avoid collisions with button/neopixel)` |
| [`promicro_to_sparkfun_pm2040`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_sparkfun_pm2040/_pin_defs.h) | `17U` | `16U` | `// LEDs (Mapped to QT connector to avoid collisions with button/neopixel)` |
| [`promicro_to_bit_c_pro`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_bit_c_pro/_pin_defs.h) | `16U` | `17U` | `// LEDs (Mapped to R and G channel of the Bit-C PRO's RGB led)` |
| [`promicro_to_blok`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_blok/_pin_defs.h) | `12U` | `13U` | `// LEDs (Mapped to unused pins to avoid collisions)` |
| [`promicro_to_rp2040_ce`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_rp2040_ce/_pin_defs.h) | `12U` | `13U` | `// LEDs` |

Same footprint, same two pin names, **three different prices**: the RGB LED (Bit-C PRO), the Stemma QT /
Qwiic I²C connector (KB2040, SparkFun PM2040), or nothing at all (Blok). A scalar cannot express this.

### 2.3 Elite-C — 23 GPIO (Pro Micro + 5)

[`elite_c_to_rp2040_ce/_pin_defs.h`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/elite_c_to_rp2040_ce/_pin_defs.h)
lists the 18 Pro Micro pins plus a `// Bottom row` of five: `B7 D5 C7 F1 F0`.

Note `D5` is a **real exposed pin** on the Elite-C footprint, where on the Pro Micro footprint it is an LED.
The same pin name means a different thing depending on footprint — another reason footprint cannot be
flattened into a count.

QMK's schema encodes exactly two pin-compatibility classes
([`keyboard.jsonschema`](https://github.com/qmk/qmk_firmware/blob/master/data/schemas/keyboard.jsonschema)):
`"pin_compatible": { "enum": ["promicro", "elite_c"] }`.

### 2.4 Seeed XIAO — 11 GPIO

[`seeed_xiao_connector.dtsi`](https://github.com/zephyrproject-rtos/zephyr/blob/main/boards/seeed/xiao_ble/seeed_xiao_connector.dtsi)
— `gpio-map` has 11 entries, `D0`–`D10`.

This is the number that will actually save a newcomer money. A XIAO has **11** pins where a Pro Micro board
has **18**. A 6×4 split half needs 10 (6 cols + 4 rows) and just fits; add an encoder (2) and the budget is
gone. "It's a controller, it'll work" is how someone buys the wrong board.

### 2.5 ADC — and the asymmetry that matters most

Hall-effect and optical-switch designs don't scan a digital matrix; they read analog sensors, so their
scarce resource is **ADC-capable pins**, a much smaller and pin-restricted subset. Establishing this
produced the sharpest finding in the document.

**MCU-level facts** (all three primary-sourced):

| MCU | ADC channels | Fixed to which pins? | Resolution | Source |
|---|---|---|---|---|
| **ATmega32U4** | 12 | Yes — `F0 F1 F4 F5 F6 F7 D4 D6 D7 B4 B5 B6` | 10-bit | Datasheet p.2: *"12-channels, 10-bit ADC (features Differential Channels with Programmable Gain)"*; pin list from [QMK `platforms/avr/drivers/analog.c`](https://github.com/qmk/qmk_firmware/blob/master/platforms/avr/drivers/analog.c) |
| **nRF52840** | 8 (AIN0–AIN7) | Yes — `P0.02–P0.05`, `P0.28–P0.31` | *"8/10/12-bit resolution, 14-bit resolution with oversampling"* | [Nordic PS pin assignment tables](http://docs.nordicsemi.com/r/bundle/ps_nrf52840/page/pin.html) + [SAADC](http://docs.nordicsemi.com/r/bundle/ps_nrf52840/page/saadc.html) |
| **RP2040** | 4 external (+1 internal temp sensor) | Yes — `GPIO26–GPIO29` | 12-bit | [QMK `platforms/chibios/drivers/analog.c`](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/drivers/analog.c); Zephyr puts `die_temp` on `io-channels = <&adc 4>` |

QMK's RP2040 branch is unambiguous — the whole table is four lines:

```c
#elif defined(RP2040)
        case 26U: return TO_MUX(0, 0);
        case 27U: return TO_MUX(1, 0);
        case 28U: return TO_MUX(2, 0);
        case 29U: return TO_MUX(3, 0);
#endif
```

**Now the asymmetry.** GPIO count *is* a property of the footprint (§2.1): every Pro Micro board exposes 18,
whatever the silicon. **ADC capability is not.** The Pro Micro footprint labels nine of its pins with
Arduino analog names — `A0 A1 A2 A3 A6 A7 A8 A9 A10` — and those labels are carried verbatim in ZMK's and
Zephyr's devicetree comments. But the labels were inherited from the ATmega32U4, and on other silicon they
are simply **wrong**:

| Footprint pin | Label | ATmega32U4 Pro Micro | nice!nano (nRF52840) | RP2040 Pro Micro boards |
|---|---|---|---|---|
| D18 | A0 | `PF7` = ADC7 ✅ | `P1.15` ❌ | `GPIO26` = ADC0 ✅ |
| D19 | A1 | `PF6` = ADC6 ✅ | `P0.02` = AIN0 ✅ | `GPIO27` = ADC1 ✅ |
| D20 | A2 | `PF5` = ADC5 ✅ | `P0.29` = AIN5 ✅ | `GPIO28` = ADC2 ✅ |
| D21 | A3 | `PF4` = ADC4 ✅ | `P0.31` = AIN7 ✅ | `GPIO29` = ADC3 ✅ |
| D4 | A6 | `PD4` = ADC8 ✅ | `P0.22` ❌ | `GPIO4` ❌ |
| D6 | A7 | `PD7` = ADC10 ✅ | `P1.00` ❌ | `GPIO6` ❌ |
| D8 | A8 | `PB4` = ADC11 ✅ | `P1.04` ❌ | `GPIO8` ❌ |
| D9 | A9 | `PB5` = ADC12 ✅ | `P1.06` ❌ | `GPIO9` ❌ |
| D10 | A10 | `PB6` = ADC13 ✅ | `P0.09` ❌ | `GPIO21` ❌ |
| **ADC-capable** | *9 labelled* | **9** | **3** | **4** |

*(Derived by intersecting each board's `gpio-map` — §2.1 sources — with the MCU's fixed ADC pin list above.
Every input is primary; the intersection is arithmetic.)*

**The footprint promises nine analog pins and delivers 9, 3, or 4 depending on which board you plug in.**
A newcomer building a Hall-effect board reads "A0–A10" on the pinout, buys a nice!nano, and gets three
channels. That is the expensive direction, and it is exactly the failure the map warns about.

Consequence for the model: `gpio_pins` belongs on `Interconnect`; **`adc_channels` cannot**. It is a
property of the (board × footprint) pair. This is why ADC has to be captured now rather than retrofitted —
it does not live where GPIO lives.

**And it inverts the ranking.** Counting the XIAO footprint the same way against XIAO BLE's `xiao_d` map:
`D0`→`P0.02` (AIN0), `D1`→`P0.03` (AIN1), `D2`→`P0.28` (AIN4), `D3`→`P0.29` (AIN5), `D4`→`P0.04` (AIN2),
`D5`→`P0.05` (AIN3) — **6 ADC-capable of 11 pins.**

| Board | GPIO | ADC-capable |
|---|---|---|
| nice!nano | **18** | **3** |
| Seeed XIAO BLE | **11** | **6** |

For a Hall-effect design the XIAO BLE is **twice** the board the nice!nano is, while having 39% fewer GPIO.
**A single scalar — of either kind — ranks these two boards exactly backwards for one of the two design
classes.** No amount of care in choosing *which* integer to store fixes that; the budget is two-dimensional
because the design classes consume different kinds of pin.

Two further ADC-specific conditionals:

- **On XIAO BLE, `AIN7` (`P0.31`) is consumed by the battery divider** and `AIN6` (`P0.30`) is not brought
  out — so 6 of the chip's 8 channels reach the footprint, and the 6 available are the *remainder* after an
  onboard feature takes one ([ZMK dts](https://github.com/zmkfirmware/zmk/blob/main/app/boards/seeed/xiao_ble/xiao_ble_zmk.dts)).
- **The bad unbranded RP2040 Pro Micro clones lose exactly the ADC pins.** The community caution in §4.6
  reports "GPIO26–29 not working" — which is precisely and entirely the RP2040's four ADC channels. Those
  units are 18-GPIO boards with **zero** analog capability, indistinguishable in a listing.

Elite-C adds two more ADC pins than the Pro Micro footprint: its bottom row is `B7 D5 C7 F1 F0`, of which
`F0` (ADC0) and `F1` (ADC1) are in QMK's ATmega32U4 list — **11 ADC-capable of 23 GPIO**.

### 2.6 Full interconnect list

ZMK ships five, each a first-class catalogue entry with its own `id`, `name`, `url`, `manufacturer`
([`app/boards/interconnects/`](https://github.com/zmkfirmware/zmk/tree/main/app/boards/interconnects)):
`pro_micro` (18), `seeed_xiao` (11), `blackpill`, `arduino_uno`, `makerdiary_nrf52840_m2`. Pin counts for
the last three were not established — see [§6](#6-gaps).

Shields reference further interconnects that have no definition file of their own — Corne declares
`exposes: [i2c_oled]`, nice!view declares `requires: [nice_view_header]`. So the interconnect namespace is
**open**, not a closed enum.

---

## 3. How the firmwares model this — read before choosing a type

### 3.1 ZMK already implements this product's headline feature

This is the most important finding in the document. ZMK's
[`hardware-metadata.schema.json`](https://github.com/zmkfirmware/zmk/blob/main/schema/hardware-metadata.schema.json)
defines three mutually exclusive entity types (`board`, `shield`, `interconnect`) and links them with two
fields:

- **`board.exposes: [interconnect_id]`** — "this controller provides this footprint"
- **`shield.requires: [interconnect_id]`** — "this PCB needs this footprint"
- **`shield.exposes: [interconnect_id]`** — a PCB can *also* provide a footprint, for daughterboards

Real data ([`corne.zmk.yml`](https://github.com/zmkfirmware/zmk/blob/main/app/boards/shields/corne/corne.zmk.yml),
[`nice_nano.zmk.yml`](https://github.com/zmkfirmware/zmk/blob/main/app/boards/nicekeyboards/nice_nano/nice_nano.zmk.yml)):

```yaml
# shield
id: corne
requires: [pro_micro]
exposes: [i2c_oled]
features: [keys, display, underglow, studio]
```
```yaml
# board
id: nice_nano//zmk
exposes: [pro_micro]
outputs: [usb, ble]
revisions: ["1.0.0", "2.0.0"]
```

Both headline queries fall straight out, in both directions:

- *"What dev boards fit a Corne PCB?"* → `{b : b.exposes ∩ corne.requires ≠ ∅}`
- *"What can I build with my nice!nano?"* → `{s : s.requires ∩ nice_nano.exposes ≠ ∅}`

And it chains: Corne `exposes: [i2c_oled]`, so a display shield requiring `i2c_oled` composes on top. The
graph is multi-level.

**No GPIO count appears anywhere in this.** The ZMK board schema is closed
(`"additionalProperties": false`) and contains no pin-count field of any kind. Neither does QMK's. Two
firmwares that have solved this problem for thousands of users both concluded the compatibility relation is
a **set intersection on footprint IDs**, not a numeric budget comparison.

Four further details worth copying:

- **`exposes` is a list.** ZMK anticipated multi-footprint boards, and they exist: Elite-Pi, Helios,
  Liatris, Stemcell and the generic RP2040-CE each have **both** a `promicro_to_*` and an `elite_c_to_*`
  converter in QMK, i.e. they satisfy both footprints. A scalar `footprint: FootprintId` would be strictly
  narrower than reality.
- **`outputs: [usb | ble]`** is the radio fact — a capability set, not `has_radio: bool`.
- **`features: [keys | display | encoder | underglow | backlight | pointer | studio]`** — a closed enum,
  shared by boards and shields.
- **Controller vs. integrated keyboard is discriminated by structure, not a flag.** ZMK's `type: board`
  covers both a nice!nano and a Glove80. The discriminator: a detachable controller has `exposes` and no
  `keys` feature; an integrated keyboard has `features: [keys, …]` and no `exposes`. Verified against
  [`nice60.zmk.yml`](https://github.com/zmkfirmware/zmk/blob/main/app/boards/nicekeyboards/nice60/nice60.zmk.yml)
  (`features: [keys, underglow, studio]`, no `exposes`) versus every controller in §4. This catalogue must
  make the same distinction or it will offer to fit a Glove80 into a Corne.

### 3.2 QMK

`development_board` is a closed enum of **18** values
([`keyboard.jsonschema`](https://github.com/qmk/qmk_firmware/blob/master/data/schemas/keyboard.jsonschema)):

```
promicro, elite_c, elite_pi, proton_c, kb2040, promicro_rp2040, blok, michi,
bit_c_pro, stemcell, bluepill, blackpill_f401, blackpill_f411, bonsai_c4,
helios, liatris, imera, svlinky
```

Each maps to a processor/bootloader/board triple in
[`defaults.hjson`](https://github.com/qmk/qmk_firmware/blob/master/data/mappings/defaults.hjson). **Ten** of
them resolve to the identical `board: QMK_PM2040` — electrically interchangeable to QMK, differing only in
the converter's pin map.

QMK ships **21 converters** in
[`platforms/chibios/converters/`](https://github.com/qmk/qmk_firmware/tree/master/platforms/chibios/converters):
16 `promicro_to_*` and 5 `elite_c_to_*`. The AVR converter directory `platforms/avr/converters/` is
**empty** — you can convert away from AVR, never toward it.

Two mismatches worth knowing if the catalogue is keyed off `development_board`:

- `bonsai_c3` has a converter but is **not** in the `development_board` enum.
- The ticket notes `development_board` is present on only 649 of 3,748 QMK records. Confirmed as a
  structural limitation: the field is optional, and the enum omits every wireless board (no nice!nano, no
  nRFMicro) because QMK does not support them. **The catalogue cannot be derived from QMK alone** — QMK's
  board vocabulary and ZMK's are disjoint on exactly the wireless boards that make this product
  interesting.

### 3.3 The firmware-support constraint the ticket predicted

Confirmed verbatim from [ZMK's own FAQ](https://github.com/zmkfirmware/zmk/blob/main/docs/docs/faq.md), on
whether ZMK will support AVR:

> "Sorry, Zephyr™ only supports 32-bit and 64-bit platforms."

So `ATmega32U4 ⇒ ¬ZMK` is a hard, MCU-family-level rule, not a per-board fact. Note this means
`firmware_support` is partly **derivable** from `mcu`, and partly not (an RP2040 board supports QMK only if
someone wrote the converter). Storing it as a per-board list is right, but a consistency check against MCU
family would catch data-entry errors.

### 3.4 Correction (2026-09-04) — a converter is not the gate on QMK support

**The parenthetical above is wrong, and it put a false sentence on 16 live pages.** Owner-raised, from
QMK's own two documentation pages.

"An RP2040 board supports QMK only if someone wrote the converter" reads board support off the
`development_board` enum. That is the wrong question, and QMK says so twice:

- [`compatible_microcontrollers`](https://docs.qmk.fm/compatible_microcontrollers) lists **RP2040** as a
  supported microcontroller outright, under the general rule *"any USB-capable AVR or ARM microcontroller
  with enough flash space — generally 32kB+ for AVR, and 64kB+ for ARM."*
- [`platformdev_rp2040`](https://docs.qmk.fm/platformdev_rp2040) documents `BOARD = GENERIC_RP_RP2040`
  specifically for keyboards that *"do not wish to leverage the configuration matching the Generic Pro Micro
  RP2040 board"* and that *"[don't] provide any pre-configured pins or drivers"* — i.e. declare their own.

So a converter is a **pin-naming convenience**. The gate is the chip. Two rules survive at MCU-family level
and they are the ones worth keeping:

| Rule | Source |
|---|---|
| `nRF52 ⇒ ¬QMK` | `compatible_microcontrollers` lists no nRF52 and says nothing about Bluetooth anywhere. |
| `AVR 8-bit ⇒ ¬ZMK` | ZMK's FAQ, under the heading *"Does ZMK compile for AVR?"*: *"Sorry, Zephyr™ only supports 32-bit and 64-bit platforms."* |

> **Corrected 2026-09-10.** This table previously attributed a longer sentence to ZMK —
> *"ZMK doesn't support boards with AVR 8-bit processors, such as the ATmega32U4, because
> Zephyr™ only supports 32-bit and 64-bit platforms."* **That sentence is not in
> `faq.md`.** Re-fetched from
> [the source](https://raw.githubusercontent.com/zmkfirmware/zmk/main/docs/docs/faq.md):
> the question is the heading and the answer is the nine words above. §3.3 of this
> document had it right all along; only this table drifted, and the drift was copied into
> `ControllerFit.kt` on 2026-09-09 labelled "verbatim". Both are fixed. Exactly
> `project_labels_dont_match_measurements`, one document over — a paraphrase that reads
> like a quote is worse than a paraphrase that admits it is one.

**Corrected:** `Raspberry Pi Pico`, `Seeed XIAO RP2040` and `Adafruit QT Py RP2040` gain `qmk` in
`seed/controllers.toml`. Before the fix, 8 designs socketing `rpi_pico` (including `piantor`, `junco` and
`sofle-pico` — QMK keyboards on a Pico) and 8 socketing `seeed_xiao` were each told that none of the
catalogued boards run their firmware. After it, **no corpus design is in that state**.

**Not corrected, and it is the control:** `Seeeduino XIAO` is SAMD21, which appears nowhere in QMK's
supported list, so it stays ZMK-only. This was a chip-driven fix, not a blanket edit.

**ZMK needed nothing in the same pass**, and that asymmetry is the interesting part.
[`zmk.dev/docs/hardware`](https://zmk.dev/docs/hardware) claims a far wider reach than nRF52 — *"including
but not limited to Nordic nRF52, Raspberry Pi RP2040/RP2350, most ST STM32 MCUs, and Microchip SAMD21"* —
but Zephyr needs a board definition to build at all, so for ZMK the supported-hardware list **is** the set
of boards, and every board on it that this catalogue models already carried `zmk`. `Bluepill (STM32F103)`
is absent from that list and stays QMK-only for exactly that reason. **QMK's board-level support is broader
than its enum; ZMK's is not broader than its list.** That is why the same reasoning corrects one column and
not the other.

> **One more summariser caution, in the spirit of §1's.** A fetch of ZMK's hardware page grouped
> `MechWild PillBug` under a heading reading "BlackPill Interconnect Boards (Wired)", contradicting this
> document's own `Radio: BLE`. The document was right: the board's own definition is
> [`pillbug_nrf52840_zmk.dts`](https://github.com/zmkfirmware/zmk/tree/main/app/boards/mechwild/pillbug)
> with `outputs: [usb, ble]` — an **nRF52840** on the BlackPill footprint. The group label describes the
> interconnect family, not the board's radio. Nothing in the catalogue changed; the check is recorded
> because it is the second time a summary of a hardware page has been wrong in this file's history.

---

## 4. Per-board findings

**Logic voltage:** every board below is **3.3 V logic** except where noted. The one genuine fork in the
catalogue is the ATmega32U4 Pro Micro (§4.1). RP2040 and nRF52840 boards are 3.3 V without exception in this
set.

### 4.1 The Pro Micro voltage fork — the one real 3.3 V/5 V hazard

The ATmega32U4 Pro Micro is sold in **two electrically different variants**: 5 V/16 MHz and 3.3 V/8 MHz.
Vendor product titles confirm both are current stock, e.g. Keebio's
["Pro Micro (USB-C Version) - 5V/16MHz"](https://keeb.io/products/pro-micro-usb-c-version-5v-16mhz-arduino-compatible-atmega32u4).

The clock/voltage coupling is **datasheet-backed**, not folklore — the Atmel ATmega16U4/32U4 datasheet
(p. 2) states the maximum frequency verbatim as:

> "8MHz at 2.7V - Industrial range"
> "16MHz at 4.5V - Industrial range"

with "Operating Voltages: 2.7 - 5.5V". A 3.3 V Pro Micro genuinely cannot run 16 MHz within spec, so the two
variants are not interchangeable.

**This is the field's reason to exist.** A 5 V Pro Micro driving a 3.3 V I²C OLED is the classic beginner
mistake, and it is invisible in every listing that just says "Pro Micro". If `ControllerBoard` carries a
voltage field at all, "Pro Micro" must be **two rows or one row with `Unknown` voltage** — never one row
confidently asserting 5 V or 3.3 V.

### 4.2 Pro Micro footprint, wireless (nRF52)

| Board | GPIO | Radio | Charger | Firmware | Primary source |
|---|---|---|---|---|---|
| **nice!nano v2** | 18 (footprint) | BLE | Yes | ZMK; **not QMK** | [ZMK board def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/nicekeyboards/nice_nano/nice_nano.zmk.yml) + [dts](https://github.com/zmkfirmware/zmk/blob/main/app/module/boards/nicekeyboards/nice_nano/nice_nano.dts) |
| **nice!nano v1** | 18 (footprint) | BLE | Yes | ZMK | [v1 overlay](https://github.com/zmkfirmware/zmk/blob/main/app/boards/nicekeyboards/nice_nano/nice_nano_nrf52840_zmk_1_0_0.overlay) |
| **nRFMicro 1.1–1.4** (nRF52840) | 18 + up to 10 extra, **revision-dependent** | BLE | Yes | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/joric/nrfmicro/nrfmicro_nrf52840.zmk.yml) + [vendor wiki](https://github.com/joric/nrfmicro/wiki/Pinout) |
| **nRFMicro** (nRF52833) | 18 + extras | BLE | Yes | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/joric/nrfmicro/nrfmicro_nrf52833.zmk.yml) |
| **Puchi-BLE V1** | 18 (footprint) | BLE | — | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/keycapsss/puchi_ble/puchi_ble.zmk.yml) |
| **Mikoto** (rev 5.20/6.1/6.3/7.2) | 18 (footprint) | BLE | — | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/zhiayang/mikoto/mikoto.zmk.yml) |
| **BlueMicro840 v1** | 18 (footprint) | BLE | — | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/jpconstantineau/bluemicro840/bluemicro840.zmk.yml) |

**nice!nano — the clean case, and it is clean for a non-obvious reason.** Its onboard features cost **zero**
connector pins, because they were deliberately routed off-footprint:

- Blue LED on `P0.15` — not on the connector ([`nice_nano.dts`](https://github.com/zmkfirmware/zmk/blob/main/app/module/boards/nicekeyboards/nice_nano/nice_nano.dts)).
- `EXT_POWER` control on `P0.13` — not on the connector.
- Battery sensing: **v1** uses ADC channel 2 (`P0.04`, off-connector); **v2** uses
  `compatible = "zmk,battery-nrf-vddh"`, the nRF52840's *internal* high-voltage divider, consuming **no pin
  at all**. Two revisions of one board, two different battery mechanisms.

So nice!nano gives you all 18. That is a fact worth storing, and it is *not* inferable from "it has a
charger and a battery" — on other designs those features do cost pins.

**nRFMicro — the case that kills the scalar outright.** Its
[wiki pinout](https://github.com/joric/nrfmicro/wiki/Pinout) is the vendor's own primary documentation. The
Pro Micro table yields the standard 18. Beyond that, verbatim:

> "These pins are available on the back side via through hole pads: P1.04, P1.02, P1.00, P0.22, P0.12,
> **P0.26 (free since 1.2)**, **P0.07 (free since 1.3)**"

> "These pins are free on the top side of the module: P0.00 (XL1), P0.01 (XL2), **P0.05 (free since 1.4)**"

> "So there are 10 free pins (including top side). **Revision 1.4 may use P0.00 and P0.01 with the Crystal
> (you can desolder it).**"

Four distinct ways the number is conditional, all in one board:

1. **Revision.** `P0.26` free since 1.2, `P0.07` since 1.3, `P0.05` since 1.4. The board named "nRFMicro"
   has a different free-pin count depending on which one you own.
2. **Onboard feature.** `P0.05` is the charger's `PROG_PIN` on revs 1.2–1.3; `P0.07` is the charger's
   `STAT_PIN` on rev 1.2. These pins are *the charger* on those revisions.
3. **Physical modification.** `P0.00`/`P0.01` are free on rev 1.4 only if you **desolder the crystal**.
4. **Pin capability, not availability.** Two separate restrictions:
   > "The underside unused pin P0.12 (apparently) cannot be used for I2C which has pull-down resistors."

   > "You're not supposed to use low drive pins for I2C protocol and RGB data."

   The wiki then lists 27 normal-drive and 21 low-drive nRF52840 pins, citing the
   [Nordic nRF52840 Product Specification](https://infocenter.nordicsemi.com/pdf/nRF52840_PS_v1.1.pdf) p.576.

Point 4 is the deepest problem for any budget model: **pins are not fungible.** A design needing an I²C
OLED needs *high-drive* pins specifically. "You need 12, the board has 18, therefore compatible" can still
be wrong.

### 4.3 Pro Micro / Elite-C footprint, RP2040

| Board | GPIO (vendor claim) | Footprints | Firmware | Primary source |
|---|---|---|---|---|
| **Elite-Pi** | "Up to 25 I/O pins" | promicro **+** elite_c | QMK, KMK, CircuitPython | [Keebio guide](https://github.com/keebio/keebio-docs/blob/master/docs/elite-pi-guide.md) + [QMK converters](https://github.com/qmk/qmk_firmware/tree/master/platforms/chibios/converters) |
| **Liatris** | 23 GPIO | promicro + elite_c | QMK | [splitkb docs](https://docs.splitkb.com/product-guides/liatris/pinout) + QMK converters |
| **0xCB Helios** | 18 + 5 bottom + 2 top + 1 inset (5 V) | promicro + elite_c | QMK | [Open hardware repo](https://github.com/0xCB-dev/0xCB-Helios), [OSHWA DE000131](https://certification.oshwa.org/de000131.html), [schematic PDF](https://github.com/0xCB-dev/0xcb-Helios/blob/main/rev1.0/helios.pdf) |
| **Blok** | 18 (footprint), 0 extra | promicro | QMK, **ZMK** | [Zephyr dtsi](https://github.com/zmkfirmware/zephyr/blob/v4.1.0%2Bzmk-fixes/boards/boardsource/blok/sparkfun_pro_micro.dtsi) + [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/boardsource/blok/boardsource_blok.zmk.yml) |
| **SparkFun Pro Micro RP2040** | 18 (footprint) | promicro | QMK, **ZMK** | [Zephyr upstream](https://github.com/zephyrproject-rtos/zephyr/blob/main/boards/sparkfun/pro_micro_rp2040/sparkfun_pro_micro_connector.dtsi) |
| **Adafruit KB2040** | 18 (footprint) + 2 on QT | promicro | QMK, **ZMK** | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/adafruit/kb2040/adafruit_kb2040.zmk.yml) + QMK converter |
| **Bit-C PRO** | 18 + 5 bottom | promicro | QMK | [QMK converter](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/promicro_to_bit_c_pro/_pin_defs.h) |
| **Stemcell** (STM32F411) | — | promicro + elite_c | QMK | QMK converters + `defaults.hjson` |
| **RP2040-CE** (generic clone) | 18 / 23 | promicro + elite_c | QMK | [QMK converter](https://github.com/qmk/qmk_firmware/blob/master/platforms/chibios/converters/elite_c_to_rp2040_ce/_pin_defs.h) |
| **Michi, Imera, Svlinky, Bonsai C4** | — | promicro | QMK | `development_board` enum + converters |
| **Bonsai C3** | — | promicro | QMK (converter only) | Converter exists, **absent from the enum** |
| **Sea-Picro EXT** | 23 (Elite-C pinout) + 5 V RGB pin | elite_c | QMK | [Open hardware repo](https://github.com/joshajohnson/sea-picro) |
| **Sea-Picro RST** | 18 (Pro Micro pinout) | promicro | QMK | [Open hardware repo](https://github.com/joshajohnson/sea-picro) |
| **Splinky (0xB2)** | 18 + 5 bottom (`GPIO12..16`) | promicro (+ Elite-C-style extras) | QMK | [Open hardware repo](https://github.com/Bastardkb/Splinky) |
| **RP Micro** | 18 + "5+2" | promicro (+ Elite-C-style extras) | QMK | [Open hardware repo](https://github.com/siderakb/rp-micro) |

**Sea-Picro — one product name, two footprints.** Verbatim from the vendor's repo:

> "There are two variants:
> - **EXT**, featuring an Elite-C pinout, with dedicated 5V RGB LED pin, but **without a reset button**.
> - **RST**, featuring a pro micro pinout, with onboard RGB LED and reset button."

This is not a revision difference — both variants ship concurrently. A catalogue row keyed on the name
"Sea-Picro" would have to assert one footprint and would be wrong half the time. It also shows the
feature/pin trade-off made *at manufacture*: the EXT buys 5 extra IO and a 5 V level-shifted RGB pin by
giving up the reset button.

**Splinky and RP Micro — "Elite-C style" is not a standard.** Splinky documents "Pro-micro / Sparkfun RP2040
compatible footprint, with 5 extra pins at bottom (Elite-C style)", with `GPIO17` as a user LED that can be
"turned OFF" and repurposed, and `GPIO19` consumed by USB VBUS detection. RP Micro documents "additional 5+2
pins (Elite-C and Elite-Pi style, **but the pinout not identical to Elite-Pi**)".

Two boards both advertising "Elite-C style" extra pins, explicitly incompatible with each other. **The
extra-pin block is not itself an interconnect.** Only the base 18 is reliably standard.

RP Micro also independently reinvents the Elite-Pi conditional, crediting it:

> "Select whether the 3rd and 4th pads in the left column are GND or GP10/GP11 by using solder bridge jumper
> `SB1` and `SB2`, the default is GND. To access GP10 or GP11, you need to **cut the track** between the
> solder bridge and GND pad… Great idea from Elite-Pi."

**A third footprint exists that neither firmware names.** Splinky's README:

> "This pinout is a _de facto_ standard, established by actors of the MK community (see discussions in
> `#RP2040` on the bastardkb Discord server)"

and Sea-Picro's changelog: "V0.3 … changing the pinout to conform to the **'BastardKB RP2040 Pinout'**".

So there is a real, adopted interconnect — the BastardKB RP2040 pinout — that appears in **neither** QMK's
`pin_compatible` enum (which has exactly two values) nor ZMK's interconnect directory. Combined with the
dangling `i2c_oled` and `nice_view_header` IDs from §2.6, this settles it: **`InterconnectId` must be an
open, extensible namespace, not a closed enum derived from either firmware.**

**Elite-Pi — vendor-documented conditional pins.** Keebio's own guide says "Number of available pins: **Up
to** 25 I/O pins" — the vendor hedges in the spec line itself. Then, verbatim:

> "If you would like to access D10 and/or D11 via the pads on the side of the Elite-Pi, you will need to
> **cut the traces connecting GND to those pads**, as by default, the side pads are connected to GND to
> match the normal Pro Micro pinout."

Two of the 25 require permanently modifying the board. "25" and "23" are both defensible; neither is
correct unconditionally.

The same guide also states a **pairing** constraint that is not a per-board property at all:

> "No, you can not mix the Elite-Pi with a non-RP2040 controller like the Pro Micro or Elite-C. However,
> another RP2040 controller can be used with it."

For a split keyboard the two halves must share an MCU family. That is a constraint on a *pair* of boards —
it cannot live in a per-board field, and any "will this work" answer for a split build needs it.

**Liatris — voltage that is neither 3.3 V nor 5 V.** splitkb's documentation states the VCC pin outputs 5 V
by default while all other pins tolerate up to 3.6 V, and that the board runs at 3.6 V for better 5 V signal
compatibility, with jumpers to select VCC voltage and to enable/disable the RAW pin. *Sourcing caveat: the
`docs.splitkb.com` pinout page renders its specifications as an image, so the numeric pinout could not be
read from text. The 3.6 V figure comes from splitkb's product pages and is corroborated by a community list
(§4.6), but I could not confirm it from a schematic. Treat as **vendor-claimed, unverified**.*

Liatris alone breaks a `logic_voltage: enum { V3_3, V5 }` field. The honest type needs a third state, and
"is it 3.3 V-safe" is a *derived predicate*, not the stored value.

### 4.4 Other footprints

| Board | GPIO | Footprint | Radio | Firmware | Primary source |
|---|---|---|---|---|---|
| **Seeed XIAO nRF52840 (XIAO BLE)** | 11 | seeed_xiao | BLE | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/seeed/xiao_ble/xiao_ble.zmk.yml), [Zephyr connector](https://github.com/zephyrproject-rtos/zephyr/blob/main/boards/seeed/xiao_ble/seeed_xiao_connector.dtsi) |
| **Seeed XIAO RP2040** | 11 | seeed_xiao | No (USB only) | **QMK**, ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/seeed/xiao_rp2040/xiao_rp2040.zmk.yml); QMK via RP2040 + [§3.4](#34-correction-2026-09-04--a-converter-is-not-the-gate-on-qmk-support) |
| **Seeeduino XIAO** (SAMD21) | 11 | seeed_xiao | No | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/seeed/seeeduino_xiao/seeeduino_xiao.zmk.yml) |
| **Adafruit QT Py RP2040** | 11 + 2 (QT) | seeed_xiao | No | **QMK**, ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/adafruit/qt_py_rp2040/adafruit_qt_py_rp2040.zmk.yml); QMK via RP2040 + [§3.4](#34-correction-2026-09-04--a-converter-is-not-the-gate-on-qmk-support) |
| **Raspberry Pi Pico** | 26 | *(none — not a Pro Micro)* | No | **QMK**, ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/raspberrypi/rpi_pico/rpi_pico.zmk.yml); QMK via RP2040 + [§3.4](#34-correction-2026-09-04--a-converter-is-not-the-gate-on-qmk-support) |
| **MechWild PillBug** | — | blackpill | BLE | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/mechwild/pillbug/pillbug.zmk.yml) |
| **Makerdiary nRF52840 M.2** | — | makerdiary_nrf52840_m2 | BLE | ZMK | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/makerdiary/nrf52840_m2/nrf52840_m2.zmk.yml) |
| **QMK Proton C** (STM32F303) | — | promicro | No | QMK, **ZMK** | [ZMK def](https://github.com/zmkfirmware/zmk/blob/main/app/boards/qmk/proton_c/proton_c.zmk.yml) + QMK |
| **WeAct Blackpill F401CC / F401CE / F411CE** | — | blackpill | No | QMK, **ZMK** | [ZMK defs](https://github.com/zmkfirmware/zmk/tree/main/app/boards/weact) + QMK enum |
| **Bluepill (STM32F103)** | — | — | No | QMK | `development_board` enum |

**XIAO BLE's battery sense costs no connector pin either** — it uses `&adc 7` (`P0.31`) and
`power-gpios = <&gpio0 14>` ([ZMK dts](https://github.com/zmkfirmware/zmk/blob/main/app/boards/seeed/xiao_ble/xiao_ble_zmk.dts)),
and neither `P0.31` nor `P0.14` is in the 11-pin `xiao_d` map. Same pattern as nice!nano: the good designs
route onboard features off-footprint. Worth recording *because* it is not universal.

### 4.5 Not detachable controllers — integrated keyboard boards

ZMK's `type: board` also covers complete keyboards, which must not appear as controller options:
nice!60, Corne-ish Zen, Kinesis Adv360 Pro, MoErgo Glove80, OLKB Planck/Preonic, Ferris,
PolarityWorks BT60/BT60-HS/BT65/BT75, KBDFans Tofu65, MechWild S40NC, Keebio BDN9, and the Nordic
nRF52840-DK / nRF5340-DK developer kits. Discriminated as described in §3.1.

### 4.6 Cross-check against the community list (not a source of record)

[`bgkendall/keyboard_mcu_list`](https://github.com/bgkendall/keyboard_mcu_list) is the most careful
community catalogue of RP2040 keyboard controllers. Used here only to (a) cross-check tier-1 numbers and (b)
discover boards. Three of its notes independently confirm firmware-source findings:

- "Blok has GPIO 16 and 17 instead of 2 and 3" — matches the Zephyr Blok dtsi exactly (`D2`→`P0.16`,
  `D3`→`P0.17`). ✅
- "KB2040 has GPIO 10, 18 and 19 instead of 21, 22 and 23" — matches QMK's `promicro_to_kb2040` header
  (`B6`→`10U`, `B1`→`18U`, `B2`→`19U`) against `promicro_to_rp2040_ce` (`21U`, `22U`, `23U`). ✅
- XIAO RP2040 "11" — matches the Zephyr `seeed_xiao` connector. ✅

**Its most interesting property is structural.** Its GPIO column is headed *"Extra GPIO"* and its values are
**prose, not numbers**:

```
5 bottom
5 bottom, 2 top, 1 inset (5V)
0; see note (1) below
5 bottom; 2—see note (2) below
11, 2 (QT)
20, 9 (pads)
```

A careful human curator working on precisely this problem concluded the field could not be an integer.
Its notes add two more conditional mechanisms not seen above:

- "Frood has **jumpers** that allow ground pins 4 and 5 to be used as GPIO."
- Its legend defines a `5V` annotation meaning "the output signal is at 5V rather than 3.3V, which means
  that RGB LEDs can be driven at spec" — so a pin's *voltage* can vary within one board (Helios's inset
  pin).

And one finding that matters directly for the ticket's "RP2040 Pro Micro (several clones)" row:

> "**CAUTION**: There are some versions of this board that have incorrectly placed components that result
> in **GPIO26–29 not working**."

**The unbranded "RP2040 Pro Micro" is not a well-defined catalogue entity.** Units sold under that name
differ in whether four of their pins function. A row asserting any GPIO count for it would be wrong for
some buyers. This should be `Unknown`, or the row should not exist.

---

## 5. The shape the data supports

### 5.1 First: the graph does not need GPIO at all

The strongest recommendation in this document is to **decouple the compatibility graph from the GPIO
budget**. They are different features with different data requirements and very different confidence
levels:

| | Compatibility graph | GPIO budget |
|---|---|---|
| Question | "Does this controller fit this PCB?" | "Can I also fit an OLED and two encoders?" / "Can it read 60 Hall sensors?" |
| Relation | Set intersection on footprint IDs | Numeric comparison, conditional, **two-dimensional** (digital + ADC) |
| Data needed | `exposes` / `requires` — one list each | Per-board, per-revision, per-feature, per-pin-kind |
| Confidence | **High** — mirrors ZMK's own data | **Low** — see all of §4 |
| Sourceable today? | Yes, directly from ZMK | Only partially |

Both headline queries — "what dev boards fit a Corne PCB" and "what can I build with the nice!nano in my
drawer" — are answered **entirely by the first column**. Blocking the differentiating feature on GPIO data
that is genuinely ambiguous would be a self-inflicted wound. Ship the footprint graph; treat GPIO as a
separate, later, lower-confidence enrichment.

### 5.2 If a GPIO budget is still wanted, this is the honest shape

`gpio_count: u8` fails on five independent grounds, each evidenced above:

| # | Failure | Evidence |
|---|---|---|
| 1 | GPIO belongs to the **footprint**, not the board or MCU | nRF52840 has 48 pins; nice!nano exposes 18 (§2.1) |
| 2 | Boards expose pins **beyond** the footprint | Elite-C +5, Helios +8, nRFMicro +10 (§2.3, §4.2–4.3) |
| 3 | Some pins cost an **onboard feature** | Bit-C PRO's RGB, KB2040's QT connector (§2.2) |
| 4 | Some pins need **physical modification** | Elite-Pi trace cutting, nRFMicro crystal desoldering, Frood jumpers (§4.3, §4.6) |
| 5 | The count is **revision-dependent** | nRFMicro 1.2/1.3/1.4 (§4.2) |
| 6 | One **product name** can be several boards | Sea-Picro EXT (23) vs RST (18); "RP2040 Pro Micro" clones (§4.3, §4.6) |
| 7 | Different **design classes consume different kinds of pin** — and a scalar ranks boards backwards | nice!nano 18 GPIO / 3 ADC vs XIAO BLE 11 GPIO / 6 ADC (§2.5) |

And an eighth that breaks any count, however structured: **pins are not fungible** (§4.2, point 4).
Low-drive nRF52840 pins cannot carry I²C or RGB data — and per Nordic's pin tables the analog-capable pins
are drawn from that same restricted group, so the ADC subset and the "good for I²C" subset overlap and
compete.

A type the data actually supports:

```rust
/// GPIO count is a property of the interconnect. ADC capability is NOT — see §2.5.
struct Interconnect { id: InterconnectId, gpio_pins: u8, /* … */ }

struct ControllerBoard {
    /// A list. Elite-Pi, Liatris, Helios, Stemcell and RP2040-CE each expose two.
    exposes: Vec<InterconnectId>,
    /// Pads beyond the interconnect, unconditionally usable.
    extra_free_pins: Unknown<u8>,
    /// Each entry: how many pins, and what you give up.
    conditional_pins: Vec<ConditionalPins>,
    /// Per (board × footprint) — cannot be hoisted to Interconnect.
    /// nice!nano 3, XIAO BLE 6, RP2040 Pro Micro 4, AVR Pro Micro 9.
    adc: Unknown<AdcBudget>,
    /// nice!nano v1 vs v2 differ in battery mechanism; nRFMicro 1.2/1.3/1.4 in free pins.
    revision: Option<Revision>,
}
// Sea-Picro EXT and RST ship concurrently under one name with different footprints,
// so concurrent variants are a separate axis from sequential revisions.

struct AdcBudget {
    /// ADC-capable pins reachable on the exposed footprint, after onboard features.
    channels_on_footprint: u8,
    /// Every MCU in this catalogue fixes ADC to specific pins; none is freely muxable.
    pins_are_fixed: bool,
    resolution_bits: Unknown<u8>,
}

struct ConditionalPins { count: u8, cost: PinCost }

enum PinCost {
    OnboardFeature(FeatureId),   // Bit-C PRO: RGB LED channels; XIAO BLE: AIN7 to battery
    Connector(ConnectorId),      // KB2040: Stemma QT
    PhysicalModification(String) // Elite-Pi: cut traces; nRFMicro: desolder crystal
}
```

The load-bearing points, independent of syntax:

1. **`gpio_pins` lives on `Interconnect`.** One authoritative 18 for `pro_micro`, one 11 for `seeed_xiao`,
   one 23 for `elite_c` — sourced once, from the firmware, correct for every board that exposes it. This
   alone removes most of the per-board sourcing burden and most of the error surface.
2. **`adc` must live on the board, not the interconnect** — the one asymmetry in the whole model, and the
   reason this is a breaking change if deferred. Same footprint, 9 / 4 / 3 channels (§2.5).
3. **The budget is two-dimensional, because design classes are.** Matrix designs spend digital pins;
   Hall-effect and optical designs spend ADC channels. One scalar cannot order boards correctly for both,
   and on the nice!nano/XIAO BLE pair it orders them *inversely*.
4. **`exposes` is a `Vec`, not a scalar.** Five boards in this catalogue expose two footprints.
5. **Conditional pins are a list of (count, cost) pairs, not a number.** This is the field that makes the
   answer *honest*: "23 pins, or 25 if you cut two traces" is a true and useful sentence; "25" is a lie that
   costs someone a rebuild.
6. **Everything is `Unknown`-able.** Per the map's "honest ignorance beats false precision", `Unknown` must
   be distinct from zero. `extra_free_pins: Unknown<0>` (Blok, genuinely none) and
   `extra_free_pins: Unknown` (Supermini, nobody knows) are different claims. Likewise a board with **zero**
   ADC channels reaching its footprint is a real, useful answer — not a missing one.
7. **Revision is part of board identity**, not a display string. ZMK models it (`revisions`,
   `default_revision`) because the electrical facts differ between revisions of one product name.

**On cost of delay.** The team lead's framing is right and the data supports it more strongly than
expected. Adding `adc` later is not a column addition: because ADC does not live where GPIO lives, a
compatibility graph built on a per-interconnect scalar has the wrong *shape*, not merely a missing field.
Getting it right now is cheap; §2.5 is one table.

### 5.3 Voltage

`logic_voltage: enum { V3_3, V5 }` is also too narrow:

- Liatris runs at **3.6 V** (§4.3) — neither variant.
- Helios has a **single inset pin at 5 V** while the rest are 3.3 V (§4.6) — voltage is per-pin, not
  per-board.
- "Pro Micro" names **two** boards at different voltages (§4.1).

Store the nominal logic voltage as a millivolt value or an open enum admitting `Unknown`, and derive
"3.3 V-safe" as a predicate rather than storing it. The 5 V/3.3 V Pro Micro fork is the highest-value single
fact in the voltage column and should be modelled as two rows.

### 5.4 Firmware support

`outputs`-style capability sets work well. Two structural notes:

- `ATmega32U4 ⇒ ¬ZMK` is derivable from MCU family (§3.3); most other entries are not. Store the list, but
  add a consistency check against MCU family to catch data-entry errors.
- QMK's and ZMK's board vocabularies are **disjoint on wireless boards**. Neither firmware's list is a
  superset. The catalogue must union them, and `Unknown` is the correct value for "no board definition
  found in either" — not `No`.

---

## 6. Gaps

Boards named in the ticket for which **no primary source was found**. Listed rather than silently omitted.

| Board | Status |
|---|---|
| **Supermini nRF52840** | **No primary source found.** No vendor datasheet, no schematic, no ZMK or QMK board definition. Sold unbranded by multiple sellers, so "the" Supermini may not be one board. Recommend `Unknown` for every field, or exclusion. |
| **Puchi-C** | Named in the ticket; ZMK ships **Puchi-BLE V1** (keycapsss). Possibly the same product under a different name, possibly two products. **Unresolved — do not merge them without checking.** |
| **Bit-C** (non-PRO) | Only **Bit-C PRO** (RP2040) is in QMK's enum. The original AVR Bit-C was not separately sourced; nullbits' docs repo returned 404 at the path tried. |
| **Frood RP2040** | Community list only (marked open hardware, 42.Keebs). Not in ZMK or QMK's enum. |
| Interconnect pin counts: `blackpill`, `arduino_uno`, `makerdiary_nrf52840_m2` | Not established. Each needs the same treatment §2.1 gave `pro_micro`; all three have ZMK definitions, so the data is available. |
| Extra-pad counts for Liatris, Bit-C PRO, Stemcell, Michi, Imera, Svlinky, Bonsai C4 | QMK confirms footprint and MCU; per-board pad counts not sourced. |
| Charger presence | Only reliably established for nice!nano and nRFMicro (both from firmware/vendor sources). Not sourced for most others — this column is mostly `Unknown` today. |
| **ADC for STM32 boards** (Proton C, Blackpill F401/F411, Bluepill, Stemcell, Bonsai C4) | **Not established.** QMK's ChibiOS driver shows STM32 parts have many ADC channels across up to 4 ADC units with per-pin mapping far more complex than the three MCUs in §2.5 (e.g. STM32F3XX maps 30+ pins, some to multiple ADCs). Deliberately not resolved — these boards are rare in the corpus and the per-footprint intersection would need doing board by board. Mark `Unknown`. |
| **ADC for SAMD21** (Seeeduino XIAO) | Not established; not covered by QMK's driver (ZMK/Zephyr board only). |
| ADC resolution as a *usable* figure | The nRF52840 SAADC is configurable — *"8/10/12-bit resolution, 14-bit resolution with oversampling"* — so "resolution" is a range the firmware picks, not a board constant. QMK's ChibiOS driver samples RP2040 at 12-bit and scales down (`// we still sample at 12bit, but scale down to the requested bit range`). Store the hardware maximum, and treat any single number as approximate. |

### Recommended next steps

1. **Build the footprint graph first.** ZMK's `exposes`/`requires` data is machine-readable, complete, and
   directly importable. It delivers both headline queries with no ambiguity.
2. **Extract the four remaining interconnect pin counts** from ZMK. Small, bounded, high-value — it is the
   denominator for every board sharing that footprint.
3. **Treat per-board GPIO as a later enrichment**, defaulting to `Unknown`, and never let a missing GPIO
   value suppress a board from a footprint-match result.
4. **Reserve the ADC field's shape now even if the column stays empty.** Per the team lead's note, almost no
   eligible open-source Hall-effect designs exist today, so the *values* can wait. The *shape* cannot: §2.5
   shows ADC hangs off the board rather than the interconnect, which is a structural difference, not a
   column. The four boards in §2.5's table are already done and are the four that matter.
4. **Do not use an AI pass to count pins** without a schema that forces a citation to a `gpio-map` or
   `_pin_defs.h` and a verbatim quote. See the caution in §1 — this specific task fails quietly.
