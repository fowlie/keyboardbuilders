#!/usr/bin/env python3
"""Draft `seed/sources.toml` -- which repo holds the hardware for each firmware entry.

Resolved by the ZMK<->QMK identity mapping (ticket 14):
a firmware entry does not name a counterpart in the other tree, it names **the source its hardware
lives in**. Two entries resolving to the same source are the same design; the pairing is computed,
never authored. This drafts the ZMK side of that file for a human to confirm.

The QMK side is drafted too, since 2026-08-20. It was withheld while *which QMK records enter the
corpus* was open; the repoless gate (ticket 21)
answered it, and the QMK record rule (ticket 28) fixed the
record rule the population is counted under.

**QMK rows are drafted only for records that name a hardware repo.** A record with no url is not drafted
at all: ~95% of repo-less records fail the publication gate on `switch_footprint` alone, so a
`source = false` row for each would be a census of designs nobody will publish -- the same argument
`declines.toml` is an override file rather than a census. ZMK drafts all 63 because 63 is the whole tree.

**Every QMK row carries the owner-match falsifier**, because
out-of-corpus credits (ticket 22) measured QMK's `url`
failing as a source pointer four ways: absent, a vendor page, two records sharing one url, and worst, a
record citing its *ancestor's* repo as its own (`handwired/dactyl_lightcycle` -> `adereth/dactyl-keyboard`).
The falsifier is the identity mapping (ticket 14) finding
3's: does the url's owner match the record's own `manufacturer`/`maintainer`? Rows that fail it are drafted
with the mismatch in `note`, so a human's eye lands on them first. Rows sharing a repo with another record
are flagged the same way -- that is a `which one is the design` pick only a human makes.

**A fourth signal, `qmk-readme`, since 2026-09-03** (ticket 56).
A record with no usable `url` often names its hardware repo anyway, in the `Hardware Availability:` line
its own readme carries -- the same block this file's sibling reads for ticket 33's
`[controller]` decline. `README_REPOS` is a static table, like `DEV_SEED_PATHS`, because turning it into
a live crawl would mean fetching every candidate repo's licence and boards on every draft; it is built by
[`scan_readme_sources.py`](scan_readme_sources.py)`--probe`, and every entry in it already cleared the
corpus's own gate -- a recognised licence, a board that places a switch and names a controller, not a
fork. Refresh it by re-running the probe when QMK's tree moves. **Drafted like everything else, never
confirmed by this script**: a readme is machine-read and first-party, which earned it the gate check, not
a skip of the human ruling ticket 26 asks for.

Usage:  draft_sources.py <zmk-checkout> <qmk-corpus.json> [--toml] [--tree zmk|qmk|both]

    <zmk-checkout>      a ZMK clone; shields are read from app/boards/shields/
    <qmk-corpus.json>   output of build_qmk_corpus.py
    --toml              emit the seed rows instead of the summary
    --tree              which side to draft (default both)
"""

import argparse
import json
import os
import pathlib
import re
import sys
from urllib.parse import unquote
from collections import defaultdict

# A shield that does not declare `keys` is not a keyboard. ZMK ships displays
# (nice_view) and hardware diagnostics (tester_*) as shields too. Cross-checked
# against "ships a .keymap": the two signals agree on 65 of 68, and where they
# differ -- the three testers, which do ship a keymap -- `features` is right.
KEYBOARD_FEATURE = "keys"

# A github repo that is a *firmware* tree is not a hardware source. qmk/qmk_firmware
# alone is cited by 34 QMK records, so admitting it would fuse 34 unrelated designs
# into one. Same failure as the owner-level match this ticket already rejected, one
# level down.
FIRMWARE_TREE = re.compile(
    r"qmk[_-]?firmware|zmk[_-]?config|vial-?qmk|tmk_keyboard|/qmk$", re.I
)

QMK_ROOT = os.path.join(os.path.expanduser(os.environ.get("QMK_DIR", "~/code/qmk_firmware")), "keyboards")

# Records whose tree states a SHOP where the hardware repo is known to a human.
# Ticket 26: `signal = "dev-seed"` -- the credential is this commit, per ticket 10's
# `ImportSource::DevSeed(file, row, commit)`. Every other row in the seed records
# what a tree stated; these record that a tree was wrong and a human knew better,
# so the table stays short and each line has to earn its trust. Rows still draft as
# `drafted`: `signal` says who spoke, `verdict` says whether anyone has ruled, and
# ticket 36 made those separate axes.
#
# `handwired/scottokeebs/*` -- all 25 records cite `https://scottokeebs.com`, a shop.
# The hardware is `joe-scotto/scottokeebs`: 39 design folders, each with .shapr CAD,
# STEP/STL case and plate, a kle.json and a readme. All 25 record names match a
# folder exactly (`scotto108` -> `Scotto108`), so `path` derives; the 14 folders with
# no QMK record are a fan-out question and cannot live in this file, which is keyed
# by firmware entry.
# The folder names are RECORDED, not computed: `ScottoCMD`, `ScottoAlp` and
# `ScottoMacrodeck` case irregularly and github paths are case-sensitive, so a
# transform would be a guess. A dev seed is a written-down human fact by
# definition, and writing them out keeps this script reproducible offline.
DEV_SEED_REPO = "joe-scotto/scottokeebs"
DEV_SEED_PATHS = {
    "handwired/scottokeebs/scotto108": "Scotto108",
    "handwired/scottokeebs/scotto34": "Scotto34",
    "handwired/scottokeebs/scotto36": "Scotto36",
    "handwired/scottokeebs/scotto37": "Scotto37",
    "handwired/scottokeebs/scotto40": "Scotto40",
    "handwired/scottokeebs/scotto61": "Scotto61",
    "handwired/scottokeebs/scotto9": "Scotto9",
    "handwired/scottokeebs/scottoalp": "ScottoAlp",
    "handwired/scottokeebs/scottocmd": "ScottoCMD",
    "handwired/scottokeebs/scottodeck": "ScottoDeck",
    "handwired/scottokeebs/scottoergo": "ScottoErgo",
    "handwired/scottokeebs/scottofly": "ScottoFly",
    "handwired/scottokeebs/scottofrog": "ScottoFrog",
    "handwired/scottokeebs/scottogame": "ScottoGame",
    "handwired/scottokeebs/scottohazard": "ScottoHazard",
    "handwired/scottokeebs/scottoinvader": "ScottoInvader",
    "handwired/scottokeebs/scottokatana": "ScottoKatana",
    "handwired/scottokeebs/scottolong": "ScottoLong",
    "handwired/scottokeebs/scottomacrodeck": "ScottoMacrodeck",
    "handwired/scottokeebs/scottomouse": "ScottoMouse",
    "handwired/scottokeebs/scottonum": "ScottoNum",
    "handwired/scottokeebs/scottoslant": "ScottoSlant",
    "handwired/scottokeebs/scottosplit": "ScottoSplit",
    "handwired/scottokeebs/scottostarter": "ScottoStarter",
    "handwired/scottokeebs/scottowing": "ScottoWing",
}


# From `scan_readme_sources.py --probe`, priced 2026-09-03: 46 repos, over 86 candidates,
# that clear the corpus's own publication gate (a recognised root licence; a board that
# places a switch and names a controller; not a fork). **45 repos and 62 records land
# here, not 46 and 72** -- the gate checks a repo, never a record, and `rgbkb/pan` (a
# whole repo excluded) and `Salicylic-acid3/PCB_Data` (8 of 14 records excluded, repo
# kept) are the two shapes where that is not enough; see below.
#
# KEYED BY THE RECORD THAT CAN ACTUALLY BE DRAFTED, not by wherever the readme happened
# to sit. `build_qmk_corpus.py`'s own record rule is "a directory holding `keyboard.json`",
# and 8 of the readmes this table's evidence came from live at a PARENT directory that
# holds only `info.json` -- `bastardkb/dilemma`, `jiran` and six more. That parent is not
# a buildable record at all, so its evidence is pushed down to every leaf beneath it:
# `bastardkb/dilemma`'s one readme names one repo for FOUR real records (`3x5_2/assembled`,
# `3x5_2/promicro`, `3x5_3`, `4x6_4`). Caught by cross-checking this table against a fresh
# `build_qmk_corpus.py` run rather than trusting the first pass -- 15 of 65 records
# silently vanished as "not in current QMK tree" before this fix.
#
# `rgbkb/pan` -- BOTH RECORDS -- IS EXCLUDED, for a reason the automated gate could not
# see. Its readme's Hardware Availability line is
# `github.com/RGBKB/Keyboard-files/tree/master/Pan`, a SUBDIRECTORY the extraction regex
# in `scan_readme_sources.py` discards (it captures `owner/repo` only, unlike
# `repo_of()` below, which keeps the path for exactly this reason). The repo is a
# multi-design monorepo -- BANANA, Mun, Mun2, Zen -- and the gate's "N boards" count was
# THEIRS, not Pan's: `Pan/` itself ships zero `.kicad_pcb`, only gerbers, DXF plates and
# firmware binaries. Found 2026-09-03 by the owner asking about this repo during review;
# checked every other repo's readme for the same dropped-subdirectory shape and found
# two more (`ph-design/PH60`, `pinky`'s parent) where it does NOT matter -- PH60's link
# names a superseded revision path but every board under the CURRENT one is still PH60's
# own hardware, and `pinky`'s split is handled below on its own terms.
#
# `pinky` is EXCLUDED on purpose, for the opposite reason: its readme names BOTH
# `tamanishi/Pinky3` and `tamanishi/Pinky4`, because IT is the parent of two sub-boards
# and its own leaves (`pinky/3`, `pinky/4`) each already name their own repo
# unambiguously. Pushing the parent's evidence down here would be a guess between two
# repos where the leaves already answer the question exactly.
#
# `Salicylic-acid3/PCB_Data` HAD 14 RECORDS; SIX SURVIVE. It is one author's personal
# hardware dump, not one keyboard's revisions -- unlike every other multi-record repo in
# this table (`Bastardkb/Dilemma`'s `3x5_2`/`3x5_3`/`4x6_4`, `evyd13/atom47`'s
# `rev2`..`rev5`), whose suffixes are plainly ONE keyboard's variants, this repo's 14
# record suffixes are 14 UNRELATED keyboard names sharing only the author's QMK
# maintainer folder. Every QMK readme in that folder cites the same repo regardless of
# whether that specific design ever made it in.
#
# Checked each record's own "Hardware Supported" name against the repo's real top-level
# folders (`7Nano`, `AJisai74`, `BeThirty`, `Getta25`, `GoForty`, `Guide68`, `J67G`,
# `KeebMicro-C`, `KeyFuda01`, `Kleine Gherkin`, `Nafuda`, `Naked48LED`, `Naked60BMP`,
# `Setta21`, `Tray by KS Japan`) rather than trusting the repo-level gate, which only
# asked "does this repo have a usable board anywhere" and could not have caught this.
# SEVEN have no matching folder at all -- `7skb`, `7splus`, `ergoarrows` (whose own
# readme claims `nknl7` PCB, which also does not exist here), `jisplit89`, `naked64`,
# `nknl7en`, `nknl7jp`. An EIGHTH, `naked48`, is worse than absent: its own readme
# claims `"Naked60BMP PCB"` -- the same hardware `naked60`'s readme claims -- almost
# certainly a copy-paste error upstream rather than two designs sharing one board.
# `Naked48LED` exists and is the obvious guess, but it is a guess: nothing states that
# folder is `naked48`'s, only that its name resembles the record's own. Removed rather
# than assigned either way. Found 2026-09-03, owner-prompted, while reviewing this exact
# repo for the Spaceboards/Pan shape and finding a third one instead.
README_REPOS = {
    'abacus': 'nickolaij/Abacus_Rev2',
    'adkb96/rev1': 'bit-trade-one/ADKB96-hardware',
    'ai03/duet': 'ai03-2725/duet-switch-tester',
    'aidansmithdotdev/fine40': 'Aidan-OS/Mochi40',
    'aidansmithdotdev/sango': 'Aidan-OS/Sango',
    'archerkeyboard/desire65': 'akyp/desire65-pcb',
    'bastardkb/dilemma/3x5_2/assembled': 'Bastardkb/Dilemma',
    'bastardkb/dilemma/3x5_2/promicro': 'Bastardkb/Dilemma',
    'bastardkb/dilemma/3x5_3': 'Bastardkb/Dilemma',
    'bastardkb/dilemma/4x6_4': 'Bastardkb/Dilemma',
    'biacco42/meishi2': 'Biacco42/meishi2',
    'dk60': 'Dbroqua/DK60',
    'enviousdesign/60f': 'Envious-Data/Env-KB60F',
    'enviousdesign/65m': 'Envious-Data/Env-KB65M',
    'enviousdesign/delirium/rev0': 'Envious-Data/Env-KB',
    'enviousdesign/delirium/rev1': 'Envious-Data/Env-KB',
    'enviousdesign/delirium/rgb': 'Envious-Data/Env-KB',
    'enviousdesign/mcro/rev1': 'Envious-Data/Env-MCRO',
    'evyd13/atom47/rev2': 'evyd13/atom47',
    'evyd13/atom47/rev3': 'evyd13/atom47',
    'evyd13/atom47/rev4': 'evyd13/atom47',
    'evyd13/atom47/rev5': 'evyd13/atom47',
    'evyd13/fin_pad': 'evyd13/fin-pad',
    'evyd13/plain60': 'evyd13/plain60-c',
    'frooastboard/nano': 'Frooastside/Frooastboard',
    'frooastboard/walnut': 'Frooastside/Frooastboard',
    'gl516/xr63gl': 'gachiham/XR63GL',
    'handwired/42': 'nglgzz/42',
    'handwired/swiftrax/pandamic': 'swiftrax/Pandamic',
    'handwired/swiftrax/unsplit': 'swiftrax/unsplit',
    'jiran/rev1': 'Ladniy/jiran-breakoff',
    'jiran/rev2': 'Ladniy/jiran-breakoff',
    'karlb/kbic65': 'b-karl/KBIC65',
    'kb_elmo/67mk_e': 'kb-elmo/67mk_e',
    'kb_elmo/qez': 'kb-elmo/QEZ',
    'kuro/kuro65': '0x544D/kuro65',
    'manta60': 'KamoNanban/Manta60',
    'meson': 'PyrooL/Meson',
    'nacly/ua62': 'na-cly/UA62',
    'p3d/eu_isolation': 'TuckTuckFloof/EUISO-pcb',
    'pdxkbc': 'fharding1/pdxkbc-badge',
    'phdesign/ph60/multi': 'ph-design/PH60',
    'pinky/3': 'tamanishi/Pinky3',
    'pinky/4': 'tamanishi/Pinky4',
    'rabbit/rabbit68': 'kaiec/rabbit68',
    'rmi_kb/herringbone/pro': 'ramonimbao/Herringbone',
    'rmi_kb/herringbone/v1': 'ramonimbao/Herringbone',
    'salicylic_acid3/ajisai74': 'Salicylic-acid3/PCB_Data',
    'salicylic_acid3/getta25/rev1': 'Salicylic-acid3/PCB_Data',
    'salicylic_acid3/guide68': 'Salicylic-acid3/PCB_Data',
    'salicylic_acid3/nafuda': 'Salicylic-acid3/PCB_Data',
    'salicylic_acid3/naked60/rev1': 'Salicylic-acid3/PCB_Data',
    'salicylic_acid3/setta21/rev1': 'Salicylic-acid3/PCB_Data',
    'silverbullet44': 'swanmatch/silverbullet44',
    'skeletonkbd/frost68': 'skeletonkbd/Frost68',
    'spaceman/2_milk': 'Spaceman/Spaceboards',
    'takashicompany/klec_01': 'takashicompany/klec_01',
    'takashicompany/palmslave': 'takashicompany/palmslave',
    'tanuki': 'SethSenpai/Tanuki',
    'tweetydabird/chameleon': 'TweetyDaBird/Chameleon',
    'tweetydabird/lbs4': 'TweetyDaBird/Little-Big-Scroll',
    'tweetydabird/lbs6': 'TweetyDaBird/Little-Big-Scroll',
    'westfoxtrot/cyclops': 'westfoxtrot/Cyclops',
    'westfoxtrot/prophet': 'westfoxtrot/prophet_pcb',
    'wren': 'walterhanley/wren-keyboard',
    'wsk/sl40': 'worldspawn00/SL40',
    'zwerg': 'floookay/zwerg',
}

# One line of evidence per repo, quoted into every `qmk-readme` row's `note` so a human
# reviewing the queue does not have to re-derive what was already measured.
README_META = {
    '0x544D/kuro65': {"licence": 'Apache-2.0', "boards": 1, "stars": 2},
    'Aidan-OS/Mochi40': {"licence": 'MIT', "boards": 2, "stars": 68},
    'Aidan-OS/Sango': {"licence": 'Apache-2.0', "boards": 1, "stars": 0},
    'Bastardkb/Dilemma': {"licence": 'CERN-OHL-S-2.0', "boards": 4, "stars": 582},
    'Biacco42/meishi2': {"licence": 'MIT', "boards": 1, "stars": 22, "verified": '2026-11-06'},
    'Dbroqua/DK60': {"licence": 'GPL-3.0', "boards": 1, "stars": 3},
    'Envious-Data/Env-KB': {"licence": 'GPL-3.0', "boards": 3, "stars": 257},
    'Envious-Data/Env-KB60F': {"licence": 'GPL-3.0', "boards": 1, "stars": 8},
    'Envious-Data/Env-KB65M': {"licence": 'GPL-3.0', "boards": 3, "stars": 8},
    'Envious-Data/Env-MCRO': {"licence": 'GPL-3.0', "boards": 2, "stars": 87},
    'Frooastside/Frooastboard': {"licence": 'GPL-3.0', "boards": 2, "stars": 1},
    'KamoNanban/Manta60': {"licence": 'MIT', "boards": 2, "stars": 42},
    'Ladniy/jiran-breakoff': {"licence": 'CC-BY-NC-SA-4.0', "boards": 3, "stars": 68},
    'PyrooL/Meson': {"licence": 'MIT', "boards": 1, "stars": 2},
    'Salicylic-acid3/PCB_Data': {"licence": 'MIT', "boards": 5, "stars": 51},
    'SethSenpai/Tanuki': {"licence": 'MIT', "boards": 1, "stars": 284},
    'Spaceman/Spaceboards': {"licence": 'CERN-OHL-S-2.0', "boards": 7, "stars": 144},
    'TuckTuckFloof/EUISO-pcb': {"licence": 'MIT', "boards": 1, "stars": 0},
    'TweetyDaBird/Chameleon': {"licence": 'CC-BY-SA-4.0', "boards": 5, "stars": 14},
    'TweetyDaBird/Little-Big-Scroll': {"licence": 'CC-BY-SA-4.0', "boards": 2, "stars": 55},
    'ai03-2725/duet-switch-tester': {"licence": 'AGPL-3.0', "boards": 1, "stars": 3},
    'akyp/desire65-pcb': {"licence": 'GPL-3.0', "boards": 2, "stars": 7},
    'b-karl/KBIC65': {"licence": 'MIT', "boards": 1, "stars": 65},
    'bit-trade-one/ADKB96-hardware': {"licence": 'MIT', "boards": 1, "stars": 1},
    'evyd13/atom47': {"licence": 'Apache-2.0', "boards": 3, "stars": 30},
    'evyd13/fin-pad': {"licence": 'MIT', "boards": 1, "stars": 1},
    'evyd13/plain60-c': {"licence": 'CC-BY-SA-4.0', "boards": 1, "stars": 229},
    'fharding1/pdxkbc-badge': {"licence": 'CC-BY-SA-4.0', "boards": 1, "stars": 3},
    'floookay/zwerg': {"licence": 'CERN-OHL-S-2.0', "boards": 1, "stars": 4},
    'gachiham/XR63GL': {"licence": 'CC-BY-NC-SA-4.0', "boards": 1, "stars": 0},
    'kaiec/rabbit68': {"licence": 'MIT', "boards": 1, "stars": 1, "verified": '2026-11-06'},
    'kb-elmo/67mk_e': {"licence": 'CC-BY-NC-SA-4.0', "boards": 1, "stars": 29},
    'kb-elmo/QEZ': {"licence": 'CC-BY-NC-SA-4.0', "boards": 1, "stars": 110},
    'na-cly/UA62': {"licence": 'GPL-3.0', "boards": 1, "stars": 2},
    'nglgzz/42': {"licence": 'MIT', "boards": 1, "stars": 41},
    'nickolaij/Abacus_Rev2': {"licence": 'MIT', "boards": 1, "stars": 8},
    'ph-design/PH60': {"licence": 'CC-BY-NC-SA-4.0', "boards": 2, "stars": 152},
    'ramonimbao/Herringbone': {"licence": 'CC-BY-NC-SA-4.0', "boards": 1, "stars": 3},
    'skeletonkbd/Frost68': {"licence": 'CC-BY-NC-4.0', "boards": 1, "stars": 5},
    'swanmatch/silverbullet44': {"licence": 'a custom (free for personal use, commercial use by asking)', "boards": 3, "stars": 6, "verified": '2026-11-06'},
    'swiftrax/Pandamic': {"licence": 'GPL-2.0', "boards": 1, "stars": 5},
    'swiftrax/unsplit': {"licence": 'GPL-2.0', "boards": 2, "stars": 68},
    'takashicompany/klec_01': {"licence": 'CC-BY-NC-ND-4.0', "boards": 1, "stars": 2, "verified": '2026-11-06'},
    'takashicompany/palmslave': {"licence": 'CC-BY-SA-4.0', "boards": 1, "stars": 5, "verified": '2026-11-06'},
    'tamanishi/Pinky3': {"licence": 'MIT', "boards": 1, "stars": 83},
    'tamanishi/Pinky4': {"licence": 'MIT', "boards": 1, "stars": 119},
    'walterhanley/wren-keyboard': {"licence": 'MIT', "boards": 6, "stars": 92},
    'westfoxtrot/Cyclops': {"licence": 'CC-BY-SA-4.0', "boards": 1, "stars": 12},
    'westfoxtrot/prophet_pcb': {"licence": 'CC-BY-SA-4.0', "boards": 1, "stars": 13},
    'worldspawn00/SL40': {"licence": 'MIT', "boards": 1, "stars": 4},
}


def draft_readme(qmk):
    """Rows for records whose readme names a hardware repo the `url` field does not.

    Ticket 56. `draft_qmk` drafts from a record's `url`; `README_REPOS` is the readme's
    own `Hardware Availability:` line, read by `scan_readme_sources.py` and priced
    against the corpus's own gate before a single line of it reached here. Measured
    control: 184 records where BOTH signals exist agree on the repo every time, which is
    why a readme earns a drafted row here rather than being read and thrown away, as
    `CONTROLLER_BLOCK`'s neighbour in this file already does for a different purpose.

    NEVER OVERRIDES `url`. A record `draft_qmk` already drafted, or `draft_dev_seed`
    already covers, is skipped -- this fills the gap, and does not re-litigate a
    stronger signal. A record `declines.toml` has ruled out gets no row here either,
    same as `draft_qmk`.

    CARRIES THE SAME OWNER-MATCH FALSIFIER `draft_qmk` puts on every url row -- it was
    missing here until this pass found it absent, which is the exact kind of silent gap
    ticket 22 built the falsifier to catch in the first place. Fourteen of 72 flag
    `mismatch`, and reading them shows the falsifier's own known limit rather than a
    real problem: `Bastardkb/Dilemma` against manufacturer `"Bastard Keyboards"`,
    `Dbroqua/DK60` against maintainer `"DARKOU"` -- a github handle and a product or
    personal name spelled two ways, not two different authors. Flagged anyway, because
    that is what the falsifier is FOR: surfacing the case for a human rather than
    silently deciding it either way.
    """
    declined = declined_ids()
    by_id = {r["id"]: r for r in qmk}
    already = {r["id"] for r in qmk if repo_of(r["url"])[0]} | set(DEV_SEED_PATHS)
    rows = []
    for rid, repo in sorted(README_REPOS.items()):
        if rid in declined or rid in already or rid not in by_id:
            continue
        r = by_id[rid]
        meta = README_META[repo]
        boards = "board" if meta["boards"] == 1 else "boards"
        places = "places" if meta["boards"] == 1 else "place"
        names = "names" if meta["boards"] == 1 else "name"
        stars = "star" if meta["stars"] == 1 else "stars"
        verdict = owner_matches(r, repo.split("/")[0])
        flag = ""
        if verdict == "mismatch":
            flag = (f" OWNER MISMATCH: `{repo.split('/')[0]}` matches neither "
                    f"maintainer `{r.get('maintainer')}` nor manufacturer "
                    f"`{r.get('manufacturer')}` -- check it is not a different repo.")
        rows.append(
            dict(
                tree="qmk",
                id=rid,
                name=r.get("keyboard_name") or rid,
                source=repo,
                path=None,
                stated_url=r.get("url") or "",
                signal="qmk-readme",
                verdict="drafted",
                note=(
                    f"QMK's own readme names this repo under Hardware Availability; "
                    f"`url` does not. Verified {meta.get('verified', '2026-09-03')} against the corpus's own "
                    f"gate: {meta['licence']} licence, {meta['boards']} {boards} "
                    f"{places} a switch and {names} a controller, {meta['stars']} "
                    f"{stars}.{flag}"
                ),
            )
        )
    return rows


def declined_ids(path="seed/declines.toml"):
    """Ids ruled `no_design`. Ticket 26: declines.toml wins, so they get no source row.

    A declined record never publishes, so a source row for it is attention nobody
    should spend. Reading the ruling here keeps one truth in one place -- the R6
    discipline -- instead of restating it as a `declined` verdict in both files.
    """
    f = pathlib.Path(path)
    if not f.is_file():
        return set()
    out, cur = set(), {}
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("[[") :
            if cur.get("id"):
                out.add(cur["id"])
            cur = {}
            continue
        m = re.match(r'^(\w+)\s*=\s*(.+)$', line)
        if m:
            cur[m.group(1)] = m.group(2).strip().strip('"')
    if cur.get("id"):
        out.add(cur["id"])
    return out


CONTROLLER_BLOCK = re.compile(
    r"Hardware Availability:(.*?)(?:\n\s*\n|\n\*\s*Keyboard|\Z)", re.S | re.I
)


def replacement_controller_flag(record_id, qmk_root=QMK_ROOT):
    """Flag a record whose readme names a `[controller]` under Hardware Availability.

    Ticket 26 found six `overnumpad_1xb` records -- replacement controllers for IBM
    and Unicomp boards -- arriving in the draft because ticket 17's five explicit
    declines are keyed by RECORD and these records only became visible when the
    JSON5 parser was fixed underneath them. So the class can grow two ways and an
    explicit list covers neither.

    **This is a flag, not a rule.** Ticket 17 refused a lexicon rule deliberately and
    that judgement stands: this points, a human rules. Measured precision 6/6 with
    zero false positives across 3,550 readmes; recall is 6 of 11 known class members,
    since the five already-declined converters do not use this readme block.
    """
    readme = pathlib.Path(qmk_root, record_id, "readme.md")
    if not readme.is_file():
        return None
    m = CONTROLLER_BLOCK.search(readme.read_text(errors="replace"))
    if not m:
        return None
    block = m.group(1).lower()
    if not re.search(r"\[\s*controller\s*\]", block):
        return None
    if re.search(r"\[\s*keyboard\s*\]", block):
        return ("readme lists a [controller] AND a separate [keyboard] under Hardware "
                "Availability -- likely ticket 17's `layout-not-its-own` class, needs a "
                "decline ruling before a source row")
    return ("readme lists a [controller] under Hardware Availability -- likely ticket "
            "17's `layout-not-its-own` class, needs a decline ruling before a source row")


def shields(zmk_root):
    """Read every `*.zmk.yml` under app/boards/shields."""
    root = pathlib.Path(zmk_root, "app", "boards", "shields")
    if not root.is_dir():
        sys.exit(f"not a ZMK checkout: {root} missing")
    out = []
    for yml in sorted(root.glob("*/*.zmk.yml")):
        text = yml.read_text(errors="ignore")

        def field(key):
            m = re.search(rf"^{key}:\s*(.+?)\s*$", text, re.M)
            return m.group(1).strip("\"'") if m else None

        block = re.search(r"^features:\s*\n((?:\s*-\s*.+\n)+)", text, re.M)
        feats = re.findall(r"^\s*-\s*(.+?)\s*$", block.group(1), re.M) if block else []
        out.append(
            dict(
                id=field("id"),
                name=field("name"),
                url=field("url"),
                features=feats,
                keymaps=len(list(yml.parent.glob("*.keymap"))),
            )
        )
    return out


def repo_of(url):
    """Split a url into `(owner/repo, subdirectory)`, or `(None, None)`.

    Lower-cased deliberately: github treats owner and repo case-insensitively, and
    this string is the **join key** -- two entries resolving to the same repo are the
    same source. `stated` keeps the url as written.

    The subdirectory is kept rather than collapsed away, because for a repo holding
    several designs it is the entry's own answer to *which one*: ZMK's `reviung34`
    cites `gtips/reviung/tree/master/reviung34`, and `gtips/reviung` holds eight
    designs. Discarding the path throws away a pick a human would otherwise make.

    Returns no repo for firmware trees -- they are not hardware sources.
    """
    if not url:
        return None, None
    # `tree` only, deliberately. A `blob` url names a FILE, and `path` means the
    # DIRECTORY holding the design -- QMK gives `shiro` as a build guide inside
    # `Shiro/manual/` and `ortho5by12` as a `.zip`, neither of which is a design
    # folder. A blob says the design is somewhere at or above that file, which is
    # a human's pick, not a derivation. The repo is still taken; `stated` keeps
    # the file url as the evidence it is.
    m = re.search(
        r"github\.com/([^/\s]+)/([^/\s#?]+)(?:/tree/[^/\s]+/([^\s#?]*))?", url, re.I
    )
    if not m:
        return None, None
    # Trim characters github cannot hold in a name. QMK's `late9` and `neopad`
    # records both state their url with a trailing `)` -- a markdown remnant in
    # QMK's own data -- and drafting it verbatim produced a source that 404s while
    # the stripped form is alive. Owner names allow alphanumerics and hyphens;
    # repo names add `.` and `_`.
    owner = re.sub(r"[^A-Za-z0-9-]+$", "", m.group(1))
    repo = re.sub(r"[^A-Za-z0-9._-]+$", "", m.group(2))
    if not owner or not repo:
        return None, None
    slug = f"{owner}/{repo}".lower().removesuffix(".git")
    if FIRMWARE_TREE.search(slug):
        return None, None
    path = unquote(m.group(3) or "").strip("/")
    return slug, (path or None)


def toml_str(s):
    return '"' + (s or "").replace("\\", "\\\\").replace('"', '\\"') + '"'


def draft(zmk_root):
    rows = []
    for s in shields(zmk_root):
        if KEYBOARD_FEATURE not in s["features"]:
            continue
        repo, path = repo_of(s["url"])
        rows.append(
            dict(
                tree="zmk",
                id=s["id"],
                name=s["name"],
                source=repo,
                path=path,
                stated_url=s["url"],
                signal="zmk-url" if repo else "none",
                verdict="drafted",
                note="" if repo else "ZMK states a vendor page, not a repo -- source unknown",
            )
        )
    return rows


# Attribution values that identify nobody. `qmk` is a placeholder on 612 records
# (16.6%), and treating it as a name turns the falsifier into a false-mismatch
# machine -- `nightly_boards/ph_arisu` was flagged as suspicious when the truth is
# that QMK holds no owner for it. A value containing a url is junk for the same
# reason: `kinesis/kintwin` records `manufacturer` as its own github url, which
# accidentally *corroborates* any owner appearing in that url.
PLACEHOLDER = re.compile(r"^(qmk|none|n/?a|unknown|tbd)$", re.I)


def norm(s):
    """Loose identity for an owner/maintainer comparison: letters and digits only.

    Returns "" for anything that identifies nobody, so the caller reads it as silence
    rather than as a name that failed to match.
    """
    s = (s or "").strip()
    if not s or PLACEHOLDER.match(s) or "://" in s or "github.com" in s.lower():
        return ""
    return re.sub(r"[^a-z0-9]", "", s.lower())


def owner_matches(record, owner):
    """Does the url's github owner corroborate the record's own attribution?

    Finding 3 of the identity mapping: this falsifier `corroborates 18 of 18 rows where
    it can speak, and is silent on 33`. Silence is not failure -- QMK holds no owner
    information for many records -- so a third value is returned rather than a boolean.

    **It is a corroborator, not a detector, and one failure mode defeats it entirely.**
    `handwired/dactyl_lightcycle` cites `adereth/dactyl-keyboard`, a Clojure *generator*
    rather than this design's hardware -- and Adereth is also the record's maintainer, so
    the owner matches and the row drafts clean. Nothing in the metadata can catch that.
    What catches it is the clone: a repo with no `.kicad_pcb` yields no `switch_footprint`
    and the design fails the publication gate. So the gate is the backstop here, not this.
    """
    o = norm(owner)
    fields = [record.get("maintainer"), record.get("manufacturer")]
    known = [norm(f) for f in fields if norm(f)]
    if not known:
        return "silent"
    for k in known:
        if k and (k in o or o in k):
            return "match"
    return "mismatch"


def draft_dev_seed(qmk):
    """Rows for records whose tree states a shop, redirected by a human.

    Ticket 26. The 25 `handwired/scottokeebs/*` records all cite scottokeebs.com, so
    `repo_of` yields nothing and `draft_qmk` never sees them -- they are unreachable
    without this. Ticket 37 called it the highest-value ratio in the confirmation pass.
    """
    known = {r["id"]: r for r in qmk}
    rows = []
    for rid, folder in sorted(DEV_SEED_PATHS.items()):
        r = known.get(rid)
        if not r:
            continue
        rows.append(
            dict(
                tree="qmk",
                id=rid,
                name=r.get("keyboard_name") or rid,
                source=DEV_SEED_REPO,
                path=folder,
                stated_url=r.get("url") or "",
                signal="dev-seed",
                verdict="drafted",
                note=("QMK states a shop, not a repo; repo and folder supplied by a human "
                      "-- this commit is the credential"),
            )
        )
    return rows


def draft_qmk(qmk):
    """Draft one row per QMK record that names a hardware repo. Never drafts `source = false`."""
    declined = declined_ids()
    by_repo = defaultdict(list)
    staged = []
    for r in qmk:
        # Ticket 26: declines.toml wins. A declined record never publishes, so a
        # source row for it is attention nobody should spend.
        if r["id"] in declined:
            continue
        repo, path = repo_of(r["url"])
        if not repo:
            continue
        owner = repo.split("/")[0]
        staged.append((r, repo, path, owner_matches(r, owner)))
        by_repo[repo].append(r["id"])

    rows = []
    for r, repo, path, verdict in staged:
        notes = []
        if verdict == "mismatch":
            notes.append(
                f"url owner `{repo.split('/')[0]}` matches neither maintainer "
                f"`{r.get('maintainer')}` nor manufacturer `{r.get('manufacturer')}` -- "
                "check it is not an ancestor's repo"
            )
        elif verdict == "silent":
            notes.append("QMK holds no owner information -- falsifier cannot speak")
        ctrl = replacement_controller_flag(r["id"])
        if ctrl:
            notes.append(ctrl)
        siblings = [i for i in by_repo[repo] if i != r["id"]]
        if siblings:
            # NOT "a human picks which is the design" -- that phrasing was wrong and
            # cost this flag its precision. Ticket 19 decision 1 made the design unit
            # the BOARD, so several records sharing a repo is normal and correct: each
            # names firmware for hardware in that repo. What is genuinely open is
            # which record NAMES the design, and ticket 23 decision 5 put it here --
            # but it cannot be answered until the repo's board count is known, which
            # is the fan-out seed. So confirming the REPO is this row's question; the
            # naming pick belongs to ticket 44.
            notes.append(
                f"{len(siblings) + 1} records share this repo ({', '.join(sorted(siblings)[:3])}"
                f"{'...' if len(siblings) > 3 else ''}) -- normal under ticket 19; confirm the "
                "repo here, ticket 44 rules which record names the design"
            )
        rows.append(
            dict(
                tree="qmk",
                id=r["id"],
                name=r.get("keyboard_name") or r["id"],
                source=repo,
                path=path,
                stated_url=r["url"],
                signal="qmk-url",
                verdict="drafted",
                note="; ".join(notes),
            )
        )
    return sorted(rows, key=lambda x: x["id"])


def qmk_report(qmk):
    """Numbers the repoless gate needs. Emits no seed rows."""
    repos = defaultdict(set)
    firmware = defaultdict(set)
    for r in qmk:
        url = r["url"] or ""
        m = re.search(r"github\.com/([^/\s]+)/([^/\s#?]+)", url, re.I)
        if not m:
            continue
        slug = f"{m.group(1)}/{m.group(2)}".lower().removesuffix(".git")
        (firmware if FIRMWARE_TREE.search(slug) else repos)[slug].add(r["id"])
    return dict(
        records=len(qmk),
        with_url=sum(1 for r in qmk if r["url"]),
        naming_a_repo=sum(len(v) for v in repos.values()) + sum(len(v) for v in firmware.values()),
        hardware_repos=len(repos),
        records_on_hardware_repos=sum(len(v) for v in repos.values()),
        firmware_tree_repos=len(firmware),
        records_lost_to_firmware_trees=sum(len(v) for v in firmware.values()),
    )


def preserve_rulings(rows, existing="seed/sources.toml"):
    """Carry a human's rulings across a regeneration. **This is load-bearing.**

    Ticket 26 asks the owner for 4-5 hours of confirmation across ~508 rows. Without
    this, the next `draft_sources.py` run silently throws all of it away -- the file
    is both a generated draft and a human-ruled record, so regeneration has to merge,
    not clobber.

    A row is a human's the moment its verdict is not `drafted` (ticket 36's invariant,
    read in reverse). For those rows the human's `verdict`, `source`, `path` and `note`
    all win: a confirmation may have CORRECTED the repo or picked the subdirectory,
    and re-deriving either would discard the pick. `name` and `stated` still refresh
    from the tree, since those are the tree's words and not the human's.
    """
    f = pathlib.Path(existing)
    if not f.is_file():
        return rows
    held, cur = {}, {}
    for line in f.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if t.startswith("[["):
            if cur.get("verdict") and cur["verdict"] != "drafted":
                held[(cur.get("tree"), cur.get("id"))] = cur
            cur = {}
            continue
        m = re.match(r'^(\w+)\s*=\s*(.+)$', t)
        if m:
            v = m.group(2).strip()
            cur[m.group(1)] = False if v == "false" else v.strip('"')
    if cur.get("verdict") and cur["verdict"] != "drafted":
        held[(cur.get("tree"), cur.get("id"))] = cur

    kept = 0
    for r in rows:
        h = held.get((r["tree"], r["id"]))
        if not h:
            continue
        r["verdict"] = h["verdict"]
        r["source"] = h.get("source", r["source"])
        r["path"] = h.get("path", r["path"])
        r["note"] = h.get("note", r["note"])
        kept += 1
    if kept:
        print(f"# preserved {kept} human-ruled rows from {existing}", file=sys.stderr)
    return rows


def toml_lines(rows):
    """Render the seed file. One code path for --toml and --write, deliberately:
    two renderers is how the stdout copy and the on-disk copy drift apart.
    """
    out = []
    out.append("# seed/sources.toml -- which repo holds the hardware for a firmware entry." + "\n")
    out.append("# Drafted by scripts/draft_sources.py. Every row is `drafted` until a human" + "\n")
    out.append("# rules on it; the build consumes rows whose verdict is not `drafted`." + "\n")
    out.append("#" + "\n")
    out.append("# `verdict` answers: is this row settled, and how did it settle? Set by" + "\n")
    out.append("# ticket 26. Three values, and both settled ones are terminal:" + "\n")
    out.append("#" + "\n")
    out.append("#   drafted      unsettled -- a machine proposed the row, nobody has ruled" + "\n")
    out.append("#   confirmed    settled, use it. The human's word, per ticket 15 -- and on" + "\n")
    out.append("#                a `source = false` row it means \"I looked, no repo is known\"" + "\n")
    out.append("#   source_gone  settled, unusable -- the repo 404s, so no clone, no board" + "\n")
    out.append("#                file, `switch_footprint` Unknown, and the gate declines it" + "\n")
    out.append("#" + "\n")
    out.append("# `source` stays the ONLY home for the content -- a repo slug, or `false` for" + "\n")
    out.append("# no repo known. Nothing states that twice." + "\n")
    out.append("#" + "\n")
    out.append("# `signal` names WHICH source stated the repo: `zmk-url` is a shield's own" + "\n")
    out.append("# `url:`, `qmk-url` is a record's own `url`. `none` means nothing did." + "\n")
    out.append("# `dev-seed` means A HUMAN SUPPLIED IT and this commit is the credential" + "\n")
    out.append("# (ticket 10's `ImportSource::DevSeed`). It is the one signal that cannot be" + "\n")
    out.append("# falsified against a tree, so it stays rare on purpose -- if it ever appears" + "\n")
    out.append("# in the hundreds, something has gone wrong." + "\n")
    out.append("#" + "\n")
    out.append("# Records ruled `no_design` in seed/declines.toml get NO ROW here. That file" + "\n")
    out.append("# wins; a declined record never publishes, so a source row for it is attention" + "\n")
    out.append("# nobody should spend." + "\n")
    out.append("#" + "\n")
    out.append("# ZMK rows cover all 63 keyboard shields, including those whose source is" + "\n")
    out.append("# unknown, because 63 is the whole tree. QMK rows are drafted ONLY for records" + "\n")
    out.append("# that name a hardware repo -- a `source = false` row for each of the ~3,200" + "\n")
    out.append("# repo-less records would be a census of designs the publication gate already" + "\n")
    out.append("# rejects ~95% of the time." + "\n")
    out.append("#" + "\n")
    out.append("# A QMK row with a non-empty `note` failed a check and wants a human's eye" + "\n")
    out.append("# first. Two checks run: the url owner against the record's own maintainer and" + "\n")
    out.append("# manufacturer, and whether another record claims the same repo. Neither can" + "\n")
    out.append("# catch a record citing an ancestor's repo when the ancestor is also the" + "\n")
    out.append("# maintainer -- see `owner_matches`; the publication gate is the backstop there." + "\n")
    for r in rows:
        out.append("\n[[entry]]\n")
        out.append(f"tree     = {toml_str(r['tree'])}" + "\n")
        out.append(f"id       = {toml_str(r['id'])}" + "\n")
        out.append(f"name     = {toml_str(r['name'])}" + "\n")
        out.append(f"source   = {toml_str(r['source']) if r['source'] else 'false'}" + "\n")
        out.append(f"path     = {toml_str(r['path']) if r['path'] else 'false'}" + "\n")
        out.append(f"stated   = {toml_str(r['stated_url'])}" + "\n")
        out.append(f"signal   = {toml_str(r['signal'])}" + "\n")
        out.append(f"verdict  = {toml_str(r['verdict'])}" + "\n")
        out.append(f"note     = {toml_str(r['note'])}" + "\n")
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("zmk", help="a ZMK checkout")
    p.add_argument("qmk", type=argparse.FileType(), help="build_qmk_corpus.py output")
    p.add_argument("--toml", action="store_true", help="emit seed rows to stdout")
    p.add_argument("--write", metavar="PATH", nargs="?", const="seed/sources.toml",
                   help="regenerate PATH in place, preserving human rulings (default "
                        "seed/sources.toml). USE THIS, not shell redirection -- `--toml > "
                        "seed/sources.toml` truncates the file before preserve_rulings can "
                        "read it, silently discarding every confirmation in it.")
    p.add_argument("--tree", choices=("zmk", "qmk", "both"), default="both")
    args = p.parse_args()

    qmk = json.load(args.qmk)
    rows = (draft(args.zmk) if args.tree in ("zmk", "both") else []) + \
           (draft_qmk(qmk) + draft_dev_seed(qmk) + draft_readme(qmk)
            if args.tree in ("qmk", "both") else [])
    rows = preserve_rulings(rows, args.write or "seed/sources.toml")

    if args.write:
        text = "".join(toml_lines(rows))
        pathlib.Path(args.write).write_text(text, encoding="utf-8")
        print(f"# wrote {len(rows)} rows to {args.write}", file=sys.stderr)
        return
    if args.toml:
        sys.stdout.write("".join(toml_lines(rows)))
        return

    qrows = [r for r in rows if r["tree"] == "qmk"]
    if qrows:
        flagged = [r for r in qrows if r["note"]]
        seeded = [r for r in qrows if r["signal"] == "dev-seed"]
        readme = [r for r in qrows if r["signal"] == "qmk-readme"]
        qrows = [r for r in qrows if r["signal"] not in ("dev-seed", "qmk-readme")]
        print(f"QMK rows from a record's own url : {len(qrows)}")
        print(f"QMK rows from a dev seed         : {len(seeded)}")
        print(f"QMK rows from a readme (#56)     : {len(readme)}"
              f"  ({len({r['source'] for r in readme})} repos)")
        print(f"  on distinct repos              : {len({r['source'] for r in qrows})}")
        print(f"  naming a subdirectory          : {sum(1 for r in qrows if r['path'])}")
        print(f"  flagged for a human's eye      : {len(flagged)}")
        print(f"    owner mismatch               : {sum(1 for r in qrows if 'matches neither' in r['note'])}")
        print(f"    falsifier silent             : {sum(1 for r in qrows if 'cannot speak' in r['note'])}")
        print(f"    repo shared with another rec : {sum(1 for r in qrows if 'share this repo' in r['note'])}")
        print(f"  clean                          : {len(qrows) - len(flagged)}")
        print()

    zrows = [r for r in rows if r["tree"] == "zmk"]
    if zrows:
        allsh = shields(args.zmk)
        print(f"ZMK shields                      : {len(allsh)}")
        print(f"  not keyboards (no `keys`)      : {sum(1 for s in allsh if KEYBOARD_FEATURE not in s['features'])}")
        print(f"  keyboard rows drafted          : {len(zrows)}")
        print(f"    source read from ZMK's url   : {sum(1 for r in zrows if r['source'])}")
        print(f"      of those, naming a subdir  : {sum(1 for r in zrows if r['path'])}")
        print(f"    source unknown (vendor page) : {sum(1 for r in zrows if not r['source'])}")
        print()
    for k, v in qmk_report(qmk).items():
        print(f"qmk.{k:32s} : {v}")


if __name__ == "__main__":
    main()
