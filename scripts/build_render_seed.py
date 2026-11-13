#!/usr/bin/env python3
"""Turn the render manifest into the per-slug seed file the app reads at startup.

Backlog #60. Two files on purpose, and the split is the same one `fetch_activity.py`
argues for: the MANIFEST is build state -- every board file of every design, its content
hash, what was uploaded -- and refreshes whenever a render is re-run. `renders.json` is
the SHIPPED artifact: one entry per design, the keys the page needs, nothing else. Folding
them together would put build bookkeeping in the jar and make every re-render look like a
product change in git.

ONE BOARD PER DESIGN, AND WHICH ONE IS RECORDED RATHER THAN IMPLIED. 31 designs have
several board files -- halves, revisions, hotswap against soldered -- and #60 says picking
one arbitrarily "would fabricate a picture of a real board". So the choice is deterministic
(the first path `design-boards.json` lists, which is the same order the attribution was
confirmed in) and the chosen `path` travels with the entry, so a page can say which board
it is showing instead of implying it is the only one.

Usage: build_render_seed.py
"""
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"
RENDERS = REPO/"build/renders"


def main():
    manifest = json.loads((SCRATCH/"render-manifest.json").read_text())
    boards = json.loads((SCRATCH/"design-boards.json").read_text())

    out = {}
    local_only = []
    for slug, info in sorted(boards.items()):
        for path in info["paths"]:                     # first listed wins, deterministically
            entry = manifest.get(f"{slug}\t{path}")
            if not entry or not entry.get("glb") or not entry.get("png"):
                continue
            # PUBLISHED ONLY IF THE ASSETS CAN ACTUALLY BE SERVED -- uploaded to the
            # bucket, or present in the local render directory a dev machine serves from.
            # A page with a URL that 404s is worse than no picture at all, because the
            # silhouette it would sit beside is already a good picture.
            uploaded = set(entry.get("uploaded") or []) >= {"glb", "png"}
            local = ((RENDERS/f"{entry['glb']}.glb").is_file()
                     and (RENDERS/f"{entry['png']}.png").is_file())
            if not (uploaded or local):
                continue
            if not uploaded:
                local_only.append(slug)
            out[slug] = {"glb": entry["glb"], "png": entry["png"], "path": path,
                         "alternates": max(0, len(info["paths"]) - 1),
                         # 0 for an entry rendered before sizes were recorded; the page
                         # then omits the size rather than printing "0 MB".
                         "glbBytes": entry.get("glb_bytes") or 0}
            break

    target = REPO/"src/main/resources/seed/renders.json"
    target.write_text(json.dumps(out, indent=1, sort_keys=True) + "\n")
    multi = sum(1 for v in out.values() if v["alternates"])
    print(f"  {len(out)} designs have a render "
          f"({multi} of them have other board files not shown)")
    print(f"  -> {target.relative_to(REPO)}")
    # LOCAL-ONLY IS A DEV CONVENIENCE AND A PRODUCTION 404. The seed took these because
    # this machine serves build/renders, and production serves only the bucket: on
    # 2026-09-29 seven were committed this way after a render run without --upload.
    if local_only:
        print(f"  WARNING: {len(local_only)} are local only -- they 404 in production until "
              f"`render_boards.py --upload --only {' '.join(local_only)}`")
    return 0


if __name__ == "__main__":
    sys.exit(main())
