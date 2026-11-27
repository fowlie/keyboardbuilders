// Every heart button on a page: the design page's byline and every card (owner, 2026-11-27).
//
// SIGNED OUT, localStorage "kb-hearts" is this browser's list, in the order hearted, and
// the server keeps only the totals -- as before.
//
// SIGNED IN, the account is the list. On the first page after signing in, the browser's
// list is merged into the account (PUT /hearts, not counted again); on every later page
// the account's list is read (GET /hearts). Either way it is written back to localStorage,
// so /favorites and the signed-out pages keep working from the same list.
// "kb-hearts-merged" names the login already merged, and a signed-out page clears it, so
// hearts pressed while signed out are merged at the next sign-in.
//
// A press changes every button for that design on the page, count included, from the
// total the server answers with -- no reload. A failed request puts it all back.
(function () {
    var KEY = "kb-hearts", MERGED = "kb-hearts-merged";
    var login = document.currentScript.getAttribute("data-login") || "";

    function get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
    function set(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }
    function load() {
        try { var v = JSON.parse(get(KEY) || "[]"); return Array.isArray(v) ? v : []; }
        catch (e) { return []; }
    }
    function save(list) { set(KEY, JSON.stringify(list)); }

    function buttons(slug) {
        var all = Array.prototype.slice.call(document.querySelectorAll("button.heart[data-slug]"));
        return slug ? all.filter(function (b) { return b.getAttribute("data-slug") === slug; }) : all;
    }
    function show(btn, on) {
        btn.setAttribute("aria-pressed", on ? "true" : "false");
        btn.querySelector(".heart-icon").textContent = on ? "♥" : "♡";
    }
    function paint(slug, on, count) {
        buttons(slug).forEach(function (b) {
            show(b, on);
            var c = b.querySelector(".heart-count");
            if (c && count != null) c.textContent = count;
        });
    }
    function wake() {
        var list = load();
        buttons().forEach(function (b) {
            show(b, list.indexOf(b.getAttribute("data-slug")) !== -1);
            b.disabled = false;
        });
    }

    function sync() {
        if (!login) { set(MERGED, null); return Promise.resolve(); }
        var merging = get(MERGED) !== login;
        return fetch("/hearts", merging
                ? { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(load()) }
                : { method: "GET" })
            .then(function (r) { if (!r.ok) throw r; return r.json(); })
            .then(function (list) { save(list); set(MERGED, login); })
            .catch(function () {});
    }

    document.addEventListener("click", function (e) {
        var btn = e.target.closest && e.target.closest("button.heart[data-slug]");
        if (!btn || btn.disabled) return;
        var slug = btn.getAttribute("data-slug");
        var list = load();
        var on = list.indexOf(slug) === -1;
        save(on ? list.concat([slug]) : list.filter(function (s) { return s !== slug; }));
        paint(slug, on, null);
        buttons(slug).forEach(function (b) { b.disabled = true; });
        fetch("/designs/" + encodeURIComponent(slug) + "/heart", { method: on ? "PUT" : "DELETE" })
            .then(function (r) { if (!r.ok) throw r; return r.json(); })
            .then(function (t) { paint(slug, on, t.count); })
            .catch(function () {
                save(load().filter(function (s) { return s !== slug; }).concat(on ? [] : [slug]));
                paint(slug, !on, null);
            })
            .finally(function () { buttons(slug).forEach(function (b) { b.disabled = false; }); });
    });

    sync().then(wake);
})();
