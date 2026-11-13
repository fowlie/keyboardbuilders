#!/usr/bin/env python3
"""Build the contact sheet a human reviews to approve photos -- #101 step 2.

Step 1's `photo-candidates.json` is a ceiling, not a decision: it survived a filter that
only removes what no FORMAT lets be a photo (an .svg, a badge host). Whether an image is
a real photo of the right board is exactly the judgement #101 measured cannot be
automated -- one repo's own README embedded a Kinesis Advantage and an ErgoDox EZ,
correctly, for comparison, and nothing about the file format says so.

EACH DESIGN'S OWN SILHOUETTE SITS BESIDE ITS CANDIDATES, and that is the whole safety
device this page is built around: a photo of the wrong board is obvious next to the right
board's own shape in a way it is not obvious next to a caption or a filename. Pulled
straight from the running site (`--base-url`, default localhost:8080) rather than
re-implemented here -- the SVG is Silhouettes.kt's own rendering, generated from the same
key positions the real page shows, so there is no second copy of that logic to drift.

ONE RADIO GROUP PER DESIGN, not checkboxes, because the contract downstream is one photo
per design -- the UI should not be able to represent a state the site cannot use. Nothing
is selected by default, and leaving a design untouched is a real, common answer here: #101
measured this ceiling as ~40% real photos, so most rows are expected to end with nothing
picked.

WRITES NOTHING ON ITS OWN. The "Copy decisions" button serializes the checked radios to
JSON and puts it in a textarea and (where the browser allows it) the clipboard -- paste
that into `photo-decisions.json` for step 3 to hand to `add_photo.py --batch`. No image is
downloaded, normalized, or uploaded by looking at this page.

Usage: photo_contact_sheet.py [--candidates data/photo-candidates.json]
                               [--base-url http://127.0.0.1:8080]
                               [--out build/photo-contact-sheet.html]
"""
import argparse
import html
import json
import pathlib
import re
import sys
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent

H1 = re.compile(r"<h1>([^<]*)</h1>")
SILHOUETTE = re.compile(r'<svg class="silhouette".*?</svg>', re.S)

# Some READMEs are real build-guide galleries (one design's own has 51 embedded images),
# not noise -- capped per row with a scrollbar rather than truncated, so nothing a human
# might pick is hidden, only pushed below the fold of its own small box.
GRID_MAX_HEIGHT = "20rem"

# A ROW GETS AN EXPLICIT "N images, scroll for more" NOTE PAST THIS MANY, rather than
# relying on a reader to notice a scrollbar on a box that already looks full. Owner's
# report: "since the photos were a bit capped I could not answer for those" -- a silent
# cap is indistinguishable from "that's everything" until you go looking.
SCROLL_HINT_ABOVE = 8


def page_furniture(base_url, slug):
    """(name, silhouette_svg) read off the design's own live page, or a fallback if the
    page 404s -- a design can be licence-clear here and still, rarely, not resolve on
    the running site (a slug rename mid-session, a design removed since)."""
    try:
        with urllib.request.urlopen(f"{base_url}/designs/{slug}", timeout=10) as resp:
            body = resp.read().decode("utf-8", "replace")
    except Exception as e:
        return slug, f"<p class='no-silhouette'>({e.__class__.__name__})</p>"
    name_match = H1.search(body)
    svg_match = SILHOUETTE.search(body)
    name = html.unescape(name_match.group(1)) if name_match else slug
    svg = svg_match.group(0) if svg_match else "<p class='no-silhouette'>(no silhouette)</p>"
    return name, svg


def render(candidates, base_url, decisions):
    rows = []
    total_images = 0
    done = 0
    for i, (slug, entry) in enumerate(sorted(candidates.items())):
        name, svg = page_furniture(base_url, slug)
        images = entry["images"]
        total_images += len(images)
        # ALREADY SHIPPED, FROM A PRIOR "COPY DECISIONS" ROUND -- pre-checked and marked,
        # so re-opening this page for a second pass (as opposed to a fresh one) shows at
        # a glance which of the 162 still need a look, rather than presenting all of them
        # as equally undecided and risking a re-pick that quietly changes what a design
        # already shows live.
        picked_url = decisions.get(slug, {}).get("url")
        if picked_url:
            done += 1
        print(f"  {i + 1}/{len(candidates)} {slug}", file=sys.stderr)

        thumbs = []
        for j, img in enumerate(images):
            url = html.escape(img["url"], quote=True)
            alt = html.escape(img["alt"] or "")
            checked = " checked" if img["url"] == picked_url else ""
            thumbs.append(
                f'<label class="shot">'
                f'<input type="radio" name="pick::{html.escape(slug, quote=True)}" '
                f'value="{url}"{checked}>'
                # OPENS THE ORIGINAL, FULL SIZE, IN A NEW TAB -- a 9rem box cannot show
                # enough of a wide split board to count its outer columns, which is
                # exactly the judgement a size variant (a 3x5 against a 4x6, a 46-key
                # against a 52) needs. stopPropagation so following the link does not
                # also toggle the radio underneath it.
                f'<a class="expand" href="{url}" target="_blank" rel="noopener" '
                f'title="Open full size" onclick="event.stopPropagation()">&#128269;</a>'
                f'<img src="{url}" alt="{alt}" loading="lazy">'
                f'</label>'
            )

        hint = ""
        if len(images) > SCROLL_HINT_ABOVE:
            hint = (f'<p class="scroll-hint">{len(images)} images -- '
                    f'scroll inside the box to see them all</p>')

        shipped_badge = '<span class="shipped">&check; live</span>' if picked_url else ""

        rows.append(f'''
    <section class="row{' done' if picked_url else ''}" id="row-{html.escape(slug, quote=True)}"
             data-slug="{html.escape(slug, quote=True)}">
      <div class="ref">
        <a href="{base_url}/designs/{html.escape(slug, quote=True)}" target="_blank"
           rel="noopener">{html.escape(name)}</a> {shipped_badge}
        <div class="slug">{html.escape(slug)}</div>
        <div class="silhouette-box">{svg}</div>
        <a class="repo" href="{html.escape(entry['repo'], quote=True)}" target="_blank"
           rel="noopener">repo &#8599;</a>
        {hint}
      </div>
      <div class="shots">{''.join(thumbs)}</div>
    </section>''')

    return DOC.format(
        count=len(candidates), total=total_images, rows="\n".join(rows),
        done=done, remaining=len(candidates) - done,
    )


DOC = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Photo contact sheet -- backlog #101</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{ font: 15px/1.4 system-ui, sans-serif; margin: 0; padding: 0 0 6rem; }}
  header {{
    position: sticky; top: 0; z-index: 10;
    background: Canvas; border-bottom: 1px solid color-mix(in srgb, CanvasText 20%, transparent);
    padding: .75rem 1rem; display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
  }}
  header h1 {{ font-size: 1rem; margin: 0; }}
  header .count {{ color: color-mix(in srgb, CanvasText 60%, transparent); font-size: .85rem; }}
  #copy {{ margin-left: auto; padding: .5rem 1rem; cursor: pointer; }}
  #picked-count {{ font-size: .85rem; }}
  #output {{
    width: 100%; box-sizing: border-box; font: 12px/1.4 ui-monospace, monospace;
    padding: .5rem; margin: 0 1rem; display: none;
  }}
  .row {{
    display: flex; gap: 1rem; padding: .75rem 1rem; border-bottom: 1px solid
    color-mix(in srgb, CanvasText 12%, transparent); align-items: flex-start;
  }}
  .row.picked {{ background: color-mix(in srgb, Highlight 12%, transparent); }}
  /* DIMMED, NOT HIDDEN -- a design already shipped from a prior "Copy decisions" round
     is still here to change your mind on, just not what a second pass should spend
     attention on first. */
  .row.done {{ opacity: .55; }}
  .row.done:focus-within, .row.done:hover {{ opacity: 1; }}
  .shipped {{
    font-size: .7rem; font-weight: 400; color: color-mix(in srgb, Highlight 70%, CanvasText);
  }}
  .ref {{ flex: 0 0 11rem; }}
  .ref a {{ font-weight: 600; text-decoration: none; }}
  .ref .slug {{ font-size: .75rem; color: color-mix(in srgb, CanvasText 55%, transparent); }}
  .ref .repo {{ display: block; font-size: .75rem; margin-top: .3rem; }}
  .silhouette-box {{ width: 100%; height: 4rem; margin: .4rem 0; }}
  .silhouette-box svg {{ width: 100%; height: 100%; color: CanvasText; }}
  .no-silhouette {{ font-size: .75rem; color: color-mix(in srgb, CanvasText 50%, transparent); }}
  .shots {{
    flex: 1; display: flex; flex-wrap: wrap; gap: .4rem; align-content: flex-start;
    max-height: {grid_max}; overflow-y: auto; padding: .2rem;
  }}
  .shot {{
    position: relative; display: block; width: 9.5rem; height: 7.5rem; cursor: pointer;
    border: 2px solid transparent; border-radius: 4px; overflow: hidden; flex: none;
    background: color-mix(in srgb, CanvasText 8%, transparent);
  }}
  /* CONTAIN, NOT COVER -- owner's report: "many photos where I don't see enough of the
     outer columns to be able to detect the different layouts apart". A crop that fills
     the box hides exactly the columns that tell a 3x5 apart from a 4x6. Letterboxing on
     the tinted background above is the honest trade for showing the whole picture. */
  .shot img {{ width: 100%; height: 100%; object-fit: contain; display: block; }}
  .shot input {{ position: absolute; top: .25rem; left: .25rem; z-index: 2; }}
  .shot:has(input:checked) {{ border-color: Highlight; }}
  .shot .expand {{
    position: absolute; top: .15rem; right: .15rem; z-index: 2;
    width: 1.4rem; height: 1.4rem; display: flex; align-items: center; justify-content: center;
    background: color-mix(in srgb, Canvas 75%, transparent); border-radius: 3px;
    text-decoration: none; font-size: .8rem; line-height: 1;
  }}
  .scroll-hint {{
    margin: .4rem 0 0; font-size: .72rem;
    color: color-mix(in srgb, CanvasText 55%, transparent);
  }}
</style>
</head>
<body>
<header>
  <h1>Photo contact sheet</h1>
  <span class="count">{count} designs &middot; {total} candidate images
    &middot; {done} live &middot; {remaining} still to review</span>
  <span id="picked-count">0 picked</span>
  <button id="copy" type="button">Copy decisions</button>
</header>
<textarea id="output" rows="6" readonly></textarea>
<main>
{rows}
</main>
<script>
(function() {{
  const rows = document.querySelectorAll(".row");
  const pickedCount = document.getElementById("picked-count");
  const output = document.getElementById("output");

  function refresh() {{
    let n = 0;
    rows.forEach((row) => {{
      const checked = row.querySelector("input:checked");
      row.classList.toggle("picked", !!checked);
      if (checked) n++;
    }});
    pickedCount.textContent = n + " picked";
  }}

  document.addEventListener("change", (e) => {{
    if (e.target.matches('input[type="radio"]')) refresh();
  }});

  // A SECOND CLICK ON THE SAME RADIO CLEARS IT, since a radio group alone cannot
  // represent "changed my mind, pick nothing" once something is checked.
  document.querySelectorAll('.shot input[type="radio"]').forEach((input) => {{
    let wasChecked = false;
    input.addEventListener("mousedown", () => {{ wasChecked = input.checked; }});
    input.addEventListener("click", (e) => {{
      if (wasChecked) {{ e.preventDefault(); input.checked = false; refresh(); }}
    }});
  }});

  document.getElementById("copy").addEventListener("click", () => {{
    const decisions = {{}};
    rows.forEach((row) => {{
      const checked = row.querySelector("input:checked");
      if (!checked) return;
      decisions[row.dataset.slug] = {{
        url: checked.value,
        repo: row.querySelector(".repo").href,
      }};
    }});
    const text = JSON.stringify(decisions, null, 2);
    output.value = text;
    output.style.display = "block";
    output.select();
    navigator.clipboard.writeText(text).catch(() => {{}});
  }});

  refresh();
}})();
</script>
</body>
</html>
""".replace("{grid_max}", GRID_MAX_HEIGHT)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--candidates", type=pathlib.Path,
                     default=REPO / "data/photo-candidates.json")
    ap.add_argument("--decisions", type=pathlib.Path,
                     default=REPO / "data/photo-decisions.json",
                     help="a prior round's approved {slug: {url, repo}}, "
                          "pre-checked and marked so a second pass shows what is left")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--out", type=pathlib.Path,
                     default=REPO / "build/photo-contact-sheet.html")
    args = ap.parse_args()

    candidates = json.loads(args.candidates.read_text())
    decisions = json.loads(args.decisions.read_text()) if args.decisions.is_file() else {}
    print(f"{len(candidates)} designs ({len(decisions)} already decided), "
          f"fetching silhouettes from {args.base_url}...", file=sys.stderr)
    doc = render(candidates, args.base_url, decisions)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(doc)
    print(f"written to {args.out.relative_to(REPO)} ({len(doc) // 1024} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
