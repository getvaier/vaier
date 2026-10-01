/* The Explorer's Topology view: the fleet painted as a coast at blue hour, with the internet as the open sea.
   The Vaier server is a lighthouse on a skerry out in that sea, a peer that reaches its LAN is a village (a
   house per machine behind it, its own peer the biggest house at the sea-facing end), the backup server a
   stabbur, other peers boats, and every tunnel a wake that crosses the open sea to the lighthouse.

   Where a village stands follows where its peer is: a Norwegian fjord coast in the north, a Mediterranean
   coast in the south. A fleet on both is one panorama, north on the left and south on the right, with the sea
   and the lighthouse between them; a fleet on one is a single coast with the sea beyond its mouth.

   It draws only what the shell hands it (see topologyFleet in explorer-shell.js) and never reads anything
   itself. Every random choice is seeded — the scenery by fixed seeds, each machine by its own identity — so
   the same fleet always paints the same picture. */
(function () {
    'use strict';

    const W = 1600, H = 900, SHORE = 520;
    const NS = 'http://www.w3.org/2000/svg';

    function rng(seed) {
        let s = seed >>> 0;
        return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
    }

    // FNV-1a: a machine's identity, turned into its seed.
    function hash(text) {
        let h = 2166136261;
        const s = String(text || '');
        for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
        return h >>> 0;
    }

    const smooth = (t) => { t = Math.min(1, Math.max(0, t)); return t * t * (3 - 2 * t); };
    const serifWidth = (text, size) => text.length * size * 0.42;
    const byName = (a, b) => a.name.localeCompare(b.name) || String(a.id).localeCompare(String(b.id));

    // Flags: simple built-in designs on a 22 x 12 cloth, keyed by the English country name the location data
    // carries. A country with no design here flies no flag; the picture never guesses one.
    const across = (...c) => (g, el) => c.forEach((col, i) => el('rect', { x: 0, y: i * 12 / c.length, width: 22, height: 12 / c.length + 0.1, fill: col }, g));
    const down = (...c) => (g, el) => c.forEach((col, i) => el('rect', { x: i * 22 / c.length, y: 0, width: 22 / c.length + 0.1, height: 12, fill: col }, g));
    const nordic = (field, cross, inner) => (g, el) => {
        el('rect', { x: 0, y: 0, width: 22, height: 12, fill: field }, g);
        el('rect', { x: 6, y: 0, width: 4, height: 12, fill: cross }, g);
        el('rect', { x: 0, y: 4, width: 22, height: 4, fill: cross }, g);
        if (inner) {
            el('rect', { x: 7, y: 0, width: 2, height: 12, fill: inner }, g);
            el('rect', { x: 0, y: 5, width: 22, height: 2, fill: inner }, g);
        }
    };
    const FLAGS = {
        'Germany': across('#141414', '#c8231e', '#f2c230'),
        'Spain': (g, el) => { across('#c8231e')(g, el); el('rect', { x: 0, y: 3, width: 22, height: 6, fill: '#f2c230' }, g); },
        'Norway': nordic('#ba1f2c', '#fff', '#1f3a78'),
        'Sweden': nordic('#1f5fa8', '#f2c230'),
        'Denmark': nordic('#c8102e', '#fff'),
        'Finland': nordic('#fff', '#1a3e8c'),
        'Iceland': nordic('#1f4ea0', '#fff', '#d0262b'),
        'Netherlands': across('#ae1c28', '#f4f4f0', '#21468b'),
        'France': down('#1f3a8a', '#f4f4f0', '#d0262b'),
        'Ireland': down('#169b62', '#f4f4f0', '#ff883e'),
        'Italy': down('#1f8a3a', '#f4f4f0', '#d0262b'),
        'Belgium': down('#141414', '#f2c230', '#d0262b'),
        'Austria': across('#c8102e', '#f4f4f0', '#c8102e'),
        'Poland': across('#f4f4f0', '#dc143c'),
        'Ukraine': across('#0057b7', '#ffd700'),
        'Russia': across('#f4f4f0', '#1c3f94', '#d52b1e'),
        'Bulgaria': across('#f4f4f0', '#1f8a56', '#d0262b'),
        'India': (g, el) => { across('#f39a33', '#f4f4f0', '#1f8a3a')(g, el); el('circle', { cx: 11, cy: 6, r: 1.4, fill: '#1f3a8a' }, g); },
        'Canada': (g, el) => {
            down('#d0262b', '#f4f4f0', '#f4f4f0', '#d0262b')(g, el);
            el('path', { d: 'M 11 2.5 L 13 6 L 11 9.5 L 9 6 Z', fill: '#d0262b' }, g);
        },
        'United States': (g, el) => {
            for (let i = 0; i < 6; i++) {
                el('rect', { x: 0, y: i * 2, width: 22, height: 1, fill: '#c8231e' }, g);
                el('rect', { x: 0, y: i * 2 + 1, width: 22, height: 1, fill: '#f4f4f0' }, g);
            }
            el('rect', { x: 0, y: 0, width: 9, height: 6.5, fill: '#23356e' }, g);
        },
        'United Kingdom': (g, el) => {
            el('rect', { x: 0, y: 0, width: 22, height: 12, fill: '#23356e' }, g);
            el('path', { d: 'M 0 0 L 22 12 M 22 0 L 0 12', stroke: '#f4f4f0', 'stroke-width': 2.4 }, g);
            el('path', { d: 'M 0 0 L 22 12 M 22 0 L 0 12', stroke: '#c8102e', 'stroke-width': 0.8 }, g);
            el('rect', { x: 9, y: 0, width: 4, height: 12, fill: '#f4f4f0' }, g);
            el('rect', { x: 0, y: 4, width: 22, height: 4, fill: '#f4f4f0' }, g);
            el('rect', { x: 9.8, y: 0, width: 2.4, height: 12, fill: '#c8102e' }, g);
            el('rect', { x: 0, y: 4.8, width: 22, height: 2.4, fill: '#c8102e' }, g);
        },
        'South Korea': (g, el) => {
            el('rect', { x: 0, y: 0, width: 22, height: 12, fill: '#f4f4f0' }, g);
            el('path', { d: 'M 7.5 6 A 3.5 3.5 0 0 1 14.5 6 Z', fill: '#cd2e3a' }, g);
            el('path', { d: 'M 7.5 6 A 3.5 3.5 0 0 0 14.5 6 Z', fill: '#0047a0' }, g);
        },
        'Hong Kong': (g, el) => {
            el('rect', { x: 0, y: 0, width: 22, height: 12, fill: '#de2910' }, g);
            el('circle', { cx: 11, cy: 6, r: 3, fill: '#f4f4f0' }, g);
        },
        'China': (g, el) => {
            el('rect', { x: 0, y: 0, width: 22, height: 12, fill: '#de2910' }, g);
            el('path', { d: 'M 4.5 1.8 L 5.3 4.1 L 7.6 4.1 L 5.7 5.5 L 6.4 7.8 L 4.5 6.4 L 2.6 7.8 L 3.3 5.5 L 1.4 4.1 L 3.7 4.1 Z', fill: '#ffde00' }, g);
        },
    };

    // What a blocked address was caught doing, in plain words. CrowdSec's scenario names are mechanism.
    const TRIED = {
        'http-probing': 'probing', 'http-admin-interface-probing': 'probing admin interfaces',
        'http-wordpress-scan': 'scanning for WordPress', 'http-backdoors-attempts': 'trying backdoors',
        'http-crawl-non_statics': 'crawling', 'http-bad-user-agent': 'using a known attack tool',
        'http-sensitive-files': 'looking for secret files', 'http-path-traversal-probing': 'trying to climb out of folders',
        'ssh-bf': 'guessing passwords', 'ssh-slow-bf': 'guessing passwords', 'http-generic-bf': 'guessing passwords',
    };
    const triedWords = (scenario) => TRIED[String(scenario || '').split('/').pop()] || 'trying to get in';
    // North first: a site with no known latitude goes after every one that has one.
    const northFirst = (a, b) => ((b.latitude ?? -999) - (a.latitude ?? -999)) || byName(a, b);

    // The climate a site's houses are drawn in. Below 45° is the Mediterranean; everything else, and anywhere
    // Vaier cannot place, is the Norwegian coast.
    const bandOf = (site) => (site.latitude != null && site.latitude < 45 ? 'med' : 'nordic');

    // The two compositions. `land` is where a coast's ridges run, falling to the sea on its `sea` side;
    // `span` is the shore its villages may use.
    function layoutFor(bands) {
        if (bands.has('nordic') && bands.has('med')) {
            return {
                two: true, light: 830, sea: [640, 1020], ship: 980, sun: 1450,
                coasts: [{ band: 'nordic', land: [-10, 660], sea: 'right', span: [20, 500] },
                         { band: 'med', land: [980, 1610], sea: 'left', span: [1150, 1580] }],
            };
        }
        const band = bands.has('med') ? 'med' : 'nordic';
        return {
            two: false, light: 1330, sea: [1130, 1600], ship: 1505, sun: band === 'med' ? 1450 : null,
            coasts: [{ band, land: [-10, 1280], sea: 'right', span: [20, 1120] }],
        };
    }

    // opts: onOpen(machineId) when a machine is chosen; ago(epochSeconds) for "last seen", read when shown.
    function draw(live, tip, fleet, opts) {
        const onOpen = opts.onOpen, ago = opts.ago || (() => '');
        // Drawn off the page and swapped in once its scenery has decoded: emptying the live picture first
        // showed it without a background for a few frames on every update.
        const mine = ++drawing;
        const svg = document.createElementNS(NS, 'svg');
        const still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        function el(tag, attrs, parent) {
            const n = document.createElementNS(NS, tag);
            for (const k in attrs) n.setAttribute(k, attrs[k]);
            (parent || svg).appendChild(n);
            return n;
        }

        const siteBands = new Set(fleet.sites.map(bandOf));
        const L = layoutFor(siteBands);
        const X = L.light, LIGHT = { x: L.light, y: 590 };
        const hasNordic = L.coasts.some((c) => c.band === 'nordic');
        const hasMed = L.coasts.some((c) => c.band === 'med');

        // --- the scenery -------------------------------------------------------------------------------
        // Everything that does not move and cannot be pointed at is painted into a picture of its own, once
        // per repaint, and shown as an image: its filters are heavy, and a lantern or the beam passing over a
        // filtered layer would have the browser re-run them every frame.
        const scene = document.createElementNS(NS, 'svg');
        scene.setAttribute('xmlns', NS);
        scene.setAttribute('viewBox', `0 0 ${W} ${H}`);
        scene.setAttribute('width', W);
        scene.setAttribute('height', H);
        const sc = (tag, attrs, parent) => el(tag, attrs, parent || scene);
        const narrow = window.innerWidth < 700;
        const sdefs = sc('defs', {});
        const defs = el('defs', {});
        function grad(into, id, stops, x2, y2, type) {
            const g = el(type || 'linearGradient',
                type === 'radialGradient' ? { id } : { id, x1: 0, y1: 0, x2: x2 ?? 0, y2: y2 ?? 1 }, into);
            stops.forEach(([o, c, a]) => el('stop', { offset: o, 'stop-color': c, 'stop-opacity': a ?? 1 }, g));
            return g;
        }
        grad(sdefs, 'sky', L.two
            ? [[0, '#070d1c'], [0.45, '#15264a'], [0.8, '#3b4a78'], [0.94, '#8a7394'], [1, '#c99583']]
            : [[0, '#070d1c'], [0.45, '#15264a'], [0.78, '#3b4a78'], [0.93, '#b77f86'], [1, '#e6a57c']]);
        grad(sdefs, 'south', L.two
            ? [[0, '#e98a5c', 0], [0.55, '#e98a5c', 0.15], [1, '#f2a36b', 0.95]]
            : [[0, '#e98a5c', 0.3], [0.6, '#e98a5c', 0.5], [1, '#f2a36b', 0.95]], 1, 0);
        grad(sdefs, 'southv', [[0, '#fff', 0], [0.55, '#fff', 0.35], [1, '#fff', 1]]);
        grad(sdefs, 'water', [[0, '#2b3558'], [0.08, '#1a2746'], [0.5, '#101b33'], [1, '#081021']]);
        grad(sdefs, 'waterwarm', L.two ? [[0, '#e0915f', 0], [1, '#e0915f', 0.35]] : [[0, '#e0915f', 0.12], [1, '#e0915f', 0.35]], 1, 0);
        grad(sdefs, 'aurora', [[0, '#7fe8b5', 0], [0.35, '#7fe8b5', 0.55], [0.7, '#5fc7c9', 0.3], [1, '#9b7fe0', 0]], 1, 0);
        grad(sdefs, 'snow', [[0, '#eef2f8'], [1, '#aeb9cc']]);
        grad(sdefs, 'reflfade', [[0, '#fff', 0.55], [1, '#fff', 0]]);
        grad(sdefs, 'sun', [[0, '#ffe2b0', 0.95], [0.3, '#ffb877', 0.5], [1, '#ff9a5a', 0]], null, null, 'radialGradient');
        grad(sdefs, 'horizon', [[0, '#f6d3a8', 0], [0.5, '#f6d3a8', 0.75], [1, '#f6d3a8', 0]], 1, 0);
        grad(defs, 'tp-glow', [[0, '#ffd99a', 0.9], [0.4, '#ffcf7a', 0.25], [1, '#ffcf7a', 0]], null, null, 'radialGradient');
        grad(defs, 'tp-beam', [[0, '#fff1c8', 0.6], [1, '#fff1c8', 0]], L.two ? 0 : 1, L.two ? 1 : 0);
        function filter(into, id, build, wide) {
            const f = el('filter', wide ? { id, x: '-50%', y: '-50%', width: '200%', height: '200%' }
                                        : { id, x: '-5%', y: '-5%', width: '110%', height: '110%' }, into);
            build(f);
        }
        const blur = (into, id, sd) => filter(into, id, (f) => el('feGaussianBlur', { stdDeviation: sd }, f), true);
        blur(sdefs, 'soft', 6); blur(sdefs, 'softer', 18); blur(sdefs, 'tiny', 1.2);
        blur(defs, 'tp-soft', 6); blur(defs, 'tp-tiny', 1.2);
        // Painterly rock: fractal edges, then broad soft planes of light and shadow kept inside the shape.
        filter(sdefs, 'paint', (f) => {
            el('feTurbulence', { type: 'fractalNoise', baseFrequency: '0.012 0.05', numOctaves: 4, seed: 3, result: 'n' }, f);
            el('feDisplacementMap', { in: 'SourceGraphic', in2: 'n', scale: 9, xChannelSelector: 'R', yChannelSelector: 'G', result: 'd' }, f);
            el('feTurbulence', { type: 'fractalNoise', baseFrequency: '0.005 0.022', numOctaves: 2, seed: 9, result: 't0' }, f);
            el('feGaussianBlur', { in: 't0', stdDeviation: 3, result: 't' }, f);
            el('feColorMatrix', { in: 't', type: 'matrix', values: '0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 -2.2 1.35', result: 'ta' }, f);
            el('feFlood', { 'flood-color': '#05070d', 'flood-opacity': 0.45, result: 'ink' }, f);
            el('feComposite', { in: 'ink', in2: 'ta', operator: 'in', result: 'strata' }, f);
            el('feComposite', { in: 'strata', in2: 'd', operator: 'in', result: 'shade' }, f);
            const m = el('feMerge', {}, f);
            el('feMergeNode', { in: 'd' }, m);
            el('feMergeNode', { in: 'shade' }, m);
        });
        // Water breaks a reflection into horizontal ripples.
        filter(sdefs, 'ripple', (f) => {
            el('feTurbulence', { type: 'fractalNoise', baseFrequency: '0.003 0.11', numOctaves: 2, seed: 17, result: 'w' }, f);
            el('feDisplacementMap', { in: 'SourceGraphic', in2: 'w', scale: 16, xChannelSelector: 'R', yChannelSelector: 'G' }, f);
        });
        const ripple = narrow ? {} : { filter: 'url(#ripple)' };
        const reflMask = sc('mask', { id: 'reflmask' }, sdefs);
        sc('rect', { x: 0, y: SHORE, width: W, height: H - SHORE, fill: 'url(#reflfade)' }, reflMask);
        const southMask = sc('mask', { id: 'southmask' }, sdefs);
        sc('rect', { x: 0, y: 0, width: W, height: SHORE, fill: 'url(#southv)' }, southMask);

        // --- sky: aurora night over the north, warming to a low sun over the south ------------------------
        sc('rect', { x: 0, y: 0, width: W, height: SHORE + 2, fill: 'url(#sky)' });
        if (hasMed) {
            sc('rect', { x: 0, y: 0, width: W, height: SHORE + 2, fill: 'url(#south)', mask: 'url(#southmask)' });
            sc('circle', { cx: L.sun, cy: SHORE - 6, r: 150, fill: 'url(#sun)' });
            sc('circle', { cx: L.sun, cy: SHORE + 2, r: 26, fill: '#ffd49a', opacity: 0.9 });
        }
        const northness = (x) => (L.two ? 1 - smooth((x - 700) / 700) : hasNordic ? 1 : 0.35);
        const stars = sc('g', {});
        const r = rng(7);
        for (let i = 0; i < 190; i++) {
            const x = r() * W, y = r() * 340;
            const big = r() < 0.08, size = 0.7 + r() * 0.5, o = 0.25 + r() * 0.7;
            sc('circle', { cx: x, cy: y, r: big ? 1.5 : size, fill: '#fff', opacity: o * (1 - y / 420) * northness(x) }, stars);
        }
        if (hasNordic) {
            const aur = sc('g', { filter: 'url(#softer)' });
            sc('path', { d: L.two
                ? 'M -40 170 C 160 60, 360 240, 560 120 S 820 70, 980 150 L 980 220 C 820 150, 700 240, 540 200 S 200 150, -40 250 Z'
                : 'M -40 170 C 260 60, 520 250, 820 120 S 1340 40, 1660 150 L 1660 230 C 1320 140, 1100 250, 820 200 S 300 150, -40 250 Z',
                fill: 'url(#aurora)', opacity: 0.75 }, aur);
            sc('path', { d: L.two
                ? 'M 60 90 C 260 30, 420 150, 600 70 S 800 40, 900 80 L 900 110 C 760 80, 660 150, 560 120 S 280 80, 60 140 Z'
                : 'M 200 90 C 500 30, 760 150, 1020 70 S 1400 20, 1640 70 L 1640 110 C 1400 70, 1200 150, 1000 120 S 520 80, 200 140 Z',
                fill: 'url(#aurora)', opacity: 0.45 }, aur);
        }
        // Low streaked clouds: cool over the north, lit from below over the south; faint rays from a low sun.
        const clouds = sc('g', { filter: 'url(#softer)' });
        const cr = rng(55);
        for (let i = 0; i < 14; i++) {
            const x = cr() * W, y = 250 + cr() * 190, w = 160 + cr() * 320, ry = 6 + cr() * 10;
            const warm = hasMed ? (L.two ? smooth((x - 700) / 600) : 1) : 0;
            sc('ellipse', { cx: x, cy: y, rx: w / 2, ry, fill: warm > 0.5 ? '#f3b18a' : '#8d9cc4',
                opacity: 0.12 + 0.18 * (warm > 0.5 ? warm : 0.6) }, clouds);
            if (warm > 0.5) sc('ellipse', { cx: x, cy: y + 5, rx: w / 2.4, ry: 3, fill: '#ffd7a8', opacity: 0.25 * warm }, clouds);
        }
        if (hasMed) {
            const rays = sc('g', { filter: 'url(#softer)', opacity: 0.16 });
            [[-0.5, 70], [-0.25, 40], [0.05, 90], [0.35, 50]].forEach(([a, w]) => {
                const x2 = L.sun + Math.sin(a - Math.PI / 2) * 700;
                sc('path', { d: `M ${L.sun} ${SHORE} L ${(x2 - w).toFixed(1)} ${SHORE - 700} L ${(x2 + w).toFixed(1)} ${SHORE - 700} Z`, fill: '#ffd9a8' }, rays);
            });
        }

        // --- the coasts -----------------------------------------------------------------------------------
        const land = sc('g', { filter: 'url(#paint)' });
        const poly = (pts, bottom) => `M ${pts[0][0].toFixed(1)} ${bottom} L `
            + pts.map((p) => p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' L ') + ` L ${pts[pts.length - 1][0].toFixed(1)} ${bottom} Z`;
        // Each coast's ridges, far to near, falling into the sea over its last stretch on the sea side. Peaks
        // sit at fractions of the coast, mirrored when the sea is on the left.
        const shapes = L.coasts.map((coast, ci) => {
            const [x0, x1] = coast.land;
            const w = x1 - x0;
            const right = coast.sea === 'right';
            const widen = Math.max(0.55, w / 1290);
            function ridge(seed, base, peaks, rough, from, to, sink) {
                const rr = rng(seed + ci * 101), pts = [];
                for (let i = 0; i <= 56; i++) {
                    const x = from + (i / 56) * (to - from);
                    let y = base;
                    peaks.forEach(([f, ph, pw]) => {
                        const d = (x - (x0 + (right ? f : 1 - f) * w)) / (pw * widen);
                        y -= ph * Math.exp(-d * d);
                    });
                    y -= (rr() - 0.5) * rough;
                    pts.push([x, y + (SHORE + 2 - y) * sink(x)]);
                }
                return pts;
            }
            const sink = right ? (x) => smooth((x - (x1 - 150)) / 150) : (x) => 1 - smooth((x - (x0 + 40)) / 170);
            // The palest, furthest ridge reaches a little further out to sea, lost in air.
            const sinkBack = right ? (x) => smooth((x - (x1 - 40)) / 180) : (x) => 1 - smooth((x - (x0 - 20)) / 200);
            const [bx0, bx1] = right ? [x0, x1 + 140] : [x0 - 80, x1];
            if (coast.band === 'nordic') {
                return { coast, sink, x0, x1,
                    back: ridge(61, 455, [[0.13, 170, 120], [0.49, 210, 160], [0.9, 120, 110]], 10, bx0, bx1, sinkBack),
                    far: ridge(3, 470, [[0.2, 240, 170], [0.5, 160, 140], [0.75, 260, 150]], 14, x0, x1, sink),
                    mid: ridge(11, SHORE, [[0.05, 245, 150], [0.37, 115, 110], [0.84, 180, 90]], 10, x0, x1, sink) };
            }
            return { coast, sink, x0, x1,
                back: ridge(67, 490, [[0.39, 90, 160], [0.87, 120, 150]], 6, bx0, bx1, sinkBack),
                far: ridge(33, SHORE, [[0.365, 150, 140], [0.76, 110, 180]], 8, x0, x1, sink),
                mid: ridge(41, SHORE + 2, [[0.22, 70, 90], [0.51, 60, 120], [0.92, 90, 110]], 6, x0, x1, sink) };
        });
        const mist = (y, h, o) => sc('rect', { x: -20, y, width: W + 40, height: h, fill: '#b9c3dc', opacity: o, filter: 'url(#softer)' }, land);
        shapes.forEach((s) => sc('path', { d: poly(s.back, SHORE),
            fill: s.coast.band === 'nordic' ? '#46557f' : '#9a6f6e', opacity: s.coast.band === 'nordic' ? 0.85 : 0.7 }, land));
        mist(SHORE - 70, 40, 0.16);
        shapes.forEach((s, ci) => {
            if (s.coast.band === 'nordic') {
                sc('path', { d: poly(s.far, SHORE), fill: '#2c3b62' }, land);
                const snowClip = sc('clipPath', { id: 'snowline' + ci }, sdefs);
                sc('path', { d: poly(s.far, SHORE) }, snowClip);
                const sr = rng(21);
                sc('path', { d: `M ${s.x0} 0 L ` + s.far.map(([x, y]) => x.toFixed(1) + ' ' + Math.min(y + 34 + sr() * 26, 330 + sr() * 30).toFixed(1)).join(' L ')
                    + ` L ${s.x1} 0 Z`, fill: 'url(#snow)', opacity: 0.85, 'clip-path': `url(#snowline${ci})` }, land);
            } else {
                sc('path', { d: poly(s.far, SHORE), fill: '#6b4b4f' }, land);
                sc('path', { d: poly(s.far, SHORE), fill: '#e8a070', opacity: 0.18 }, land);
            }
        });
        mist(SHORE - 40, 30, 0.14);
        shapes.forEach((s, ci) => {
            if (s.coast.band === 'nordic') {
                sc('path', { d: poly(s.mid, SHORE + 4), fill: '#1b2744' }, land);
                const fr = rng(5);
                const spruce = sc('g', { fill: '#111a30' }, land);
                for (let x = Math.max(0, s.x0); x < s.x1 - 60; x += 7) {
                    const h = (8 + fr() * 16) * (1 - s.sink(x) * 0.7);
                    sc('path', { d: `M ${x} ${SHORE + 2} L ${x + 4} ${(SHORE + 2 - h).toFixed(1)} L ${x + 8} ${SHORE + 2} Z` }, spruce);
                }
                return;
            }
            // Low dry hills in warm light, cypresses and olive trees.
            sc('path', { d: poly(s.mid, SHORE + 4), fill: '#3b2f3a' }, land);
            const tr = rng(77 + ci);
            const grove = sc('g', {}, land);
            const trees = Math.round(46 * (s.x1 - s.x0) / 630);
            for (let i = 0; i < trees; i++) {
                const f = 0.08 + tr() * 0.84;
                const x = s.x0 + f * (s.x1 - s.x0);
                const k = Math.round(f * 56);
                const ground = s.mid[k][1] + 6 + tr() * Math.max(0, SHORE - s.mid[k][1] - 10);
                const cypress = tr() < 0.4;
                if (s.sink(x) > 0.6) continue;
                if (cypress) sc('ellipse', { cx: x, cy: ground - 14, rx: 3.2, ry: 15, fill: '#1f2a24' }, grove);
                else sc('ellipse', { cx: x, cy: ground - 5, rx: 7, ry: 5, fill: '#39433a', opacity: 0.9 }, grove);
            }
        });
        const groundOf = shapes.map((s) => (s.coast.band === 'nordic' ? '#1b2744' : '#3b2f3a'));

        // --- water: the open sea is the internet ----------------------------------------------------------
        sc('rect', { x: 0, y: SHORE, width: W, height: H - SHORE, fill: 'url(#water)' });
        if (hasMed) sc('rect', { x: 0, y: SHORE, width: W, height: H - SHORE, fill: 'url(#waterwarm)' });
        const refl = sc('g', { mask: 'url(#reflmask)', opacity: 0.55 });
        sc('g', { transform: `translate(0 ${2 * SHORE}) scale(1 -1)` }, sc('g', ripple, refl)).appendChild(land.cloneNode(true));
        if (hasMed) sc('rect', { x: L.sun - 70, y: SHORE + 4, width: 140, height: 220, fill: '#ffc98a', opacity: 0.16, filter: 'url(#soft)' });
        // Glints: a broken path of light under the sun and under the lighthouse.
        const glint = sc('g', { fill: '#ffe6bf' });
        const gr = rng(303);
        const glints = (cx, spread, n, o) => {
            for (let i = 0; i < n; i++) {
                const y = SHORE + 6 + Math.pow(gr(), 1.3) * 330, w = 3 + gr() * (8 + (y - SHORE) / 12);
                const x = cx + (gr() - 0.5) * spread * (0.4 + (y - SHORE) / 300), a = o * (0.4 + gr() * 0.6);
                sc('rect', { x: x.toFixed(1), y: y.toFixed(1), width: w.toFixed(1), height: (1.2 + (y - SHORE) / 250).toFixed(2), opacity: a.toFixed(3) }, glint);
            }
        };
        if (hasMed) glints(L.sun, 120, 120, 0.55);
        glints(X, 60, 40, 0.3);
        const [s0, s1] = L.sea;
        sc('rect', { x: s0 - (L.two ? 120 : 40), y: SHORE - 2, width: s1 - s0 + (L.two ? 240 : 40), height: 4,
            fill: 'url(#horizon)', filter: 'url(#tiny)' });
        const swell = sc('g', { fill: 'none', stroke: '#cfdcf2', 'stroke-linecap': 'round' });
        const sw = rng(123);
        for (let i = 0; i < 30; i++) {
            const y = SHORE + 10 + Math.pow(i / 30, 1.6) * (H - SHORE - 20);
            const len = 100 + (y - SHORE) * 1.15;
            const xm = L.two ? s0 - 60 + sw() * (s1 - s0) : s0 + 60 + sw() * 120 + (y - SHORE) * 0.3 + len / 2;
            const o = 0.09 + sw() * 0.08;
            sc('path', { d: `M ${(xm - len / 2).toFixed(1)} ${y.toFixed(1)} q ${len / 2} ${(-3 - (y - SHORE) / 60).toFixed(1)} ${len} 0`,
                'stroke-width': 0.8 + (y - SHORE) / 200, opacity: o.toFixed(3) }, swell);
        }
        const rip = sc('g', { stroke: '#c9d6ee', 'stroke-linecap': 'round' });
        const rp = rng(99);
        for (let i = 0; i < 130; i++) {
            const y = SHORE + 8 + Math.pow(rp(), 1.4) * (H - SHORE - 10);
            const len = 10 + (y - SHORE) * 0.18 * rp();
            const x = rp() * W;
            sc('line', { x1: x.toFixed(1), y1: y.toFixed(1), x2: (x + len).toFixed(1), y2: y.toFixed(1),
                'stroke-width': 0.6 + (y - SHORE) / 400, opacity: (0.06 + rp() * 0.1).toFixed(3) }, rip);
        }
        // The lighthouse's glow lying on the water, and the lit windows' smears (filled in by the villages).
        sc('ellipse', { cx: X + 10, cy: LIGHT.y + 70, rx: 90, ry: 26, fill: '#ffe2a4', opacity: 0.08, filter: 'url(#soft)' });
        const smears = sc('g', ripple);

        // --- the tooltip, and what a machine does when it is chosen ----------------------------------------
        function hoverable(g, info, machineId) {
            g.setAttribute('class', 'tp-hit' + (machineId ? ' is-open' : ''));
            g.setAttribute('tabindex', '0');
            const words = () => {
                const seen = info.seen ? ago(info.seen) : '';
                const state = typeof info.state === 'function' ? info.state() : info.state;
                return [info.role, info.address, state + (state && seen ? ', last seen ' + seen : '')]
                    .filter(Boolean).join(' \u00b7 ');
            };
            const line = words();
            g.setAttribute('aria-label', info.name + (line ? ', ' + line : ''));
            if (machineId) g.setAttribute('role', 'link');
            const show = (x, y) => {
                tip.textContent = '';
                const s = document.createElement('strong'); s.textContent = info.name;
                const d = document.createElement('span'); d.textContent = words();
                tip.append(s, d);
                tip.hidden = false;
                tip.style.left = Math.max(8, Math.min(x + 14, window.innerWidth - 276)) + 'px';
                tip.style.top = Math.min(y + 14, window.innerHeight - 80) + 'px';
            };
            g.addEventListener('mousemove', (e) => show(e.clientX, e.clientY));
            g.addEventListener('mouseleave', () => { tip.hidden = true; });
            g.addEventListener('focus', () => { const b = g.getBoundingClientRect(); show(b.left, b.bottom); });
            g.addEventListener('blur', () => { tip.hidden = true; });
            if (machineId) {
                g.addEventListener('click', () => { tip.hidden = true; onOpen(machineId); });
                g.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); tip.hidden = true; onOpen(machineId); }
                });
            }
        }

        // A waving cloth with its top-left at (x, y), or false for a country with no design.
        let flagN = 0;
        function cloth(parent, x, y, country, scale) {
            const design = FLAGS[country];
            if (!design) return false;
            const id = 'tp-flag' + (flagN++);
            const wave = el('clipPath', { id }, defs);
            el('path', { d: 'M 0 1 q 5.5 -2 11 0 t 11 0 V 12 q -5.5 -2 -11 0 t -11 0 Z' }, wave);
            design(el('g', { transform: `translate(${x} ${y}) scale(${scale || 1})`, 'clip-path': `url(#${id})` }, parent), el);
            return true;
        }
        // A small flag on a pole standing at (x, y): the country a place is in.
        function flagpole(parent, x, y, country, role, place) {
            if (!FLAGS[country]) return;
            const g = el('g', {}, parent);
            el('line', { x1: x, y1: y, x2: x, y2: y - 46, stroke: '#d9dce2', 'stroke-width': 1.4 }, g);
            el('circle', { cx: x, cy: y - 47, r: 1.6, fill: '#e8c46a' }, g);
            cloth(g, x + 1, y - 46, country);
            hoverable(g, { name: country, role, address: place || '' }, null);
        }

        // --- wakes (tunnels), each from a village's own peer to the lighthouse -----------------------------
        // Wakes run on water only: a village up the coast is seen sailing out from the shore below it.
        const onWater = el('clipPath', { id: 'tp-onwater' }, defs);
        el('rect', { x: 0, y: SHORE + 2, width: W, height: H - SHORE }, onWater);
        const wakes = el('g', { 'clip-path': 'url(#tp-onwater)' });
        const lanterns = el('g', { 'clip-path': 'url(#tp-onwater)' });
        function wake(d, faint, dur, seed) {
            el('path', { d, fill: 'none', stroke: '#e9d7a8', 'stroke-width': faint ? 1.2 : 2,
                'stroke-dasharray': faint ? '2 7' : '3 9', 'stroke-linecap': 'round', opacity: faint ? 0.3 : 0.55 }, wakes);
            el('path', { d, fill: 'none', stroke: '#ffe2a4', 'stroke-width': 8, opacity: faint ? 0.03 : 0.06,
                filter: 'url(#tp-soft)' }, wakes);
            // A dark tunnel carries no lantern.
            if (still || faint) return;
            const begin = (rng(seed)() * dur).toFixed(2) + 's';
            const lamp = el('circle', { r: 3, fill: '#ffe7b0' }, lanterns);
            const halo = el('circle', { r: 10, fill: 'url(#tp-glow)' }, lanterns);
            [lamp, halo].forEach((n) => {
                el('animateMotion', { dur: dur + 's', repeatCount: 'indefinite', path: d, keyPoints: '0;1;0',
                    keyTimes: '0;0.5;1', calcMode: 'linear', begin }, n);
            });
        }
        function villageWake(x1, y1, i, faint, seed) {
            const k = i % 5;
            if (L.two) {
                // Straight out across the sea between the coasts.
                const x2 = X + (x1 < X ? -60 : 60), y2 = LIGHT.y + 30, sag = 150 + k * 30;
                wake(`M ${x1.toFixed(1)} ${y1} C ${(x1 + (x2 - x1) * 0.25).toFixed(1)} ${y1 + sag}, ${(x1 + (x2 - x1) * 0.7).toFixed(1)} ${y2 + sag * 0.7}, ${x2} ${y2}`,
                    faint, 20 + k * 5, seed);
                return;
            }
            // Out through the fjord mouth, over the open sea, and in to the lighthouse.
            const sea = { x: 1480 - k * 20, y: 700 + k * 40, dip: 140 + k * 50 };
            const x2 = X + 30, y2 = LIGHT.y + 40;
            wake(`M ${x1.toFixed(1)} ${y1} C ${(x1 + 120).toFixed(1)} ${y1 + sea.dip}, ${sea.x - 160} ${sea.y + 40}, ${sea.x} ${sea.y} S ${x2 + 90} ${y2 + 10}, ${x2} ${y2}`,
                faint, 22 + k * 5, seed);
        }

        // --- buildings ------------------------------------------------------------------------------------
        // Drawn in a village's own coordinates: x along the shore, y = 0 at the foot of the house.
        function windows(g, x, y, w, dark, seed, smear, arched) {
            const wr = rng(seed);
            const cols = w > 40 ? 3 : 2;
            const ww = 5, wh = 7, gap = (w - cols * ww) / (cols + 1);
            for (let c = 0; c < cols; c++) {
                const lit = !dark && wr() > 0.15;
                const wx = x + gap + c * (ww + gap);
                el(arched ? 'path' : 'rect', arched
                    ? { d: `M ${wx} ${y + wh} L ${wx} ${y + 2.5} A 2.5 2.5 0 0 1 ${wx + ww} ${y + 2.5} L ${wx + ww} ${y + wh} Z`, fill: lit ? '#ffd88f' : '#2a2630' }
                    : { x: wx, y, width: ww, height: wh, fill: lit ? '#ffd88f' : '#1a2032' }, g);
                if (lit) {
                    el('circle', { cx: wx + ww / 2, cy: y + wh / 2, r: 9, fill: 'url(#tp-glow)', opacity: 0.55 }, g);
                    if (smear) {
                        el('rect', { x: wx, y: 4 - y * 0.6, width: ww, height: 16 + wr() * 18, fill: '#ffcf7a',
                            opacity: 0.18, filter: 'url(#tp-tiny)' }, smear);
                    }
                }
            }
        }
        function rorbu(parent, x, w, h, house, big, smear) {
            const g = el('g', {}, parent);
            const top = -h;
            for (let i = 0; i <= 3; i++) el('rect', { x: x + 2 + i * (w - 6) / 3, y: -2, width: 2.2, height: 12, fill: '#2b2622' }, g);
            el('rect', { x, y: top, width: w, height: h, fill: house.dark ? '#5b2a28' : '#a8352a', class: 'tp-ring' }, g);
            for (let i = 1; i < w / 5; i++) el('line', { x1: x + i * 5, y1: top + 2, x2: x + i * 5, y2: -2, stroke: '#000', opacity: 0.12 }, g);
            el('path', { d: `M ${x - 4} ${top + 1} L ${x + w / 2} ${top - h * 0.55} L ${x + w + 4} ${top + 1} Z`, fill: '#23242c' }, g);
            windows(g, x, top + h * 0.35, w, house.dark, hash(house.id || house.name), smear, false);
            if (big) el('rect', { x: x + w / 2 - 5, y: -13, width: 10, height: 13, fill: '#1e1a1a' }, g);
            return g;
        }
        // A whitewashed house with a terracotta roof and arched windows.
        function casa(parent, x, w, h, house, big, smear) {
            const g = el('g', {}, parent);
            const top = -h;
            el('rect', { x, y: top, width: w, height: h, fill: house.dark ? '#8e877e' : '#d8cdbd', class: 'tp-ring' }, g);
            el('rect', { x, y: top, width: w, height: h, fill: '#f0a06a', opacity: 0.12 }, g);
            el('path', { d: `M ${x - 3} ${top + 1} L ${x + w / 2} ${top - h * 0.28} L ${x + w + 3} ${top + 1} Z`, fill: '#a4492a' }, g);
            windows(g, x, top + h * 0.38, w, house.dark, hash(house.id || house.name), smear, true);
            if (big) {
                el('rect', { x: x + w / 2 - 5, y: -13, width: 10, height: 13, fill: '#5a3a2a' }, g);
                el('rect', { x: x + w - 9, y: top - h * 0.28 - 12, width: 7, height: 16, fill: '#d8cdbd' }, g);
            }
            return g;
        }
        function stabbur(parent, x, house, smear) {
            const g = el('g', {}, parent);
            const base = -2;
            [x + 3, x + 20, x + 37].forEach((px) => el('rect', { x: px, y: base - 12, width: 5, height: 12, fill: '#6f6a64' }, g));
            el('rect', { x: x + 4, y: base - 36, width: 38, height: 24, fill: house.dark ? '#4f2a1a' : '#7a3b22', class: 'tp-ring' }, g);
            el('rect', { x: x - 2, y: base - 58, width: 50, height: 22, fill: house.dark ? '#5a3020' : '#8a4526' }, g);
            for (let i = 1; i < 10; i++) el('line', { x1: x - 2 + i * 5, y1: base - 58, x2: x - 2 + i * 5, y2: base - 36, stroke: '#000', opacity: 0.18 }, g);
            el('path', { d: `M ${x - 8} ${base - 57} L ${x + 23} ${base - 84} L ${x + 54} ${base - 57} Z`, fill: '#2d3a2a' }, g);
            el('path', { d: `M ${x - 8} ${base - 57} L ${x + 23} ${base - 84} L ${x + 54} ${base - 57}`, fill: 'none', stroke: '#465c3f', 'stroke-width': 2 }, g);
            windows(g, x + 4, base - 30, 38, house.dark, hash(house.id || house.name), smear, false);
            return g;
        }
        const houseWidth = (h) => (h.stabbur || h.isPeer ? 50 : 30 + hash(h.id) % 12);
        const houseHeight = (h) => (h.isPeer ? 38 : 24 + hash(h.id) % 10);
        function building(parent, x, h, band, smear) {
            if (h.stabbur) return stabbur(parent, x, h, smear);
            return band === 'med' ? casa(parent, x, houseWidth(h), houseHeight(h) + 4, h, h.isPeer, smear)
                                  : rorbu(parent, x, houseWidth(h), houseHeight(h), h, h.isPeer, smear);
        }

        // --- villages -------------------------------------------------------------------------------------
        // Rows along each coast: the near shore first, then further up the coast, drawn smaller and set on a
        // hill of their own. A row that still overflows is squeezed to fit.
        const ROWS = [{ base: SHORE + 4, s: 1 }, { base: 370, s: 0.6 }, { base: 262, s: 0.42 }];
        const town = el('g', {});
        groundOf.forEach((colour, ci) => grad(defs, 'tp-ledge' + ci, [[0, colour, 1], [0.7, colour, 0.6], [1, colour, 0]]));
        let wakeIndex = 0;
        L.coasts.forEach((coast, ci) => {
            const facesRight = coast.sea === 'right';
            const villages = fleet.sites.filter((s) => (L.two ? bandOf(s) === coast.band : true)).sort(northFirst)
                .map((site) => {
                    // The village's own peer stands at the end that faces the sea.
                    const peer = Object.assign({}, site.peer, { isPeer: true });
                    const lan = site.lan.slice().sort(byName);
                    const houses = facesRight ? lan.concat(peer) : [peer].concat(lan);
                    const widths = houses.map(houseWidth);
                    const housesW = widths.reduce((a, b) => a + b, 0) + 16 * (houses.length - 1);
                    const natural = Math.max(housesW, serifWidth(site.name, 30),
                        serifWidth(site.country || '', 18) * 1.4) + 44;
                    return { site, houses, widths, housesW, natural };
                });
            const rows = ROWS.map(() => []);
            const [x0, x1] = coast.span;
            let row = 0;
            villages.forEach((v) => {
                while (row < ROWS.length - 1 && rows[row].length
                       && rows[row].reduce((a, b) => a + b.natural, 0) + v.natural > (x1 - x0) / ROWS[row].s) row++;
                rows[row].push(v);
            });
            // The furthest row first, so a nearer village is painted in front of the ones behind it.
            rows.map((placed, ri) => [placed, ri]).reverse().forEach(([placed, ri]) => {
                if (!placed.length) return;
                const R = ROWS[ri];
                const span = x1 - x0;
                const used = placed.reduce((a, b) => a + b.natural, 0);
                const k = R.s * Math.min(1, span / (used * R.s));
                const spare = (span - used * k) / placed.length;
                let x = x0;
                placed.forEach((v) => {
                    const vx = x + spare / 2;
                    x += v.natural * k + spare;
                    const g = el('g', { transform: `translate(${vx.toFixed(1)} ${R.base}) scale(${k.toFixed(3)})` }, town);
                    const hx0 = (v.natural - v.housesW) / 2;
                    if (ri > 0) {
                        // A ledge for the village to stand on, fading into the mountainside below it.
                        el('path', { d: `M ${hx0 - 70} 60 Q ${hx0 - 30} 4, ${hx0} 4 L ${hx0 + v.housesW} 4 Q ${hx0 + v.housesW + 30} 4, ${hx0 + v.housesW + 70} 60 Z`,
                            fill: `url(#tp-ledge${ci})` }, g);
                        el('rect', { x: hx0 - 40, y: 0, width: v.housesW + 80, height: 7, fill: '#1c2a4c' }, g);
                        el('rect', { x: hx0 - 30, y: 2, width: v.housesW + 60, height: 1.2, fill: '#f6d3a8', opacity: 0.25 }, g);
                    }
                    // The shore it stands on: a boardwalk in the north, a harbour wall in the south.
                    el('rect', coast.band === 'nordic'
                        ? { x: hx0 - 10, y: 0, width: v.housesW + 20, height: 3, fill: '#3a302a' }
                        : { x: hx0 - 14, y: -2, width: v.housesW + 28, height: 8, fill: '#8a7766' }, g);
                    const smear = ri === 0
                        ? el('g', { transform: `translate(${vx.toFixed(1)} ${R.base}) scale(${k.toFixed(3)})` }, smears) : null;
                    let hx = hx0, peerX = 0;
                    const eaves = [];
                    v.houses.forEach((house, i) => {
                        const b = building(g, hx, house, coast.band, smear);
                        const w = v.widths[i];
                        hoverable(b, house, house.id || null);
                        if (house.isPeer) peerX = vx + (hx + w / 2) * k;
                        const top = house.stabbur ? -60 : coast.band === 'med' ? -(houseHeight(house) + 4) : -houseHeight(house);
                        eaves.push({ l: hx, r: hx + w, y: top + 3, dark: house.dark });
                        if (house.isPeer) flagpole(g, facesRight ? hx + w + 9 : hx - 9, 0, v.site.country, 'where ' + v.site.name + ' stands');
                        hx += w + 16;
                    });
                    // The village's LAN: one string of festoon lights along every eave. It is one shared network, so it
                    // stays lit past a machine that is down; only the bulb at that house's own eave goes dark. The
                    // whole string is dark when the village's own peer is, since then nothing of the LAN is known.
                    const lights = el('g', {}, g);
                    for (let e = 0; e + 1 < eaves.length; e++) {
                        const a = eaves[e], c = eaves[e + 1];
                        const x1 = a.r - 2, y1 = a.y, x2 = c.l + 2, y2 = c.y, sag = 7 + (x2 - x1) * 0.12;
                        const mx = (x1 + x2) / 2, my = Math.max(y1, y2) + sag;
                        el('path', { d: `M ${x1} ${y1} Q ${mx} ${my} ${x2} ${y2}`, fill: 'none', stroke: '#1c1a1e', 'stroke-width': 0.8, opacity: 0.8 }, lights);
                        const n = Math.max(2, Math.round((x2 - x1) / 7));
                        for (let j = 1; j < n; j++) {
                            const lit = !v.site.peer.dark && !(j === 1 && a.dark) && !(j === n - 1 && c.dark);
                            const t = j / n;
                            const bx = (1 - t) * (1 - t) * x1 + 2 * (1 - t) * t * mx + t * t * x2;
                            const by = (1 - t) * (1 - t) * y1 + 2 * (1 - t) * t * my + t * t * y2 + 1.5;
                            el('circle', { cx: bx.toFixed(1), cy: by.toFixed(1), r: 1.3, fill: lit ? '#ffe2a0' : '#3a3530' }, lights);
                            if (lit) el('circle', { cx: bx.toFixed(1), cy: by.toFixed(1), r: 4, fill: 'url(#tp-glow)', opacity: 0.5 }, lights);
                        }
                    }
                    // The tunnel leaves from the village's own peer; the machines behind it reach the
                    // internet through that house and have no wake of their own.
                    villageWake(peerX, ri === 0 ? SHORE + 10 : R.base + 4, wakeIndex++, v.site.peer.dark,
                        hash(v.site.peer.id || v.site.name));
                });
            });
        });

        // --- the lighthouse on its skerry -------------------------------------------------------------------
        // Machines on the Vaier server's own LAN stand on the skerry beside the tower.
        const skerry = fleet.skerry.slice().sort(byName);
        const SLOTS = [{ from: X - 22, to: X - 180 }, { from: X + 26, to: X + 160 }];
        const room = SLOTS.reduce((a, s) => a + Math.abs(s.to - s.from), 0);
        const skerryW = skerry.reduce((a, h) => a + (h.stabbur ? 50 : 32) + 12, 0);
        const sk = Math.max(0.3, Math.min(0.62, room / Math.max(1, skerryW)));
        const skerryPlaced = [];
        let side = 0, cursor = SLOTS[0].from;
        skerry.forEach((house) => {
            const w = ((house.stabbur ? 50 : 32) + 12) * sk;
            if (side === 0 && cursor - w < SLOTS[0].to) { side = 1; cursor = SLOTS[1].from; }
            const x = side === 0 ? cursor - w : cursor;
            cursor = side === 0 ? cursor - w : cursor + w;
            skerryPlaced.push({ house, x, w });
        });
        const skL = Math.min(X - 100, ...skerryPlaced.map((p) => p.x - 24));
        const skR = Math.max(X + 140, ...skerryPlaced.map((p) => p.x + p.w + 24));

        const lh = el('g', {});
        el('path', { d: `M ${skL} 640 C ${skL + 20} 600, ${X - 40} 600, ${X - 20} 590 C ${X + 20} 578, ${X + 60} 596, ${skR - 50} 612 C ${skR - 20} 628, ${skR} 640, ${skR - 30} 648 Z`, fill: '#1b1f2a' }, lh);
        el('path', { d: `M ${skL + 20} 632 C ${X - 40} 612, ${X} 606, ${X + 50} 612`, fill: 'none', stroke: '#2d3342', 'stroke-width': 3 }, lh);
        // The beam: Vaier's light, what the internet sees — the published services, sweeping the open sea.
        const published = fleet.server.published;
        const beam = el('g', { class: 'tp-beam tp-anim' + (L.two ? ' is-up' : ''), style: `transform-origin: ${X}px 484px` }, lh);
        el('path', { d: L.two ? `M ${X} 484 L ${X - 90} 0 L ${X + 90} 0 Z` : `M ${X} 484 L 2000 380 L 2000 560 Z`,
            fill: 'url(#tp-beam)', opacity: published ? (L.two ? 0.32 : 0.4) : 0.12, filter: 'url(#tp-soft)' }, beam);
        const tower = el('g', {}, lh);
        el('path', { d: `M ${X - 18} 605 L ${X - 12} 500 L ${X + 12} 500 L ${X + 18} 605 Z`, fill: '#eef0f3', class: 'tp-ring' }, tower);
        [520, 555, 588].forEach((y) => {
            const k = (y - 500) * 0.056;
            el('path', { d: `M ${X - 14 - k} ${y} L ${X + 14 + k} ${y} L ${X + 15 + k} ${y + 14} L ${X - 15 - k} ${y + 14} Z`, fill: '#b8352b' }, tower);
        });
        el('rect', { x: X - 16, y: 494, width: 32, height: 6, fill: '#2a2e38' }, tower);
        el('rect', { x: X - 11, y: 474, width: 22, height: 20, fill: '#fff0c4' }, tower);
        el('circle', { cx: X, cy: 484, r: 42, fill: 'url(#tp-glow)' }, tower);
        el('path', { d: `M ${X - 14} 474 L ${X} 460 L ${X + 14} 474 Z`, fill: '#2a2e38' }, tower);
        el('rect', { x: X - 30, y: 598, width: 60, height: 10, fill: '#e3e5ea' }, tower);
        el('rect', { x: X - 16, y: 612, width: 36, height: 55, fill: '#fff1c8', opacity: 0.12, filter: 'url(#tp-soft)' }, tower);
        const lightWords = published
            ? published + (published === 1 ? ' service published' : ' services published') + ' to the internet'
            : 'nothing published to the internet yet';
        hoverable(tower, { name: fleet.server.name, role: 'the Vaier server', address: fleet.server.address, state: lightWords },
            fleet.server.id);

        skerryPlaced.forEach((p) => {
            const g = el('g', { transform: `translate(${p.x.toFixed(1)} 604) scale(${sk.toFixed(3)})` }, lh);
            const b = p.house.stabbur ? stabbur(g, 6, p.house, null) : rorbu(g, 6, 32, 26, p.house, false, null);
            hoverable(b, p.house, p.house.id);
        });
        const rightmost = Math.max(X + 62, ...skerryPlaced.filter((p) => p.x > X).map((p) => p.x + p.w + 10));
        flagpole(lh, rightmost, 604, fleet.server.country, 'where the Vaier server stands', fleet.server.place);

        const beamHit = el('path', { d: L.two ? `M ${X} 468 L ${X - 70} 280 L ${X + 70} 280 Z` : `M ${X + 20} 470 L 1600 400 L 1600 500 Z`,
            fill: 'transparent' });
        hoverable(beamHit, { name: 'Published services', role: 'the beam', state: lightWords }, null);

        // The public, steering for the light: a coastal ship on the horizon, there only when something shines.
        const ship = el('g', { transform: `translate(${L.ship} ${SHORE - 1})` });
        if (published) {
            el('path', { d: 'M -34 0 L 34 0 L 30 7 L -30 7 Z', fill: '#101218' }, ship);
            el('rect', { x: -24, y: -9, width: 44, height: 9, fill: '#eef0f2' }, ship);
            el('rect', { x: -14, y: -15, width: 26, height: 6, fill: '#eef0f2' }, ship);
            el('rect', { x: 2, y: -22, width: 6, height: 8, fill: '#b8352b' }, ship);
            for (let i = 0; i < 8; i++) el('rect', { x: -22 + i * 5.2, y: -6, width: 2.4, height: 2.4, fill: '#ffd88f' }, ship);
            el('path', { d: 'M -34 8 L 34 8', stroke: '#ffd88f', opacity: 0.25, 'stroke-width': 3, filter: 'url(#tp-tiny)' }, ship);
            hoverable(ship, { name: 'The public', role: 'coastal ship', state: 'people on the internet, steering for the published services' }, null);
        }

        const seaHit = L.two
            ? el('rect', { x: s0, y: SHORE + 30, width: s1 - s0, height: H - SHORE - 30, fill: 'transparent' })
            : el('rect', { x: s0 + 40, y: SHORE + 40, width: s1 - s0 - 40, height: H - SHORE - 40, fill: 'transparent' });
        hoverable(seaHit, { name: 'The internet', role: 'open sea', state: 'every tunnel crosses it to reach the lighthouse' }, null);

        // --- boats ------------------------------------------------------------------------------------------
        // A peer that is online sails the open sea, each in a cell of its own, the cell picked by the machine's
        // identity so a boat keeps its water when another joins. One that is offline lies moored at a quay
        // running out from the skerry: sail furled, lantern out, no wake.
        const boats = fleet.boats.slice().sort(byName);
        const sailing = boats.filter((b) => !b.dark);
        const moored = boats.filter((b) => b.dark);
        const quays = [];
        if (moored.length) {
            // Out from the skerry toward open water, then along its other side when that one is full.
            const left = { from: Math.min(X - 70, skL + 40), dir: -1, room: Math.min(520, Math.min(X - 70, skL + 40) - 30) };
            const right = { from: Math.max(X + 70, skR - 40), dir: 1, room: Math.min(520, W - 30 - Math.max(X + 70, skR - 40)) };
            const sides = L.two || X > W / 2 ? [left, right] : [right, left];
            const need = moored.length * 90;
            const fit = Math.min(1, (sides[0].room + sides[1].room) / need);
            const step = 90 * fit;
            let side = 0, used = 0;
            moored.forEach((b) => {
                if (used + step > sides[side].room + 1 && side === 0) { side = 1; used = 0; }
                const q = sides[side];
                b.at = { x: q.from + q.dir * (used + 45 * fit + 10), y: 664, s: 0.8 * Math.max(0.5, fit) };
                used += step;
                q.used = used;
            });
            const quay = el('g', {});
            sides.filter((q) => q.used).forEach((q) => {
                const a = q.dir < 0 ? q.from - q.used - 20 : q.from;
                const len = q.used + 20;
                for (let x = a + 6; x < a + len; x += 22) el('rect', { x: x.toFixed(1), y: 646, width: 3, height: 16, fill: '#2a2320' }, quay);
                el('rect', { x: a.toFixed(1), y: 643, width: len.toFixed(1), height: 5, fill: '#4a3c33' }, quay);
                el('rect', { x: a.toFixed(1), y: 666, width: len.toFixed(1), height: 3, fill: '#4a3c33', opacity: 0.15, filter: 'url(#tp-tiny)' }, quay);
                quays.push([a - 10, 600, a + len + 10, 700]);
            });
            town.appendChild(quay);
        }

        const keepOut = [[skL - 10, 540, skR + 10, 704]].concat(quays);
        const [coreFrom, coreTo] = L.two ? [s0 - 110, s1 + 110] : [960, W - 6];
        const [wideFrom, wideTo] = L.two ? [300, 1380] : [640, W - 6];
        let bs = 1, slots = [];
        for (const s of [1, 0.8, 0.62, 0.48, 0.36]) {
            bs = s;
            slots = [];
            const cw = 116 * s, ch = 100 * s;
            for (let y = 628; y + ch <= H - 8; y += ch) {
                const [from, to] = y > 740 ? [wideFrom, wideTo] : [coreFrom, coreTo];
                for (let x = from; x + cw <= to; x += cw) {
                    const hit = keepOut.some(([a, b, c, d]) => x < c && x + cw > a && y < d && y + ch > b);
                    if (!hit) slots.push({ x: x + cw / 2, y: y + ch * 0.45, cw });
                }
            }
            if (slots.length >= sailing.length) break;
        }
        const taken = new Set();
        sailing.forEach((b) => {
            let i = hash(b.id) % Math.max(1, slots.length);
            for (let n = 0; n < slots.length && taken.has(i); n++) i = (i + 1) % slots.length;
            taken.add(i);
            b.at = Object.assign({ s: bs }, slots[i]);
        });

        // --- pirate ships: addresses the edge is keeping out ------------------------------------------------
        // A fresh ban sits close in, just turned away; as it runs down its ship drifts out toward the horizon.
        // Spread over the whole water, clear of the lighthouse, its quay and each other.
        const pirates = el('g', {});
        const placed = sailing.map((b) => ({ x: b.at.x, y: b.at.y }));
        fleet.bans.slice().sort((a, b) => a.drift - b.drift || a.ip.localeCompare(b.ip)).forEach((ban) => {
            const t = Math.min(1, ban.drift / 240);
            const hr = rng(hash(ban.ip));
            const y = SHORE + 60 + t * 250 + (hr() - 0.5) * 40;
            const k = 0.3 + t * 0.4;
            let x = 70 + hr() * (W - 140);
            for (let tries = 0; tries < 60; tries++) {
                const nearLight = Math.abs(x - X) < 260 && y < 760;
                const nearQuay = quays.some(([a, b, c, d]) => x > a - 40 && x < c + 40 && y > b - 30 && y < d + 30);
                if (!nearLight && !nearQuay && placed.every((p) => Math.hypot(p.x - x, (p.y - y) * 2) > 150)) break;
                x = 70 + hr() * (W - 140);
            }
            placed.push({ x, y });
            const away = x < X ? -1 : 1;   // the bow points away from the lighthouse
            const outer = el('g', { transform: `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${k.toFixed(3)})`,
                opacity: (0.55 + t * 0.45).toFixed(2) }, pirates);
            const g = el('g', { transform: `scale(${away} 1)` }, outer);
            el('path', { d: 'M -40 0 L 44 0 L 36 12 L -32 12 Q -42 8 -46 -6 Z', fill: '#0c0d12', class: 'tp-ring' }, g);
            el('path', { d: 'M -46 -6 L -30 -6 L -30 0 L -40 0 Z', fill: '#16171d' }, g);
            el('line', { x1: -6, y1: 0, x2: -6, y2: -64, stroke: '#1b1a1a', 'stroke-width': 2.4 }, g);
            el('line', { x1: 20, y1: 0, x2: 20, y2: -48, stroke: '#1b1a1a', 'stroke-width': 2.2 }, g);
            el('line', { x1: 40, y1: -2, x2: 62, y2: -16, stroke: '#1b1a1a', 'stroke-width': 1.6 }, g);
            el('path', { d: 'M -26 -58 Q -6 -52 14 -58 L 12 -30 Q -6 -24 -24 -30 Z', fill: '#17171c' }, g);
            el('path', { d: 'M -24 -26 Q -6 -20 12 -26 L 10 -6 Q -6 -2 -22 -6 Z', fill: '#17171c' }, g);
            el('path', { d: 'M 8 -44 Q 20 -40 32 -44 L 30 -10 Q 20 -6 10 -10 Z', fill: '#17171c' }, g);
            el('path', { d: 'M -6 -64 L 8 -61 L -6 -58 Z', fill: '#0a0a0a' }, g);
            el('circle', { cx: -6, cy: -44, r: 3.2, fill: '#d9d4c8' }, g);
            el('path', { d: 'M -11 -38 L -1 -34 M -1 -38 L -11 -34', stroke: '#d9d4c8', 'stroke-width': 1.3 }, g);
            el('circle', { cx: -38, cy: -8, r: 2.2, fill: '#ff5a3c' }, g);
            el('circle', { cx: -38, cy: -8, r: 10, fill: '#ff5a3c', opacity: 0.25, filter: 'url(#tp-soft)' }, g);
            // The flag at the masthead, outside the mirrored hull so it never reads backwards.
            if (FLAGS[ban.country]) {
                el('line', { x1: -6 * away, y1: -64, x2: -6 * away, y2: -80, stroke: '#1b1a1a', 'stroke-width': 2 }, outer);
                cloth(outer, -6 * away + 1, -80, ban.country, 1.3);
            }
            const said = [ban.country, triedWords(ban.scenario)].filter(Boolean).join(', ');
            hoverable(outer, { name: ban.ip, role: said, state: () => {
                const left = Math.max(1, Math.round((ban.until - Date.now()) / 60000));
                const hrs = Math.floor(left / 60), mins = left % 60;
                return 'kept out for ' + (hrs ? hrs + ' h ' : '') + mins + ' min more';
            } }, null);
            outer.classList.add('is-open');
            outer.setAttribute('role', 'link');
            outer.addEventListener('click', () => { tip.hidden = true; if (opts.onBan) opts.onBan(); });
            outer.addEventListener('keydown', (e) => {
                if ((e.key === 'Enter' || e.key === ' ') && opts.onBan) { e.preventDefault(); tip.hidden = true; opts.onBan(); }
            });
        });

        function boat(b) {
            const { x, y, s } = b.at;
            const g = el('g', { transform: `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${s.toFixed(3)})` });
            if (b.dark) {
                el('path', { d: 'M -34 0 C -20 14, 20 14, 36 -2 L 30 2 L -30 2 Z', fill: '#8f8b84', class: 'tp-ring' }, g);
                el('path', { d: 'M -34 0 C -20 14, 20 14, 36 -2', fill: 'none', stroke: '#4d3326', 'stroke-width': 3 }, g);
                // The mooring line up to the quay.
                el('path', { d: 'M 30 -1 Q 40 -14, 44 -24', fill: 'none', stroke: '#8a7a66', 'stroke-width': 1 }, g);
                if (b.kind === 'sail') {
                    el('line', { x1: 0, y1: 0, x2: 0, y2: -58, stroke: '#2e2520', 'stroke-width': 2 }, g);
                    el('path', { d: 'M 0 -10 L 30 -8 L 30 -5 L 0 -6 Z', fill: '#b7b0a4' }, g);
                } else if (b.kind === 'sjark') {
                    el('rect', { x: 6, y: -20, width: 20, height: 18, fill: '#6e6a63' }, g);
                    el('rect', { x: 4, y: -23, width: 24, height: 4, fill: '#4d2a22' }, g);
                } else {
                    el('line', { x1: -22, y1: -2, x2: 22, y2: -3, stroke: '#5a3f2d', 'stroke-width': 2 }, g);
                }
                hoverable(g, b, b.id);
                return;
            }
            el('path', { d: 'M -34 0 C -20 14, 20 14, 36 -2 L 30 2 L -30 2 Z', fill: '#e9e2d3', class: 'tp-ring' }, g);
            el('path', { d: 'M -34 0 C -20 14, 20 14, 36 -2', fill: 'none', stroke: '#6b3a24', 'stroke-width': 3 }, g);
            let lampX = 0;
            if (b.kind === 'sail') {
                el('line', { x1: 0, y1: 0, x2: 0, y2: -62, stroke: '#3a2c24', 'stroke-width': 2 }, g);
                el('path', { d: 'M 2 -60 L 2 -6 L 34 -6 Z', fill: '#f1ece2' }, g);
                el('path', { d: 'M -2 -52 L -2 -8 L -24 -8 Z', fill: '#d9d1c2' }, g);
                lampX = -12;
            } else if (b.kind === 'sjark') {
                // A small fishing boat: a wheelhouse aft and a short mast.
                el('rect', { x: 6, y: -20, width: 20, height: 18, fill: '#dcd6c8' }, g);
                el('rect', { x: 4, y: -23, width: 24, height: 4, fill: '#8a3a2a' }, g);
                el('line', { x1: -14, y1: 0, x2: -14, y2: -40, stroke: '#3a2c24', 'stroke-width': 2 }, g);
                lampX = 16;
            } else {
                el('line', { x1: -26, y1: -4, x2: -46, y2: 10, stroke: '#6b4a34', 'stroke-width': 2 }, g);
                el('line', { x1: 24, y1: -4, x2: 44, y2: 10, stroke: '#6b4a34', 'stroke-width': 2 }, g);
            }
            el('circle', { cx: lampX, cy: -8, r: 3, fill: '#ffd88f' }, g);
            el('circle', { cx: lampX, cy: -8, r: 14, fill: 'url(#tp-glow)', opacity: 0.8 }, g);
            el('path', { d: 'M -30 8 C -16 18, 16 18, 32 6', fill: 'none', stroke: '#e9e2d3', opacity: 0.15, 'stroke-width': 3 }, g);
            hoverable(g, b, b.id);
        }

        // --- assemble ----------------------------------------------------------------------------------------
        svg.appendChild(wakes);
        sailing.forEach((b, i) => {
            const at = b.at;
            const left = at.x < X;
            const x1 = at.x + (left ? 34 : -34) * at.s, y1 = at.y + 4 * at.s, x2 = X + (left ? -40 : 40), y2 = LIGHT.y + 44;
            wake(`M ${x1.toFixed(1)} ${y1.toFixed(1)} Q ${((x1 + x2) / 2).toFixed(1)} ${(Math.max(y1, y2) + 40).toFixed(1)} ${x2} ${y2}`,
                false, 12 + (i % 4) * 3, hash(b.id));
        });
        svg.appendChild(seaHit);
        svg.appendChild(town);
        svg.appendChild(pirates);
        svg.appendChild(ship);
        svg.appendChild(beamHit);
        boats.forEach(boat);
        svg.appendChild(lh);
        svg.appendChild(lanterns);
        const vg = el('radialGradient', { id: 'tp-vig', cx: 0.5, cy: 0.45, r: 0.75 }, defs);
        el('stop', { offset: 0.6, 'stop-color': '#000', 'stop-opacity': 0 }, vg);
        el('stop', { offset: 1, 'stop-color': '#000', 'stop-opacity': 0.5 }, vg);
        el('rect', { x: 0, y: 0, width: W, height: H, fill: 'url(#tp-vig)', 'pointer-events': 'none' });

        // The scenery is complete now the villages have added their smears: paint it once, under everything.
        const fresh = [];
        const picture = (root) => {
            const u = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(root)], { type: 'image/svg+xml' }));
            fresh.push(u);
            return u;
        };
        svg.insertBefore(el('image', { href: picture(scene), x: 0, y: 0, width: W, height: H, 'pointer-events': 'none' }),
            defs.nextSibling);
        // Film grain over everything, where there is room and motion to carry it.
        if (!narrow && !still) {
            const grain = document.createElementNS(NS, 'svg');
            grain.setAttribute('xmlns', NS);
            grain.setAttribute('viewBox', `0 0 ${W} ${H}`);
            grain.setAttribute('width', W);
            grain.setAttribute('height', H);
            filter(grain, 'grain', (f) => {
                el('feTurbulence', { type: 'fractalNoise', baseFrequency: '0.85', numOctaves: 2, seed: 2, stitchTiles: 'stitch', result: 'g' }, f);
                el('feColorMatrix', { in: 'g', type: 'saturate', values: 0 }, f);
            });
            el('rect', { x: 0, y: 0, width: W, height: H, filter: 'url(#grain)' }, grain);
            el('image', { href: picture(grain), x: 0, y: 0, width: W, height: H, opacity: 0.035,
                'pointer-events': 'none', style: 'mix-blend-mode: overlay' });
        }
        Promise.all(fresh.map(decoded)).then(() => {
            if (mine !== drawing) { fresh.forEach((u) => URL.revokeObjectURL(u)); return; }
            live.replaceChildren(...svg.childNodes);
            // An animation keeps the clock of the picture it was made in, and the buffer's never runs.
            live.querySelectorAll('animateMotion').forEach((a) => a.replaceWith(a.cloneNode(true)));
            urls.forEach((u) => URL.revokeObjectURL(u));
            urls = fresh;
            resync(live);
        });
    }

    // Resolves once the browser holds the picture decoded, so the swap shows it at once.
    function decoded(url) {
        const img = new Image();
        img.src = url;
        return img.decode().catch(() => {});
    }

    let drawing = 0;   // the latest repaint; an older one still decoding is dropped
    let urls = [];   // the painted scenery of the last repaint, released on the next

    // Everything that moves keeps time by the wall clock, so a picture taken out of the page and put back (every
    // Explorer re-render does that) or redrawn carries on where it was instead of jumping to its start.
    function resync(svg) {
        const now = Date.now() / 1000;
        // Only a clock that restarted needs setting; nudging one that runs true is itself a stutter.
        try {
            if (Math.abs(svg.getCurrentTime() - now % 86400) > 0.25) svg.setCurrentTime(now % 86400);
        } catch (e) { /* not attached yet */ }
        svg.querySelectorAll('.tp-beam').forEach((b) => {
            b.style.animationDelay = -(now % (b.classList.contains('is-up') ? 11 : 9)).toFixed(3) + 's';
        });
    }

    window.VaierTopology = { draw, resync };
})();
