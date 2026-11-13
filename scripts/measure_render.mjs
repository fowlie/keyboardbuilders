// Measure what a facet click costs on /designs. Forgejo issue #12.
//
// `ux.md` §2.7 chose full-page render plus `hx-select` over partial templates, for a
// good reason -- *"No partial templates, no `HX-Request` branching, therefore no
// JS-off/JS-on drift."* -- and named the bet in the same breath: *"full-page rendering
// per swap trades bandwidth for correctness, a bet that only pays if the server hits
// its latency budget."* This script supplies the number that bet is settled against.
//
// TWO NUMBERS PER CONFIGURATION
//
//   cold load  navigation timing, relative to responseEnd so the server and the
//              network are excluded: domInteractive, domComplete, first paint.
//   swap       what an hx-select swap actually does -- replace <main>'s markup and
//              force layout. Timed in-page around `innerHTML` plus a forced reflow,
//              with the fetch deliberately OUTSIDE the timed region, because the
//              cold-load figure already covers the wire.
//
// It also prices one lever in the same visit: `content-visibility: auto` on the result
// rows, which lets the browser skip layout and paint for offscreen rows. Measured
// rather than assumed, and measured here rather than in its own script so the two
// numbers come from the same page, the same browser and the same minute.
//
// THE CPU THROTTLE IS A CONVENTION, NOT A HANDSET. `Emulation.setCPUThrottlingRate`
// at 4x is the multiplier Lighthouse uses for its mid-tier mobile profile on developer
// hardware. Quote it as "4x throttled", never as "on a phone".
//
// MEASURE ON AN IDLE MACHINE. The first run of this against a freshly booted server
// read ~2.5x high -- 142 ms where a re-run gave 52 -- because Chromium was sharing
// cores with the JVM still warming up. Boot the server, wait for it to go quiet, then
// measure. A number that moves 2.5x between runs is measuring the machine, not the page.
//
// Usage:
//   chromium --headless=new --remote-debugging-port=9222 --disable-gpu --no-sandbox \
//            --user-data-dir=/tmp/prof about:blank &
//   LABEL="136 designs" node scripts/measure_render.mjs 9222 http://localhost:8080/designs

const PORT = process.argv[2] || 9222;
const URL_ = process.argv[3] || "http://localhost:8080/designs";
const RUNS = 7;

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find((t) => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));

let id = 0;
const waiting = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m.result ?? m.error); waiting.delete(m.id); }
};
const send = (method, params = {}) =>
  new Promise((res) => {
    const n = ++id;
    waiting.set(n, res);
    ws.send(JSON.stringify({ id: n, method, params }));
  });

const evaluate = async (expression) => {
  const r = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
  return r.result?.value;
};

await send("Page.enable");
await send("Runtime.enable");

const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };

async function load(url) {
  await send("Page.navigate", { url });
  for (let i = 0; i < 300; i++) {
    if (await evaluate("document.readyState === 'complete'").catch(() => false)) break;
    await new Promise((r) => setTimeout(r, 50));
  }
  await new Promise((r) => setTimeout(r, 250));
}

/** The swap, optionally under one CSS lever. Returns every run, not just the median. */
const swapScript = (css) => `(async () => {
  ${css ? `const st = document.createElement('style');
  st.textContent = ${JSON.stringify(css)};
  document.head.appendChild(st);` : ""}
  const html = await (await fetch(location.href)).text();
  const frag = html.slice(html.indexOf('<main>'), html.indexOf('</main>') + 7);
  const main = document.querySelector('main');
  const runs = [];
  for (let i = 0; i < ${RUNS}; i++) {
    const t0 = performance.now();
    main.innerHTML = frag;        // parse
    document.body.offsetHeight;   // force layout
    runs.push(performance.now() - t0);
    await new Promise(r => requestAnimationFrame(r));
  }
  ${css ? "st.remove();" : ""}
  return runs;
})()`;

// `contain-intrinsic-size` is a placeholder height for a skipped row. 44px is this
// page's row height; a wrong value costs scrollbar stability, not swap time.
const LEVER = ".results > li { content-visibility: auto; contain-intrinsic-size: auto 44px; }";

async function measure(label, rate) {
  await send("Emulation.setCPUThrottlingRate", { rate });
  await load(URL_);

  const cold = await evaluate(`(() => {
    const n = performance.getEntriesByType('navigation')[0];
    const fcp = performance.getEntriesByName('first-contentful-paint')[0];
    return { domInteractive: n.domInteractive - n.responseEnd,
             domComplete:    n.domComplete - n.responseEnd,
             fcp:            fcp ? fcp.startTime - n.responseEnd : null,
             elements:       document.getElementsByTagName('*').length,
             rects:          document.getElementsByTagName('rect').length };
  })()`);

  const plain = await evaluate(swapScript(null));
  const lever = await evaluate(swapScript(LEVER));

  console.log(JSON.stringify({
    label, cpuThrottle: `${rate}x`, cold,
    swap: Math.round(median(plain)), swapRuns: plain.map(Math.round),
    contentVisibility: Math.round(median(lever)), contentVisibilityRuns: lever.map(Math.round),
  }));
}

await measure(process.env.LABEL || "designs", 1);
await measure(`${process.env.LABEL || "designs"} (4x throttled)`, 4);
ws.close();
process.exit(0);
