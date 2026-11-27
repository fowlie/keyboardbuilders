// Compare from the cards (owner, 2026-11-27). The first "Compare" press picks that design
// and shows a bar naming it; a press on another card opens the two side by side. Pressing
// the picked card again, or Cancel, lets it go.
//
// The pick is kept in sessionStorage "kb-compare", so it lasts while this tab searches and
// filters, and is gone when the tab closes. Each "Compare" is a link to the page that asks
// for a second design, so without this script it still does something sensible.
(function () {
    var KEY = "kb-compare";
    function get() {
        try { var v = JSON.parse(sessionStorage.getItem(KEY) || "null"); return v && v.slug ? v : null; }
        catch (e) { return null; }
    }
    function set(v) {
        try { if (v) sessionStorage.setItem(KEY, JSON.stringify(v)); else sessionStorage.removeItem(KEY); } catch (e) {}
    }

    var bar = null;
    function paint() {
        var picked = get();
        document.querySelectorAll("a.card-compare").forEach(function (a) {
            var on = !!picked && a.getAttribute("data-slug") === picked.slug;
            a.classList.toggle("picked", on);
            a.textContent = on ? "Picked" : "Compare";
            if (on) a.setAttribute("aria-current", "true"); else a.removeAttribute("aria-current");
        });
        if (!picked) { if (bar) bar.hidden = true; return; }
        if (!bar) {
            bar = document.createElement("div");
            bar.className = "compare-bar";
            bar.setAttribute("role", "status");
            var text = document.createElement("span");
            var cancel = document.createElement("button");
            cancel.type = "button";
            cancel.textContent = "Cancel";
            cancel.addEventListener("click", function () { set(null); paint(); });
            bar.appendChild(text);
            bar.appendChild(cancel);
            document.body.appendChild(bar);
        }
        bar.firstChild.textContent = "Comparing " + picked.name + " — pick another design";
        bar.hidden = false;
    }

    document.addEventListener("click", function (e) {
        var a = e.target.closest && e.target.closest("a.card-compare");
        // A modified click (new tab, new window) keeps the plain link's behaviour.
        if (!a || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        var slug = a.getAttribute("data-slug");
        var picked = get();
        if (!picked) { set({ slug: slug, name: a.getAttribute("data-name") }); paint(); return; }
        if (picked.slug === slug) { set(null); paint(); return; }
        set(null);
        location.href = "/designs/compare?a=" + encodeURIComponent(picked.slug) + "&b=" + encodeURIComponent(slug);
    });

    // Back from the comparison restores this page from the cache with the old marks.
    window.addEventListener("pageshow", paint);
    paint();
})();
