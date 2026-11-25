#!/usr/bin/env python3
"""Can we tell which BUS and which VOLTAGE a display or trackpad slot is wired for?

Backlog #103. The question behind it is a configurator's: a reader picks a nice!nano,
a nice!view and a Cirque trackpad for one design, and the page has to say whether that
combination works. `Extension` (Design.kt) only records that a design has ROOM for a
display; a configurator also needs how that room is wired. This measures whether the
corpus can say so, before anyone designs a model that assumes it can.

TWO INDEPENDENT READINGS, and agreement between them is the check that can fail:

  * THE BOARD. Every net on a display or trackpad footprint's pads, plus each pad's
    `pinfunction`. `SDA`/`SCL` is I2C; `MOSI`/`SCK`/`CS`/... is SPI; both at once is
    a shared-pin connector (Stront's `MOSI/SCL`). A net named after the pad itself --
    `Net-(J2-Pad1)` -- says nothing, and is counted as such rather than guessed.
  * THE FIRMWARE. QMK's `OLED_ENABLE` (I2C unless `OLED_TRANSPORT = spi` -- QMK's own
    default), `QUANTUM_PAINTER_DRIVERS`, `POINTING_DEVICE_DRIVER`; ZMK's devicetree
    (`ssd1306` is I2C, `nice_view`/`sharp,ls0xx` is SPI).

THE DENOMINATOR IS ONLY DESIGNS WE ALREADY KNOW HAVE A SLOT -- `extras-scan.json`, which
proves presence and never absence. A slot the name scan cannot see is not in it.

THE VOLTAGE IS TRACED, NOT READ OFF THE NAME. The slot's power pad (by `pinfunction`,
else the pad that reaches a power-named net) is followed through every jumper footprint
to whatever SOURCES sit on its net, and the source decides, in this order:

  1. A dev-board socket pin -- its `pinfunction`, else the name of its net. On a Pro
     Micro that is `VCC` for every design here that reaches the socket, which is 5V
     under an ATmega and 3.3V under a nice!nano: the voltage is a join with the
     controller, not a fact of the slot. Two different pins reachable = a jumper choice.
  2. A regulator's OUTPUT pin. Its input pin is not a source, it is the upstream rail:
     Corne v4's OLED sits on `+5VR`, the regulator's input.
  3. A USB connector's VBUS pin, and only when no socket pin spoke: Stront's USB-C
     carries 3.3V between halves on the pin labelled VBUS.
  4. The net's own name, `+5V`/`+3V3`, for a soldered board where nothing above is found.

The name alone would have been wrong once: `lily58-lite-rev3` names the net `VCC` and
it lands on the RP2040-Zero's `5V` pin.

KICAD 8 INDENTS WITH TABS. A top-level block is `\\n(` after exactly two spaces or one
tab; a regex allowing "one or two of either" matched a footprint's own children and cut
every KiCad 8 footprint off at its first line. Seven designs read as "no nets" that way.

Usage:  measure_slot_buses.py [--cache DIR]      (board files fetched once, then cached)
"""
import argparse
import collections
import hashlib
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from attributed_boards import REPO, SCRATCH  # noqa: E402
import board_refs  # noqa: E402
import generated_boards  # noqa: E402
from measure_controller_socket import socket_of  # noqa: E402
from measure_extras import EXTRAS  # noqa: E402
import trees as tree_registry  # noqa: E402

KINDS = ("display", "trackpad")

TOP = re.compile(r"\n(?:  |\t)\(")
FOOTPRINT = re.compile(r'\n(?:  |\t)\((?:footprint|module) "?([^"\n]+?)"?[ \n\t]')
NET = re.compile(r'\(net (?:\d+ )?("(?:[^"\\]|\\.)*"|[^\s()]+)\)')
PINFUNCTION = re.compile(r'\(pinfunction "([^"]*)"\)')

# `SCL(?!K)` because `SCLK` is SPI's clock. Substring, not word-bounded: `oledSCL`.
I2C = re.compile(r"SDA|SCL(?!K)", re.I)
SPI = re.compile(r"(?<![A-Z0-9])(MOSI|MISO|SCLK|SCK|COPI|CIPO|SDI|SDO|N?CS|SS|DIN|CLK)(?![A-Z])", re.I)
POWER = re.compile(r"GND|VCC|VDD|3V3|3\.3V|5V|VBUS|RAW|\+", re.I)
V5 = re.compile(r"(?<![0-9.])5V|VBUS|\bRAW\b", re.I)
V33 = re.compile(r"3V3|3\.3V", re.I)
VCC = re.compile(r"\bVCC\b|\bVDD\b", re.I)


def blocks(text):
    for m in FOOTPRINT.finditer(text):
        nxt = TOP.search(text, m.end())
        yield m.group(1), text[m.start():nxt.start() if nxt else len(text)]


def kind_of(name):
    lib, _, bare = name.rpartition(":")
    for kind in KINDS:
        # The library fallback is measure_extras.py's, trackpad only, for the same reason.
        if EXTRAS[kind].search(bare) or (kind == "trackpad" and EXTRAS[kind].search(lib)):
            return kind
    return None


def board_verdict(nets):
    nets = [n.strip('"').upper() for n in nets if n.strip('"')]
    i2c, spi = any(I2C.search(n) for n in nets), any(SPI.search(n) for n in nets)
    signal = [n for n in nets if not POWER.search(n)]
    bus = ("i2c+spi" if i2c and spi else "i2c" if i2c else "spi" if spi
           else "unnamed" if signal else "no-signal-nets")
    power = ("5v" if any(V5.search(n) for n in nets) else "3v3" if any(V33.search(n) for n in nets)
             else "vcc" if any(VCC.search(n) for n in nets) else "unstated")
    return bus, power


REFERENCE = re.compile(r'\(property "Reference" "([^"]*)"|\(fp_text reference "?([^"\s)]*)')
PAD_NUMBER = re.compile(r'\(pad "?([^"\s]*)"?\s')
# A jumper joins nets; the union over its pads is "reachable under SOME setting". A
# socket is never a jumper, even when named like one (`ProMicro_jumpers`, Swoop).
JUMPER = re.compile(r"jumper|bridge|solder_?jump", re.I)
AUTO_NET = re.compile(r"^Net-\(|^unconnected-|^$", re.I)
POWER_NET = re.compile(r"VCC|VDD|3V3|3\.3V|5V|VBUS|RAW|VIN|\+", re.I)
GROUND = re.compile(r"GND|VSS", re.I)
POWER_PIN = re.compile(r"VCC|VDD|VIN|3V3|5V|\+?3\.3V|\+?5V|V\+", re.I)
# `MJ-4PP-9` is the TRRS jack, NOT USB, and matching it made every split read as USB-fed.
USB = re.compile(r"usb|type-?c|gct|hro|u4085|12402012", re.I)
REGULATOR = re.compile(r"regulat|ldo|ams1117|ap2112|xc6206|me6211|mcp1700|lm1117|sot-?223|"
                       r"sot-23-5|ht7333|rt9013|tlv7", re.I)


def footprints(text):
    """`(name, reference, [(pad, net, pinfunction)])` for every footprint on a board."""
    for name, blk in blocks(text):
        r = REFERENCE.search(blk)
        ref = ((r.group(1) or r.group(2)) if r else "") or ""
        starts = [m.start() for m in re.finditer(r"\(pad ", blk)] + [len(blk)]
        pads = []
        for a, b in zip(starts, starts[1:]):
            seg = blk[a:b]
            num, net, pf = PAD_NUMBER.match(seg), NET.search(seg), PINFUNCTION.search(seg)
            pads.append((num.group(1) if num else "?", net.group(1).strip('"') if net else "",
                         pf.group(1) if pf else ""))
        yield name, ref, pads


def pin_voltage(form_factor, identity):
    i = identity.upper()
    if re.search(r"RAW|VBUS|5V", i):
        return "5v"
    if re.search(r"3V3|3\.3", i):
        return "3v3"
    if re.search(r"VCC|VDD", i):
        return "controller" if form_factor == "pro_micro" else f"vcc-on-{form_factor}"
    return None


class Board:
    """One board's net graph: which nets a jumper joins, and which pads sit on each net."""

    def __init__(self, text):
        self.fps = list(footprints(text))
        self.adj, self.on_net = collections.defaultdict(set), collections.defaultdict(list)
        for name, ref, pads in self.fps:
            if not socket_of(name) and (JUMPER.search(name.rpartition(":")[2]) or re.match(r"JP\d", ref)):
                nets = {p[1] for p in pads if p[1]}
                for n in nets:
                    self.adj[n] |= nets - {n}
            for num, net, pf in pads:
                if net:
                    self.on_net[net].append((name, ref, num, pf))

    def reach(self, net):
        seen, todo = {net}, [net]
        while todo:
            for n in self.adj[todo.pop()] - seen:
                seen.add(n)
                todo.append(n)
        return seen

    def power(self, name, ref, pads):
        """The slot's power source, as one verdict string. See the module docstring."""
        cand = [p for p in pads if p[1] and POWER_PIN.fullmatch(p[2])]
        if not cand:
            cand = [p for p in pads if p[1] and any(
                POWER_NET.search(n) and not GROUND.search(n) and not AUTO_NET.search(n)
                for n in self.reach(p[1]))]
        if not cand:
            return "no power pad found"
        sockets, regulated, vbus, names = set(), False, False, set()
        for _, net, _ in cand:
            reached = self.reach(net)
            powered = {n for n in reached if POWER_NET.search(n) and not GROUND.search(n)
                       and not AUTO_NET.search(n)}
            names |= powered
            # A net that reaches the socket through a jumper can be named after the pin
            # (`PIN21`, avalanche): only power-named nets, else the pad's own net, count.
            for n in powered or {net}:
                for fname, fref, fnum, fpf in self.on_net[n]:
                    if fname == name and fref == ref:
                        continue
                    ff = socket_of(fname)
                    if ff:
                        v = pin_voltage(ff, fpf or n)
                        if v:
                            sockets.add(v)
                    elif USB.search(fname) and re.search("VBUS", fpf, re.I):
                        vbus = True
                    elif REGULATOR.search(fname) and re.search(r"^V?O(UT)?$|VOUT|OUTPUT", fpf, re.I):
                        regulated = True
        if len(sockets) > 1:
            return "selectable by jumper"
        if sockets:
            return {"controller": "Pro Micro VCC pin: follows the controller",
                    "3v3": "3.3V (socket pin)", "5v": "5V (socket pin)"}.get(*sockets, *sockets)
        if regulated:
            return "regulator output"
        if vbus:
            return "5V (USB VBUS)"
        named = {pin_voltage("none", n) for n in names} - {None}
        if named == {"5v"}:
            return "5V (net name only)"
        if named == {"3v3"}:
            return "3.3V (net name only)"
        return "unknown: net named VCC, no source found"


def firmware_files(design, trees, reg):
    """Text of the files a design's own firmware records live in."""
    out = []
    for fw, tree in zip(design.get("firmware") or [], trees):
        if tree not in reg or not reg.root(tree):
            continue
        reader, base = reg.reader(tree), pathlib.Path(reg.root(tree))
        rid = fw.get("path") if reader == "qmk" else fw.get("shield")
        if not rid:
            continue
        if reader == "qmk":
            # The record's own folder and every parent up to `keyboards/` -- QMK merges
            # rules.mk and config.h down that chain -- plus each level's default/vial
            # keymap. The keymaps usually sit ABOVE the revision: `marksard/rhymestone/
            # keymaps`, not `.../rev1/keymaps`, and reading only the record's own level
            # lost four designs.
            files, d = [], base/rid
            while d != base and base in d.parents:
                files += [f for f in d.iterdir() if f.is_file()]
                for km in ("default", "vial"):
                    files += list((d/"keymaps"/km).glob("*"))
                d = d.parent
        else:
            cands = [base/rid] + [p.parent for p in base.glob(f"**/{rid}.overlay")]
            d = next((c for c in cands if c.is_dir()), None)
            files = list(d.rglob("*")) if d else []
        out += [(reader, f.read_text(errors="replace")) for f in files
                if f.is_file() and f.stat().st_size < 400_000]
    return out


def firmware_verdict(kind, texts):
    hits = set()
    for reader, t in texts:
        if kind == "display":
            if re.search(r"OLED_TRANSPORT\s*=\s*spi", t, re.I):
                hits.add("spi")
            elif re.search(r'OLED_ENABLE\s*=\s*yes|"oled"\s*:\s*true|oled_task_(?:user|kb)', t, re.I):
                hits.add("i2c")
            for drivers in re.findall(r"QUANTUM_PAINTER_DRIVERS\s*\+?=\s*([\w ]+)", t):
                hits.update("spi" if "spi" in x else "i2c" if "i2c" in x else "unmapped" for x in drivers.split())
            if reader == "zmk":
                if re.search(r"nice_view|sharp,ls0|st7789|il0323|uc8151|gc9a01", t, re.I):
                    hits.add("spi")
                if re.search(r"ssd1306|sh1106", t, re.I):
                    hits.add("i2c")
        else:
            for m in re.findall(r"POINTING_DEVICE_DRIVER\s*=\s*(\w+)", t):
                hits.add("spi" if m.endswith("_spi") else "i2c" if m.endswith("_i2c") or "azoteq" in m else "unmapped")
            if reader == "zmk" and re.search(r"cirque,pinnacle", t, re.I):
                hits.add("spi" if re.search(r"&\w*spi\w*\s*\{[^}]*cirque", t, re.S)
                         else "i2c" if re.search(r"&\w*i2c\w*\s*\{[^}]*cirque", t, re.S) else "unmapped")
    return sorted(hits)


def fetch(cache, repo, path):
    if (text := generated_boards.read(repo, path)) is not None:
        return text
    # A pinned board's file is cached under its ref too, so pinning reads it afresh.
    pin = board_refs.ref(repo, path)
    key = f"{repo}\t{path}" + ("" if pin == "HEAD" else f"@{pin}")
    f = cache/(hashlib.sha1(key.encode()).hexdigest() + ".kicad_pcb")
    if not f.exists():
        url = board_refs.raw_url(repo, path)
        with urllib.request.urlopen(url, timeout=120) as r:
            f.write_bytes(r.read())
    return f.read_text(errors="replace")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", default=str(pathlib.Path.home()/".cache/keyboardbuilders/pcb"))
    ap.add_argument("--qmk", default=str(pathlib.Path.home()/"code/qmk_firmware"))
    ap.add_argument("--zmk", default=str(pathlib.Path.home()/"code/zmk"))
    args = ap.parse_args()
    cache = pathlib.Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)

    extras = json.loads((SCRATCH/"extras-scan.json").read_text())
    boards = json.loads((SCRATCH/"design-boards.json").read_text())
    trees = json.loads((SCRATCH/"design-trees.json").read_text())
    designs = {d["slug"]: d for d in json.loads((REPO/"src/main/resources/seed/designs.json").read_text())}
    reg = tree_registry.load(qmk=args.qmk, zmk=args.zmk)

    todo = sorted(s for s, kinds in extras.items()
                  if any(k in kinds for k in KINDS) and s in boards and s in designs)
    jobs = {(boards[s]["repo"], p) for s in todo for p in boards[s]["paths"]}
    with ThreadPoolExecutor(8) as ex:
        texts = dict(zip(jobs, ex.map(lambda j: fetch(cache, *j), jobs)))

    rows = []
    for slug in todo:
        fw_texts = firmware_files(designs[slug], trees.get(slug) or [], reg)
        for kind in (k for k in KINDS if k in extras[slug]):
            slots = []
            for path in boards[slug]["paths"]:
                board = Board(texts[(boards[slug]["repo"], path)])
                for name, ref, pads in board.fps:
                    if kind_of(name) != kind:
                        continue
                    nets = [f'"{p[1]}"' for p in pads if p[1]] + [f'"{p[2]}"' for p in pads if p[2]]
                    bus, by_name = board_verdict(nets)
                    # A footprint with no nets is an outline or a cutout (`Display_1.69inch`
                    # beside Stront's connector), not a slot, so it gets no power verdict.
                    power = board.power(name, ref, pads) if any(p[1] for p in pads) else None
                    slots.append({"footprint": name, "ref": ref, "bus": bus, "power": power,
                                  "power_by_name": by_name,
                                  "nets": sorted({p[1] for p in pads if p[1]})})
            rows.append({"slug": slug, "kind": kind, "board": slots,
                         "firmware": firmware_verdict(kind, fw_texts)})
    (SCRATCH/"slot-bus-scan.json").write_text(json.dumps(rows, indent=1) + "\n")

    for kind in KINDS:
        rs = [r for r in rows if r["kind"] == kind]
        cells, power, disagree = collections.Counter(), collections.Counter(), []
        for r in rs:
            board = {s["bus"] for s in r["board"] if s["bus"] in ("i2c", "spi", "i2c+spi")}
            fw = {f for f in r["firmware"] if f in ("i2c", "spi")}
            cells[("board" if board else "-", "firmware" if fw else "-")] += 1
            if board and fw and not fw <= {b for x in board for b in x.split("+")}:
                disagree.append(r["slug"])
            p = sorted({s["power"] for s in r["board"] if s["power"]})
            power[" + ".join(p) or "no board slot"] += 1
        print(f"{kind}: {len(rs)} designs")
        for (b, f), n in sorted(cells.items()):
            print(f"  bus from {b:5} + {f:8}: {n}")
        print(f"  both speak and disagree: {len(disagree)} {disagree}")
        print("  power, traced:")
        for v, n in power.most_common():
            print(f"    {n:3}  {v}")


if __name__ == "__main__":
    main()
