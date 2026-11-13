#!/usr/bin/env python3
"""Derive a key count for every keyed record in ZMK's hardware metadata.

Reimplements ZMK's own ZMK_KEYMAP_LEN precedence (app/include/zmk/matrix.h) and
cross-checks each result against the independent `.keymap` layer binding count.
Findings: docs/research/zmk-corpus.md

Usage:
    mkdir -p /tmp/zmk-corpus && cd /tmp/zmk-corpus
    curl -sSLO https://zmk.dev/hardware-metadata.json
    git clone --depth 1 https://github.com/zmkfirmware/zmk.git zmk
    python3 <repo>/scripts/derive_zmk_key_counts.py /tmp/zmk-corpus

Expects <workdir>/hardware-metadata.json and <workdir>/zmk (a ZMK checkout).
Verified against commit aed236f53fc2e6377293b3c6bd4a2eb3d97dc763:
    keyed records 77 | derived 77 | keymap agrees 77 | disagrees 0

Handles the two devicetree traps that break naive parsing:
  * nodes may be unlabelled (`keymap { ... }`)
  * properties are frequently assigned via a node *reference* override
    (`&foostan_corne_6col_layout { transform = <&default_transform>; };`)
    rather than in the node's original definition.
"""
import json, os, re, glob, sys

HERE = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
ROOT = os.path.join(HERE, "zmk")
SEARCH = [os.path.join(ROOT, "app", "dts"),
          os.path.join(ROOT, "app", "include"),
          os.path.join(ROOT, "app", "module", "include")]
meta = json.load(open(os.path.join(HERE, "hardware-metadata.json")))


def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", " ", t, flags=re.S)
    return re.sub(r"//[^\n]*", " ", t)


def flatten(path, seen=None, depth=0):
    if seen is None:
        seen = set()
    rp = os.path.realpath(path)
    if rp in seen or depth > 15:
        return ""
    seen.add(rp)
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""
    out = []
    for line in text.splitlines():
        m = re.match(r'\s*#include\s*([<"])([^>"]+)[>"]', line)
        if m:
            inc, angle = m.group(2), m.group(1) == "<"
            cands = ([os.path.join(b, inc) for b in SEARCH] if angle
                     else [os.path.join(os.path.dirname(path), inc)])
            for c in cands:
                if os.path.isfile(c):
                    out.append(flatten(c, seen, depth + 1))
                    break
            continue
        out.append(line)
    return "\n".join(out)


HEAD = re.compile(r'(?:([A-Za-z0-9_]+)\s*:\s*)?(&?[A-Za-z0-9_@,.+-]+)\s*\{')


def parse_nodes(text):
    """Return list of (label, name, body). Brace-matched; includes nested nodes."""
    res = []
    for m in HEAD.finditer(text):
        i = m.end() - 1
        depth = 0
        for j in range(i, len(text)):
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
                if depth == 0:
                    res.append((m.group(1), m.group(2), text[i + 1:j]))
                    break
    return res


def prop(body, name):
    m = re.search(r'(?<![\w-])%s\s*=\s*(.*?);' % re.escape(name), body, re.S)
    return m.group(1) if m else None


def phandle(body, name):
    v = prop(body, name)
    if not v:
        return None
    m = re.search(r'&([A-Za-z0-9_]+)', v)
    return m.group(1) if m else None


def collect(text):
    """Merge node definitions with their `&label {}` reference overrides."""
    nodes = parse_nodes(text)
    merged = {}          # label -> concatenated body (definition first, overrides after)
    for lbl, name, body in nodes:
        if lbl:
            merged.setdefault(lbl, []).insert(0, body)
        elif name.startswith("&"):
            merged.setdefault(name[1:], []).append(body)
    # later text wins for a given property; search overrides first
    return {k: list(reversed(v)) for k, v in merged.items()}, nodes


def mprop(bodies, name):
    for b in bodies:
        v = prop(b, name)
        if v is not None:
            return v
    return None


def mphandle(bodies, name):
    for b in bodies:
        v = phandle(b, name)
        if v is not None:
            return v
    return None


def has_compat(bodies, compat):
    return any(re.search(r'compatible\s*=\s*"%s"' % re.escape(compat), b) for b in bodies)


def analyse(directory):
    d = os.path.join(ROOT, directory)
    srcs = sorted(glob.glob(os.path.join(d, "*.overlay")) +
                  glob.glob(os.path.join(d, "*.dts")))
    if not srcs:
        srcs = sorted(glob.glob(os.path.join(d, "*.dtsi")))

    best = None
    for f in srcs:
        t = strip_comments(flatten(f))
        merged, nodes = collect(t)
        transforms, layouts, kscans = {}, {}, {}
        for lbl, bodies in merged.items():
            if has_compat(bodies, "zmk,matrix-transform"):
                p = mprop(bodies, "map")
                if p:
                    transforms[lbl] = len(re.findall(r'\bRC\s*\(', p))
            if has_compat(bodies, "zmk,physical-layout"):
                ks = mprop(bodies, "keys")
                layouts[lbl] = dict(
                    transform=mphandle(bodies, "transform"),
                    nkeys=(len(re.findall(r'&key_physical_attrs', ks)) if ks else None),
                    keys_raw=ks,
                    name=(mprop(bodies, "display-name") or "").strip().strip('"'))
            for kind in ("matrix", "direct"):
                if has_compat(bodies, "zmk,kscan-gpio-" + kind):
                    kscans[lbl] = dict(
                        kind=kind,
                        rows=len(re.findall(r'&', mprop(bodies, "row-gpios") or "")),
                        cols=len(re.findall(r'&', mprop(bodies, "col-gpios") or "")),
                        inputs=len(re.findall(r'&', mprop(bodies, "input-gpios") or "")),
                        prows=mprop(bodies, "rows"), pcols=mprop(bodies, "columns"))
        chosen = {}
        for _, name, body in nodes:
            if name == "chosen":
                for k in ("zmk,physical-layout", "zmk,matrix-transform", "zmk,kscan"):
                    v = phandle(body, k)
                    if v:
                        chosen.setdefault(k, v)

        rule = count = None
        detail = ""
        if layouts and "zmk,matrix-transform" not in chosen:
            rule = "1-physical-layout"
            sizes = {lbl: transforms.get(l["transform"]) for lbl, l in layouts.items()}
            sel = chosen.get("zmk,physical-layout")
            if sel and sizes.get(sel):
                count, detail = sizes[sel], f"chosen {sel}"
            else:
                ok = [v for v in sizes.values() if v]
                if ok:
                    count = max(ok)
                    detail = "no chosen; max of " + ",".join(
                        f"{k}={v}" for k, v in sizes.items())
        elif "zmk,matrix-transform" in chosen:
            rule = "2-chosen-transform"
            count = transforms.get(chosen["zmk,matrix-transform"])
            detail = chosen["zmk,matrix-transform"]
        elif len(transforms) == 1:
            rule = "2b-sole-transform"
            (detail, count), = transforms.items()
        if count is None:
            ks = kscans.get(chosen.get("zmk,kscan")) or (
                next(iter(kscans.values())) if len(kscans) == 1 else None)
            if ks:
                rule = "3-kscan"
                if ks["kind"] == "direct":
                    count, detail = ks["inputs"], f"direct {ks['inputs']} input-gpios"
                elif ks["rows"] and ks["cols"]:
                    count = ks["rows"] * ks["cols"]
                    detail = f"matrix {ks['rows']}r x {ks['cols']}c"
                elif ks["prows"] and ks["pcols"]:
                    count = int(re.sub(r'\D', '', ks["prows"])) * \
                        int(re.sub(r'\D', '', ks["pcols"]))
                    detail = f"matrix props {ks['prows']}x{ks['pcols']}"
        cand = dict(rule=rule, count=count, detail=detail, transforms=transforms,
                    layouts=layouts, chosen=chosen, src=os.path.basename(f))
        # a split shield's halves each see the whole transform; take the richest view
        if best is None or (cand["count"] or 0) > (best["count"] or 0):
            best = cand
    return best or dict(rule=None, count=None, detail="no dts sources",
                        transforms={}, layouts={}, chosen={}, src=None)


def keymap_counts(directory):
    d = os.path.join(ROOT, directory)
    res = {}
    for f in sorted(glob.glob(os.path.join(d, "*.keymap"))):
        t = strip_comments(flatten(f))
        for lbl, name, body in parse_nodes(t):
            if re.search(r'compatible\s*=\s*"zmk,keymap"', body):
                for l2, n2, b2 in parse_nodes(body):
                    b = prop(b2, "bindings")
                    if b:
                        res.setdefault(os.path.basename(f), []).append(
                            (n2, len(re.findall(r'&', b))))
    return res


def split_flag(directory):
    p = os.path.join(ROOT, directory, "Kconfig.defconfig")
    if not os.path.isfile(p):
        return None
    t = open(p, encoding="utf-8", errors="replace").read()
    return bool(re.search(r'^\s*config\s+ZMK_SPLIT\s*$.*?^\s*default\s+y', t, re.M | re.S))


out = []
for rec in meta:
    if rec["type"] == "interconnect" or "keys" not in (rec.get("features") or []):
        continue
    a = analyse(rec["directory"])
    km = keymap_counts(rec["directory"])
    counts = sorted({n for v in km.values() for _, n in v})
    out.append(dict(id=rec["id"], type=rec["type"], dir=rec["directory"],
                    rule=a["rule"], count=a["count"], detail=a["detail"],
                    keymap_counts=counts, split=split_flag(rec["directory"]),
                    layouts={k: v["nkeys"] for k, v in a["layouts"].items()},
                    transforms=a["transforms"], chosen=a["chosen"]))

json.dump(out, open(os.path.join(HERE, "extract.json"), "w"), indent=1)

print(f"{'id':<30}{'rule':<19}{'cnt':>4}{'keymap layers':>16} {'ok':<3}{'spl':<6}detail")
agree = bad = nokm = nocount = 0
for r in sorted(out, key=lambda x: x["id"]):
    kc = r["keymap_counts"]
    if r["count"] is None:
        ok = "!!"; nocount += 1
    elif not kc:
        ok = "?"; nokm += 1
    elif kc == [r["count"]]:
        ok = "Y"; agree += 1
    elif r["count"] in kc:
        ok = "y~"; agree += 1
    else:
        ok = "N"; bad += 1
    print(f"{r['id']:<30}{r['rule'] or '-':<19}{str(r['count']):>4}"
          f"{','.join(map(str, kc)):>16} {ok:<3}{str(r['split']):<6}{r['detail'][:40]}")
print(f"\nkeyed records {len(out)} | derived {len(out)-nocount} | "
      f"keymap agrees {agree} | disagrees {bad} | no keymap {nokm} | no count {nocount}")
