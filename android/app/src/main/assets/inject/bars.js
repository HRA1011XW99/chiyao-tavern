// Injected into SillyTavern's page (MainActivity): reports the colors of its top bar and its input area, so
// the app paints the space it keeps free above and below the page (status bar, navigation bar, keyboard) in
// the same colors. Themes change them through CSS variables on <html>, so changes there are reported again.
(function () {
    'use strict';
    if (window.__tavernBars) return;
    window.__tavernBars = true;
    const KEY = '__KEY__';

    // "rgb(23, 23, 23)" / "rgba(23, 23, 23, 0.5)" -> [r, g, b, a]
    function parse(css) {
        const m = /rgba?\(([^)]+)\)/.exec(css || '');
        if (!m) return null;
        const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
        return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
    }

    // the element's own color, or the first ancestor's that is not fully transparent; drawn as opaque
    function colorOf(selector) {
        for (let el = document.querySelector(selector); el; el = el.parentElement) {
            const c = parse(getComputedStyle(el).backgroundColor);
            if (c && c[3] > 0) return '#' + c.slice(0, 3).map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
        }
        const c = parse(getComputedStyle(document.body).backgroundColor);
        return c ? '#' + c.slice(0, 3).map((v) => Math.round(v).toString(16).padStart(2, '0')).join('') : '';
    }

    let last = '';
    function report() {
        const top = colorOf('#top-bar');
        const bottom = colorOf('#send_form');
        if (!top || !bottom || top + bottom === last) return;
        last = top + bottom;
        try { window.TavernBars.colors(KEY, top, bottom); } catch (e) { /* bridge missing */ }
    }

    let pending = 0;
    function soon() {
        clearTimeout(pending);
        pending = setTimeout(report, 100);
    }
    new MutationObserver(soon).observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class'] });
    new MutationObserver(soon).observe(document.body, { attributes: true, attributeFilter: ['style', 'class'] });
    report();
    // settings and themes load after the page; catch anything the observers do not see
    setInterval(report, 3000);
})();
