"""One-off facet-depth measurement, retained for reproducibility only.

BACKED A RESOLVED TICKET AND DOES NOT COMPOSE WITH THE CURRENT TOOLING. Read this
before running it, because it will otherwise mislead:

  * It expects **raw dumps** named `qmk.json` / `zmk.json` in the working directory,
    carrying full merged records -- `layouts` as a dict of layout objects (each with
    a `layout` array), plus `features`, `split`, `community_layouts`,
    `processor_type`, `type`. Those dumps were scratch artifacts and are NOT in the
    repo.
  * `scripts/build_qmk_corpus.py` output is **not a substitute**. It emits `layouts`
    as a sorted list of NAMES and `key_counts` as a separate mapping, and carries no
    `features` / `split` / `type` at all. Feeding it here yields zeros, not an error.
  * Its record counts predate the corrected record rule
    (ticket 28): the corpus is 3,690
    records, not 3,334, and per-record figures moved ~+11%.

Reproducing it means re-dumping both trees with the richer schema first. Left in place
rather than deleted so the numbers it produced remain traceable to something.
"""

import json, re, itertools, statistics, collections

D = json.load(open('qmk.json'))
recs = D['keyboards'] if isinstance(D, dict) and 'keyboards' in D else D
if isinstance(recs, dict):
    items = list(recs.items())
else:
    items = [(r.get('keyboard_name', str(i)), r) for i, r in enumerate(recs)]
print(f"records: {len(items)}")

SHORT = re.compile(r'split_(\d)x(\d)_(\d)')

def feat(r, name):
    f = r.get('features') or {}
    return bool(f.get(name))

def keycount(r):
    best = 0
    for lname, l in (r.get('layouts') or {}).items():
        n = len(l.get('layout') or [])
        best = max(best, n)
    return best

def thumbs(r):
    # community_layouts / layout names encode rows x cols + thumbs, e.g. split_3x6_3
    names = list((r.get('layouts') or {}).keys()) + list(r.get('community_layouts') or [])
    for n in names:
        m = SHORT.search(n)
        if m:
            return int(m.group(3))
    return None

rows = []
for kid, r in items:
    if not isinstance(r, dict):
        continue
    sp = r.get('split') or {}
    rows.append(dict(
        id=kid,
        split=bool(sp.get('enabled')),
        wireless=bool((r.get('features') or {}).get('bluetooth')) or 'nrf52' in str(r.get('processor','')).lower(),
        arm=(r.get('processor_type') == 'arm'),
        oled=feat(r, 'oled'),
        encoder=feat(r, 'encoder'),
        rgb=feat(r, 'rgb_matrix') or feat(r, 'rgblight'),
        keys=keycount(r),
        thumbs=thumbs(r),
        devboard=r.get('development_board'),
        url=bool(r.get('url')),
    ))
n = len(rows)

# --- individual facet prevalence ---
FACETS = {
    'split=yes':        lambda d: d['split'],
    'wireless=yes':     lambda d: d['wireless'],
    'oled=yes':         lambda d: d['oled'],
    'encoder=yes':      lambda d: d['encoder'],
    'rgb=yes':          lambda d: d['rgb'],
    'arm mcu':          lambda d: d['arm'],
    'keys<=49 (40%)':   lambda d: 0 < d['keys'] <= 49,
    'keys 36-48':       lambda d: 36 <= d['keys'] <= 48,
    '3 thumbs/hand':    lambda d: d['thumbs'] == 3,
    'has dev_board':    lambda d: bool(d['devboard']),
    'has url':          lambda d: d['url'],
}
print("\n=== facet prevalence (n=%d) ===" % n)
for k, f in FACETS.items():
    c = sum(1 for d in rows if f(d))
    print(f"  {k:20s} {c:5d}  {100*c/n:5.1f}%")

# --- zero-rate by filter depth over ALL combinations ---
print("\n=== result counts over all k-facet combinations (AND) ===")
print(f"{'depth':>5} {'combos':>7} {'zero':>6} {'zero%':>7} {'median':>7} {'p90':>6} {'<5 results':>11}")
keys = list(FACETS)
for k in range(1, 6):
    combos = list(itertools.combinations(keys, k))
    counts = []
    for cb in combos:
        fs = [FACETS[x] for x in cb]
        c = 0
        for d in rows:
            ok = True
            for f in fs:
                if not f(d):
                    ok = False
                    break
            if ok:
                c += 1
        counts.append(c)
    z = sum(1 for c in counts if c == 0)
    lt5 = sum(1 for c in counts if c < 5)
    counts_s = sorted(counts)
    p90 = counts_s[int(0.9*(len(counts_s)-1))]
    print(f"{k:5d} {len(combos):7d} {z:6d} {100*z/len(combos):6.1f}% {statistics.median(counts):7.1f} {p90:6d} {100*lt5/len(combos):10.1f}%")

# --- the flagship-shaped chain, added one at a time ---
print("\n=== cumulative AND, flagship-shaped chain ===")
chain = ['split=yes', 'wireless=yes', '3 thumbs/hand', 'oled=yes', 'encoder=yes']
sel = rows
for i, name in enumerate(chain, 1):
    f = FACETS[name]
    sel = [d for d in sel if f(d)]
    print(f"  depth {i}: + {name:18s} -> {len(sel):5d}")

# --- same chain restricted to the eligible-ish subset (has url) ---
print("\n=== same chain, restricted to records with a url (eligibility proxy) ===")
sel = [d for d in rows if d['url']]
print(f"  depth 0: has url            -> {len(sel):5d}")
for i, name in enumerate(chain, 1):
    f = FACETS[name]
    sel = [d for d in sel if f(d)]
    print(f"  depth {i}: + {name:18s} -> {len(sel):5d}")

# --- identity collapse check ---
print("\n=== identity ===")
tops = collections.Counter(str(k).split('/')[0] for k, _ in items)
print(f"  distinct top-level dirs: {len(tops)}")
print(f"  entries in subfolders:   {sum(1 for k,_ in items if '/' in str(k))}")
print(f"  handwired/:              {sum(1 for k,_ in items if str(k).startswith('handwired/'))}")

# --- ZMK ---
Z = json.load(open('zmk.json'))
zl = Z if isinstance(Z, list) else Z.get('records') or []
print(f"\n=== zmk: {len(zl)} records ===")
print("  types:", collections.Counter(r.get('type') for r in zl if isinstance(r, dict)))
fc = collections.Counter()
for r in zl:
    if isinstance(r, dict):
        for f in (r.get('features') or []):
            fc[f] += 1
print("  features:", dict(fc))
