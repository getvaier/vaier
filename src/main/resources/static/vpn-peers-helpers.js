// Shared helpers for the Machines page (#273 slice 2): pure formatting, status/icon,
// device-category and HTML/JS-escaping utilities. Loaded as a classic script before
// vpn-peers.js so these stay globally available to it and to inline on* handlers.

// Turn an arbitrary key (peer name/id, LAN-server name, or a published service's
// dnsAddress+pathPrefix) into a DOM-id-safe token. Each non-alphanumeric char is encoded as
// `_<charCode>_` rather than collapsed to a single `_`, so distinct keys can never map to the
// same id — published services full of dots and slashes (e.g. "a.example.com/api" vs
// "a.example.com/a/pi") stay distinct, keeping expand/collapse and field targeting correct.
function cardId(peerName) {
    return String(peerName).replace(/[^a-zA-Z0-9]/g, c => '_' + c.charCodeAt(0) + '_');
}

function formatBytes(bytes) {
    const b = parseInt(bytes);
    if (isNaN(b) || b === 0) return '0 B';
    const k = 1024, sizes = ['B','KB','MB','GB','TB'];
    const i = Math.floor(Math.log(b) / Math.log(k));
    return Math.round((b / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
}

function lastSeenAbsolute(handshakeEpoch) {
    const h = parseInt(handshakeEpoch);
    if (isNaN(h) || h <= 0) return 'never';
    return new Date(h * 1000).toLocaleString();
}

// Compact "Xs/m/h/d ago" for a handshake/probe epoch; null when never seen (#270).
function relativeTime(handshakeEpoch) {
    const t = parseInt(handshakeEpoch);
    if (isNaN(t) || t <= 0) return null;
    const secs = Math.max(0, Math.floor(Date.now() / 1000) - t);
    if (secs < 60)  return secs + 's ago';
    const mins = Math.floor(secs / 60);
    if (mins < 60)  return mins + 'm ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    return Math.floor(hours / 24) + 'd ago';
}

// Plain-language machine state + the evidence behind it, for the icon's hover tooltip
// (#270). Mirrors the four-state machine-icon colour defined in UBIQUITOUS_LANGUAGE.md.
function statusTooltip(statusClass, handshakeEpoch) {
    const ago = relativeTime(handshakeEpoch);
    switch (statusClass) {
        case 'status-connected':
            return ago ? `Green — connected, last handshake ${ago}` : 'Green — reachable';
        case 'status-degraded':
            return ago ? `Amber — reachable but Docker scrape failed (last seen ${ago})`
                       : 'Amber — reachable but Docker scrape failed';
        case 'status-disconnected':
            return ago ? `Red — unreachable, last handshake ${ago}`
                       : 'Red — unreachable / no handshake';
        default:
            return 'Grey — not yet probed';
    }
}

// Inline SVG icons keep the cards self-contained and recolour via currentColor.
function machineIconSvg(kind) {
    const A = 'width="24" height="24" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"';
    switch (kind) {
        // 'mobile' kept as the phone glyph; 'phone' is an alias so deviceCategoryIconKind
        // can name the category directly without the renderer caring which it gets.
        case 'mobile':
        case 'phone':   return `<svg ${A}><rect x="4.5" y="1.5" width="7" height="13" rx="1.5"/><line x1="6.5" y1="12.5" x2="9.5" y2="12.5"/></svg>`;
        case 'laptop':  return `<svg ${A}><rect x="2" y="3" width="12" height="8" rx="1"/><line x1="1" y1="13.5" x2="15" y2="13.5"/></svg>`;
        case 'desktop': return `<svg ${A}><rect x="2" y="2" width="12" height="8.5" rx="1"/><line x1="6" y1="13.5" x2="10" y2="13.5"/><line x1="8" y1="10.5" x2="8" y2="13.5"/></svg>`;
        case 'server':  return `<svg ${A}><rect x="2.5" y="2.5" width="11" height="4" rx="0.8"/><rect x="2.5" y="9" width="11" height="4" rx="0.8"/><circle cx="4.6" cy="4.5" r="0.55" fill="currentColor" stroke="none"/><circle cx="4.6" cy="11" r="0.55" fill="currentColor" stroke="none"/><line x1="9.5" y1="4.5" x2="11.8" y2="4.5"/><line x1="9.5" y1="11" x2="11.8" y2="11"/></svg>`;
        case 'nas':     return `<svg ${A}><rect x="4" y="1.5" width="8" height="13" rx="1"/><line x1="6.2" y1="3.5" x2="6.2" y2="10.5"/><line x1="8" y1="3.5" x2="8" y2="10.5"/><line x1="9.8" y1="3.5" x2="9.8" y2="10.5"/><circle cx="8" cy="12.5" r="0.55" fill="currentColor" stroke="none"/></svg>`;
        case 'printer': return `<svg ${A}><polyline points="4.5 5.5 4.5 2.5 11.5 2.5 11.5 5.5"/><rect x="2.5" y="5.5" width="11" height="5" rx="0.8"/><rect x="4.5" y="9.5" width="7" height="4"/><circle cx="11.5" cy="7.5" r="0.5" fill="currentColor" stroke="none"/></svg>`;
        case 'router':  return `<svg ${A}><rect x="2.5" y="8.5" width="11" height="5" rx="0.8"/><circle cx="5" cy="11" r="0.5" fill="currentColor" stroke="none"/><line x1="11" y1="11" x2="12" y2="11"/><line x1="5.5" y1="8.5" x2="4" y2="3.5"/><line x1="10.5" y1="8.5" x2="12" y2="3.5"/></svg>`;
        case 'gateway': return `<svg ${A}><rect x="3" y="3" width="10" height="10" rx="1"/><polyline points="6 7.5 6 5 7.8 6.5"/><polyline points="10 8.5 10 11 8.2 9.5"/></svg>`;
        case 'iot':     return `<svg ${A}><rect x="4.5" y="4.5" width="7" height="7" rx="0.8"/><line x1="6.5" y1="4.5" x2="6.5" y2="2.5"/><line x1="9.5" y1="4.5" x2="9.5" y2="2.5"/><line x1="6.5" y1="13.5" x2="6.5" y2="11.5"/><line x1="9.5" y1="13.5" x2="9.5" y2="11.5"/><line x1="4.5" y1="6.5" x2="2.5" y2="6.5"/><line x1="4.5" y1="9.5" x2="2.5" y2="9.5"/><line x1="13.5" y1="6.5" x2="11.5" y2="6.5"/><line x1="13.5" y1="9.5" x2="11.5" y2="9.5"/></svg>`;
        case 'camera':  return `<svg ${A}><rect x="2" y="4" width="12" height="9" rx="1.5"/><circle cx="8" cy="8.5" r="2.5"/><line x1="11" y1="6" x2="12" y2="6"/></svg>`;
        case 'media':   return `<svg ${A}><rect x="2" y="2.5" width="12" height="8.5" rx="1"/><line x1="5" y1="13.5" x2="11" y2="13.5"/></svg>`;
        case 'generic': return `<svg ${A}><rect x="2.5" y="3" width="11" height="8" rx="1"/><line x1="5.5" y1="13.5" x2="10.5" y2="13.5"/><line x1="8" y1="11" x2="8" y2="13.5"/></svg>`;
        case 'lan':     return `<svg ${A}><path d="M2 7.5 L8 2.5 L14 7.5 V14 H2 Z"/><line x1="6.5" y1="14" x2="6.5" y2="10"/><line x1="9.5" y1="14" x2="9.5" y2="10"/></svg>`;
        case 'vaier':   return `<svg ${A}><circle cx="8" cy="8" r="2.2" fill="currentColor" stroke="none"/><line x1="2.5" y1="3.5" x2="6" y2="6.5"/><line x1="13.5" y1="3.5" x2="10" y2="6.5"/><line x1="2.5" y1="12.5" x2="6" y2="9.5"/><line x1="13.5" y1="12.5" x2="10" y2="9.5"/><circle cx="2.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="2.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/></svg>`;
    }
    return '';
}

function capabilityIconSvg(kind) {
    if (kind === 'docker') {
        // Stylised Docker logo: stacked containers + whale body underneath.
        return `<svg width="20" height="20" viewBox="0 0 16 16" fill="currentColor">
            <rect x="2.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="5.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="8.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="5.5" y="3.5" width="2.5" height="2.5"/>
            <path d="M1.5 10 c0.8 2.2 3 3.2 6 3.2 c4 0 7 -2 8 -3.5 z"/>
        </svg>`;
    }
    if (kind === 'relay') {
        // "Share/hub" — three nodes connected to a central forwarding point.
        return `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="18" cy="5" r="2.5"/>
            <circle cx="6" cy="12" r="2.5"/>
            <circle cx="18" cy="19" r="2.5"/>
            <line x1="8.6" y1="13.5" x2="15.4" y2="17.5" fill="none"/>
            <line x1="15.4" y1="6.5" x2="8.6" y2="10.5" fill="none"/>
        </svg>`;
    }
    return '';
}

// Map the legacy "status-*" dot class names to the icon-colour variants. Lets the
// existing branching in the renderers stay untouched while we move the status colour
// off the (now removed) dot and onto the machine icon itself.
function iconStatusClass(statusDotClass) {
    switch (statusDotClass) {
        case 'status-connected':    return 'icon-up';
        case 'status-disconnected': return 'icon-down';
        case 'status-degraded':     return 'icon-degraded';
        default:                    return 'icon-unknown';
    }
}

function machineIconHtml(kind, title, statusClass, id) {
    const cls = 'machine-icon' + (statusClass ? ' ' + statusClass : '');
    const idAttr = id ? ` id="${id}"` : '';
    return `<span class="${cls}"${idAttr} title="${title}">${machineIconSvg(kind)}</span>`;
}

function capabilityIconHtml(kind, title) {
    return `<span class="machine-icon-cap cap-${kind}" title="${title}">${capabilityIconSvg(kind)}</span>`;
}

// Renders a fixed-column strip of capability icons. Each slot is either an
// {kind,title,label} object or null; null slots render as empty placeholders so that
// the docker icon (etc.) lines up vertically across every machine card. The label
// appears next to the icon on desktop and is hidden on mobile via CSS.
function capabilitySlotsHtml(slots) {
    const cells = slots.map(slot => {
        if (!slot) return '<div class="cap-slot"></div>';
        const label = slot.label
            ? `<span class="cap-label">${slot.label}</span>`
            : '';
        return `<div class="cap-slot">${capabilityIconHtml(slot.kind, slot.title)}${label}</div>`;
    }).join('');
    return `<div class="machine-caps">${cells}</div>`;
}

function peerTypeIconKind(peerType) {
    switch (peerType) {
        case 'MOBILE_CLIENT':  return 'mobile';
        case 'WINDOWS_CLIENT': return 'laptop';
        default:               return 'server';
    }
}

// Device category (#…): the effective category every machine carries (peers, LAN servers,
// scanned hosts) drives which machine icon renders. Single source of truth for the enum
// values + their friendly labels, used both by the icon mapper and the card selector so
// the option list isn't duplicated. Order matches the spec.
const DEVICE_CATEGORIES = [
    { value: 'PHONE',   label: 'Phone' },
    { value: 'LAPTOP',  label: 'Laptop' },
    { value: 'DESKTOP', label: 'Desktop' },
    { value: 'SERVER',  label: 'Server' },
    { value: 'NAS',     label: 'NAS' },
    { value: 'PRINTER', label: 'Printer' },
    { value: 'ROUTER',  label: 'Router' },
    { value: 'GATEWAY', label: 'Gateway' },
    { value: 'IOT',     label: 'IoT device' },
    { value: 'CAMERA',  label: 'Camera' },
    { value: 'MEDIA',   label: 'TV / Media' },
    { value: 'GENERIC', label: 'Generic' },
];
const DEVICE_CATEGORY_LABELS =
    Object.fromEntries(DEVICE_CATEGORIES.map(c => [c.value, c.label]));

// Maps a device category enum value (case-insensitive) to a machineIconSvg kind.
// Unknown/missing falls back to 'generic'.
function deviceCategoryIconKind(category) {
    switch (String(category || '').toUpperCase()) {
        case 'PHONE':   return 'phone';
        case 'LAPTOP':  return 'laptop';
        case 'DESKTOP': return 'desktop';
        case 'SERVER':  return 'server';
        case 'NAS':     return 'nas';
        case 'PRINTER': return 'printer';
        case 'ROUTER':  return 'router';
        case 'GATEWAY': return 'gateway';
        case 'IOT':     return 'iot';
        case 'CAMERA':  return 'camera';
        case 'MEDIA':   return 'media';
        default:        return 'generic';
    }
}

// Friendly label for an effective category, falling back to the raw value if unknown.
function deviceCategoryLabel(category) {
    return DEVICE_CATEGORY_LABELS[String(category || '').toUpperCase()]
        || (category || 'Generic');
}

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    }[c]));
}

// Escapes a value for embedding in a single-quoted JS string inside a double-quoted HTML
// attribute, e.g. onclick="f('...')". First neutralises JS-string breakouts (backslash,
// quote, CR/LF), then HTML-escapes so it is also safe in the attribute context. Needed for
// operator-controlled values (LAN-server names) that aren't constrained to a safe charset.
function jsArg(s) {
    return escapeHtml(String(s ?? '')
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/\r/g, '\\r')
        .replace(/\n/g, '\\n'));
}

