#!/usr/bin/env python3
"""Export each design's PCB to a GLB (and a PNG poster) from its own board file.

Backlog #60. The silhouette draws where the KEYS are; this draws what the BOARD is --
its outline, its shape, its colour -- which is the one thing the silhouette cannot
supply. The owner's bar, stated plainly and worth quoting because it decides everything
below: *"there's a lot of value seeing the PCB and its physical outline because it
complements the layout silhouette. Resistors and LEDs not rendering totally fine."*

THE BOARD FILE ALONE IS ENOUGH, AND THAT IS THE FINDING THIS SCRIPT IS BUILT ON.
Every earlier plan for this assumed a full `git clone --recurse-submodules` per design --
the whole project, so that `fp-lib-table` and the local `.pretty` and the 3D models
resolve -- which measured at 867 MB for five repos and was the single biggest cost in the
ticket. Measured 2026-09-09 against `idank/spankbd`:

    span, exported from the 19 MB recursive clone   1,763,876 bytes, 398-point outline
    span, exported from the bare .kicad_pcb alone   1,763,900 bytes, 398-point outline
    (no project, no fp-lib-table, no submodule, empty directory)

Identical geometry. The clone buys component MODELS, and components are explicitly not
the bar. So this fetches one file per board and exports it, and the ~40 GB of clones, the
submodule breakage (18 of 325 designs have a dead or SSH-only submodule) and the
clone-cache question all stop being this script's problem. If per-part fidelity ever
becomes the goal, the clone comes back -- and then it must be best-effort, because a
failing recursive clone still leaves the board file on disk.

EXIT 0 MEANS NOTHING HERE, so every output is stat'd. `kicad-cli` logs "Successfully
created 3D render image" and returns 0 having written no file at all -- observed five
times in a row while its writes were failing on a permission error. A pipeline that
trusts the exit code reports a corpus of renders it does not have. The loud failure looks
different and is fine: a board saved in a newer KiCad format than the image reads exits
**3** with "Failed to load board".

ROOTLESS PODMAN, NOT DOCKER, and `--userns=keep-id` is what makes the write land: this
machine deliberately keeps the user out of the docker group, so the container's own uid
maps to a subuid with no access to the output directory without it.

`-full` IS REQUIRED. The plain `kicad/kicad:10.0.5` image ships NO stock 3D model library
at all, so a board renders as a bare slab and still exits 0 -- the silent-success failure
again, one layer down. Pinned to 10.0.5 rather than 9.0 because 9.0 cannot open one
design in the corpus (`koca58`, KiCad 10 format, the only one) while 10.0.5 opens every
file including the six KiCad-4-era ones, byte-identical.

Usage:
    render_boards.py --only macrocat sofle-pico      # a few, by slug
    render_boards.py --all                           # every design with a board
    render_boards.py --all --stale-only              # only what changed since the manifest
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import objectstorage  # noqa: E402
from scan_boards import fetch  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRATCH = REPO/"data"

IMAGE = "docker.io/kicad/kicad:10.0.5-full"
MODELS = "/usr/share/kicad/3dmodels"

# THE RENDERER ID IS PART OF THE CACHE KEY, which is what makes a settings change
# invalidate every asset it affects. `last_commit` cannot see a change to these flags --
# the board did not move, the picture did -- so the id carries them instead. Two ids,
# because the two artefacts are tuned independently: the PNG's angle and lighting will be
# revisited many times and the GLB's detail level almost never.
GLB_RENDERER = "kicad10.0.5-full/export-glb/mask-silk-tracks-opaquecore/3"
PNG_RENDERER = "kicad10.0.5-full/render/high-persp-rot-30,0,25-transparent-1400x900/2"

# SOLDERMASK, SILKSCREEN AND TRACKS -- WHICH IS MORE THAN THE FIRST VERSION SHIPPED, and
# the owner's own reaction is why. Measured on `macrocat`:
#
#     soldermask only     472 KB      <- shipped first, and looked disappointing
#     + silkscreen        1.3 MB
#     + tracks            2.9 MB      <- this
#     + pads and zones    5.1 MB
#
# The mismatch that made the first choice wrong: `pcb render` draws the board's REAL
# appearance, so the PNG poster already showed traces and silkscreen, and the 3D model
# sitting under it visibly had less in it. A viewer that is poorer than its own thumbnail
# reads as broken rather than as economical.
#
# Pads and zones are left out because they are the 2.2 MB that changes the picture least:
# both sit under the soldermask that is already being drawn. And the bytes are affordable
# here in a way they would not be in a poster, because the viewer is loaded ON CLICK --
# whoever pays for this model asked for it.
GLB_FLAGS = ["--include-soldermask", "--include-silkscreen", "--include-tracks"]
# `--background transparent` AND NO `--floor`, both for the same reason: the poster has
# to sit on the page rather than bring its own. An opaque background baked a near-white
# rectangle into the PNG, which disagreed with the transparent 3D canvas that replaces it
# on click and looked wrong on a dark-themed page. The floor goes with it -- a drawn floor
# is a background by another name, and it cannot match a page colour it does not know.
PNG_FLAGS = [
    "--width", "1400", "--height", "900", "--quality", "high",
    "--perspective", "--rotate", "-30,0,25", "--background", "transparent",
]

# Every stock-library variable a board file might name, all pointed at the one directory
# the image actually ships. A file written by KiCad 5 says `KISYS3DMOD`, by 6 says
# `KICAD6_3DMODEL_DIR`, and so on; an unset one resolves to nothing and the part silently
# does not draw. Cheap to set them all, and impossible to debug when one is missing.
MODEL_VARS = [
    arg for var in ("KISYS3DMOD", "KICAD6_3DMODEL_DIR", "KICAD7_3DMODEL_DIR",
                    "KICAD8_3DMODEL_DIR", "KICAD9_3DMODEL_DIR", "KICAD10_3DMODEL_DIR")
    for arg in ("-D", f"{var}={MODELS}")
]


def digest(renderer, repo, path, content_sha):
    """The object key: a hash of everything that decides the pixels.

    CONTENT-ADDRESSED, so a re-render writes a NEW key and never overwrites a live one --
    which keeps this out of the cache-invalidation business entirely. A slug-keyed object
    would have to be overwritten in place, and then a browser or a CDN holding the old
    bytes is a bug you cannot fix from here.

    The board file's own sha256 rather than its git blob oid: the oid would save
    downloading the file, but this script downloads it anyway to export it, so the
    content hash is free, more direct, and needs no API call.

    `repo.lower()` because the seed's recorded repo strings differ in case from GitHub's
    canonical answer for 105 of 325 designs -- `fetch_activity.py` learned that first.
    The key must be stable against what the seed cites, not what GitHub answers.
    """
    payload = "\n".join([renderer, repo.lower(), path, content_sha]) + "\n"
    return hashlib.sha256(payload.encode()).hexdigest()[:32]


def podman(args, mount, label):
    """Run kicad-cli in the image, with the output directory mounted writable.

    `--network=none`: a render reads one local board file and needs no network. Rootless
    podman on the VPS also CAN'T set one up (no `pasta`), which is how the first server
    test failed with exit 127 (2026-11-15); Ergogen's container already runs this way.
    """
    cmd = [
        "podman", "run", "--rm", "--userns=keep-id", "--network=none",
        "-v", f"{mount}:/work:z", "-e", "HOME=/tmp", "-w", "/work", IMAGE,
    ] + args
    done = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    return done.returncode, (done.stdout + done.stderr)


def save_manifest(path: pathlib.Path, manifest) -> None:
    """
    Write the manifest, atomically, via a temp file and a rename.

    CALLED AFTER EVERY BOARD FILE AND NOT JUST AT THE END, because the end is 3.6 hours
    away for a full corpus run and everything before a crash used to be forgotten. The
    assets and the uploads survived such a crash -- they are content-addressed and already
    on disk or in the bucket -- but the manifest is what `--stale-only` reads to know that,
    so losing it meant re-rendering work that was already paid for.

    Atomically, because the process that is interrupted mid-run is exactly the process
    writing this file: a half-written JSON manifest is worse than none, since it parses as
    nothing and takes the whole record with it. Rename on the same filesystem is atomic, so
    a reader sees either the previous manifest or the new one.
    """
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(manifest, indent=1, sort_keys=True))
    tmp.replace(path)


def make_board_body_opaque(glb: pathlib.Path) -> str:
    """
    Force the FR4 substrate to OPAQUE, in place. Returns a note for the manifest.

    WHY THIS EXISTS. KiCad exports every board layer with `alphaMode: BLEND` -- mask at
    0.83, silkscreen at 0.90, the substrate itself at 0.98 -- which is physically
    reasonable and wrong for a web viewer in two visible ways. A transparent material in
    three.js does not write to the depth buffer, so silkscreen on the FAR side of the
    board draws straight through it: the owner spotted the word "reset" mirrored, which is
    back-face silkscreen bleeding forward. And 2% transmission through the board body lets
    a light page background wash the green out, which is what made the first 3D view look
    pale and weak beside its own poster.

    ONLY THE SUBSTRATE IS TOUCHED, and that is the point. An opaque core occludes the far
    face, which is the entire bug; the soldermask stays translucent so the copper traces
    underneath it stay visible, which is the detail this pipeline just grew from 472 KB to
    13 MB to obtain. Making everything opaque would fix the bleed-through by throwing away
    the tracks.

    The substrate is identified BY GEOMETRY, not by index or colour, because material
    order shifts with the --include flags and mask colour varies by board: it is the only
    material with volume. Mask, silkscreen and copper are flat sheets at the extremes;
    the board body spans the thickness.
    """
    raw = glb.read_bytes()
    if raw[:4] != b"glTF":
        return "not a glb, left alone"
    json_len, = struct.unpack_from("<I", raw, 12)
    doc = json.loads(raw[20:20 + json_len])
    bin_chunk = raw[20 + json_len:]

    mats = doc.get("materials") or []
    if not mats:
        return "no materials"

    # Per material: its bounding box in the board plane (XZ) and its thickness (Y).
    boxes = {}
    for mesh in doc.get("meshes", []):
        for prim in mesh.get("primitives", []):
            acc = doc["accessors"][prim["attributes"]["POSITION"]]
            if "min" not in acc or "max" not in acc:
                continue
            m = prim.get("material")
            if m is None:
                continue
            lo, hi = acc["min"], acc["max"]
            b = boxes.setdefault(m, [list(lo), list(hi)])
            for ax in range(3):
                b[0][ax] = min(b[0][ax], lo[ax])
                b[1][ax] = max(b[1][ax], hi[ax])
    if not boxes:
        return "no usable bounds"

    # THE SUBSTRATE IS THE NON-METAL THAT COVERS THE WHOLE OUTLINE AND HAS THICKNESS.
    #
    # Two earlier rules were wrong and both are worth recording, because each looked
    # obviously right until a real board refuted it:
    #
    #   "the biggest Y span"            picked the COPPER -- plated through-holes run the
    #                                   full thickness, 95% against the substrate's 94%.
    #   "...as a share of total height" picked NOTHING on koca58 -- the total includes
    #                                   tall components, so 1.6 mm of board scored 0.14
    #                                   and failed the guard.
    #
    # Area in the board plane is the signal neither of those had: the board body spans the
    # entire outline, and a connector or a via does not. Mask and silkscreen tie with it on
    # area, being sheets laid over the same outline, so Y span breaks the tie -- they are
    # flat and the substrate is not. Metals are excluded first, since FR4 is a dielectric.
    cands = {m: b for m, b in boxes.items()
             if mats[m].get("pbrMetallicRoughness", {}).get("metallicFactor", 1.0) < 0.5}
    if not cands:
        return "no non-metallic material, left alone"

    def area(b):
        return (b[1][0] - b[0][0]) * (b[1][2] - b[0][2])

    widest = max(area(b) for b in cands.values())
    if widest <= 0:
        return "degenerate board plane, left alone"
    full = {m: b for m, b in cands.items() if area(b) >= 0.8 * widest}
    body = max(full, key=lambda m: full[m][1][1] - full[m][0][1])
    span = full[body][1][1] - full[body][0][1]

    # A plausible PCB thickness, in metres. Guards against a board whose export contains
    # no substrate at all, where the winner would be a flat mask sheet (span ~0).
    if not (0.0002 <= span <= 0.005):
        return f"best candidate is {span * 1000:.2f} mm thick, not a board body, left alone"

    mat = mats[body]
    mat["alphaMode"] = "OPAQUE"
    pbr = mat.setdefault("pbrMetallicRoughness", {})
    if "baseColorFactor" in pbr:
        pbr["baseColorFactor"][3] = 1.0

    # Repack. Both chunks are 4-byte aligned; JSON pads with SPACES and BIN with zeros --
    # a stray zero inside the JSON chunk makes the file unreadable by strict loaders.
    out = json.dumps(doc, separators=(",", ":")).encode()
    out += b" " * (-len(out) % 4)
    total = 12 + 8 + len(out) + len(bin_chunk)
    glb.write_bytes(
        struct.pack("<III", 0x46546C67, 2, total)
        + struct.pack("<II", len(out), 0x4E4F534A) + out
        + bin_chunk
    )
    return f"substrate mat_{body} forced opaque ({span * 1000:.2f} mm thick)"


def export_one(board_bytes, name, workdir):
    """(glb_path, png_path, notes) for one board file, or Nones where it did not land."""
    board = workdir/f"{name}.kicad_pcb"
    board.write_bytes(board_bytes)
    notes = []

    glb = workdir/f"{name}.glb"
    code, log = podman(
        ["kicad-cli", "pcb", "export", "glb", "--output", f"/work/{glb.name}"]
        + GLB_FLAGS + MODEL_VARS + [f"/work/{board.name}"], workdir, name)
    # STAT, NEVER THE EXIT CODE. See the module docstring.
    if not glb.exists() or glb.stat().st_size == 0:
        notes.append(f"glb absent after exit {code}: {log.strip().splitlines()[-1][:120] if log.strip() else 'no output'}")
        glb = None

    png = workdir/f"{name}.png"
    code, log = podman(
        ["kicad-cli", "pcb", "render", "--output", f"/work/{png.name}"]
        + PNG_FLAGS + MODEL_VARS + [f"/work/{board.name}"], workdir, name)
    if not png.exists() or png.stat().st_size == 0:
        notes.append(f"png absent after exit {code}: {log.strip().splitlines()[-1][:120] if log.strip() else 'no output'}")
        png = None

    return glb, png, notes


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="*", metavar="SLUG", help="just these designs")
    ap.add_argument("--all", action="store_true", help="every design with a board file")
    ap.add_argument("--stale-only", action="store_true",
                    help="skip a board whose content hash already has both assets")
    ap.add_argument("--out", type=pathlib.Path, default=REPO/"build/renders",
                    help="where the assets land (never the repo tree: these are binaries)")
    ap.add_argument("--manifest", type=pathlib.Path,
                    default=SCRATCH/"render-manifest.json")
    ap.add_argument("--upload", action="store_true",
                    help="also PUT each asset to object storage under boards/<key>")
    args = ap.parse_args()

    if not args.only and not args.all:
        sys.exit("nothing to do: pass --only SLUG... or --all")

    boards = json.loads((SCRATCH/"design-boards.json").read_text())
    slugs = sorted(boards) if args.all else [s for s in args.only if s in boards]
    missing = [s for s in (args.only or []) if s not in boards]
    if missing:
        # LOUD, because a slug that names no design is a stale command or a rename, and
        # silently rendering the other four is how a caller concludes it rendered five.
        sys.exit(f"no design with board files for: {missing}")

    manifest = {}
    if args.manifest.exists():
        manifest = json.loads(args.manifest.read_text())

    args.out.mkdir(parents=True, exist_ok=True)
    done = skipped = failed = 0
    unreadable = []

    for slug in slugs:
        info = boards[slug]
        # EVERY board file, not one. 31 designs have several -- halves, revisions,
        # hotswap-versus-soldered -- and #60 records that picking one arbitrarily
        # "would fabricate a picture of a real board". Export them all, record which
        # is which, and let the page decide what to show.
        for path in info["paths"]:
            text, err, _ = fetch(info["repo"], path, cap=0)
            if text is None:
                # An unreachable file is Unknown, never a design with no board. The
                # existing prober retries; a bare failure here is reported and skipped.
                unreadable.append((slug, path, err))
                continue
            data = text.encode("utf-8", "surrogateescape")
            content_sha = hashlib.sha256(data).hexdigest()
            key = f"{slug}\t{path}"
            glb_key = digest(GLB_RENDERER, info["repo"], path, content_sha)
            png_key = digest(PNG_RENDERER, info["repo"], path, content_sha)

            try:
                have = manifest.get(key) or {}
                # WITH --upload, "stale" ALSO MEANS "not fully uploaded yet" -- not just
                # rendering skips a board file this happily. Found the hard way: a run that
                # crashed on a 504 left 190 board files rendered but only 183 uploaded, and
                # the version of this check that only looked at glb/png keys and local files
                # would have skipped every one of those 7 forever on the next --stale-only
                # run, silently leaving them unpublished with no error and no sign anything
                # was wrong.
                already_uploaded = not args.upload or set(have.get("uploaded") or []) >= {"glb", "png"}
                if (args.stale_only and have.get("glb") == glb_key
                        and have.get("png") == png_key
                        and (args.out/f"{glb_key}.glb").exists()
                        and (args.out/f"{png_key}.png").exists()
                        and already_uploaded):
                    skipped += 1
                    continue

                with tempfile.TemporaryDirectory(prefix="kbrender-") as tmp:
                    workdir = pathlib.Path(tmp)
                    glb, png, notes = export_one(data, "board", workdir)
                    if glb:
                        shutil.copy2(glb, args.out/f"{glb_key}.glb")
                        # AFTER the copy, so the fix is applied to the artefact that ships
                        # rather than to a temp file that is about to be deleted.
                        notes_opaque = make_board_body_opaque(args.out/f"{glb_key}.glb")
                        if "forced opaque" not in notes_opaque:
                            notes.append(f"opaque core: {notes_opaque}")
                    if png:
                        shutil.copy2(png, args.out/f"{png_key}.png")

                uploaded = []
                if args.upload:
                    for asset, digest_key in (("glb", glb_key if glb else None),
                                              ("png", png_key if png else None)):
                        if not digest_key:
                            continue
                        obj = f"boards/{digest_key}.{asset}"
                        # Content-addressed, so present means identical -- skip the PUT.
                        if objectstorage.exists(obj):
                            uploaded.append(asset)
                            continue
                        # A FAILED UPLOAD IS A NOTE ON THIS BOARD FILE, NOT A CRASH OF THE RUN.
                        # `put()` already retries transient errors; this catches what survives
                        # that -- a sustained outage, a bad credential -- so one unlucky asset
                        # forfeits its own upload rather than every board file after it. The
                        # local .glb/.png are untouched either way and `--stale-only` will
                        # retry the upload on the next run, since `uploaded` will not contain
                        # this asset.
                        try:
                            status, _ = objectstorage.put(args.out/f"{digest_key}.{asset}", obj)
                            if status == 200:
                                uploaded.append(asset)
                            else:
                                notes.append(f"{asset} upload returned {status}")
                        except Exception as e:
                            notes.append(f"{asset} upload failed: {e}")

                # THE SIZE IS RECORDED HERE AND NOT MEASURED LATER, because the seed build
                # runs after `build/` may have been cleaned and the assets may exist only in
                # the bucket. Stat it while the file is certainly on disk.
                entry = {
                    "repo": info["repo"], "path": path, "board_sha256": content_sha,
                    "uploaded": uploaded,
                    "glb_bytes": (args.out/f"{glb_key}.glb").stat().st_size if glb else 0,
                    "glb": glb_key if glb else None, "png": png_key if png else None,
                    "glb_renderer": GLB_RENDERER, "png_renderer": PNG_RENDERER,
                }
                if notes:
                    entry["notes"] = notes
                    failed += 1
                else:
                    done += 1
                manifest[key] = entry
                save_manifest(args.manifest, manifest)
                state = "ok" if not notes else "PARTIAL"
                print(f"  {state:8} {slug:28} {path[:44]:44} "
                      f"{(args.out/f'{glb_key}.glb').stat().st_size // 1024 if glb else 0:>5} KB glb")
            except Exception as e:
                # NO SINGLE BOARD FILE MAY END THE RUN. Two DIFFERENT unhandled
                # exceptions already have -- a 504 inside put(), then a bare
                # TimeoutError inside exists() -- each discovered only by losing
                # the rest of a 3.5-hour run to it. Patching exception types one
                # at a time as they turn up chases the wrong thing: across
                # hundreds of network calls to dozens of remote hosts over several
                # hours, something unanticipated will keep happening. This is the
                # backstop for whatever that is next time -- the answer to "this
                # board file broke in a new way" becomes "it is a note on this
                # board file," permanently, not "read the traceback and add
                # another except clause."
                manifest[key] = {"repo": info["repo"], "path": path,
                                  "notes": [f"crashed: {type(e).__name__}: {e}"[:300]]}
                save_manifest(args.manifest, manifest)
                failed += 1
                print(f"  CRASHED  {slug:28} {path[:44]:44} "
                      f"{type(e).__name__}: {e}"[:120])

    save_manifest(args.manifest, manifest)
    print(f"\n  {done} board files exported, {failed} partial, {skipped} unchanged")
    if unreadable:
        print(f"  {len(unreadable)} board files could not be fetched (Unknown, not absent):")
        for slug, path, err in unreadable[:10]:
            print(f"    {slug} {path} -- {err}")
    print(f"  assets -> {args.out}")
    print(f"  manifest -> {args.manifest.relative_to(REPO)}")
    return 1 if failed or unreadable else 0


if __name__ == "__main__":
    sys.exit(main())
