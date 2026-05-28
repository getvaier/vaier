const expandedServices = new Set();
// Tracks explicit operator toggles of the Advanced disclosure on each card (#236).
// Keyed by uniqueName; absence means "use the auto-open rule" (open iff any rare-touch
// field is non-default). A present entry overrides the rule — once the operator
// collapses the section it stays collapsed across re-renders.
const advancedExpanded = new Map();

function hasAdvancedNonDefault(service) {
    return !!service.rootRedirectPath
        || !!service.versionEndpoint
        || !!service.versionProperty
        || !!service.directUrlDisabled
        || !!service.hiddenFromLaunchpad;
}

function shouldAdvancedBeOpen(service, uniqueName) {
    return advancedExpanded.has(uniqueName)
        ? advancedExpanded.get(uniqueName)
        : hasAdvancedNonDefault(service);
}

function onAdvancedToggle(uniqueName, isOpen) {
    advancedExpanded.set(uniqueName, isOpen);
}

function cardId(name) {
    return name.replace(/[^a-zA-Z0-9]/g, '_');
}

// Inline SVG icons keep the cards self-contained and recolour via currentColor.
function machineIconSvg(kind) {
    const A = 'width="24" height="24" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"';
    switch (kind) {
        case 'mobile':  return `<svg ${A}><rect x="4.5" y="1.5" width="7" height="13" rx="1.5"/><line x1="6.5" y1="12.5" x2="9.5" y2="12.5"/></svg>`;
        case 'laptop':  return `<svg ${A}><rect x="2" y="3" width="12" height="8" rx="1"/><line x1="1" y1="13.5" x2="15" y2="13.5"/></svg>`;
        case 'server':  return `<svg ${A}><rect x="2" y="2.5" width="12" height="4" rx="0.6"/><rect x="2" y="9.5" width="12" height="4" rx="0.6"/><circle cx="4.5" cy="4.5" r="0.6" fill="currentColor" stroke="none"/><circle cx="4.5" cy="11.5" r="0.6" fill="currentColor" stroke="none"/></svg>`;
        case 'lan':     return `<svg ${A}><path d="M2 7.5 L8 2.5 L14 7.5 V14 H2 Z"/><line x1="6.5" y1="14" x2="6.5" y2="10"/><line x1="9.5" y1="14" x2="9.5" y2="10"/></svg>`;
        case 'vaier':   return `<svg ${A}><circle cx="8" cy="8" r="2.2" fill="currentColor" stroke="none"/><line x1="2.5" y1="3.5" x2="6" y2="6.5"/><line x1="13.5" y1="3.5" x2="10" y2="6.5"/><line x1="2.5" y1="12.5" x2="6" y2="9.5"/><line x1="13.5" y1="12.5" x2="10" y2="9.5"/><circle cx="2.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="3.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="2.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="13.5" cy="12.5" r="1.5" fill="currentColor" stroke="none"/></svg>`;
    }
    return '';
}

function capabilityIconSvg(kind) {
    if (kind === 'docker') {
        return `<svg width="20" height="20" viewBox="0 0 16 16" fill="currentColor">
            <rect x="2.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="5.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="8.5" y="6.5" width="2.5" height="2.5"/>
            <rect x="5.5" y="3.5" width="2.5" height="2.5"/>
            <path d="M1.5 10 c0.8 2.2 3 3.2 6 3.2 c4 0 7 -2 8 -3.5 z"/>
        </svg>`;
    }
    if (kind === 'auth') {
        return `<svg width="20" height="20" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="7.5" width="10" height="6.5" rx="1"/>
            <path d="M5 7.5 V5 a3 3 0 0 1 6 0 V7.5"/>
        </svg>`;
    }
    if (kind === 'no-auth') {
        return `<svg width="20" height="20" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="7.5" width="10" height="6.5" rx="1"/>
            <path d="M5 7.5 V5 a3 3 0 0 1 5.5 -1.6"/>
        </svg>`;
    }
    return '';
}

// Map dot-style status names to icon-colour variants. The page used to render a
// standalone dot; status now rides on the per-service machine icon.
function iconStatusClass(statusDotClass) {
    switch (statusDotClass) {
        case 'status-connected':    return 'icon-up';
        case 'status-disconnected': return 'icon-down';
        case 'status-degraded':     return 'icon-degraded';
        case 'status-pending':      return 'icon-degraded';
        default:                    return 'icon-unknown';
    }
}

function machineIconHtml(kind, title, statusClass) {
    const cls = 'machine-icon' + (statusClass ? ' ' + statusClass : '');
    return `<span class="${cls}" title="${title}">${machineIconSvg(kind)}</span>`;
}

function capabilityIconHtml(kind, title) {
    const cls = kind === 'no-auth' ? 'cap-no-auth' : `cap-${kind}`;
    return `<span class="machine-icon-cap ${cls}" title="${title}">${capabilityIconSvg(kind)}</span>`;
}

// Renders a fixed-column strip of capability icons. Each slot is either an
// {kind,title,label} object or null; null slots render as empty placeholders so the
// capability icon (auth/no-auth) lines up vertically across every service card.
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

function serviceTypeIcon(service) {
    // The server tells us where the route lives — VAIER_SERVER / PEER_SERVER / LAN_SERVICE.
    // Mapping that onto an icon kind is the only thing the browser does here.
    switch (service.serviceLocation) {
        case 'LAN_SERVICE':  return 'lan';
        case 'VAIER_SERVER': return 'vaier';
        default:             return 'server';
    }
}

function discoverableTypeIcon(s) {
    if (s.source === 'LAN_SERVER')   return 'lan';
    if (s.source === 'VAIER_SERVER') return 'vaier';
    return 'server';
}

function toggleService(name) {
    const id = cardId(name);
    const body    = document.getElementById('body-' + id);
    const chevron = document.getElementById('chevron-' + id);
    if (!body) return;
    if (expandedServices.has(name)) {
        expandedServices.delete(name);
        body.classList.remove('open');
        chevron.classList.remove('open');
    } else {
        expandedServices.add(name);
        body.classList.add('open');
        chevron.classList.add('open');
    }
}

let _servicesLoaded = false;
let _publishableLoaded = false;

async function fetchWithRetry(url, attempts = 4) {
    let lastErr;
    for (let i = 0; i < attempts; i++) {
        try {
            const response = await fetch(url, { cache: 'no-store' });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return response;
        } catch (e) {
            lastErr = e;
            if (i < attempts - 1) await new Promise(r => setTimeout(r, 400 * Math.pow(2, i)));
        }
    }
    throw lastErr;
}

async function fetchServices() {
    try {
        const response = await fetchWithRetry('/published-services/discover');
        displayServices(await response.json());
        _servicesLoaded = true;
        document.getElementById('error-message').innerHTML = '';
    } catch (error) {
        if (!_servicesLoaded) {
            document.getElementById('error-message').innerHTML =
                `<div class="error">Failed to load services: ${error.message}</div>`;
        }
    }
}

async function fetchPublishable() {
    try {
        const response = await fetchWithRetry('/published-services/publishable');
        displayPublishable(await response.json());
        _publishableLoaded = true;
    } catch (error) {
        if (!_publishableLoaded) {
            document.getElementById('publishable-container').innerHTML =
                `<div class="error">Failed to load: ${error.message}</div>`;
        }
    }
}

async function refreshPublishable() {
    document.getElementById('publishable-container').innerHTML = '<div class="loading">Loading…</div>';
    await fetchPublishable();
}

function getStatusHtml(state, isOk) {
    // Tri-state: UNKNOWN renders grey (no signal yet) — distinct from the red of a
    // confirmed UNREACHABLE. DNS state never produces UNKNOWN, so this only triggers
    // for the Host row.
    const cls = state === 'UNKNOWN' ? 'status-unknown' : (isOk ? 'status-ok' : 'status-error');
    return `<span class="status ${cls}"><span class="status-indicator"></span>${state}</span>`;
}

// Re-rendering replaces every service card's DOM node. Doing that on every 30s poll,
// window-focus refresh, or SSE update used to (a) swallow a click that arrived while
// the swap was in flight — so a card needed two clicks to expand — and (b) destroy an
// inline edit in progress. We skip the swap when the markup is unchanged, and hold it
// back while a field inside the list is focused (or while a PATCH is in flight),
// flushing once focus leaves / the PATCH resolves.
let _lastServicesHtml = null;
let _pendingServices = null;
// A PATCH triggered from a card (checkbox toggle, alias save, etc.) immediately fires a
// server-side `service-updated` SSE event. The handler turns around and calls
// fetchServices, which used to re-render with the pre-PATCH snapshot — the checkbox
// visibly snapped back for a frame. While any PATCH is outstanding we defer renders,
// then flush once the set drains. Keyed by `${dnsAddress}|${pathPrefix}|${field}` so two
// concurrent edits on different fields of the same row still both hold the render off.
const _inFlightEdits = new Set();

function inFlightKey(dnsAddress, pathPrefix, field) {
    return `${dnsAddress}|${pathPrefix || ''}|${field}`;
}

async function withInFlightEdit(dnsAddress, pathPrefix, field, op) {
    const key = inFlightKey(dnsAddress, pathPrefix, field);
    _inFlightEdits.add(key);
    try {
        return await op();
    } finally {
        _inFlightEdits.delete(key);
        if (_inFlightEdits.size === 0 && _pendingServices && !isEditingServiceCard()) {
            const pending = _pendingServices;
            _pendingServices = null;
            displayServices(pending);
        }
    }
}

function isEditingServiceCard() {
    const active = document.activeElement;
    const container = document.getElementById('services-container');
    return !!active && container.contains(active)
        && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA');
}

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
        '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
    }[c]));
}

// Render the Version row only when the route has a backing image or a probed version (#245).
// image comes from a backing container (Vaier server / VPN peer / LAN server scrape); version
// comes from that container or from a configured versionEndpoint — a service running natively
// on a LAN host can report a version with no image. Bare LAN host:port routes have neither.
function versionRowHtml(service) {
    if (!service.image && !service.version) return '';
    // The row label is already "Version", so the value drops the "version" prefix. Image and
    // version share the monospace font so the version doesn't look like prose pinned to a code
    // chip — when both are present we render them inline separated by a middle dot.
    const parts = [];
    if (service.image) parts.push(`<span style="color:var(--text)">${escapeHtml(service.image)}</span>`);
    if (service.version) parts.push(`<span style="color:var(--text-muted)">${escapeHtml(service.version)}</span>`);
    return `
        <div class="detail-row">
            <span class="detail-label">Version</span>
            <span class="detail-value" style="font-family:var(--mono);display:flex;gap:0.5rem;align-items:center;flex-wrap:wrap">${parts.join('<span style="color:var(--text-dim)">·</span>')}</span>
        </div>`;
}

function renderServiceCard(service) {
    const dnsOk  = service.dnsState === 'OK';
    const uniqueName = service.dnsAddress + (service.pathPrefix || '');
    const id         = cardId(uniqueName);
    const isExpanded = expandedServices.has(uniqueName);
    // Tri-state: state UNKNOWN renders the machine-type icon grey, so a service
    // whose host hasn't been probed yet doesn't show a misleading green.
    const dotClass = service.state === 'OK' ? 'status-connected'
        : (service.state === 'UNKNOWN' ? 'status-unknown' : 'status-disconnected');
    const authSlot   = service.authenticated
        ? { kind: 'auth',    title: 'Authelia authentication required',         label: 'Auth' }
        : { kind: 'no-auth', title: 'No authentication — service is publicly reachable', label: 'No auth' };
    const serviceUrl = `https://${service.dnsAddress}${service.pathPrefix || ''}`;
    const displayHost = `${service.dnsAddress}${service.pathPrefix || ''}`;
    const isSelf = service.dnsAddress.startsWith('vaier.');

    // The bold heading is the operator-facing Display Name when set, else the
    // computed shortName. The peer/host is already named by the section heading
    // (we group cards by hostName), so the sub-line only carries (a) the LAN host's
    // display name for LAN services — the relay is in the heading but operators
    // think of "DSM @ NAS", not "DSM @ relay-peer" — and (b) the pathPrefix when set,
    // so a path-based route doesn't conflate name and path (#235).
    const heading = service.launchpadAlias || service.shortName;
    const subParts = [];
    if (service.lanServerName) subParts.push(`@ ${service.lanServerName}`);
    if (service.pathPrefix)    subParts.push(service.pathPrefix);
    const subLine = subParts.join(' · ');
    const typeKind  = serviceTypeIcon(service);
    const typeTip   = ({ 'lan': 'LAN service (routed through a relay peer)', 'vaier': 'Service on the Vaier server', 'server': 'Service on a VPN peer' })[typeKind];

    const urlValue = dnsOk && !isSelf
        ? `<a class="service-link" href="${serviceUrl}" target="${service.dnsAddress}${service.pathPrefix || ''}">${displayHost}</a>`
        : `<span style="color:var(--text-dim);font-family:var(--mono)">${displayHost}</span>`;

    return `
        <div class="service-card">
            <div class="service-header" onclick="toggleService('${uniqueName.replace(/'/g, "\\'")}')">
                <div class="service-header-left">
                    ${machineIconHtml(typeKind, typeTip, iconStatusClass(dotClass))}
                    <span class="service-name">${heading}</span>
                    ${subLine ? `<span class="service-peer">${subLine}</span>` : ''}
                    ${capabilitySlotsHtml([authSlot])}
                </div>
                <span class="service-chevron ${isExpanded ? 'open' : ''}" id="chevron-${id}">▼</span>
            </div>
            <div class="service-body ${isExpanded ? 'open' : ''}" id="body-${id}">
                <div class="service-details">
                    <div class="detail-row">
                        <span class="detail-label">URL</span>
                        <span class="detail-value">${urlValue}</span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label">DNS</span>
                        <span class="detail-value">${getStatusHtml(service.dnsState, dnsOk)}</span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label">Host</span>
                        <span class="detail-value">${service.hostAddress}:${service.hostPort}</span>
                    </div>
                    ${versionRowHtml(service)}
                    <div class="detail-row">
                        <span class="detail-label">Auth</span>
                        <span class="detail-value">
                            <input type="checkbox" id="requiresAuth-${id}"
                                ${service.authenticated ? 'checked' : ''}
                                style="accent-color:var(--accent);cursor:pointer"
                                title="When checked, Authelia authentication is required to reach this service."
                                onchange="setRequiresAuth('${service.dnsAddress}', '${service.pathPrefix || ''}', this.checked)">
                        </span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label" title="Optional label shown on the launchpad tile. Leave blank to use the default (path-segment for path-based routes, subdomain otherwise).">Display name</span>
                        <span class="detail-value">
                            <input type="text" id="alias-${id}" class="form-input" style="width:100%;max-width:240px"
                                   value="${service.launchpadAlias || ''}"
                                   data-original="${service.launchpadAlias || ''}"
                                   placeholder="(default)"
                                   onkeydown="if (event.key === 'Enter') this.blur();"
                                   onblur="saveAlias('${service.dnsAddress}', '${service.pathPrefix || ''}', '${id}')">
                        </span>
                    </div>
                </div>
                <details class="service-advanced" ${shouldAdvancedBeOpen(service, uniqueName) ? 'open' : ''}
                         ontoggle="onAdvancedToggle('${uniqueName.replace(/'/g, "\\'")}', this.open)">
                    <summary>Advanced</summary>
                    <div class="service-details">
                    <div class="detail-row">
                        <span class="detail-label">Redirect</span>
                        <span class="detail-value">
                            <input type="text" id="redirect-${id}" class="form-input" style="width:100%;max-width:240px"
                                   value="${service.rootRedirectPath || ''}"
                                   data-original="${service.rootRedirectPath || ''}"
                                   placeholder="e.g. /dashboard"
                                   onkeydown="if (event.key === 'Enter') this.blur();"
                                   onblur="saveRedirect('${service.dnsAddress}', '${service.pathPrefix || ''}', '${id}')">
                        </span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label" title="Read this service's running version over HTTP and show it on its launchpad tile. Endpoint is a path on the service (or a full URL); property is the label name to read the version from. Useful for services not backed by a discoverable container (e.g. running natively on a LAN machine).">Version endpoint</span>
                        <span class="detail-value" style="display:flex;gap:6px;align-items:center;flex-wrap:nowrap">
                            <input type="text" id="verEndpoint-${id}" class="form-input" style="flex:2;min-width:0;max-width:170px"
                                   value="${service.versionEndpoint || ''}"
                                   data-original="${service.versionEndpoint || ''}"
                                   placeholder="/sys/metrics"
                                   onkeydown="if (event.key === 'Enter') this.blur();"
                                   onblur="saveVersionEndpoint('${service.dnsAddress}', '${service.pathPrefix || ''}', '${id}')">
                            <input type="text" id="verProperty-${id}" class="form-input" style="flex:1;min-width:0;max-width:100px"
                                   value="${service.versionProperty || ''}"
                                   data-original="${service.versionProperty || ''}"
                                   placeholder="property"
                                   onkeydown="if (event.key === 'Enter') this.blur();"
                                   onblur="saveVersionEndpoint('${service.dnsAddress}', '${service.pathPrefix || ''}', '${id}')">
                        </span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label" title="When checked, the launchpad uses the direct LAN URL for callers sharing the peer's NAT. Uncheck for apps whose public origin differs from http://lan:port (e.g. Vaultwarden).">Direct LAN URL</span>
                        <span class="detail-value">
                            <input type="checkbox" id="dulEnabled-${id}"
                                ${service.directUrlDisabled ? '' : 'checked'}
                                style="accent-color:var(--accent);cursor:pointer"
                                title="When checked, the launchpad links to the direct LAN URL when applicable."
                                onchange="setDirectUrlDisabled('${service.dnsAddress}', '${service.pathPrefix || ''}', !this.checked)">
                        </span>
                    </div>
                    <div class="detail-row">
                        <span class="detail-label" title="When checked, the launchpad renders a tile for this service. Uncheck for internal APIs that back another service and don't need their own tile.">Launchpad</span>
                        <span class="detail-value">
                            <input type="checkbox" id="showOnLaunchpad-${id}"
                                ${service.hiddenFromLaunchpad ? '' : 'checked'}
                                style="accent-color:var(--accent);cursor:pointer"
                                title="When checked, the launchpad shows a tile for this service."
                                onchange="setHiddenFromLaunchpad('${service.dnsAddress}', '${service.pathPrefix || ''}', !this.checked)">
                        </span>
                    </div>
                    </div>
                </details>
                <div class="service-actions-row">
                    <div class="service-actions-left"></div>
                    <button class="btn btn-small btn-danger" onclick="showDeleteModal('${service.dnsAddress}', '${service.pathPrefix || ''}')">Delete</button>
                </div>
            </div>
        </div>`;
}

// Replace the services-container HTML, but preserve any in-progress typing — and the
// focused field's caret position — so an SSE-driven re-render or a 30s poll can't wipe
// an unsaved edit out from under the operator's fingers (#239 follow-up). The existing
// in-flight + focus guards still defer the swap when possible; this is the last-resort
// belt-and-suspenders for the moments those checks miss.
function renderServicesContainer(html) {
    const container = document.getElementById('services-container');
    const dirty = new Map();
    container.querySelectorAll('input[data-original]').forEach(input => {
        if (input.id && input.value !== input.dataset.original) dirty.set(input.id, input.value);
    });
    const active = document.activeElement;
    let restoreFocus = null;
    if (active && active.id && container.contains(active)
        && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) {
        restoreFocus = {
            id: active.id,
            selectionStart: active.selectionStart,
            selectionEnd: active.selectionEnd,
        };
    }
    container.innerHTML = html;
    dirty.forEach((value, id) => {
        const input = document.getElementById(id);
        if (input) input.value = value;
    });
    if (restoreFocus) {
        const input = document.getElementById(restoreFocus.id);
        if (input) {
            input.focus();
            try {
                if (typeof restoreFocus.selectionStart === 'number') {
                    input.setSelectionRange(restoreFocus.selectionStart, restoreFocus.selectionEnd);
                }
            } catch (_) { /* selection API doesn't apply to this input type — ignore */ }
        }
    }
}

function displayServices(services) {
    const container = document.getElementById('services-container');
    if (services.length === 0) {
        _lastServicesHtml = null;
        _pendingServices = null;
        container.innerHTML = '<div class="loading">No published services yet</div>';
        return;
    }

    // Group by hostName so the page reads "what's on each host", mirroring the launchpad
    // (#234). Services on the Vaier server itself have an empty hostName — they go under
    // "Vaier". LAN services have hostName set to their relay peer (the domain decides
    // that), so they group with the rest of that peer's services.
    const groups = new Map();
    for (const s of services) {
        const key = s.hostName || 'Vaier';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(s);
    }
    const sortedPeers = [...groups.keys()].sort((a, b) => a.localeCompare(b));

    const html = sortedPeers.map(peer => {
        const cards = groups.get(peer)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map(renderServiceCard)
            .join('');
        return `<div class="peer-section"><div class="peer-heading">${peer}</div>${cards}</div>`;
    }).join('');

    if (html === _lastServicesHtml) { _pendingServices = null; return; }
    if (isEditingServiceCard() || _inFlightEdits.size > 0) { _pendingServices = services; return; }

    _pendingServices = null;
    _lastServicesHtml = html;
    renderServicesContainer(html);
}

// Discovered-list controls (#244). State persists in localStorage so a reload doesn't
// reset the operator's filter / grouping / show-ignored preference.
const LS_DISCOVERED_FILTER  = 'vaier.discovered.filter';
const LS_DISCOVERED_GROUP   = 'vaier.discovered.groupByHost';
const LS_DISCOVERED_IGNORED = 'vaier.discovered.showIgnored';

let showIgnored          = localStorage.getItem(LS_DISCOVERED_IGNORED) === '1';
let discoveredFilter     = localStorage.getItem(LS_DISCOVERED_FILTER) || '';
let discoveredGroupByHost = localStorage.getItem(LS_DISCOVERED_GROUP) === '1';
let lastPublishableServices = [];

function toggleShowIgnored() {
    showIgnored = document.getElementById('showIgnoredChk').checked;
    localStorage.setItem(LS_DISCOVERED_IGNORED, showIgnored ? '1' : '0');
    displayPublishable(lastPublishableServices);
}

function onDiscoveredFilterInput() {
    discoveredFilter = document.getElementById('discoveredFilter').value;
    localStorage.setItem(LS_DISCOVERED_FILTER, discoveredFilter);
    displayPublishable(lastPublishableServices);
}

function onDiscoveredGroupToggle() {
    discoveredGroupByHost = document.getElementById('discoveredGroupChk').checked;
    localStorage.setItem(LS_DISCOVERED_GROUP, discoveredGroupByHost ? '1' : '0');
    displayPublishable(lastPublishableServices);
}

function discoveredSourceLabel(s) {
    return s.source === 'VAIER_SERVER' ? 'Vaier server' : s.peerName;
}

function discoveredMatchesFilter(s, needle) {
    if (!needle) return true;
    const haystack = (s.containerName + ' ' + discoveredSourceLabel(s) + ' ' + s.address).toLowerCase();
    return haystack.includes(needle);
}

function renderDiscoveredCard(s) {
    const sourceLabel = discoveredSourceLabel(s);
    const key = s.ignoreKey;
    const dimStyle = s.ignored ? 'opacity:0.55' : '';
    const typeKind = discoverableTypeIcon(s);
    const typeTip  = ({ 'lan': 'Discovered on a LAN Docker host', 'vaier': 'Discovered on the Vaier server', 'server': 'Discovered on a VPN peer' })[typeKind];
    const actionButtons = s.ignored
        ? `<button class="btn btn-success btn-small" title="Show this service again in the list" onclick="unignoreService('${key}')">Unignore</button>`
        : `<button class="btn btn-primary btn-small"
            title="Publish ${s.containerName} as a reverse-proxy route"
            data-address="${s.address}"
            data-port="${s.port}"
            data-container="${s.containerName}"
            data-source-label="${sourceLabel}"
            data-suggested-subdomain="${s.suggestedSubdomain}"
            data-redirect="${s.rootRedirectPath || ''}"
            onclick="showPublishModalFromBtn(this)">
            + Publish
        </button>
        <button class="btn btn-warning btn-small" title="Hide this service from the list" onclick="ignoreService('${key}')">Ignore</button>`;
    // The "@ source" sub-label is dropped when grouping by host, because the section
    // heading already names the source — matches how the published-services list reads.
    const subLabel = discoveredGroupByHost ? '' : `<span class="service-peer">@ ${sourceLabel}</span>`;
    return `
        <div class="discoverable-card">
            <div class="service-header-left" style="${dimStyle}">
                ${machineIconHtml(typeKind, typeTip)}
                <span class="service-name">${s.containerName}</span>
                ${subLabel}
                <span class="discoverable-meta" style="margin-left:auto">${s.address}:${s.port}</span>
            </div>
            <div style="display:flex;gap:0.4rem;align-items:center">
                ${actionButtons}
            </div>
        </div>`;
}

function displayPublishable(services) {
    lastPublishableServices = services;
    const container = document.getElementById('publishable-container');
    const needle = discoveredFilter.trim().toLowerCase();
    const visible = (showIgnored ? services : services.filter(s => !s.ignored))
        .filter(s => discoveredMatchesFilter(s, needle))
        .slice()
        .sort((a, b) => a.containerName.localeCompare(b.containerName));
    if (visible.length === 0) {
        container.innerHTML = needle
            ? `<div class="loading">No discovered services match "${discoveredFilter}"</div>`
            : '<div class="loading">No discovered services</div>';
        return;
    }

    if (!discoveredGroupByHost) {
        container.innerHTML = visible.map(renderDiscoveredCard).join('');
        return;
    }

    const groups = new Map();
    for (const s of visible) {
        const key = discoveredSourceLabel(s);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(s);
    }
    const sortedSources = [...groups.keys()].sort((a, b) => a.localeCompare(b));
    container.innerHTML = sortedSources.map(source => {
        const cards = groups.get(source).map(renderDiscoveredCard).join('');
        return `<div class="peer-section"><div class="peer-heading">${source}</div>${cards}</div>`;
    }).join('');
}

async function ignoreService(key) {
    applyIgnoredOptimistically(key, true);
    await fetch('/published-services/publishable/ignore', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key })
    });
    await fetchPublishable();
}

async function unignoreService(key) {
    applyIgnoredOptimistically(key, false);
    await fetch('/published-services/publishable/unignore', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key })
    });
    await fetchPublishable();
}

// Re-render immediately so the click registers visibly — fetchPublishable() takes seconds
// because it scrapes every peer and LAN Docker host. The next refresh reconciles authority.
function applyIgnoredOptimistically(key, ignored) {
    const svc = lastPublishableServices.find(s => s.ignoreKey === key);
    if (svc) {
        svc.ignored = ignored;
        displayPublishable(lastPublishableServices);
    }
}

// Shared modal state — the data-mode attribute on #publishModal also drives the CSS
// that shows/hides container-only vs LAN-only sections (#240).
function publishMode() {
    return document.getElementById('publishModal').dataset.mode;
}

function resetPublishFormCommon() {
    document.getElementById('publishSubdomain').value     = '';
    document.getElementById('publishPathPrefix').value    = '';
    document.getElementById('publishRequiresAuth').checked = false;
    document.getElementById('publishDirectUrlEnabled').checked = true;
    document.getElementById('publishRootRedirectPath').value = '';
    document.getElementById('publishAdvanced').open = false;
}

function showPublishModalFromBtn(btn) {
    showPublishModal(
        btn.dataset.address,
        parseInt(btn.dataset.port),
        btn.dataset.container,
        btn.dataset.sourceLabel,
        btn.dataset.suggestedSubdomain,
        btn.dataset.redirect || null);
}

function showPublishModal(address, port, containerName, sourceLabel, suggestedSubdomain, rootRedirectPath) {
    const modal = document.getElementById('publishModal');
    modal.dataset.mode = 'container';
    document.getElementById('publishModalHeader').textContent = 'Publish Service';
    resetPublishFormCommon();
    document.getElementById('publishAddress').value          = address;
    document.getElementById('publishPort').value             = port;
    document.getElementById('publishRootRedirectPath').value = rootRedirectPath || '';
    // suggestedSubdomain is computed in the domain by PublishableService.suggestedSubdomain().
    document.getElementById('publishSubdomain').value        = suggestedSubdomain;
    document.getElementById('publishServiceLabel').textContent =
        `${containerName} on ${sourceLabel} (${address}:${port})`;
    document.getElementById('publishAdvanced').open = !!rootRedirectPath;
    modal.classList.add('active');
    document.getElementById('publishSubdomain').focus();
}

async function showPublishLanModal() {
    const modal = document.getElementById('publishModal');
    modal.dataset.mode = 'lan';
    document.getElementById('publishModalHeader').textContent = 'Publish LAN Service';
    resetPublishFormCommon();
    document.getElementById('publishAddress').value = '';
    document.getElementById('publishPort').value    = '';  // hidden field stays empty in LAN mode
    document.getElementById('publishLanPort').value = '';
    document.getElementById('publishLanProtocol').value = 'http';
    document.getElementById('publishLanRelayHint').textContent = '';
    modal.classList.add('active');
    await loadLanServersForPublish();
    document.getElementById('publishSubdomain').focus();
}

function hidePublishModal() {
    document.getElementById('publishModal').classList.remove('active');
}

function addProcessingCard(subdomain, requiresAuth, dnsPropagated) {
    const id = cardId(subdomain);
    if (document.getElementById('proc-' + id)) return; // already exists
    const authBadge = requiresAuth
        ? '<span class="badge badge-success">auth</span>'
        : '<span class="badge badge-warning">no-auth</span>';
    const step2opacity = dnsPropagated ? '1' : '0.35';
    const step2icon    = dnsPropagated ? '✓' : '⏳';
    const card = document.createElement('div');
    card.id = 'proc-' + id;
    card.className = 'service-card';
    card.innerHTML = `
        <div class="service-header" style="cursor:default">
            <div class="service-header-left">
                <span class="service-name">${subdomain}</span>
                ${authBadge}
            </div>
        </div>
        <div style="padding:0.5rem 1rem 0.75rem;border-top:1px solid var(--border);display:flex;flex-direction:column;gap:0.35rem">
            <div id="proc-step-dns-create-${id}"    class="progress-step"><span class="step-icon">✓</span><span>DNS record created</span></div>
            <div id="proc-step-dns-propagate-${id}" class="progress-step" style="opacity:${step2opacity}"><span class="step-icon">${step2icon}</span><span>Waiting for DNS propagation…</span></div>
            <div id="proc-step-traefik-${id}"       class="progress-step" style="opacity:0.35"><span class="step-icon">⏳</span><span>Activating reverse proxy route…</span></div>
        </div>`;
    document.getElementById('processing-container').appendChild(card);
    document.getElementById('processing-section').style.display = '';
}

function removeProcessingCard(subdomain) {
    const el = document.getElementById('proc-' + cardId(subdomain));
    if (el) el.remove();
    const container = document.getElementById('processing-container');
    if (container.children.length === 0)
        document.getElementById('processing-section').style.display = 'none';
}

function setProcessingStep(subdomain, stepId, done) {
    const el = document.getElementById('proc-step-' + stepId + '-' + cardId(subdomain));
    if (!el) return;
    el.style.opacity = '1';
    el.querySelector('.step-icon').textContent = done ? '✓' : '⏳';
}

async function fetchPending() {
    try {
        const response = await fetch('/published-services/pending');
        if (!response.ok) return;
        (await response.json()).forEach(p => {
            addProcessingCard(p.subdomain, p.requiresAuth, p.dnsPropagated);
            if (p.dnsPropagated) setProcessingStep(p.subdomain, 'dns-propagate', true);
        });
    } catch (e) { /* ignore */ }
}

let _lanServerOptions = [];

async function loadLanServersForPublish() {
    try {
        const response = await fetch('/lan-servers');
        if (!response.ok) return;
        _lanServerOptions = await response.json();
    } catch (e) { _lanServerOptions = []; }
    populateLanServerSelect();
}

function populateLanServerSelect() {
    const select = document.getElementById('publishLanMachine');
    select.innerHTML = '';
    if (_lanServerOptions.length === 0) {
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = '— no LAN servers registered —';
        select.appendChild(opt);
        select.disabled = true;
        return;
    }
    select.disabled = false;
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = '— select a LAN server —';
    select.appendChild(placeholder);
    _lanServerOptions.forEach(s => {
        const opt = document.createElement('option');
        opt.value = s.name;
        const relay = s.relayPeerName ? ` — via ${s.relayPeerName}` : '';
        opt.textContent = `${s.name} (${s.lanAddress})${relay}`;
        select.appendChild(opt);
    });
    onPublishLanMachineChange();
}

function onPublishLanMachineChange() {
    const name = document.getElementById('publishLanMachine').value;
    const hint = document.getElementById('publishLanRelayHint');
    if (!name) { hint.textContent = ''; return; }
    const machine = _lanServerOptions.find(s => s.name === name);
    if (!machine) { hint.textContent = ''; return; }
    if (machine.relayPeerName) {
        hint.textContent = `Routes via ${machine.relayPeerName} to ${machine.lanAddress}`;
        hint.style.color = 'var(--text-muted)';
    } else {
        hint.textContent = `${machine.lanAddress} is not inside any relay peer's lanCidr — set lanCidr on a relay first.`;
        hint.style.color = 'var(--red)';
    }
}

async function submitPublish() {
    const mode = publishMode();
    const subdomain          = document.getElementById('publishSubdomain').value.trim();
    const pathPrefix         = document.getElementById('publishPathPrefix').value.trim() || null;
    const requiresAuth       = document.getElementById('publishRequiresAuth').checked;
    const directUrlDisabled  = !document.getElementById('publishDirectUrlEnabled').checked;
    const rootRedirectPath   = document.getElementById('publishRootRedirectPath').value.trim() || null;

    if (!subdomain) { alert('Please enter a subdomain'); return; }

    // Per-mode validation + payload — the only thing that diverges between the two
    // publish flows is which endpoint we hit and which extra fields it expects.
    let endpoint, body, errorPrefix;
    if (mode === 'lan') {
        const machineName = document.getElementById('publishLanMachine').value;
        const port        = parseInt(document.getElementById('publishLanPort').value);
        const protocol    = document.getElementById('publishLanProtocol').value;
        if (!machineName) { alert('Please select a LAN server'); return; }
        if (!port || port < 1 || port > 65535) { alert('Please enter a valid port (1-65535)'); return; }
        endpoint = '/published-services/lan';
        // LAN API field name is `requireAuth` (singular), not `requiresAuth`. Quirk preserved.
        body = { subdomain, machineName, port, protocol, requireAuth: requiresAuth, directUrlDisabled, rootRedirectPath, pathPrefix };
        errorPrefix = 'Failed to publish LAN service';
    } else {
        const address = document.getElementById('publishAddress').value;
        const port    = parseInt(document.getElementById('publishPort').value);
        endpoint = '/published-services/publish';
        body = { address, port, subdomain, requiresAuth, rootRedirectPath, directUrlDisabled, pathPrefix };
        errorPrefix = 'Failed to publish service';
    }

    const submitBtn = document.getElementById('publishSubmitBtn');
    const cancelBtn = document.getElementById('publishCancelBtn');
    submitBtn.disabled = true;
    cancelBtn.disabled = true;
    submitBtn.textContent = 'Publishing…';

    try {
        const response = await fetch(endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (mode === 'lan' && response.status === 400) {
            alert(`Could not publish — make sure ${body.machineName}'s lanAddress is inside a relay peer's lanCidr.`);
            return;
        }
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        hidePublishModal();
        addProcessingCard(subdomain, requiresAuth, false);
        if (mode === 'container') fetchPublishable();
    } catch (error) {
        alert(`${errorPrefix}: ${error.message}`);
    } finally {
        submitBtn.disabled = false;
        cancelBtn.disabled = false;
        submitBtn.textContent = 'Publish';
    }
}

function withPathQuery(url, pathPrefix) {
    return pathPrefix ? `${url}?pathPrefix=${encodeURIComponent(pathPrefix)}` : url;
}

async function patchService(dnsAddress, pathPrefix, patch) {
    const response = await fetch(withPathQuery(
        `/published-services/${encodeURIComponent(dnsAddress)}`, pathPrefix), {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch)
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
}

async function setDirectUrlDisabled(dnsAddress, pathPrefix, disabled) {
    await withInFlightEdit(dnsAddress, pathPrefix, 'direct-url-disabled', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { directUrlDisabled: disabled });
            await fetchServices();
        } catch (error) {
            alert(`Failed to update direct LAN URL setting: ${error.message}`);
        }
    });
}

async function setHiddenFromLaunchpad(dnsAddress, pathPrefix, hidden) {
    await withInFlightEdit(dnsAddress, pathPrefix, 'hidden-from-launchpad', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { hiddenFromLaunchpad: hidden });
            await fetchServices();
        } catch (error) {
            alert(`Failed to update launchpad visibility: ${error.message}`);
        }
    });
}

async function setRequiresAuth(dnsAddress, pathPrefix, requiresAuth) {
    await withInFlightEdit(dnsAddress, pathPrefix, 'auth', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { requiresAuth });
            await fetchServices();
        } catch (error) {
            alert(`Failed to update authentication: ${error.message}`);
        }
    });
}

// Brief green-border flash after a successful auto-save (#238). The transient class
 // gets cleared on a timer; if the card re-renders in the meantime, the new DOM node
 // simply has no class and the absence reads as "no recent save" — which is correct.
function flashSaveOk(input) {
    input.classList.add('save-ok');
    setTimeout(() => input.classList.remove('save-ok'), 900);
}

async function saveAlias(dnsAddress, pathPrefix, id) {
    const input = document.getElementById(`alias-${id}`);
    if (!input) return;
    const alias = input.value.trim();
    if (alias === (input.dataset.original || '')) return;
    await withInFlightEdit(dnsAddress, pathPrefix, 'launchpad-alias', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { launchpadAlias: alias });
            input.dataset.original = alias;
            flashSaveOk(input);
            await fetchServices();
        } catch (error) {
            alert(`Failed to update display name: ${error.message}`);
        }
    });
}

async function saveVersionEndpoint(dnsAddress, pathPrefix, id) {
    const ep = document.getElementById(`verEndpoint-${id}`);
    const prop = document.getElementById(`verProperty-${id}`);
    if (!ep || !prop) return;
    const endpoint = ep.value.trim();
    const property = prop.value.trim();
    if (endpoint === (ep.dataset.original || '') && property === (prop.dataset.original || '')) return;
    await withInFlightEdit(dnsAddress, pathPrefix, 'version-endpoint', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { versionEndpoint: endpoint, versionProperty: property });
            ep.dataset.original = endpoint;
            prop.dataset.original = property;
            flashSaveOk(ep);
            flashSaveOk(prop);
            await fetchServices();
        } catch (error) {
            alert(`Failed to update version endpoint: ${error.message}`);
        }
    });
}

async function saveRedirect(dnsAddress, pathPrefix, id) {
    const input = document.getElementById(`redirect-${id}`);
    if (!input) return;
    const raw = input.value.trim();
    if (raw === (input.dataset.original || '')) return;
    const path = raw || '';
    await withInFlightEdit(dnsAddress, pathPrefix, 'redirect', async () => {
        try {
            await patchService(dnsAddress, pathPrefix, { rootRedirectPath: path });
            input.dataset.original = path;
            flashSaveOk(input);
            await fetchServices();
        } catch (error) {
            alert(`Failed to update redirect: ${error.message}`);
        }
    });
}

function showDeleteModal(dnsAddress, pathPrefix) {
    const label = pathPrefix ? `${dnsAddress}${pathPrefix}` : dnsAddress;
    document.getElementById('deleteServiceName').textContent = label;
    document.getElementById('deleteConfirmBtn').dataset.dns  = dnsAddress;
    document.getElementById('deleteConfirmBtn').dataset.path = pathPrefix || '';
    document.getElementById('deleteModal').classList.add('active');
}

function hideDeleteModal() {
    document.getElementById('deleteModal').classList.remove('active');
}

async function confirmDelete() {
    const dnsAddress = document.getElementById('deleteConfirmBtn').dataset.dns;
    const pathPrefix = document.getElementById('deleteConfirmBtn').dataset.path || '';
    if (!dnsAddress) return;
    const deleteBtn = document.getElementById('deleteConfirmBtn');
    const cancelBtn = document.getElementById('deleteCancelBtn');
    deleteBtn.disabled = true;
    cancelBtn.disabled = true;
    deleteBtn.textContent = 'Deleting…';
    try {
        const url = pathPrefix
            ? `/published-services/${encodeURIComponent(dnsAddress)}?pathPrefix=${encodeURIComponent(pathPrefix)}`
            : `/published-services/${encodeURIComponent(dnsAddress)}`;
        const response = await fetch(url, { method: 'DELETE' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        hideDeleteModal();
        await Promise.all([fetchServices(), fetchPublishable()]);
    } catch (error) {
        alert(`Failed to delete service: ${error.message}`);
    } finally {
        deleteBtn.disabled = false;
        cancelBtn.disabled = false;
        deleteBtn.textContent = 'Delete';
    }
}

window.onclick = e => {
    if (e.target.classList.contains('modal')) e.target.classList.remove('active');
};

// Reflect persisted Discovered-list controls into the DOM before first fetch (#244).
document.getElementById('discoveredFilter').value     = discoveredFilter;
document.getElementById('discoveredGroupChk').checked = discoveredGroupByHost;
document.getElementById('showIgnoredChk').checked     = showIgnored;

fetchServices();
fetchPublishable();
fetchPending();
setInterval(fetchServices, 30000);

const _sse = new EventSource('/published-services/events');

_sse.addEventListener('publish-dns-propagated', e => {
    setProcessingStep(e.data, 'dns-propagate', true);
    setProcessingStep(e.data, 'traefik', false);  // reveal step 3
});

_sse.addEventListener('publish-traefik-active', e => {
    setProcessingStep(e.data, 'traefik', true);
    setTimeout(() => {
        removeProcessingCard(e.data);
        Promise.all([fetchServices(), fetchPublishable()]);
    }, 800);
});

_sse.addEventListener('publish-dns-timeout', e => {
    removeProcessingCard(e.data);
    alert(`DNS propagation timed out for "${e.data}". The DNS record was created but did not become visible on authoritative nameservers in time. Try publishing again — if it keeps failing, check your Route53 zone.`);
    Promise.all([fetchServices(), fetchPublishable()]);
});

_sse.addEventListener('service-updated', () => {
    fetchServices();
});

let _sseConnected = false;
_sse.onopen = () => {
    if (_sseConnected) { fetchServices(); fetchPublishable(); fetchPending(); }
    _sseConnected = true;
};

_sse.onerror = () => {
    // browser auto-reconnects; re-fetch on reconnection to catch up
    fetchServices();
    fetchPublishable();
};

function refreshAll() {
    fetchServices();
    fetchPublishable();
    fetchPending();
}
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refreshAll();
});
window.addEventListener('online', refreshAll);
window.addEventListener('focus', refreshAll);

// A status update that arrived while the user was editing a field is deferred
// (see displayServices); render it once focus leaves the services list and no PATCH
// is in flight. The in-flight branch is also flushed inside withInFlightEdit so a
// focus-less checkbox toggle reconciles too.
document.getElementById('services-container').addEventListener('focusout', () => {
    setTimeout(() => {
        if (_pendingServices && !isEditingServiceCard() && _inFlightEdits.size === 0) {
            const pending = _pendingServices;
            _pendingServices = null;
            displayServices(pending);
        }
    }, 0);
});
