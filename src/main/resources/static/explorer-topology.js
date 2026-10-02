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

    // What a pirate was caught doing, in words anyone in the family understands.
    const TRIED = {
        'http-probing': 'looking for weak spots', 'http-admin-interface-probing': 'looking for weak spots',
        'http-wordpress-scan': 'looking for weak spots', 'http-backdoors-attempts': 'trying to break in',
        'http-crawl-non_statics': 'snooping around', 'http-bad-user-agent': 'using a break-in tool',
        'http-sensitive-files': 'looking for secret files', 'http-path-traversal-probing': 'trying to sneak into files',
        'ssh-bf': 'guessing passwords', 'ssh-slow-bf': 'guessing passwords', 'http-generic-bf': 'guessing passwords',
    };
    const triedWords = (scenario) => TRIED[String(scenario || '').split('/').pop()] || 'trying to break in';
    // Who a pirate is: someone from somewhere.
    const THE = /^(United |Netherlands|Philippines|Czech|Dominican|Bahamas|Maldives|Seychelles|Gambia|Central African)/;
    const someone = (country) => (country ? 'Someone in ' + (THE.test(country) ? 'the ' : '') + country : 'Someone, location unknown');
    // North first: a site with no known latitude goes after every one that has one.
    const northFirst = (a, b) => ((b.latitude ?? -999) - (a.latitude ?? -999)) || byName(a, b);

    // The climate a site's houses are drawn in. Below 45° is the Mediterranean; everything else, and anywhere
    // Vaier cannot place, is the Norwegian coast.
    const bandOf = (site) => (site.latitude != null && site.latitude < 45 ? 'med' : 'nordic');

    // Where a boat that is offline lies moored: at a village in the country it was last seen in (the nearest
    // one by latitude), else on the coast of its own climate if that is drawn, else on the only coast there is.
    // A boat Vaier cannot place has the Norwegian climate, as a site does.
    function mooringOf(boat, harbours, bands) {
        const same = harbours.filter((h) => boat.country && h.country === boat.country);
        if (same.length) {
            const off = (h) => (boat.latitude != null && h.latitude != null ? Math.abs(h.latitude - boat.latitude) : 0) + h.row * 0.01;
            const harbour = same.reduce((a, h) => (off(h) < off(a) ? h : a));
            return { harbour, band: harbour.band };
        }
        const band = bandOf(boat);
        return { band: bands.includes(band) ? band : bands[0] };
    }

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

    // How a coast looks under its sun. water runs horizon to foreground; back/cloud carry an opacity; snow its
    // top, foot and opacity; trees the spruce (or cypress, olive); tint is the south's warm wash on its hills.
    const LOOKS = {
        'nordic-day': { sky: [[0, '#3a6aa3'], [0.5, '#76a3d0'], [0.85, '#b7d1e6'], [1, '#e2ecf2']],
            water: ['#9ab8d0', '#5480a2', '#2f5a7c', '#1b3a58'], back: ['#9cb0ca', 0.85], far: '#5a7090', mid: '#2a4238',
            trees: ['#1a2e26'], snow: ['#ffffff', '#cdd9e8', 0.95], cloud: ['#ffffff', 0.5], mist: '#eef3f8', mistO: 0.3,
            stars: 0, lit: false },
        'nordic-twilight': { sky: [[0, '#0a1430'], [0.42, '#1f3060'], [0.72, '#4f5486'], [0.9, '#b9808a'], [1, '#f0a878']],
            water: ['#5a4e6e', '#2a3356', '#141f3c', '#0a1226'], back: ['#5b5f8a', 0.85], far: '#343e69', mid: '#1d2645',
            trees: ['#121a31'], snow: ['#ffe3d6', '#c7a9bf', 0.85], cloud: ['#e9a58e', 0.25], cloudLit: '#ffd0a8',
            mist: '#d6b9c4', mistO: 0.16, stars: 0.35, lit: true },
        'nordic-night': { sky: [[0, '#060b19'], [0.45, '#132346'], [0.8, '#35446f'], [0.94, '#5f5b82'], [1, '#8a7490']],
            water: ['#283252', '#18243f', '#0f1930', '#071020'], back: ['#46557f', 0.85], far: '#2c3b62', mid: '#1b2744',
            trees: ['#111a30'], snow: ['#eef2f8', '#aeb9cc', 0.85], cloud: ['#8d9cc4', 0.2], mist: '#b9c3dc', mistO: 0.16,
            stars: 1, lit: true },
        'med-day': { sky: [[0, '#2c69b0'], [0.5, '#6aa4da'], [0.85, '#b8d6ea'], [1, '#f1e8d8']],
            water: ['#a8d4e2', '#4b9cc2', '#226a96', '#114068'], back: ['#c9b29e', 0.75], far: '#b08664', tint: ['#ffe2b0', 0.15],
            mid: '#857252', trees: ['#2f4229', '#66734a'], cloud: ['#ffffff', 0.5], mist: '#f6ecdc', mistO: 0.3,
            stars: 0, lit: false },
        'med-twilight': { sky: [[0, '#141a38'], [0.4, '#34386a'], [0.7, '#9a6680'], [0.88, '#e48c64'], [1, '#f7b47a']],
            water: ['#80585e', '#3e3854', '#1d253f', '#0d1328'], back: ['#9a6f6e', 0.7], far: '#6b4b4f', tint: ['#e8a070', 0.18],
            mid: '#3b2f3a', trees: ['#1f2a24', '#39433a'], cloud: ['#f3b18a', 0.3], cloudLit: '#ffd7a8', mist: '#e8b8a0', mistO: 0.16,
            stars: 0.15, lit: true },
        'med-night': { sky: [[0, '#070917'], [0.45, '#151834'], [0.78, '#2c2644'], [0.93, '#4e3548'], [1, '#74483f']],
            water: ['#3a2c40', '#1d1a30', '#111325', '#080a16'], back: ['#4c3a4c', 0.7], far: '#34263a', tint: ['#e0915f', 0.05],
            mid: '#221a26', trees: ['#121814', '#1a201a'], cloud: ['#5e4e6a', 0.2], mist: '#6e5a6e', mistO: 0.14,
            stars: 0.8, lit: true },
    };
    const HORIZON = { day: { horizon: '#ffffff', horizonO: 0.5 }, twilight: { horizon: '#f6d3a8', horizonO: 0.75 },
                      night: { horizon: '#b7bfd8', horizonO: 0.35 } };

    // The sun's elevation in degrees (the USNO low-precision formulae, good to about a minute of arc this century).
    function sunElevation(latitude, longitude, date) {
        const rad = Math.PI / 180;
        const d = date.getTime() / 86400000 - 10957.5;   // days from J2000
        const g = (357.529 + 0.98560028 * d) * rad;
        const lambda = (280.459 + 0.98564736 * d + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * rad;
        const e = (23.439 - 0.00000036 * d) * rad;
        const ra = Math.atan2(Math.cos(e) * Math.sin(lambda), Math.cos(lambda));
        const dec = Math.asin(Math.sin(e) * Math.sin(lambda));
        const ha = (280.46061837 + 360.98564736629 * d + longitude) * rad - ra;
        const lat = latitude * rad;
        return Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(ha)) / rad;
    }
    // Day above 6°, night below −6°, twilight (civil, give or take) between.
    function skyAt(latitude, longitude, date) {
        const h = sunElevation(latitude, longitude, date);
        return h > 6 ? 'day' : h < -6 ? 'night' : 'twilight';
    }
    // The look of each coast the fleet has, from the sun over the middle of its sites right now.
    const HOME = { nordic: [63, 10], med: [39, -1] };
    function skyOver(sites, date) {
        const at = {};
        sites.forEach((s) => {
            const band = bandOf(s);
            at[band] = at[band] || [];
            if (s.latitude != null && s.longitude != null) at[band].push([s.latitude, s.longitude]);
        });
        if (!sites.length) at.nordic = [];
        const sky = {};
        Object.keys(at).forEach((band) => {
            const pts = at[band];
            const [lat, lon] = pts.length
                ? [0, 1].map((i) => pts.reduce((a, p) => a + p[i], 0) / pts.length) : HOME[band];
            sky[band] = skyAt(lat, lon, date);
        });
        return sky;
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
        const sky = Object.assign({ nordic: 'night', med: 'twilight' }, fleet.sky);

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
        // Each coast under its own sun; on two coasts the south's sky and sea fade in across the middle.
        const lookOf = (band) => sky[band];
        const P = (band) => LOOKS[band + '-' + sky[band]];
        const medShare = (x) => (L.two ? smooth((x - 650) / 450) : hasMed ? 1 : 0);
        const bandAt = (x) => (medShare(x) > 0.5 ? 'med' : 'nordic');
        const DARK = { day: 0, twilight: 1, night: 2 };
        // The lighthouse stands between the coasts and is lit while either is dark.
        const lightLook = L.coasts.map((c) => lookOf(c.band)).sort((a, b) => DARK[b] - DARK[a])[0];
        const bands = L.coasts.map((c) => c.band);
        bands.forEach((band) => {
            const p = P(band);
            grad(sdefs, 'sky-' + band, p.sky);
            grad(sdefs, 'water-' + band, p.water.map((c, i) => [[0, 0.08, 0.5, 1][i], c]));
            if (band === 'nordic') grad(sdefs, 'snow', [[0, p.snow[0]], [1, p.snow[1]]]);
        });
        grad(sdefs, 'medfade', [[0.4, '#fff', 0], [0.69, '#fff', 1]], 1, 0);
        const medMask = sc('mask', { id: 'medside' }, sdefs);
        sc('rect', { x: 0, y: 0, width: W, height: H, fill: 'url(#medfade)' }, medMask);
        // Paints one layer per coast, the southern one masked in over the middle.
        const perCoast = (paint) => bands.forEach((band, i) => paint(band, i && L.two ? { mask: 'url(#medside)' } : {}));
        const nordicP = hasNordic ? P('nordic') : null, medP = hasMed ? P('med') : null;
        grad(sdefs, 'mistc', [[0.4, (nordicP || medP).mist], [0.69, (medP || nordicP).mist]], 1, 0);
        grad(sdefs, 'aurora', [[0, '#7fe8b5', 0], [0.35, '#7fe8b5', 0.55], [0.7, '#5fc7c9', 0.3], [1, '#9b7fe0', 0]], 1, 0);
        grad(sdefs, 'reflfade', [[0, '#fff', 0.55], [1, '#fff', 0]]);
        grad(sdefs, 'sun', [[0, '#ffe2b0', 0.95], [0.3, '#ffb877', 0.5], [1, '#ff9a5a', 0]], null, null, 'radialGradient');
        grad(sdefs, 'daysun', [[0, '#fffdf4', 0.95], [0.12, '#fff6dc', 0.6], [0.4, '#fff3d6', 0.18], [1, '#fff3d6', 0]], null, null, 'radialGradient');
        grad(sdefs, 'moon', [[0, '#e8edf8', 0.35], [0.3, '#cdd6ea', 0.12], [1, '#cdd6ea', 0]], null, null, 'radialGradient');
        const hz = HORIZON[lightLook];
        grad(sdefs, 'horizon', [[0, hz.horizon, 0], [0.5, hz.horizon, hz.horizonO], [1, hz.horizon, 0]], 1, 0);
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
            el('feFlood', { 'flood-color': '#05070d', 'flood-opacity': lightLook === 'day' ? 0.28 : 0.45, result: 'ink' }, f);
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

        // --- sky ------------------------------------------------------------------------------------------
        perCoast((band, m) => sc('rect', Object.assign({ x: 0, y: 0, width: W, height: SHORE + 2, fill: `url(#sky-${band})` }, m)));
        // One sun and at most one moon in the picture, over the coast whose sky shows them best.
        const sunBand = hasMed && lookOf('med') !== 'night' ? 'med' : hasNordic && lookOf('nordic') !== 'night' ? 'nordic' : null;
        const sunX = sunBand === 'med' ? (L.two ? 1450 : 1420) : L.two ? 380 : 1400;
        const sunHigh = sunBand && lookOf(sunBand) === 'day';
        const sunY = sunHigh ? 150 : SHORE - 6;
        if (sunBand && sunHigh) {
            sc('circle', { cx: sunX, cy: sunY, r: 260, fill: 'url(#daysun)' });
            sc('circle', { cx: sunX, cy: sunY, r: 15, fill: '#fffdf6' });
        } else if (sunBand) {
            sc('circle', { cx: sunX, cy: sunY, r: 150, fill: 'url(#sun)' });
            sc('circle', { cx: sunX, cy: SHORE + 2, r: 26, fill: '#ffd49a', opacity: 0.9 });
        }
        // A northern dusk on two coasts has its sun behind the mountains: only its glow on the horizon.
        if (L.two && hasNordic && lookOf('nordic') === 'twilight' && sunBand !== 'nordic') {
            sc('ellipse', { cx: 520, cy: SHORE - 20, rx: 420, ry: 110, fill: '#f2a37a', opacity: 0.35, filter: 'url(#softer)' });
        }
        const moonBand = hasMed && lookOf('med') === 'night' && (!hasNordic || L.two) ? 'med'
            : hasNordic && lookOf('nordic') === 'night' ? 'nordic' : null;
        const moon = moonBand && { x: moonBand === 'med' ? (L.two ? 1330 : 1180) : L.two ? 330 : 1180, y: moonBand === 'med' ? 130 : 96 };
        const starsAt = (x) => P(bandAt(x)).stars;
        const stars = sc('g', {});
        const r = rng(7);
        for (let i = 0; i < 190; i++) {
            const x = r() * W, y = r() * 340;
            const big = r() < 0.08, size = 0.7 + r() * 0.5, o = 0.25 + r() * 0.7;
            const k = starsAt(x) * (moon ? Math.min(1, Math.hypot(x - moon.x, y - moon.y) / 160) : 1);
            if (k > 0) sc('circle', { cx: x, cy: y, r: big ? 1.5 : size, fill: '#fff', opacity: o * (1 - y / 420) * k }, stars);
        }
        if (moon) {
            sc('circle', { cx: moon.x, cy: moon.y, r: 120, fill: 'url(#moon)' });
            sc('circle', { cx: moon.x, cy: moon.y, r: 13, fill: '#f1efe6' });
            sc('circle', { cx: moon.x - 4, cy: moon.y - 3, r: 4, fill: '#d6d3c8', opacity: 0.6 });
            sc('circle', { cx: moon.x + 4, cy: moon.y + 4, r: 2.6, fill: '#d6d3c8', opacity: 0.5 });
        }
        if (hasNordic && lookOf('nordic') === 'night') {
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
        // A clear day's own mark: high thin cirrus, combed by the wind.
        const cirrus = sc('g', { filter: 'url(#soft)', fill: 'none', stroke: '#ffffff', 'stroke-linecap': 'round' });
        const ci = rng(808);
        for (let i = 0; i < 9; i++) {
            const x = ci() * W, y = 40 + ci() * 170, len = 120 + ci() * 260;
            if (lookOf(bandAt(x)) !== 'day') continue;
            sc('path', { d: `M ${x.toFixed(1)} ${y.toFixed(1)} q ${(len * 0.5).toFixed(1)} ${(-14 - ci() * 16).toFixed(1)} ${len.toFixed(1)} ${(-6 + ci() * 12).toFixed(1)}`,
                'stroke-width': 3 + ci() * 4, opacity: (0.18 + ci() * 0.17).toFixed(2) }, cirrus);
        }
        // Low streaked clouds in each coast's light; lit from below where its sun is low.
        const clouds = sc('g', { filter: 'url(#softer)' });
        const cr = rng(55);
        for (let i = 0; i < 14; i++) {
            const x = cr() * W, y = 250 + cr() * 190, w = 160 + cr() * 320, ry = 6 + cr() * 10;
            const p = P(bandAt(x));
            sc('ellipse', { cx: x, cy: y, rx: w / 2, ry, fill: p.cloud[0], opacity: p.cloud[1] }, clouds);
            if (p.cloudLit) sc('ellipse', { cx: x, cy: y + 5, rx: w / 2.4, ry: 3, fill: p.cloudLit, opacity: 0.25 }, clouds);
        }
        if (sunBand && !sunHigh) {
            const rays = sc('g', { filter: 'url(#softer)', opacity: 0.16 });
            [[-0.5, 70], [-0.25, 40], [0.05, 90], [0.35, 50]].forEach(([a, w]) => {
                const x2 = sunX + Math.sin(a - Math.PI / 2) * 700;
                sc('path', { d: `M ${sunX} ${SHORE} L ${(x2 - w).toFixed(1)} ${SHORE - 700} L ${(x2 + w).toFixed(1)} ${SHORE - 700} Z`, fill: '#ffd9a8' }, rays);
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
                return { coast, sink, x0, x1, p: P('nordic'),
                    back: ridge(61, 455, [[0.13, 170, 120], [0.49, 210, 160], [0.9, 120, 110]], 10, bx0, bx1, sinkBack),
                    far: ridge(3, 470, [[0.2, 240, 170], [0.5, 160, 140], [0.75, 260, 150]], 14, x0, x1, sink),
                    mid: ridge(11, SHORE, [[0.05, 245, 150], [0.37, 115, 110], [0.84, 180, 90]], 10, x0, x1, sink) };
            }
            return { coast, sink, x0, x1, p: P('med'),
                back: ridge(67, 490, [[0.39, 90, 160], [0.87, 120, 150]], 6, bx0, bx1, sinkBack),
                far: ridge(33, SHORE, [[0.365, 150, 140], [0.76, 110, 180]], 8, x0, x1, sink),
                mid: ridge(41, SHORE + 2, [[0.22, 70, 90], [0.51, 60, 120], [0.92, 90, 110]], 6, x0, x1, sink) };
        });
        const mist = (y, h, o) => sc('rect', { x: -20, y, width: W + 40, height: h, fill: 'url(#mistc)', opacity: o, filter: 'url(#softer)' }, land);
        shapes.forEach((s) => sc('path', { d: poly(s.back, SHORE), fill: s.p.back[0], opacity: s.p.back[1] }, land));
        mist(SHORE - 70, 40, (nordicP || medP).mistO);
        shapes.forEach((s, ci) => {
            sc('path', { d: poly(s.far, SHORE), fill: s.p.far }, land);
            if (s.coast.band === 'nordic') {
                const snowClip = sc('clipPath', { id: 'snowline' + ci }, sdefs);
                sc('path', { d: poly(s.far, SHORE) }, snowClip);
                const sr = rng(21);
                sc('path', { d: `M ${s.x0} 0 L ` + s.far.map(([x, y]) => x.toFixed(1) + ' ' + Math.min(y + 34 + sr() * 26, 330 + sr() * 30).toFixed(1)).join(' L ')
                    + ` L ${s.x1} 0 Z`, fill: 'url(#snow)', opacity: s.p.snow[2], 'clip-path': `url(#snowline${ci})` }, land);
            } else {
                sc('path', { d: poly(s.far, SHORE), fill: s.p.tint[0], opacity: s.p.tint[1] }, land);
            }
        });
        mist(SHORE - 40, 30, (nordicP || medP).mistO * 0.9);
        shapes.forEach((s, ci) => {
            if (s.coast.band === 'nordic') {
                sc('path', { d: poly(s.mid, SHORE + 4), fill: s.p.mid }, land);
                const fr = rng(5);
                const spruce = sc('g', { fill: s.p.trees[0] }, land);
                for (let x = Math.max(0, s.x0); x < s.x1 - 60; x += 7) {
                    const h = (8 + fr() * 16) * (1 - s.sink(x) * 0.7);
                    sc('path', { d: `M ${x} ${SHORE + 2} L ${x + 4} ${(SHORE + 2 - h).toFixed(1)} L ${x + 8} ${SHORE + 2} Z` }, spruce);
                }
                return;
            }
            // Low dry hills, cypresses and olive trees.
            sc('path', { d: poly(s.mid, SHORE + 4), fill: s.p.mid }, land);
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
                if (cypress) sc('ellipse', { cx: x, cy: ground - 14, rx: 3.2, ry: 15, fill: s.p.trees[0] }, grove);
                else sc('ellipse', { cx: x, cy: ground - 5, rx: 7, ry: 5, fill: s.p.trees[1], opacity: 0.9 }, grove);
            }
        });
        const groundOf = shapes.map((s) => s.p.mid);

        // --- water: the open sea is the internet ----------------------------------------------------------
        perCoast((band, m) => sc('rect', Object.assign({ x: 0, y: SHORE, width: W, height: H - SHORE, fill: `url(#water-${band})` }, m)));
        const refl = sc('g', { mask: 'url(#reflmask)', opacity: 0.55 });
        sc('g', { transform: `translate(0 ${2 * SHORE}) scale(1 -1)` }, sc('g', ripple, refl)).appendChild(land.cloneNode(true));
        // A path of broken light on the water under the sun, or the moon.
        const gr = rng(303);
        const glints = (cx, spread, n, o, colour) => {
            const glint = sc('g', { fill: colour });
            for (let i = 0; i < n; i++) {
                const y = SHORE + 6 + Math.pow(gr(), 1.3) * 330, w = 3 + gr() * (8 + (y - SHORE) / 12);
                const x = cx + (gr() - 0.5) * spread * (0.4 + (y - SHORE) / 300), a = o * (0.4 + gr() * 0.6);
                sc('rect', { x: x.toFixed(1), y: y.toFixed(1), width: w.toFixed(1), height: (1.2 + (y - SHORE) / 250).toFixed(2), opacity: a.toFixed(3) }, glint);
            }
        };
        if (sunBand && sunHigh) {
            sc('ellipse', { cx: sunX, cy: SHORE + 170, rx: 120, ry: 170, fill: '#ffffff', opacity: 0.1, filter: 'url(#softer)' });
            glints(sunX, 260, 150, 0.6, '#ffffff');
        } else if (sunBand) {
            sc('ellipse', { cx: sunX, cy: SHORE + 110, rx: 75, ry: 120, fill: '#ffc98a', opacity: 0.2, filter: 'url(#softer)' });
            glints(sunX, 120, 120, 0.55, '#ffe6bf');
        }
        if (moon) {
            sc('ellipse', { cx: moon.x, cy: SHORE + 130, rx: 45, ry: 140, fill: '#dfe6f5', opacity: 0.09, filter: 'url(#softer)' });
            glints(moon.x, 70, 70, 0.4, '#e4eaf6');
        }
        if (lightLook !== 'day') glints(X, 60, 40, 0.3, '#ffe6bf');
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
        if (lightLook !== 'day') sc('ellipse', { cx: X + 10, cy: LIGHT.y + 70, rx: 90, ry: 26, fill: '#ffe2a4', opacity: 0.08, filter: 'url(#soft)' });
        const smears = sc('g', ripple);

        // --- the tooltip, and what a machine does when it is chosen ----------------------------------------
        function hoverable(g, info, machineId) {
            g.setAttribute('class', 'tp-hit' + (machineId ? ' is-open' : ''));
            g.setAttribute('tabindex', '0');
            const words = () => {
                const seen = info.seen ? ago(info.seen) : '';
                const state = (typeof info.state === 'function' ? info.state() : info.state) || '';
                return [info.role, state + (state && seen ? ', last seen ' + seen : '')]
                    .filter(Boolean).join(' \u00b7 ');
            };
            const line = words();
            g.setAttribute('aria-label', info.name + (line ? ', ' + line : ''));
            if (machineId) g.setAttribute('role', 'link');
            const show = (x, y) => {
                pointer = { x, y };
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
        function flagpole(parent, x, y, country, role) {
            if (!FLAGS[country]) return;
            const g = el('g', {}, parent);
            el('line', { x1: x, y1: y, x2: x, y2: y - 46, stroke: '#d9dce2', 'stroke-width': 1.4 }, g);
            el('circle', { cx: x, cy: y - 47, r: 1.6, fill: '#e8c46a' }, g);
            cloth(g, x + 1, y - 46, country);
            hoverable(g, { name: country, role }, null);
        }

        // --- wakes (tunnels), each from a village's own peer to the lighthouse -----------------------------
        // Wakes run on water only: a village up the coast is seen sailing out from the shore below it.
        const onWater = el('clipPath', { id: 'tp-onwater' }, defs);
        el('rect', { x: 0, y: SHORE + 2, width: W, height: H - SHORE }, onWater);
        const wakes = el('g', { 'clip-path': 'url(#tp-onwater)', 'data-layer': 'wakes' });
        const lanterns = el('g', { 'clip-path': 'url(#tp-onwater)', 'data-layer': 'lanterns' });
        // Every wake's course as points on the water, so a moored boat can be kept off them.
        const wakeTrack = [];
        // segs: cubic Béziers [p0, c1, c2, p3], each point [x, y], one running on from the last.
        function track(segs) {
            const pts = [];
            segs.forEach(([a, b, c, e]) => {
                for (let i = 0; i <= 48; i++) {
                    const t = i / 48, u = 1 - t;
                    const y = u * u * u * a[1] + 3 * u * u * t * b[1] + 3 * u * t * t * c[1] + t * t * t * e[1];
                    if (y > SHORE) pts.push([u * u * u * a[0] + 3 * u * u * t * b[0] + 3 * u * t * t * c[0] + t * t * t * e[0], y]);
                }
            });
            return pts;
        }
        const crosses = (pts, [a, b, c, d]) => pts.some(([x, y]) => x > a - 5 && x < c + 5 && y > b - 5 && y < d + 5);
        function course(segs) {
            wakeTrack.push(...track(segs));
            const f = (p) => p[0].toFixed(1) + ' ' + p[1].toFixed(1);
            return `M ${f(segs[0][0])} ` + segs.map(([, b, c, e]) => `C ${f(b)}, ${f(c)}, ${f(e)}`).join(' ');
        }
        // By day a wake is white foam and what travels it a fleck of spray; after dark, a lantern.
        // key names the tunnel, so a repaint can fade a wake in or out rather than switch it.
        function wake(segs, faint, dur, seed, x, key) {
            const d = course(segs);
            const day = lookOf(bandAt(x)) === 'day';
            const w = el('g', { 'data-key': 'wake:' + key + (faint ? ':dark' : '') }, wakes);
            el('path', { d, fill: 'none', stroke: day ? '#f6f9fc' : '#e9d7a8', 'stroke-width': faint ? 1.2 : 2,
                'stroke-dasharray': faint ? '2 7' : '3 9', 'stroke-linecap': 'round', opacity: faint ? 0.3 : day ? 0.7 : 0.55 }, w);
            el('path', { d, fill: 'none', stroke: day ? '#ffffff' : '#ffe2a4', 'stroke-width': 8, opacity: faint ? 0.03 : 0.06,
                filter: 'url(#tp-soft)' }, w);
            // A dark tunnel carries no lantern.
            if (still || faint) return;
            const begin = (rng(seed)() * dur).toFixed(2) + 's';
            const lit = el('g', { 'data-key': 'lamp:' + key }, lanterns);
            const lamp = el('circle', { r: day ? 2.4 : 3, fill: day ? '#ffffff' : '#ffe7b0' }, lit);
            const halo = el('circle', day ? { r: 6, fill: '#ffffff', opacity: 0.25 } : { r: 10, fill: 'url(#tp-glow)' }, lit);
            [lamp, halo].forEach((n) => {
                el('animateMotion', { dur: dur + 's', repeatCount: 'indefinite', path: d, keyPoints: '0;1;0',
                    keyTimes: '0;0.5;1', calcMode: 'linear', begin }, n);
            });
        }
        function villageWake(x1, y1, i, faint, seed, key) {
            const k = i % 5;
            if (L.two) {
                // Straight out across the sea between the coasts.
                const x2 = X + (x1 < X ? -60 : 60), y2 = LIGHT.y + 30, sag = 150 + k * 30;
                wake([[[x1, y1], [x1 + (x2 - x1) * 0.25, y1 + sag], [x1 + (x2 - x1) * 0.7, y2 + sag * 0.7], [x2, y2]]],
                    faint, 20 + k * 5, seed, x1, key);
                return;
            }
            // Out through the fjord mouth, over the open sea, and in to the lighthouse.
            const sea = { x: 1480 - k * 20, y: 700 + k * 40, dip: 140 + k * 50 };
            const x2 = X + 30, y2 = LIGHT.y + 40;
            wake([[[x1, y1], [x1 + 120, y1 + sea.dip], [sea.x - 160, sea.y + 40], [sea.x, sea.y]],
                  [[sea.x, sea.y], [sea.x + 160, sea.y - 40], [x2 + 90, y2 + 10], [x2, y2]]],
                faint, 22 + k * 5, seed, x1, key);
        }

        // --- buildings ------------------------------------------------------------------------------------
        // Drawn in a village's own coordinates: x along the shore, y = 0 at the foot of the house.
        // Windows are lit only after dark; by day the glass holds a little sky.
        function windows(g, x, y, w, dark, seed, smear, arched, look) {
            const wr = rng(seed);
            const cols = w > 40 ? 3 : 2;
            const ww = 5, wh = 7, gap = (w - cols * ww) / (cols + 1);
            const day = look === 'day';
            for (let c = 0; c < cols; c++) {
                const lit = !dark && wr() > 0.15 && !day;
                const wx = x + gap + c * (ww + gap);
                el(arched ? 'path' : 'rect', arched
                    ? { d: `M ${wx} ${y + wh} L ${wx} ${y + 2.5} A 2.5 2.5 0 0 1 ${wx + ww} ${y + 2.5} L ${wx + ww} ${y + wh} Z`, fill: lit ? '#ffd88f' : '#2a2630' }
                    : { x: wx, y, width: ww, height: wh, fill: lit ? '#ffd88f' : '#1a2032' }, g);
                if (day) el('rect', { x: wx, y: y + (arched ? 1 : 0), width: ww, height: 2, fill: '#b9cfe2', opacity: 0.45 }, g);
                if (lit) {
                    el('circle', { cx: wx + ww / 2, cy: y + wh / 2, r: 9, fill: 'url(#tp-glow)', opacity: 0.55 }, g);
                    if (smear) {
                        el('rect', { x: wx, y: 4 - y * 0.6, width: ww, height: 16 + wr() * 18, fill: '#ffcf7a',
                            opacity: 0.18, filter: 'url(#tp-tiny)' }, smear);
                    }
                }
            }
        }
        function rorbu(parent, x, w, h, house, big, smear, look) {
            const g = el('g', {}, parent);
            const top = -h;
            for (let i = 0; i <= 3; i++) el('rect', { x: x + 2 + i * (w - 6) / 3, y: -2, width: 2.2, height: 12, fill: '#2b2622' }, g);
            el('rect', { x, y: top, width: w, height: h, fill: house.dark ? '#5b2a28' : '#a8352a', class: 'tp-ring' }, g);
            for (let i = 1; i < w / 5; i++) el('line', { x1: x + i * 5, y1: top + 2, x2: x + i * 5, y2: -2, stroke: '#000', opacity: 0.12 }, g);
            el('path', { d: `M ${x - 4} ${top + 1} L ${x + w / 2} ${top - h * 0.55} L ${x + w + 4} ${top + 1} Z`, fill: '#23242c' }, g);
            windows(g, x, top + h * 0.35, w, house.dark, hash(house.id || house.name), smear, false, look);
            if (big) el('rect', { x: x + w / 2 - 5, y: -13, width: 10, height: 13, fill: '#1e1a1a' }, g);
            return g;
        }
        // A whitewashed house with a terracotta roof and arched windows.
        function casa(parent, x, w, h, house, big, smear, look) {
            const g = el('g', {}, parent);
            const top = -h;
            el('rect', { x, y: top, width: w, height: h, fill: house.dark ? '#8e877e' : '#d8cdbd', class: 'tp-ring' }, g);
            if (look !== 'day') el('rect', { x, y: top, width: w, height: h, fill: look === 'night' ? '#6a5a8a' : '#f0a06a', opacity: look === 'night' ? 0.25 : 0.12 }, g);
            el('path', { d: `M ${x - 3} ${top + 1} L ${x + w / 2} ${top - h * 0.28} L ${x + w + 3} ${top + 1} Z`, fill: '#a4492a' }, g);
            windows(g, x, top + h * 0.38, w, house.dark, hash(house.id || house.name), smear, true, look);
            if (big) {
                el('rect', { x: x + w / 2 - 5, y: -13, width: 10, height: 13, fill: '#5a3a2a' }, g);
                el('rect', { x: x + w - 9, y: top - h * 0.28 - 12, width: 7, height: 16, fill: '#d8cdbd' }, g);
            }
            return g;
        }
        function stabbur(parent, x, house, smear, look) {
            const g = el('g', {}, parent);
            const base = -2;
            [x + 3, x + 20, x + 37].forEach((px) => el('rect', { x: px, y: base - 12, width: 5, height: 12, fill: '#6f6a64' }, g));
            el('rect', { x: x + 4, y: base - 36, width: 38, height: 24, fill: house.dark ? '#4f2a1a' : '#7a3b22', class: 'tp-ring' }, g);
            el('rect', { x: x - 2, y: base - 58, width: 50, height: 22, fill: house.dark ? '#5a3020' : '#8a4526' }, g);
            for (let i = 1; i < 10; i++) el('line', { x1: x - 2 + i * 5, y1: base - 58, x2: x - 2 + i * 5, y2: base - 36, stroke: '#000', opacity: 0.18 }, g);
            el('path', { d: `M ${x - 8} ${base - 57} L ${x + 23} ${base - 84} L ${x + 54} ${base - 57} Z`, fill: '#2d3a2a' }, g);
            el('path', { d: `M ${x - 8} ${base - 57} L ${x + 23} ${base - 84} L ${x + 54} ${base - 57}`, fill: 'none', stroke: '#465c3f', 'stroke-width': 2 }, g);
            windows(g, x + 4, base - 30, 38, house.dark, hash(house.id || house.name), smear, false, look);
            return g;
        }
        const houseWidth = (h) => (h.stabbur || h.isPeer ? 50 : 30 + hash(h.id) % 12);
        const houseHeight = (h) => (h.isPeer ? 38 : 24 + hash(h.id) % 10);
        function building(parent, x, h, band, smear) {
            const look = lookOf(band);
            if (h.stabbur) return stabbur(parent, x, h, smear, look);
            return band === 'med' ? casa(parent, x, houseWidth(h), houseHeight(h) + 4, h, h.isPeer, smear, look)
                                  : rorbu(parent, x, houseWidth(h), houseHeight(h), h, h.isPeer, smear, look);
        }

        // --- villages -------------------------------------------------------------------------------------
        // Rows along each coast: the near shore first, then further up the coast, drawn smaller and set on a
        // hill of their own. A row that still overflows is squeezed to fit.
        const ROWS = [{ base: SHORE + 4, s: 1 }, { base: 370, s: 0.6 }, { base: 262, s: 0.42 }];
        const town = el('g', {});
        groundOf.forEach((colour, ci) => grad(defs, 'tp-ledge' + ci, [[0, colour, 1], [0.7, colour, 0.6], [1, colour, 0]]));
        let wakeIndex = 0;
        const harbours = [];   // each village as drawn, for a boat last seen in its country to moor at
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
                        el('rect', { x: hx0 - 40, y: 0, width: v.housesW + 80, height: 7, fill: groundOf[ci] }, g);
                        el('rect', { x: hx0 - 30, y: 2, width: v.housesW + 60, height: 1.2, fill: HORIZON[lookOf(coast.band)].horizon, opacity: 0.25 }, g);
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
                        if (house.isPeer) flagpole(g, facesRight ? hx + w + 9 : hx - 9, 0, v.site.country, 'where ' + v.site.name + ' is');
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
                            if (lit && lookOf(coast.band) !== 'day') el('circle', { cx: bx.toFixed(1), cy: by.toFixed(1), r: 4, fill: 'url(#tp-glow)', opacity: 0.5 }, lights);
                        }
                    }
                    // The tunnel leaves from the village's own peer; the machines behind it reach the
                    // internet through that house and have no wake of their own.
                    villageWake(peerX, ri === 0 ? SHORE + 10 : R.base + 4, wakeIndex++, v.site.peer.dark,
                        hash(v.site.peer.id || v.site.name), v.site.peer.id || v.site.name);
                    harbours.push({ band: coast.band, country: v.site.country, latitude: v.site.latitude, row: ri,
                        left: vx + hx0 * k, right: vx + (hx0 + v.housesW) * k });
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
        // The flag stands past the houses on the seaward side, and the sign past the flag; the rock reaches to hold both.
        const rightmost = Math.max(X + 62, ...skerryPlaced.filter((p) => p.x > X).map((p) => p.x + p.w + 10));
        const signX = rightmost + 38;
        const skL = Math.min(X - 100, ...skerryPlaced.map((p) => p.x - 24));
        const skR = Math.max(X + 140, signX + 44, ...skerryPlaced.map((p) => p.x + p.w + 24));

        const lh = el('g', {});
        el('path', { d: `M ${skL} 640 C ${skL + 20} 600, ${X - 40} 600, ${X - 20} 590 C ${X + 20} 578, ${X + 60} 596, ${skR - 50} 612 C ${skR - 20} 628, ${skR} 640, ${skR - 30} 648 Z`, fill: lightLook === 'day' ? '#3a414c' : '#1b1f2a' }, lh);
        el('path', { d: `M ${skL + 20} 632 C ${X - 40} 612, ${X} 606, ${X + 50} 612`, fill: 'none', stroke: lightLook === 'day' ? '#6a717c' : '#2d3342', 'stroke-width': 3 }, lh);
        // The beam: Vaier's light, what the internet sees — the published services, sweeping the open sea.
        const published = fleet.server.published;
        // A still picture keeps its beam still: the stylesheet's reduced-motion rule loses to .tp-beam.is-up.
        const beam = el('g', still ? {} : { class: 'tp-beam tp-anim' + (L.two ? ' is-up' : ''), style: `transform-origin: ${X}px 484px` }, lh);
        el('path', { d: L.two ? `M ${X} 484 L ${X - 90} 0 L ${X + 90} 0 Z` : `M ${X} 484 L 2000 380 L 2000 560 Z`,
            fill: 'url(#tp-beam)', opacity: (published ? (L.two ? 0.32 : 0.4) : 0.12) * (lightLook === 'day' ? 0.3 : 1), filter: 'url(#tp-soft)' }, beam);
        const tower = el('g', {}, lh);
        el('path', { d: `M ${X - 18} 605 L ${X - 12} 500 L ${X + 12} 500 L ${X + 18} 605 Z`, fill: '#eef0f3', class: 'tp-ring' }, tower);
        [520, 555, 588].forEach((y) => {
            const k = (y - 500) * 0.056;
            el('path', { d: `M ${X - 14 - k} ${y} L ${X + 14 + k} ${y} L ${X + 15 + k} ${y + 14} L ${X - 15 - k} ${y + 14} Z`, fill: '#b8352b' }, tower);
        });
        el('rect', { x: X - 16, y: 494, width: 32, height: 6, fill: '#2a2e38' }, tower);
        el('rect', { x: X - 11, y: 474, width: 22, height: 20, fill: lightLook === 'day' ? '#fff6dc' : '#fff0c4' }, tower);
        el('circle', { cx: X, cy: 484, r: lightLook === 'day' ? 22 : 42, fill: 'url(#tp-glow)', opacity: lightLook === 'day' ? 0.6 : 1 }, tower);
        el('path', { d: `M ${X - 14} 474 L ${X} 460 L ${X + 14} 474 Z`, fill: '#2a2e38' }, tower);
        el('rect', { x: X - 30, y: 598, width: 60, height: 10, fill: '#e3e5ea' }, tower);
        if (lightLook !== 'day') el('rect', { x: X - 16, y: 612, width: 36, height: 55, fill: '#fff1c8', opacity: 0.12, filter: 'url(#tp-soft)' }, tower);
        const lightWords = published
            ? 'shows the way to your ' + (published === 1 ? 'website' : published + ' websites')
            : 'no websites to show the way to yet';
        hoverable(tower, { name: 'Vaier', role: lightWords }, fleet.server.id);

        skerryPlaced.forEach((p) => {
            const g = el('g', { transform: `translate(${p.x.toFixed(1)} 604) scale(${sk.toFixed(3)})` }, lh);
            const b = p.house.stabbur ? stabbur(g, 6, p.house, null, lightLook) : rorbu(g, 6, 32, 26, p.house, false, null, lightLook);
            hoverable(b, p.house, p.house.id);
        });
        flagpole(lh, rightmost, 604, fleet.server.country, 'where Vaier is' + (fleet.server.place ? ', in ' + fleet.server.place : ''));

        // A sign on the rock: no pirates. A pictogram only, the picture carries no words.
        const SIGN = { day: ['#8a6a4a', '#5e4630', '#f4f1ea', '#c8352b', '#1c1a1a'], twilight: ['#5d4836', '#3f3024', '#cfc6b6', '#9e3a30', '#2a2522'],
                       night: ['#3a2f27', '#2a211b', '#8f8a82', '#7a3530', '#22201e'] }[lightLook];
        const [wood, frame, face, red, ink] = SIGN;
        const sign = el('g', { transform: `translate(${signX.toFixed(1)} 615)` }, lh);
        el('rect', { x: -1.5, y: -30, width: 3, height: 31, fill: wood }, sign);
        el('rect', { x: -12.5, y: -45, width: 25, height: 25, rx: 2.5, fill: frame, class: 'tp-ring' }, sign);
        el('circle', { cx: 0, cy: -32.5, r: 10.2, fill: face }, sign);
        // Skull and crossbones, struck through by the red ring's bar.
        const bones = el('g', { stroke: ink, 'stroke-width': 1.4, 'stroke-linecap': 'round' }, sign);
        el('path', { d: 'M -5.2 -26.6 L 5.2 -33.4 M 5.2 -26.6 L -5.2 -33.4' }, bones);
        [[-5.2, -26.6], [5.2, -26.6], [-5.2, -33.4], [5.2, -33.4]].forEach(([x, y]) => el('circle', { cx: x, cy: y, r: 1.1, fill: ink, stroke: 'none' }, bones));
        el('circle', { cx: 0, cy: -34.4, r: 3.6, fill: ink }, sign);
        el('rect', { x: -2.1, y: -32.2, width: 4.2, height: 2.6, rx: 0.6, fill: ink }, sign);
        el('circle', { cx: -1.35, cy: -34.4, r: 1, fill: face }, sign);
        el('circle', { cx: 1.35, cy: -34.4, r: 1, fill: face }, sign);
        el('circle', { cx: 0, cy: -32.5, r: 9, fill: 'none', stroke: red, 'stroke-width': 2.3 }, sign);
        el('line', { x1: -6.4, y1: -38.9, x2: 6.4, y2: -26.1, stroke: red, 'stroke-width': 2 }, sign);
        // After dark it catches a little of the lighthouse's light, never more than a lantern's.
        if (lightLook !== 'day') el('circle', { cx: 0, cy: -32.5, r: 10.2, fill: '#ffd99a', opacity: lightLook === 'night' ? 0.1 : 0.07 }, sign);
        hoverable(sign, { name: 'No pirates', role: 'anyone caught trying to break in is turned away here' }, null);

        const beamHit = el('path', { d: L.two ? `M ${X} 468 L ${X - 70} 280 L ${X + 70} 280 Z` : `M ${X + 20} 470 L 1600 400 L 1600 500 Z`,
            fill: 'transparent' });
        hoverable(beamHit, { name: 'The light', role: lightWords }, null);

        // The public, steering for the light: a coastal ship on the horizon, there only when something shines.
        // It plies the horizon a few minutes each way, far out beyond the lighthouse (which hides it as it
        // passes), turning its bow at each end; on the picture's clock, so a repaint carries on where it was.
        const ship = el('g', { transform: `translate(${still ? L.ship : 0} ${SHORE - 1})` });
        let hull = ship;
        if (published && !still) {
            const leg = 200, [from, to] = L.two ? [700, 960] : [1190, 1560];
            const mover = el('g', {}, ship);
            hull = el('g', {}, mover);
            el('animateMotion', { dur: 2 * leg + 's', repeatCount: 'indefinite', path: `M ${from} 0 L ${to} 0`,
                keyPoints: '0;1;0', keyTimes: '0;0.5;1', calcMode: 'linear', begin: '0s' }, mover);
            el('animateTransform', { attributeName: 'transform', type: 'scale', values: '1 1;-1 1', keyTimes: '0;0.5',
                calcMode: 'discrete', dur: 2 * leg + 's', repeatCount: 'indefinite', begin: '0s' }, hull);
        }
        if (published) {
            el('path', { d: 'M -33 0 L 38 -2 L 31 7 L -30 7 Z', fill: '#101218' }, hull);
            el('rect', { x: -24, y: -9, width: 44, height: 9, fill: '#eef0f2' }, hull);
            el('rect', { x: -14, y: -15, width: 26, height: 6, fill: '#eef0f2' }, hull);
            el('rect', { x: 2, y: -22, width: 6, height: 8, fill: '#b8352b' }, hull);
            const shipLit = lightLook !== 'day';
            for (let i = 0; i < 8; i++) el('rect', { x: -22 + i * 5.2, y: -6, width: 2.4, height: 2.4, fill: shipLit ? '#ffd88f' : '#3a4250' }, hull);
            if (shipLit) el('path', { d: 'M -34 8 L 34 8', stroke: '#ffd88f', opacity: 0.25, 'stroke-width': 3, filter: 'url(#tp-tiny)' }, hull);
            hoverable(ship, { name: 'Visitors', role: 'people on the internet, heading for your websites' }, null);
        }

        const seaHit = L.two
            ? el('rect', { x: s0, y: SHORE + 30, width: s1 - s0, height: H - SHORE - 30, fill: 'transparent' })
            : el('rect', { x: s0 + 40, y: SHORE + 40, width: s1 - s0 - 40, height: H - SHORE - 40, fill: 'transparent' });
        hoverable(seaHit, { name: 'The internet', role: 'everything crosses it to reach Vaier' }, null);

        // --- pirate ships: addresses the edge is keeping out ------------------------------------------------
        // A fresh ban sits close in, just turned away; as it runs down its ship drifts out toward the horizon.
        // Where it lies follows from the ban and the fixed lie of the land alone, clear of the lighthouse and of
        // the other pirates, so a boat coming or going never moves one; the boats keep clear of the pirates.
        const pirates = el('g', { 'data-layer': 'pirates' });
        const placed = [], pirateBoxes = [];
        fleet.bans.slice().sort((a, b) => a.drift - b.drift || a.ip.localeCompare(b.ip)).forEach((ban) => {
            const t = Math.min(1, ban.drift / 240);
            const hr = rng(hash(ban.ip));
            const y = SHORE + 60 + t * 250 + (hr() - 0.5) * 40;
            const k = 0.3 + t * 0.4;
            let x = 70 + hr() * (W - 140);
            for (let tries = 0; tries < 60; tries++) {
                const nearLight = Math.abs(x - X) < 260 && y < 760;
                if (!nearLight && placed.every((p) => Math.hypot(p.x - x, (p.y - y) * 2) > 150)) break;
                x = 70 + hr() * (W - 140);
            }
            placed.push({ x, y });
            pirateBoxes.push([x - 52 * k - 10, y - 82 * k - 10, x + 52 * k + 10, y + 14 * k + 10]);
            const away = x < X ? -1 : 1;   // the bow points away from the lighthouse
            const outer = el('g', { transform: `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${k.toFixed(3)})`,
                opacity: (0.55 + t * 0.45).toFixed(2), 'data-key': 'ban:' + ban.ip }, pirates);
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
            hoverable(outer, { name: someone(ban.country), role: triedWords(ban.scenario), state: () => {
                const left = Math.max(1, Math.round((ban.until - Date.now()) / 60000));
                const hrs = Math.round(left / 60);
                return 'kept away for ' + (left < 60 ? left + (left === 1 ? ' more minute' : ' more minutes')
                    : hrs + (hrs === 1 ? ' more hour' : ' more hours'));
            } }, null);
            outer.classList.add('is-open');
            outer.setAttribute('role', 'link');
            outer.addEventListener('click', () => { tip.hidden = true; if (opts.onBan) opts.onBan(); });
            outer.addEventListener('keydown', (e) => {
                if ((e.key === 'Enter' || e.key === ' ') && opts.onBan) { e.preventDefault(); tip.hidden = true; opts.onBan(); }
            });
        });

        // --- boats ------------------------------------------------------------------------------------------
        // A peer that is online sails the open sea, each in a cell of its own, the cell picked by the machine's
        // identity so a boat keeps its water when another joins. One that is offline lies moored at a jetty off
        // the coast where it was last seen (see mooringOf): sail furled, lantern out, no wake.
        const boats = fleet.boats.slice().sort(byName);
        const sailing = boats.filter((b) => !b.dark);
        const moored = boats.filter((b) => b.dark);
        const quays = [];   // each jetty with its boats, as a box
        const piers = [];   // each jetty's pier back to the shore, as a box
        const skerryBox = [skL - 10, 540, skR + 10, 704];
        const overlaps = (p, q) => p[0] < q[2] && p[2] > q[0] && p[1] < q[3] && p[3] > q[1];
        const keepOut = [skerryBox];
        // An online boat's wake: a curve from its stern in to the lighthouse.
        function sailWake(at) {
            const left = at.x < X;
            const x1 = at.x + (left ? 34 : -34) * at.s, y1 = at.y + 4 * at.s, x2 = X + (left ? -40 : 40), y2 = LIGHT.y + 44;
            const q = [(x1 + x2) / 2, Math.max(y1, y2) + 40];
            return [[[x1, y1], [x1 + (q[0] - x1) * 2 / 3, y1 + (q[1] - y1) * 2 / 3], [x2 + (q[0] - x2) * 2 / 3, y2 + (q[1] - y2) * 2 / 3], [x2, y2]]];
        }

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
                    if (keepOut.some(([a, b, c, d]) => x < c && x + cw > a && y < d && y + ch > b)) continue;
                    const slot = { x: x + cw / 2, y: y + ch * 0.45, cw, s, box: [x, y, x + cw, y + ch] };
                    // A cell under a pirate is taken for now; nothing else ever moves an online boat.
                    slot.busy = pirateBoxes.some((q) => overlaps(slot.box, q));
                    slots.push(slot);
                }
            }
            // Sized for the whole fleet, online or not, so a boat coming or going never reshapes the grid.
            if (slots.length >= boats.length * 1.5 + 2) break;
        }
        // First come, first served: a boat already at sea keeps its cell; a newcomer takes the cell its identity
        // picks, or the next free one, so it never pushes aside a boat that was there first.
        const taken = new Set(), cellOf = new Map();
        const spare = slots.filter((c) => !c.busy).length >= sailing.length;
        const index = new Map(slots.map((c, i) => [`${c.x.toFixed(1)} ${c.y.toFixed(1)} ${c.s}`, i]));
        const free = (i) => !taken.has(i) && !(spare && slots[i].busy);
        sailing.forEach((b) => {
            const i = index.get(seaCells.get(b.id));
            if (i != null && free(i)) { taken.add(i); cellOf.set(b.id, i); }
        });
        sailing.forEach((b) => {
            if (cellOf.has(b.id)) return;
            let i = hash(b.id) % Math.max(1, slots.length);
            for (let n = 0; n < slots.length && !free(i); n++) i = (i + 1) % slots.length;
            taken.add(i);
            cellOf.set(b.id, i);
        });
        seaCells = new Map();
        sailing.forEach((b) => {
            const c = slots[cellOf.get(b.id)];
            b.at = Object.assign({ s: bs }, c);
            seaCells.set(b.id, `${c.x.toFixed(1)} ${c.y.toFixed(1)} ${c.s}`);
        });

        // Where the online boats and their wakes lie, for the jetties to keep clear of.
        const sailTrack = [].concat(...sailing.map((b) => track(sailWake(b.at))));
        const sailBoxes = sailing.map(({ at }) => [at.x - 44 * at.s, at.y - 66 * at.s, at.x + 44 * at.s, at.y + 14 * at.s]);

        if (moored.length) {
            const groups = new Map();
            moored.forEach((b) => {
                const m = mooringOf(b, harbours, L.coasts.map((c) => c.band));
                const key = m.harbour ? harbours.indexOf(m.harbour) : m.band;
                if (!groups.has(key)) groups.set(key, { m, boats: [] });
                groups.get(key).boats.push(b);
            });
            // The houses' reflections and window light, the busiest water under a village.
            const busy = harbours.filter((h) => h.row === 0).map((h) => [h.left - 6, SHORE, h.right + 6, SHORE + 44]);
            groups.forEach(({ m, boats: group }, key) => {
                const quay = el('g', { 'data-key': 'quay:' + key }, town);
                const [from, to] = L.two ? (m.band === 'nordic' ? [20, 620] : [1040, W - 20]) : [20, W - 20];
                const s = Math.max(0.7, Math.min(0.9, (to - from - 20) / (group.length * 100)));
                const len = group.length * 100 * s + 20;
                const want = m.harbour ? (m.harbour.left + m.harbour.right) / 2 : (from + to) / 2;
                // The clearest stretch of water nearest the place, as close in to the shore as it can lie.
                let best = null;
                for (const dy of [40, 58, 76, 94, 112, 130]) {
                    for (let x = from; x + len <= to; x += 8) {
                        const deck = SHORE + dy;
                        const box = [x - 4, deck - 50 * s, x + len + 4, deck + 26 * s];
                        // The pier out from the shore leaves from the jetty's end nearest the village.
                        const gx = x + len / 2 < want ? x + len - 10 : x + 10;
                        const clash = [skerryBox].concat(busy, quays, pirateBoxes, sailBoxes).filter((q) => overlaps(box, q)).length
                            + (crosses(wakeTrack, box) || crosses(sailTrack, box) ? 1 : 0)
                            + (crosses(wakeTrack, [gx - 4, SHORE, gx + 4, deck]) || crosses(sailTrack, [gx - 4, SHORE, gx + 4, deck]) ? 1 : 0);
                        const score = clash * 10000 + Math.abs(x + len / 2 - want) + dy * 3;
                        if (!best || score < best.score) best = { score, x, deck, box, gx };
                    }
                }
                const { x: a, deck, gx } = best;
                // Drawn in its own coordinates, deck at y = 0, so a repaint can glide it to a new berth.
                quay.setAttribute('transform', `translate(${a.toFixed(1)} ${deck})`);
                const px = gx - a, up = SHORE - deck;
                piers.push([gx - 8, SHORE, gx + 8, deck]);
                el('path', { d: `M ${px - 2.5} ${up + 1} L ${px + 2.5} ${up + 1} L ${px + 6} 2 L ${px - 6} 2 Z`, fill: '#5a4a3e' }, quay);
                for (let y = up + 14; y < -4; y += 14) {
                    const w = 2.5 + 3.5 * (y - up) / -up;
                    el('rect', { x: (px - w).toFixed(1), y: y.toFixed(1), width: 1.4, height: 5, fill: '#2a2320' }, quay);
                    el('rect', { x: (px + w - 1.4).toFixed(1), y: y.toFixed(1), width: 1.4, height: 5, fill: '#2a2320' }, quay);
                }
                group.forEach((b, i) => { b.at = { x: a + 10 + (50 + i * 100) * s, y: deck + 13 * s, s, deck }; });
                for (let x = 6; x < len; x += 22) el('rect', { x: x.toFixed(1), y: 3, width: 3, height: (18 * s).toFixed(1), fill: '#2a2320' }, quay);
                el('rect', { x: 0, y: 0, width: len.toFixed(1), height: 4, fill: '#4a3c33' }, quay);
                el('rect', { x: 0, y: (22 * s).toFixed(1), width: len.toFixed(1), height: 3, fill: '#4a3c33', opacity: 0.15, filter: 'url(#tp-tiny)' }, quay);
                quays.push(best.box);
            });
        }

        // A boat in profile, bow to the right, its waterline at y = 0: a sheer rising to stem and stern, the
        // side below a pale top strake, plank lines, and its shadow and reflection on the water.
        const HULLS = {
            faering: { l: 36, stern: 17, bow: 18, free: 4, side: '#8a5634', strake: '#e0b884', plank: '#5e3a22' },
            sjark: { l: 38, stern: 11, bow: 17, free: 8, side: '#ebe6da', strake: '#22415e', plank: '#c9c2b3', boot: '#8a3a2a' },
            sail: { l: 38, stern: 9, bow: 14, free: 6, side: '#8a2e26', strake: '#f1ece2', plank: '#64201b' },
        };
        const mix = (a, b, t) => '#' + [1, 3, 5].map((k) => Math.round(parseInt(a.slice(k, k + 2), 16) * (1 - t)
            + parseInt(b.slice(k, k + 2), 16) * t).toString(16).padStart(2, '0')).join('');
        let hullN = 0;
        function boatHull(g, H, c) {
            const { l, stern, bow, free } = H;
            const sheer = `M ${-l} ${-stern} Q ${-l * 0.5} ${-free}, 0 ${-free} Q ${l * 0.5} ${-free}, ${l} ${-bow}`;
            const d = sheer + ` Q ${l - 5} 2, ${l - 15} 4 L ${-l + 13} 4 Q ${-l + 4} 2, ${-l} ${-stern} Z`;
            // Shadow and broken reflection, so it lies in the water.
            el('path', { d, fill: c.side, opacity: 0.28, transform: 'translate(0 8) scale(1 -0.45)', filter: 'url(#tp-tiny)' }, g);
            el('ellipse', { cx: 0, cy: 4.5, rx: l * 0.85, ry: 2.6, fill: '#06101c', opacity: 0.35 }, g);
            el('path', { d, fill: c.side, class: 'tp-ring' }, g);
            const id = 'tp-hull' + (hullN++);
            el('path', { d }, el('clipPath', { id }, defs));
            const planks = el('g', { 'clip-path': `url(#${id})`, fill: 'none' }, g);
            if (c.boot) el('rect', { x: -l, y: 1, width: 2 * l, height: 4, fill: c.boot }, planks);
            [3.5, 7].forEach((dy) => el('path', { d: sheer, transform: `translate(0 ${dy})`, stroke: c.plank, 'stroke-width': 0.9, opacity: 0.7 }, planks));
            el('path', { d: sheer, fill: 'none', stroke: c.strake, 'stroke-width': 2.4, 'stroke-linecap': 'round' }, g);
            // The stem and stern posts stand a little proud of the sheer.
            el('path', { d: `M ${l - 1} ${-bow + 1} L ${l + 2} ${-bow - 3} M ${-l + 1} ${-stern + 1} L ${-l - 2} ${-stern - 3}`,
                stroke: c.plank, 'stroke-width': 1.6, 'stroke-linecap': 'round' }, g);
            if (c.edge) el('path', { d: sheer, fill: 'none', stroke: c.edge, 'stroke-width': 0.9, opacity: 0.8, transform: 'translate(0 -1.2)' }, g);
        }
        function oar(g, x1, y1, x2, y2, colour, w, o) {
            el('line', { x1, y1, x2, y2, stroke: colour, 'stroke-width': w, opacity: o, 'stroke-linecap': 'round' }, g);
            const a = Math.atan2(y2 - y1, x2 - x1) * 180 / Math.PI;
            el('ellipse', { cx: 0, cy: 0, rx: 5, ry: 1.8, fill: colour, opacity: o, transform: `translate(${x2} ${y2}) rotate(${a.toFixed(1)})` }, g);
        }
        function boat(b) {
            const { x, y, s } = b.at;
            const g = el('g', { transform: `translate(${x.toFixed(1)} ${y.toFixed(1)}) scale(${s.toFixed(3)})`, 'data-key': 'boat:' + b.id, 'data-state': b.dark ? 'moored' : 'sea' });
            const look = lookOf(bandAt(x));
            const H = HULLS[b.kind] || HULLS.faering;
            const wood = '#c9a06a', rig = '#3a2c24';
            if (b.dark) {
                // At rest: tied alongside the jetty, sail furled, oars shipped, lantern out, no wake. After dark
                // the moon (or the dusk) catches its gunwale; by day it is just a boat tied up.
                const cool = look === 'day' ? 0.25 : 0.45, to = look === 'day' ? '#8e99a3' : '#9a9ca4';
                boatHull(g, H, { side: mix(H.side, to, cool), strake: mix(H.strake, to, cool * 0.6), plank: mix(H.plank, to, cool),
                    boot: H.boot && mix(H.boot, to, cool), edge: look === 'night' ? '#e2ded5' : look === 'twilight' ? '#f2d9bd' : null });
                const up = (b.at.deck - y) / s;   // the jetty's edge, in this boat's own units
                el('path', { d: `M ${H.l - 1} ${-H.bow} Q ${H.l + 8} ${(up - H.bow) / 2}, ${H.l + 12} ${up + 2} M ${-H.l + 1} ${-H.stern} Q ${-H.l - 8} ${(up - H.stern) / 2}, ${-H.l - 12} ${up + 2}`,
                    fill: 'none', stroke: '#b9a88c', 'stroke-width': 1.2 }, g);
                if (b.kind === 'sail') {
                    el('line', { x1: -2, y1: -H.free, x2: -2, y2: -58, stroke: rig, 'stroke-width': 2 }, g);
                    el('line', { x1: -2, y1: -16, x2: 30, y2: -13, stroke: rig, 'stroke-width': 2 }, g);
                    el('path', { d: 'M -1 -18 Q 14 -23 30 -16 Q 30 -12 27 -13 Q 14 -15 -1 -14 Z', fill: mix('#e8e2d4', to, cool * 0.5) }, g);
                } else if (b.kind === 'sjark') {
                    el('rect', { x: 6, y: -27, width: 20, height: 19, fill: mix('#e6e1d6', to, cool * 0.6) }, g);
                    el('rect', { x: 4, y: -30, width: 24, height: 4, fill: mix('#8a3a2a', to, cool) }, g);
                    el('rect', { x: 10, y: -23, width: 6, height: 5, fill: '#2b2f38' }, g);
                    el('line', { x1: -16, y1: -H.free, x2: -16, y2: -44, stroke: rig, 'stroke-width': 2 }, g);
                } else {
                    oar(g, -24, -7, 24, -9, mix(wood, to, cool), 1.8, 1);
                    oar(g, -22, -9, 26, -11, mix(wood, to, cool), 1.6, 0.85);
                }
                hoverable(g, b, b.id);
                return;
            }
            boatHull(g, H, H);
            let lamp = [-14, -12];
            if (b.kind === 'sail') {
                el('line', { x1: 0, y1: -H.free, x2: 0, y2: -64, stroke: rig, 'stroke-width': 2 }, g);
                el('path', { d: 'M 2 -62 L 2 -10 L 34 -10 Z', fill: '#f1ece2' }, g);
                el('path', { d: 'M -2 -54 L -2 -11 L -26 -11 Z', fill: '#d9d1c2' }, g);
                lamp = [-12, -12];
            } else if (b.kind === 'sjark') {
                // A small fishing boat: a wheelhouse aft and a short mast.
                el('rect', { x: 6, y: -27, width: 20, height: 19, fill: '#e6e1d6' }, g);
                el('rect', { x: 4, y: -30, width: 24, height: 4, fill: '#8a3a2a' }, g);
                el('rect', { x: 10, y: -23, width: 6, height: 5, fill: '#2b2f38' }, g);
                el('line', { x1: -16, y1: -H.free, x2: -16, y2: -44, stroke: rig, 'stroke-width': 2 }, g);
                lamp = [16, -33];
            } else {
                // A rower at the oars, blades in the water.
                const day = look === 'day';
                oar(g, 6, -10, 38, 7, wood, 1.4, 0.7);
                el('path', { d: 'M -1 -6 L 1 -17 L 7 -17 L 6 -6 Z', fill: day ? '#b5442f' : '#2b2420' }, g);
                el('circle', { cx: 4, cy: -21, r: 3.4, fill: day ? '#e0b48a' : '#2b2420' }, g);
                if (day) el('path', { d: 'M 0.6 -22 A 3.4 3.4 0 0 1 7.4 -22 Z', fill: '#2f4a6a' }, g);
                oar(g, 3, -10, -36, 8, wood, 1.9, 1);
                const splash = { fill: 'none', stroke: day ? '#ffffff' : '#cfdcf2', 'stroke-width': 1, opacity: day ? 0.85 : 0.6, 'stroke-linecap': 'round' };
                el('path', Object.assign({ d: 'M -44 9 q 3 -4 6 -1 M -36 11 q 3 -3 7 0 M 34 8 q 3 -3 6 0' }, splash), g);
                lamp = [-H.l + 2, -H.stern - 6];
            }
            if (look !== 'day') {
                el('circle', { cx: lamp[0], cy: lamp[1], r: 3, fill: '#ffd88f' }, g);
                el('circle', { cx: lamp[0], cy: lamp[1], r: 14, fill: 'url(#tp-glow)', opacity: 0.8 }, g);
            }
            el('path', { d: 'M -30 9 C -16 18, 16 18, 32 7', fill: 'none', stroke: '#e9e2d3', opacity: 0.15, 'stroke-width': 3 }, g);
            hoverable(g, b, b.id);
        }

        // --- assemble ----------------------------------------------------------------------------------------
        svg.appendChild(wakes);
        sailing.forEach((b, i) => wake(sailWake(b.at), false, 12 + (i % 4) * 3, hash(b.id), b.at.x, b.id));
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
        el('stop', { offset: 1, 'stop-color': '#000', 'stop-opacity': lightLook === 'day' ? 0.22 : 0.5 }, vg);
        el('rect', { x: 0, y: 0, width: W, height: H, fill: 'url(#tp-vig)', 'pointer-events': 'none' });

        // A boat moving between two places goes round what it cannot cross: the shore, the lighthouse's rock (deep
        // enough that its mast clears it), other jetties and their piers. A clear straight line stays straight;
        // otherwise it goes down into open water, along beneath the obstacle, and back up.
        const rockBox = [skL - 40, SHORE, skR + 40, 718];
        function route(a, b) {
            const inside = (p, q) => p.x > q[0] && p.x < q[2] && p.y > q[1] && p.y < q[3];
            const walls = [rockBox].concat(quays, piers).filter((q) => !inside(a, q) && !inside(b, q));
            const blocked = (p, q) => {
                const n = Math.ceil(Math.hypot(q.x - p.x, q.y - p.y) / 6);
                for (let i = 0; i <= n; i++) {
                    const c = { x: p.x + (q.x - p.x) * i / n, y: p.y + (q.y - p.y) * i / n };
                    if (c.y < SHORE + 8 || walls.some((w) => inside(c, w))) return true;
                }
                return false;
            };
            if (!blocked(a, b)) return [a, b];
            const floor = Math.max(a.y, b.y, ...walls.map((w) => w[3])) + 20;
            for (let low = Math.min(floor, H - 12); low <= H - 12; low += 20) {
                const pts = [a, { x: a.x, y: low }, { x: b.x, y: low }, b];
                if (pts.slice(1).every((p, i) => !blocked(pts[i], p))) {
                    let r = pts;
                    for (let k = 0; k < 2; k++) {
                        r = [r[0]].concat(...r.slice(0, -1).map((p, i) => {
                            const q = r[i + 1];
                            return [{ x: p.x * 0.75 + q.x * 0.25, y: p.y * 0.75 + q.y * 0.25 }, { x: p.x * 0.25 + q.x * 0.75, y: p.y * 0.25 + q.y * 0.75 }];
                        }), [r[r.length - 1]]);
                    }
                    return r;
                }
            }
            return [a, b];
        }

        // The scenery is complete now the villages have added their smears: paint it once, under everything. A
        // repaint that leaves it unchanged (a boat coming or going) keeps the picture already on screen.
        const sceneText = new XMLSerializer().serializeToString(scene);
        const reuse = sceneNow && sceneNow.text === sceneText;
        const sceneUrl = reuse ? sceneNow.url : URL.createObjectURL(new Blob([sceneText], { type: 'image/svg+xml' }));
        const waits = reuse ? [] : [decoded(sceneUrl)];
        svg.insertBefore(el('image', { href: sceneUrl, x: 0, y: 0, width: W, height: H, 'pointer-events': 'none', 'data-scene': '',
            // Its own layer, so a crossfade's end never makes the browser paint the heavy scenery again.
            style: 'will-change: opacity' }), defs.nextSibling);
        // Film grain over everything, where there is room and motion to carry it. It never changes.
        if (!narrow && !still) {
            if (!grainUrl) {
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
                grainUrl = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(grain)], { type: 'image/svg+xml' }));
                waits.push(decoded(grainUrl));
            }
            el('image', { href: grainUrl, x: 0, y: 0, width: W, height: H, opacity: 0.035,
                'pointer-events': 'none', style: 'mix-blend-mode: overlay', 'data-grain': '' });
        }
        Promise.all(waits).then(() => {
            if (mine !== drawing) { if (!reuse) URL.revokeObjectURL(sceneUrl); return; }
            const before = still ? null : where(live);
            const kids = Array.from(svg.childNodes);
            const newScene = kids.find((n) => n.hasAttribute('data-scene')), newGrain = kids.find((n) => n.hasAttribute('data-grain'));
            const oldScene = live.querySelector('image[data-scene]'), oldGrain = live.querySelector('image[data-grain]');
            // Images that stay are left where they are: moving one makes the browser paint it all over again.
            const keepScene = reuse && oldScene && oldScene.getAttribute('href') === sceneUrl ? oldScene : null;
            const under = !still && !keepScene && oldScene ? oldScene : null;
            const keepGrain = newGrain && oldGrain ? oldGrain : null;
            Array.from(live.childNodes).forEach((n) => { if (n !== keepScene && n !== under && n !== keepGrain) n.remove(); });
            let cursor = null;
            const put = (n) => { if (cursor) cursor.after(n); else live.prepend(n); cursor = n; };
            kids.forEach((n) => {
                if (n === newScene && keepScene) { cursor = keepScene; return; }
                if (n === newGrain && keepGrain) { cursor = keepGrain; return; }
                if (n === newScene && under) cursor = under;
                put(n);
            });
            // An animation keeps the clock of the picture it was made in, and the buffer's never runs.
            live.querySelectorAll('animateMotion, animateTransform').forEach((a) => a.replaceWith(a.cloneNode(true)));
            resync(live);
            const was = sceneNow;
            sceneNow = { text: sceneText, url: sceneUrl };
            const release = () => { if (was && was.url !== sceneUrl) URL.revokeObjectURL(was.url); };
            // A first picture has nothing to change from.
            if (before && before.size) glide(live, before, route);
            reopenTip(live, tip);
            if (under) {
                // The old scenery stays under the new while the new fades in, then goes.
                under.removeAttribute('data-scene');
                const a = newScene.animate([{ opacity: 0 }, { opacity: 1 }], { duration: FADE, easing: 'ease-in-out' });
                const done = () => { under.remove(); release(); };
                a.finished.then(done, done);
            } else {
                release();
            }
        });
    }

    // A repaint swaps the elements under the cursor; an open tooltip follows whatever is there now.
    function reopenTip(live, tip) {
        if (tip.hidden || !pointer) return;
        const under = document.elementFromPoint(pointer.x, pointer.y);
        const hit = under && live.contains(under) ? under.closest('.tp-hit') : null;
        if (hit) hit.dispatchEvent(new MouseEvent('mousemove', { clientX: pointer.x, clientY: pointer.y, bubbles: true }));
        else tip.hidden = true;
    }

    // Resolves once the browser holds the picture decoded, so the swap shows it at once.
    function decoded(url) {
        const img = new Image();
        img.src = url;
        return img.decode().catch(() => {});
    }

    // --- the change from one picture to the next ------------------------------------------------------------
    // Everything a repaint keeps (a boat, a pirate, a wake) carries a data-key. Before the swap the picture notes
    // where each one is, mid-move included; after it, each glides from there to its new place, what is new fades
    // in, and what is gone fades out over the new picture.
    const GLIDE = 2200, FADE = 800;
    function cssTransform(attr) {
        if (!attr) return 'none';
        const t = attr.match(/translate\(([-\d.]+)[ ,]+([-\d.]+)\)/), s = attr.match(/scale\(([-\d.]+)\)/);
        return (t ? `translate(${t[1]}px, ${t[2]}px)` : '') + (s ? ` scale(${s[1]})` : '') || 'none';
    }
    // A glide as keyframes along the route, its speed steady: longer detours take a little longer.
    function place(t) {
        const m = t.match(/matrix\(([^)]+)\)/);
        if (m) { const v = m[1].split(',').map(Number); return { x: v[4], y: v[5], s: v[0] }; }
        const tt = t.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/), ss = t.match(/scale\(([-\d.]+)\)/);
        return { x: tt ? +tt[1] : 0, y: tt ? +tt[2] : 0, s: ss ? +ss[1] : 1 };
    }
    function voyage(from, to, route) {
        const a = place(from), b = place(to), pts = route(a, b);
        const cum = [0];
        for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y));
        const L = cum[cum.length - 1] || 1, straight = Math.hypot(b.x - a.x, b.y - a.y) || 1;
        const frames = pts.map((p, i) => ({ offset: cum[i] / L,
            transform: `translate(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px) scale(${(a.s + (b.s - a.s) * cum[i] / L).toFixed(3)})` }));
        frames[0].transform = from;
        frames[frames.length - 1].transform = to;
        return { frames, duration: Math.min(3000, Math.max(GLIDE, GLIDE * L / straight)) };
    }
    function where(live) {
        const at = new Map();
        live.querySelectorAll('[data-key]').forEach((n) => {
            const moving = n.getAnimations && n.getAnimations().some((a) => a.playState === 'running');
            at.set(n.getAttribute('data-key'), { n, state: n.getAttribute('data-state'), unseen: getComputedStyle(n).opacity === '0',
                t: moving ? getComputedStyle(n).transform : cssTransform(n.getAttribute('transform')) });
        });
        return at;
    }
    // A boat that goes offline sails in as it was, under way, and only at the jetty turns into a boat tied up;
    // one that comes online makes ready at the jetty first and then sails out. Whichever drawing is travelling
    // carries the key, so a repaint mid-trip turns the boat round from where it is.
    const TURN = 600;
    function glide(live, before, route) {
        const now = Array.from(live.querySelectorAll('[data-key]')).map((n) => [n, n.getAttribute('data-key')]);
        const casting = new Set();
        now.forEach(([n, key]) => {
            const was = before.get(key), state = n.getAttribute('data-state');
            if (key.startsWith('boat:') && was && was.state && state && was.state !== state && state === 'sea') casting.add(key.slice(5));
            // A wake still waiting for its boat when this repaint came keeps waiting for the boat's new trip.
            const id = /^(wake|lamp):/.test(key) && key.replace(/^(wake|lamp):/, '').replace(/:dark$/, '');
            if (id && was && was.unseen) casting.add(id);
        });
        const quit = (n) => { n.removeAttribute('data-key'); n.setAttribute('pointer-events', 'none'); n.getAnimations().forEach((a) => a.cancel()); };
        // A boat casting off draws its tunnel only once it is out at sea: its wake and lantern wait, unseen, for
        // its trip to end. A repaint meanwhile replaces them, so they are never left waiting.
        const waiting = new Map(), underway = new Set();
        const reveal = (n) => n.animate([{ opacity: 0 }, { opacity: n.getAttribute('opacity') || 1 }], { duration: FADE, easing: 'ease-out' });
        now.forEach(([n, key]) => {
            const was = before.get(key);
            before.delete(key);
            const tid = /^(wake|lamp):/.test(key) && key.replace(/^(wake|lamp):/, '').replace(/:dark$/, '');
            if (tid && casting.has(tid) && (!was || was.unseen)) {
                const hold = n.animate([{ opacity: 0 }, { opacity: 0 }], { duration: 1, fill: 'forwards' });
                if (!waiting.has(tid)) waiting.set(tid, []);
                waiting.get(tid).push(() => { hold.cancel(); reveal(n); });
                return;
            }
            if (!was) {
                reveal(n);
                return;
            }
            const to = cssTransform(n.getAttribute('transform')), state = n.getAttribute('data-state');
            if (key.startsWith('boat:') && was.state && state && was.state !== state && was.n !== n) {
                const old = was.n, v = voyage(was.t, to, route), total = v.duration + TURN;
                quit(old);
                if (state === 'moored') {
                    // Sails in as it was, then ties up.
                    n.after(old);
                    old.setAttribute('data-key', key);
                    n.setAttribute('data-key', key + ':hidden');
                    old.animate(v.frames, { duration: v.duration, easing: 'ease-in-out', fill: 'forwards' });
                    old.animate([{ opacity: 1 }, { opacity: 0 }], { duration: TURN, delay: v.duration, fill: 'forwards' })
                        .finished.then(() => old.remove(), () => old.remove());
                    const a = n.animate([{ opacity: 0 }, { opacity: 0, offset: v.duration / total }, { opacity: 1 }], { duration: total });
                    a.finished.then(() => n.setAttribute('data-key', key), () => {});
                } else {
                    // Makes ready at the jetty, then sails out.
                    n.before(old);
                    old.animate([{ transform: was.t, opacity: 1 }, { transform: was.t, opacity: 0 }], { duration: TURN, fill: 'forwards' })
                        .finished.then(() => old.remove(), () => old.remove());
                    n.animate([{ opacity: 0 }, { opacity: 1 }], { duration: TURN, fill: 'backwards' });
                    const trip = n.animate(v.frames, { duration: v.duration, delay: TURN, easing: 'ease-in-out', fill: 'backwards' });
                    const id = key.slice(5);
                    trip.finished.then(() => (waiting.get(id) || []).forEach((go) => go()), () => {});
                    underway.add(id);
                }
                return;
            }
            if (to !== 'none' && was.t !== to) {
                // Only a boat goes round things; a jetty simply slides to its new berth.
                const v = voyage(was.t, to, key.startsWith('boat:') ? route : (p, q) => [p, q]);
                const trip = n.animate(v.frames, { duration: v.duration, easing: 'ease-in-out' });
                const id = key.startsWith('boat:') && key.slice(5);
                if (id && casting.has(id)) {
                    underway.add(id);
                    trip.finished.then(() => (waiting.get(id) || []).forEach((go) => go()), () => {});
                }
            }
        });
        // Should no trip have started after all, nothing is left unseen.
        waiting.forEach((goes, id) => { if (!underway.has(id)) goes.forEach((go) => go()); });
        // What is gone leaves over the new picture, in its own layer; a lantern simply goes out.
        before.forEach(({ n, unseen }, key) => {
            if (unseen || key.startsWith('lamp:') || key.endsWith(':hidden')) return;
            const layer = n.parentNode && n.parentNode.getAttribute && n.parentNode.getAttribute('data-layer');
            const host = (layer && live.querySelector(`[data-layer="${layer}"]`)) || live;
            quit(n);
            host.appendChild(n);
            const a = n.animate([{ opacity: n.getAttribute('opacity') || 1 }, { opacity: 0 }], { duration: FADE, easing: 'ease-in', fill: 'forwards' });
            a.finished.then(() => n.remove(), () => n.remove());
        });
    }

    let pointer = null;   // where the open tooltip was last placed, so a repaint can re-open it there
    let drawing = 0;   // the latest repaint; an older one still decoding is dropped
    let seaCells = new Map();   // each online boat's cell at the last repaint, so it keeps it at the next
    let sceneNow = null;   // the scenery on screen, as text and as the picture made of it
    let grainUrl = null;   // the film grain, the same for every repaint

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

    window.VaierTopology = { draw, resync, skyAt, skyOver };
})();
