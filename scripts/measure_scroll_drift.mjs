// Does `content-visibility` make the scrollbar lie? Forgejo #13.
//
// A row whose layout has been skipped still occupies space, and the space it claims is
// `contain-intrinsic-size`. Get that number wrong and the page's height is wrong until
// each row is scrolled into view and laid out for real -- a scrollbar that moves under
// the reader's thumb. Nothing in the JUnit suite can see this: the stylesheet parses
// fine, the rows are all in the document, and only a real layout engine disagrees.
//
// THE BUG THIS EXISTS FOR, because it will be made again. The rule first shipped with
// `contain-intrinsic-size: auto 107px`, the row's measured height. But
// `contain-intrinsic-size` sizes the CONTENT box -- `box-sizing: border-box` does not
// apply to it -- so every row was over-estimated by its 29px of padding and border, and
// the page lost 14.5% of its height as it was read. The content box is 78px. Measured
// drift fell to 0.0% on desktop and 0.6% on mobile, the residual being real variation
// in restacked row heights around their median.
//
// HOW TO READ THE OUTPUT. `driftPct` is how much the page's total height changed
// between "just loaded" and "every row laid out". The baseline run disables the
// property, so it is 0 by construction and exists to prove the harness measures
// something. Anything above ~1% with the property on is a wrong intrinsic size.
//
// Usage:
//   chromium --headless=new --remote-debugging-port=9222 --disable-gpu --no-sandbox \
//            --user-data-dir=/tmp/prof about:blank &
//   node scripts/measure_scroll_drift.mjs 9222 http://localhost:8080/designs

const PORT = process.argv[2] || 9222;
const URL_ = process.argv[3] || "http://localhost:8080/designs";

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));

let id = 0;
const waiting = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m.result); waiting.delete(m.id); }
};
const send = (method, params = {}) =>
  new Promise((res) => { const n = ++id; waiting.set(n, res); ws.send(JSON.stringify({ id: n, method, params })); });
const evaluate = async (expression) =>
  (await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true })).result?.value;

await send("Page.enable");
await send("Runtime.enable");

// `disable` forces the rows back to ordinary rendering, which is the control.
const probe = (disable) => `(async () => {
  ${disable ? `const off = document.createElement('style');
  off.textContent = 'ul.results li { content-visibility: visible !important; }';
  document.head.appendChild(off);
  await new Promise(r => requestAnimationFrame(r));` : ""}
  const before = document.documentElement.scrollHeight;
  for (let y = 0; y < before; y += window.innerHeight) {
    window.scrollTo(0, y);
    await new Promise(r => requestAnimationFrame(r));
  }
  window.scrollTo(0, 0);
  await new Promise(r => requestAnimationFrame(r));
  const after = document.documentElement.scrollHeight;
  const cs = getComputedStyle(document.querySelector('ul.results li'));
  const chrome = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom)
               + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
  const content = [...document.querySelectorAll('ul.results li')]
    .map(e => Math.round(e.getBoundingClientRect().height - chrome)).sort((a, b) => a - b);
  return {
    rows: content.length,
    scrollHeightBefore: before,
    scrollHeightAfter: after,
    driftPct: +(100 * Math.abs(after - before) / before).toFixed(1),
    // What the intrinsic size should be: the CONTENT box, not the row's height.
    contentBox: { min: content[0], median: content[Math.floor(content.length / 2)],
                  max: content[content.length - 1], paddingAndBorder: Math.round(chrome) },
  };
})()`;

const VIEWPORTS = [
  ["desktop 1280", { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }],
  ["mobile 390", { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }],
];

for (const [label, metrics] of VIEWPORTS) {
  for (const disable of [false, true]) {
    await send("Emulation.setDeviceMetricsOverride", metrics);
    await send("Page.navigate", { url: URL_ });
    for (let i = 0; i < 200; i++) {
      if (await evaluate("document.readyState === 'complete'")) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    await new Promise((r) => setTimeout(r, 500));
    const result = await evaluate(probe(disable));
    console.log(JSON.stringify({ viewport: label, contentVisibility: !disable, ...result }));
  }
}
ws.close();
process.exit(0);
