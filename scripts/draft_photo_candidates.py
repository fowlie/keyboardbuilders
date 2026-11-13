#!/usr/bin/env python3
"""Find candidate photos in each design's own repo, for a human to look at -- #101 step 1.

#101 measured why this cannot be the drafter itself: a filename or an alt attribute is
not evidence of what an image shows. Only one of fifteen sampled repos said "Render" in
its own alt text, and `robcmills/karn-keyboard` embeds real photos of a Kinesis Advantage
and an ErgoDox EZ -- shown for comparison, never labelled as such. So this script's job
ends at "here is every image a README embeds, minus the ones no format lets be a photo";
deciding which is a real, right photo is `photo_contact_sheet.py`'s job, for a human.

LICENCE-GATED THE SAME WAY #60's RENDERS WERE: only designs whose hardware licence is
neither unstated nor non-commercial are asked about at all, so a "yes, use it" from the
contact sheet needs no separate legal review after the fact. Measured over the corpus:

    266  publishable   (this script's whole input)
     36  unstated                    -- not asked
     26  non-commercial              -- not asked

FETCHED BY graphql, ONE QUERY PER BATCH OF REPOS, the same shape `measure_summaries.py`
already proved out: `HEAD:README.md` resolves against whatever the default branch is
without this script ever guessing "main" vs "master", and a batch of 20 costs one `gh`
call rather than 20. `defaultBranchRef.name` rides along in the same query, because a
relative image path (`assets/photo.jpg`, the common case) needs a real branch name to
become a fetchable URL and the query is the one place that name is already known.

WHAT NEVER SURVIVES, categorically, not by guessing at content: `.svg`, because no
photograph is ever vector art; shields.io/badge-shaped hosts and GitHub's own workflow
badges, because a build-status icon is not a picture of a board; `camo.githubusercontent
.com`, GitHub's proxy for a hotlinked external image, which is nearly always another badge
and is in any case a second hop this script cannot verify. Everything else survives to the
contact sheet, including images this script cannot itself tell apart from a schematic or
a render -- that judgement is exactly the one #101 found cannot be automated.

THE REPO'S OTHER IMAGES COME SECOND, after every image the README embeds. By 2026-09-29
the README alone had run dry: of 270 licence-clear designs, 102 had no photo and no README
image to ask about, while their repos held files like `img/axon_built.jpg` that no README
links. So each repo's file listing (`source-trees.json`, which the nightly's refresh step
fills) adds its image files, minus the ones that are categorically not a board photo:
vendored firmware and libraries, KiCad footprint and 3D-model folders, logos, icons,
badges. The README's own images stay first, because the README is where an author puts
the picture they chose; a `.jpg` sorts before a `.png`, because photos are JPEGs far more
often than renders and diagrams are.

A SHARED REPO'S FILES MUST NAME THE DESIGN. A README shared by several variants is at
least written about all of them; a repo's files are not. Where several designs cite the
same repo, a file only counts for a design if its path contains the design's slug
(dashes and case ignored) -- presence, not absence: a variant whose photos are named some
other way is just not asked from the tree, never answered "none" for it.

A HEAD REQUEST IS THE LAST GATE, not a content check: a relative path resolved against
the wrong branch, or a README that names a file since deleted, 404s, and a 404 is worth
dropping before a human ever sees it rather than after.

Usage: draft_photo_candidates.py [--out data/photo-candidates.json]
"""
import argparse
import concurrent.futures
import json
import pathlib
import re
import subprocess
import sys
import urllib.parse
import urllib.error
import urllib.request
from collections import Counter

REPO = pathlib.Path(__file__).resolve().parent.parent
DESIGNS = REPO / "src/main/resources/seed/designs.json"
TREES = REPO / "data/source-trees.json"

GITHUB = re.compile(r"https://github\.com/([^/]+)/([^/#?]+)")
NC = re.compile(r"NC", re.I)

# Five names, same set `measure_summaries.py` already checks -- a hardware project's
# README is not always spelled the GitHub-default way.
README_PATHS = {
    "md": "README.md", "lower": "readme.md", "bare": "README",
    "rst": "README.rst", "txt": "README.txt",
}
BATCH = 20

# Markdown image syntax, with or without a title in quotes; and a raw <img> tag, since a
# README that wants to size or centre a picture drops to HTML for it -- discipline's own
# "Photo from doc/images/discipline.jpeg" arrived that way in #101's sample.
IMAGE_MD = re.compile(r"!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
IMG_TAG = re.compile(r"""<img\b[^>]*?\bsrc=["']([^"']+)["'][^>]*>""", re.I)
ALT_ATTR = re.compile(r"""\balt=["']([^"']*)["']""", re.I)

NEVER_A_PHOTO = (".svg",)
BADGE_HOST = re.compile(
    r"shields\.io|badge\.fury\.io|badgen\.net|creativecommons\.org"
    r"|github\.com/[^/\s]+/[^/\s]+/(actions|workflows)/"
    r"|camo\.githubusercontent\.com"
    # A FILENAME OR PATH SEGMENT THAT SAYS "badge", not a guess about content: nobody
    # names their own board photo that way, and it is the same category of signal as
    # the .svg rule above -- a fact about what the file IS, not a claim about what it
    # shows. Measured false-negative before this: robcmills/karn-keyboard's own images
    # never say "badge" and still needed the contact sheet, which this does not change.
    r"|[/_-]badge[s]?[/_.-]",
    re.I,
)


def bucket(hardware_licences):
    if not hardware_licences:
        return "unstated"
    if any(NC.search(l) for l in hardware_licences):
        return "non-commercial"
    return "publishable"


def repos_in(designs):
    """slug -> (owner, name), one entry per design whose sole/first GitHub source we can
    read -- ambiguity about WHICH source is a design's own is #22's problem, not this
    script's; the first GitHub URL a design cites is the one already used everywhere else
    on its page."""
    out = {}
    for design in designs:
        for source in design.get("sources") or []:
            hit = GITHUB.match(source)
            if hit:
                out[design["slug"]] = (hit.group(1), hit.group(2).removesuffix(".git"))
                break
    return out


def query_for(batch):
    parts = []
    for i, (owner, name) in enumerate(batch):
        objects = " ".join(
            f'{key}: object(expression: "HEAD:{path}") {{ ... on Blob {{ text }} }}'
            for key, path in README_PATHS.items()
        )
        parts.append(
            f'  r{i}: repository(owner: "{owner}", name: "{name}") {{ '
            f'defaultBranchRef {{ name }} {objects} }}'
        )
    return "query {\n" + "\n".join(parts) + "\n}"


def fetch(repos):
    """slug -> (branch, readme_text), or (None, None) where nothing was readable."""
    out = {}
    slugs = list(repos)
    for start in range(0, len(slugs), BATCH):
        chunk = slugs[start:start + BATCH]
        query = query_for([repos[s] for s in chunk])
        run = subprocess.run(["gh", "api", "graphql", "-f", f"query={query}"],
                              capture_output=True, text=True)
        if run.returncode != 0:
            print(f"  batch {start}: {run.stderr.strip()[:200]}", file=sys.stderr)
            for slug in chunk:
                out[slug] = (None, None)
            continue
        data = json.loads(run.stdout).get("data") or {}
        for i, slug in enumerate(chunk):
            repo = data.get(f"r{i}") or {}
            branch = (repo.get("defaultBranchRef") or {}).get("name")
            text = None
            for key in README_PATHS:
                blob = repo.get(key)
                if blob and blob.get("text"):
                    text = blob["text"]
                    break
            out[slug] = (branch, text)
        print(f"  fetched {min(start + BATCH, len(slugs))}/{len(slugs)}", file=sys.stderr)
    return out


def images_in(readme):
    """(alt, raw_ref) for every embedded image, markdown or HTML, in document order,
    de-duplicated by the reference itself -- a README that shows the same picture twice
    (a badge in the header AND the footer) should not become two candidates."""
    seen, out = set(), []
    for alt, ref in IMAGE_MD.findall(readme):
        if ref not in seen:
            seen.add(ref)
            out.append((alt, ref))
    for tag in IMG_TAG.finditer(readme):
        ref = tag.group(1)
        if ref in seen:
            continue
        seen.add(ref)
        alt_match = ALT_ATTR.search(tag.group(0))
        out.append((alt_match.group(1) if alt_match else "", ref))
    return out


# A GITHUB "BLOB" VIEW URL, HANDED TO US AS IF IT WERE A FILE -- gtips/reviung's own
# README embeds its images this way: `![REVIUNG34](https://github.com/gtips/reviung/
# blob/master/reviung34/image/reviung34-01.jpg)`, no `?raw=true`. GitHub's OWN markdown
# renderer resolves that specially when the README is viewed ON github.com, but fetched
# from anywhere else -- this script, an `<img src>`, `curl` -- it is 200 OK text/html,
# the file-viewer PAGE, never the bytes. Measured, not assumed: an owner-reported "the
# reviung photos doesn't show" traced to exactly this, and add_photo.py would have
# thrown a real "not an image" error on the first one had it gone through unrewritten.
BLOB_URL = re.compile(r"^https://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+?)(?:\?.*)?$")


def resolve(owner, name, branch, ref):
    """A README's own image reference, however it spelled it, as an absolute URL a
    contact sheet can put in an <img src> directly."""
    if ref.startswith("http://") or ref.startswith("https://"):
        blob = BLOB_URL.match(ref)
        # REWRITTEN TO raw.githubusercontent.com REGARDLESS OF `?raw=true`'s PRESENCE,
        # rather than trusting that query param: it works by triggering a 302 (confirmed
        # against dogtag's own already-correct candidate), which is one more hop this
        # script and add_photo.py would otherwise both have to follow blind. The canonical
        # host is what nearly every other candidate in this corpus already resolves to.
        if blob:
            b_owner, b_repo, b_ref, b_path = blob.groups()
            return f"https://raw.githubusercontent.com/{b_owner}/{b_repo}/{b_ref}/{b_path}"
        return ref
    if ref.startswith("//"):
        return "https:" + ref
    # ROOT-RELATIVE, because the only READMEs this script ever reads are at the repo
    # root (README_PATHS names nothing else) -- a relative reference in that file is
    # relative to the root it lives in, not to some other path.
    return f"https://raw.githubusercontent.com/{owner}/{name}/{branch}/{ref.lstrip('./')}"


def looks_like_noise(url):
    lower = url.lower().split("?", 1)[0]
    if lower.endswith(NEVER_A_PHOTO):
        return True
    return bool(BADGE_HOST.search(url))


# TREE IMAGES ONLY FILL UP TO WHAT AN ISSUE SHOWS -- intake_ask.py's MAX_IMAGES, the
# first dozen. `kbd-template` has 106; checking the other 94 would triple the HEAD
# requests for pictures no one sees, and more requests is more transient failures, each
# one a design that silently drops out of the night's questions (wave-rounded and zero
# did, in the first trial).
SHOWN = 12
TREE_IMAGE = re.compile(r"\.(jpe?g|png|webp)$", re.I)
# Folders that hold someone else's files or a CAD tool's, never a photo of this board:
# `45` vendors a whole qmk_firmware, LUFA's documentation screenshots and all.
NOT_THIS_BOARD_DIR = re.compile(
    r"(^|/)(qmk_firmware|tmk_core|zmk|lib|libs|library|libraries|vendor|third_party"
    r"|node_modules|\.github|fonts?|footprints?|symbols?|3d_?models?|3d|kicad[-_]?lib[^/]*"
    r"|[^/]*\.pretty[^/]*|[^/]*\.3dshapes[^/]*|[^/]*librar[^/]*"
    # `-master` is GitHub's name for a downloaded zip of ANOTHER repo, unpacked in this
    # one: SparkFun-KiCad-Libraries-master, MX_Alps_Hybrid-master.
    r"|[^/]+-master)/",
    re.I,
)
# A filename that says what it is. Same category as the badge rule: a fact about the
# file, not a guess about what it shows.
NOT_A_PHOTO_NAME = re.compile(
    r"(^|[/_. -])(logo|icon|favicon|badge|banner|keyboard-layout|kle)s?[_. -]", re.I)


def squash(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())


def tree_images(paths, slug, shared):
    """A repo listing's image files that could be a photo of `slug`, best first."""
    kept = [p for p in paths
            if TREE_IMAGE.search(p) and not NOT_THIS_BOARD_DIR.search(p)
            and not NOT_A_PHOTO_NAME.search("/" + p)]
    if shared:
        kept = [p for p in kept if squash(slug) in squash(p)]
    return sorted(kept, key=lambda p: (not re.search(r"\.jpe?g$", p, re.I), p.count("/"), p))


def raw_url(owner, name, branch, path):
    return (f"https://raw.githubusercontent.com/{owner}/{name}/{branch}/"
            + urllib.parse.quote(path))


def reachable(url, tries=2):
    for _ in range(tries):
        try:
            req = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
        except Exception:
            pass
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=pathlib.Path,
                     default=REPO / "data/photo-candidates.json")
    ap.add_argument("--limit", type=int, help="only the first N eligible designs, for a trial run")
    args = ap.parse_args()

    designs = json.loads(DESIGNS.read_text())
    eligible = [d for d in designs if bucket(d.get("licensing", {}).get("hardware")) == "publishable"]
    print(f"{len(designs)} designs, {len(eligible)} licence-clear", file=sys.stderr)
    if args.limit:
        eligible = eligible[:args.limit]

    repos = repos_in(eligible)
    slug_to_repo = repos
    print(f"fetching READMEs for {len(repos)} repos...", file=sys.stderr)
    fetched = fetch(repos)

    # SHARED COUNTS EVERY DESIGN, not just the licence-clear ones: a repo is shared with
    # a non-commercial sibling all the same.
    citing = Counter(repos_in(designs).values())
    try:
        trees = {k.lower(): v["paths"] for k, v in json.loads(TREES.read_text()).items()}
    except FileNotFoundError:
        print(f"no {TREES.relative_to(REPO)}: README images only", file=sys.stderr)
        trees = {}

    candidates = {}
    images_to_check = []  # (slug, alt, path, url) awaiting a HEAD request
    no_readme = no_images = from_tree = 0
    for slug, (branch, readme) in fetched.items():
        owner, name = slug_to_repo[slug]
        kept = []
        if readme:
            for alt, ref in images_in(readme):
                url = resolve(owner, name, branch or "HEAD", ref)
                if not looks_like_noise(url):
                    kept.append((alt, None, url))
        else:
            no_readme += 1
        seen = {urllib.parse.unquote(url) for _, _, url in kept}
        for path in tree_images(trees.get(f"{owner}/{name}".lower(), []), slug,
                                citing[(owner, name)] > 1):
            if len(kept) >= SHOWN:
                break
            url = raw_url(owner, name, branch or "HEAD", path)
            if urllib.parse.unquote(url) not in seen:
                seen.add(urllib.parse.unquote(url))
                kept.append(("", path, url))
                from_tree += 1
        if not kept:
            no_images += 1
            continue
        for alt, path, url in kept:
            images_to_check.append((slug, alt, path, url))

    print(f"{len(images_to_check)} candidate images across "
          f"{len(images_to_check) and len({s for s, *_ in images_to_check})} designs "
          f"({from_tree} from the repo tree, not the README), "
          f"checking reachability...", file=sys.stderr)

    checked = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results = pool.map(lambda item: (item, reachable(item[3])), images_to_check)
        for (slug, alt, path, url), ok in results:
            checked += 1
            if checked % 100 == 0:
                print(f"  checked {checked}/{len(images_to_check)}", file=sys.stderr)
            if not ok:
                continue
            owner, name = slug_to_repo[slug]
            entry = candidates.setdefault(slug, {
                "repo": f"https://github.com/{owner}/{name}",
                "images": [],
            })
            entry["images"].append({"url": url, "alt": alt, **({"path": path} if path else {})})

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(dict(sorted(candidates.items())), indent=2) + "\n")

    total_images = sum(len(v["images"]) for v in candidates.values())
    print(file=sys.stderr)
    print(f"{len(eligible)} licence-clear designs", file=sys.stderr)
    print(f"{no_readme} no README readable", file=sys.stderr)
    print(f"{no_images} no image in README or repo survived the noise filter", file=sys.stderr)
    print(f"{len(candidates)} designs with at least one reachable candidate image "
          f"({total_images} images total)", file=sys.stderr)
    print(f"written to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
