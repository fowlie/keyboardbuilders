#!/usr/bin/env python3
"""Add an already-approved photo (or a whole batch of them) to seed/photos.json.

Backlog #101. This is NOT the review step -- that is a human (or an agent proposing a
candidate) actually opening the image and deciding it is a real photo of the right board,
which #101 found is not something a filename or alt text can be trusted to say. This
script is what runs AFTER that decision: copy the approved bytes into this project's own
object store, at a content-addressed key, and record the one fact the page needs.

Never a hotlinked GitHub URL. #101 measured why: a repo can rename its default branch,
move the file, or vanish entirely, and none of that trips this project's rebuild -- the
photo would just start 404ing on a live page with nothing to notice. Copying the bytes
once, the same way `render_boards.py` already does for 3D renders, means the credit line
can still point at the source without the picture depending on it staying reachable.

`magick`, not Pillow: every other script in this directory is standard library only
(see `objectstorage.py`'s own docstring for why), and this project already shells out to
external tools for build-time media work (`render_boards.py` -> podman + KiCad). A second
CLI dependency fits that pattern; a Python package that appears in no requirements file
does not.

Usage:
    add_photo.py cantor-remix assets/cantor-remix-original.jpg \\
        --source-url https://github.com/nilokr/cantor-remix

    add_photo.py --batch data/photo-decisions.json
        (the {slug: {url, repo}} shape photo_contact_sheet.py's "Copy decisions" writes,
        plus an optional `credit` for a photo from outside the design's repo)
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

import objectstorage

REPO = pathlib.Path(__file__).resolve().parent.parent
SEED = REPO / "src/main/resources/seed/photos.json"
# SAME DEFAULT AS PhotoAssetController'S OWN, and that is the point: this script and the
# dev server agree on where a photo lives without either one being told twice. An earlier
# version wrote to a TemporaryDirectory and deleted it right after uploading, which meant
# a developer with no bucket credentials could add a photo and then not see it --
# render_boards.py never made that mistake for renders, and this follows the same
# local-directory-first shape as its own controller.

# Wide enough that nobody sees upscaling artefacts on a large monitor, narrow enough that
# a phone-camera original (routinely 3000px+) does not ship its full resolution to every
# reader. `>` on the geometry means "only shrink" -- a smaller source is left alone.
MAX_WIDTH = "1600x1600>"

# IMAGEMAGICK 7's `magick`, or 6's `convert` where that is all a distro ships (the VPS's
# Debian, backlog #104). The arguments below mean the same to both; `identify` is spelled
# the same in both already.
CONVERT = "magick" if shutil.which("magick") else "convert"
QUALITY = "85"


class AddPhotoError(Exception):
    """A single photo's own failure, caught per-item in --batch mode so one bad URL
    (a 404, a moved file, a source gone the way #101's own reasoning expects some of
    these eventually will) does not lose everything else in the run."""


def fetch(spec: str, workdir: pathlib.Path) -> pathlib.Path:
    """A local path or a URL, always ending as a local file worth normalizing. The
    contact sheet's candidates are almost all URLs (raw.githubusercontent.com, imgur,
    a gist, one repo's own CDN) -- this is the one place that stops mattering."""
    if not spec.startswith(("http://", "https://")):
        path = pathlib.Path(spec)
        if not path.is_file():
            raise AddPhotoError(f"no such file: {path}")
        return path
    name = pathlib.Path(urllib.parse.urlparse(spec).path).name or "downloaded"
    dest = workdir / f".fetched-{name}"
    req = urllib.request.Request(spec, headers={"User-Agent": "keyboardbuilders/add_photo"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp, open(dest, "wb") as f:
            shutil.copyfileobj(resp, f)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise AddPhotoError(f"could not fetch {spec}: {e}") from e
    return dest


def normalize(src: pathlib.Path, dst: pathlib.Path) -> None:
    """Re-encode as JPEG, auto-orient then drop the EXIF that recorded the orientation
    (and, on a real phone photo, sometimes GPS coordinates -- stripped regardless of
    whether this particular file carries any, because the cost of checking case-by-case
    is higher than the cost of always stripping).

    `[0]` ON THE SOURCE, ALWAYS: harmless for an ordinary JPEG or PNG (frame 0 is the
    only frame), and load-bearing for one real candidate in this batch -- an animated
    GIF of RGB lighting, whose first frame is a genuine photo of the board. Without it
    ImageMagick treats a multi-frame source as a request to write one output file PER
    FRAME, silently producing dozens of files instead of the one this script expects."""
    try:
        subprocess.run(
            [CONVERT, f"{src}[0]", "-auto-orient", "-resize", MAX_WIDTH, "-strip",
             "-quality", QUALITY, str(dst)],
            check=True, capture_output=True, text=True,
        )
    except subprocess.CalledProcessError as e:
        raise AddPhotoError(f"{CONVERT} failed on {src}: {e.stderr.strip()[:300]}") from e


def dimensions(path: pathlib.Path) -> tuple[int, int]:
    out = subprocess.run(["identify", "-format", "%w %h", str(path)],
                          check=True, capture_output=True, text=True).stdout
    w, h = out.split()
    return int(w), int(h)


def add_one(source: str, out_dir: pathlib.Path) -> dict:
    """Fetch, normalize, hash, upload -- everything but the seed write, which the
    caller does once per run (single item or a whole batch) rather than once per photo,
    so a batch is one seed diff instead of 102 sequential rewrites of the same file."""
    out_dir.mkdir(parents=True, exist_ok=True)
    fetched = fetch(source, out_dir)
    try:
        # NORMALIZED TO A TEMP NAME FIRST, hashed, THEN renamed to the content-addressed
        # name -- the hash is not known until after normalization, so there is nowhere
        # else for the intermediate file to live.
        tmp = out_dir / f".{fetched.name}.normalizing.jpg"
        normalize(fetched, tmp)
    finally:
        if fetched.name.startswith(".fetched-"):
            fetched.unlink(missing_ok=True)

    width, height = dimensions(tmp)
    body = tmp.read_bytes()
    # Same recipe as render_boards.py's `digest()`: the name IS the content, so the asset
    # is safe to cache forever under it. No renderer/version component here -- unlike a
    # render, nothing about how this file was produced can change later without a human
    # re-running this script on purpose, so the bytes alone are the whole input.
    key_hash = hashlib.sha256(body).hexdigest()[:32]
    normalized = out_dir / f"{key_hash}.jpg"
    tmp.replace(normalized)

    obj_key = f"photos/{key_hash}.jpg"
    if objectstorage.exists(obj_key):
        uploaded = False
    else:
        status, _etag = objectstorage.put(normalized, obj_key)
        if status != 200:
            raise AddPhotoError(f"upload failed: HTTP {status}")
        uploaded = True

    return {"jpg": key_hash, "width": width, "height": height, "uploaded": uploaded,
            "bytes": len(body)}


def run_batch(decisions_path: pathlib.Path, out_dir: pathlib.Path) -> None:
    decisions = json.loads(decisions_path.read_text())
    seed = json.loads(SEED.read_text()) if SEED.is_file() else {}

    ok, failed = [], []
    for i, (slug, decision) in enumerate(sorted(decisions.items()), 1):
        url, source_url = decision["url"], decision["repo"]
        print(f"[{i}/{len(decisions)}] {slug} <- {url}", file=sys.stderr)
        try:
            result = add_one(url, out_dir)
        except AddPhotoError as e:
            print(f"    FAILED: {e}", file=sys.stderr)
            failed.append((slug, str(e)))
            continue
        seed[slug] = {"jpg": result["jpg"], "width": result["width"],
                      "height": result["height"], "sourceUrl": source_url}
        if decision.get("credit"):
            seed[slug].update(sourceUrl=decision["credit"], credit=decision["credit"])
        note = "uploaded" if result["uploaded"] else "already in bucket"
        print(f"    ok: {result['jpg']} ({result['width']}x{result['height']}, "
              f"{result['bytes']} bytes, {note})", file=sys.stderr)
        ok.append(slug)

    SEED.write_text(json.dumps(dict(sorted(seed.items())), indent=2) + "\n")

    print(file=sys.stderr)
    print(f"{len(ok)} added, {len(failed)} failed, {SEED.relative_to(REPO)} now has "
          f"{len(seed)} entries", file=sys.stderr)
    if failed:
        print("failed:", file=sys.stderr)
        for slug, reason in failed:
            print(f"  {slug}: {reason}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("slug", nargs="?", help="the design's slug, e.g. cantor-remix")
    ap.add_argument("photo", nargs="?", help="a local path or URL to the approved photo")
    ap.add_argument("--source-url", help="the repo (or README) this photo was approved from")
    ap.add_argument("--credit", help="the page to credit, for a photo not from the design's "
                                     "own repo (kbd.news, a builder's post); shown on the page")
    ap.add_argument("--batch", type=pathlib.Path,
                     help="a {slug: {url, repo}} JSON file, e.g. photo-decisions.json")
    ap.add_argument("--out", type=pathlib.Path, default=REPO / "build/photos",
                     help="local cache dir PhotoAssetController also reads from (default: build/photos)")
    args = ap.parse_args()

    if args.batch:
        if args.slug or args.photo:
            sys.exit("--batch does not take a slug/photo pair -- those come from the file")
        run_batch(args.batch, args.out)
        return

    if not (args.slug and args.photo and args.source_url):
        sys.exit("need slug, photo, and --source-url (or --batch FILE instead)")

    try:
        result = add_one(args.photo, args.out)
    except AddPhotoError as e:
        sys.exit(str(e))

    seed = json.loads(SEED.read_text()) if SEED.is_file() else {}
    seed[args.slug] = {"jpg": result["jpg"], "width": result["width"],
                       "height": result["height"], "sourceUrl": args.source_url}
    if args.credit:
        seed[args.slug]["credit"] = args.credit
    SEED.write_text(json.dumps(dict(sorted(seed.items())), indent=2) + "\n")
    note = "uploaded" if result["uploaded"] else "already in bucket"
    print(f"{args.slug} -> photos/{result['jpg']}.jpg "
          f"({result['width']}x{result['height']}, {note}) recorded in "
          f"{SEED.relative_to(REPO)}")


if __name__ == "__main__":
    main()
