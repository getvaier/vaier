// Extracted from vpn-peers.html (#273). Classic script — runs in global scope so the page's
// inline on* handlers keep resolving. Modularisation tracked as follow-up slices of #273.
        let peers = [];
        let peerServices = {};
        let vaierServerServices = [];
        let vaierServerStatus = 'UNKNOWN'; // domain MachineStatus enum value
        let lanServers = [];
        let lanServerServices = {};
        let lastFetchSuccessful = false;

        const expandedPeers = new Set();

        function parseImageTag(image) {
            if (!image) return '';
            const slashIdx = image.lastIndexOf('/');
            const nameAndTag = slashIdx >= 0 ? image.slice(slashIdx + 1) : image;
            const colonIdx = nameAndTag.indexOf(':');
            if (colonIdx < 0) return 'latest';
            const tag = nameAndTag.slice(colonIdx + 1);
            return tag.length > 20 ? tag.slice(0, 12) + '…' : tag;
        }

        function cardId(peerName) {
            return peerName.replace(/[^a-zA-Z0-9]/g, '_');
        }

        // A peer has an immutable id (slug — drives DOM keys + REST paths) and a separate
        // editable display name. This resolves the display name from the id for toasts and
        // dialogs; the backend owns the id↔name split (issue #209).
        function peerDisplayName(peerId) {
            const p = peers.find(x => x.id === peerId);
            return p ? p.name : peerId;
        }

        async function fetchPeers() {
            try {
                const response = await fetch('/vpn/peers');
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                peers = await response.json();
                displayPeers(peers);
                lastFetchSuccessful = true;
                document.getElementById('error-message').innerHTML = '';
            } catch (error) {
                console.error('Failed to load peers:', error);
                if (!lastFetchSuccessful && peers.length === 0)
                    displayError(`Failed to load peers: ${error.message}`);
            }

            try {
                const response = await fetch('/docker-services/peers');
                if (response.ok) {
                    const services = await response.json();
                    peerServices = Object.fromEntries(services.map(s => [s.peerName, s]));
                    displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load peer services:', error);
            }
        }

        async function fetchVaierServerServices() {
            try {
                const response = await fetch('/docker-services/vaier-server');
                if (response.ok) {
                    const body = await response.json();
                    vaierServerServices = body.containers;
                    vaierServerStatus = body.status;
                } else {
                    vaierServerStatus = 'DOWN';
                }
            } catch (error) {
                console.error('Failed to load Vaier server services:', error);
                vaierServerStatus = 'DOWN';
            }
            displayPeers(peers);
        }

        async function fetchLanServers() {
            try {
                const response = await fetch('/lan-servers');
                if (response.ok) {
                    lanServers = await response.json();
                    displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load LAN servers:', error);
            }
            try {
                const response = await fetch('/docker-services/lan-servers');
                if (response.ok) {
                    const services = await response.json();
                    lanServerServices = Object.fromEntries(services.map(s => [s.name, s]));
                    displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load LAN server services:', error);
            }
        }

        function displayError(message) {
            renderBanner('error-message', 'error', message, 8000);
        }

        function displaySuccess(message) {
            renderBanner('success-message', 'success', message, 5000);
        }

        // Auto-dismiss timer per banner host, so a fresh message cancels the previous one's
        // pending clear instead of having a stale timeout wipe the new banner early.
        const _bannerTimers = {};

        // Safe sink: set the message as text, never HTML, so callers never need to
        // escape and pre-escaped values can't double-escape into &amp; artifacts.
        // dismissMs auto-clears the banner after that delay (omit/0 to leave it sticky).
        function renderBanner(hostId, cls, message, dismissMs) {
            const host = document.getElementById(hostId);
            if (_bannerTimers[hostId]) {
                clearTimeout(_bannerTimers[hostId]);
                delete _bannerTimers[hostId];
            }
            host.innerHTML = '';
            const div = document.createElement('div');
            div.className = cls;
            div.textContent = message;
            host.appendChild(div);
            if (dismissMs) {
                _bannerTimers[hostId] = setTimeout(() => {
                    host.innerHTML = '';
                    delete _bannerTimers[hostId];
                }, dismissMs);
            }
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

        // Builds the inner-edit "Device category" selector + handler for a machine card. `kind` is
        // 'peer' or 'lan' (selects the PATCH endpoint), `idArg` is the value passed to the change
        // handler (peer id or LAN-server name). Mirrors the description/LAN-address inline pattern.
        function deviceCategorySelectHtml(domId, kind, idArg, effectiveCategory, overridden) {
            const selected = overridden ? String(effectiveCategory || '').toUpperCase() : '';
            const options = [{ value: '', label: 'Auto-detect' }].concat(DEVICE_CATEGORIES)
                .map(o => `<option value="${o.value}"${o.value === selected ? ' selected' : ''}>${escapeHtml(o.label)}</option>`)
                .join('');
            // Auto-detect caption: when not overridden, effective == detected category.
            const caption = overridden
                ? ''
                : `<span class="device-cat-auto" id="${domId}-auto">Auto-detected: ${escapeHtml(deviceCategoryLabel(effectiveCategory))}</span>`;
            // kind/idArg ride along as data attributes (HTML-escaped) and are read back from the
            // element in the handler — the operator-controlled name never enters an inline JS string.
            return `<span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
                        <select id="${domId}" class="form-input" style="max-width:200px"
                                data-cat-kind="${escapeHtml(kind)}" data-cat-arg="${escapeHtml(String(idArg))}"
                                onchange="onDeviceCategoryChange(this)">${options}</select>
                        ${caption}
                    </span>`;
        }

        // PATCH the effective category for a machine; empty value clears the override (→ auto).
        async function onDeviceCategoryChange(select) {
            if (!select) return;
            const kind = select.dataset.catKind;
            const idArg = select.dataset.catArg;
            const deviceCategory = select.value; // '' clears the override
            const endpoint = kind === 'lan'
                ? `/lan-servers/${encodeURIComponent(idArg)}/device-category`
                : `/vpn/peers/${encodeURIComponent(idArg)}/device-category`;
            const label = kind === 'lan' ? idArg : peerDisplayName(idArg);
            try {
                const response = await fetch(endpoint, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ deviceCategory })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`Device category saved for "${label}"`);
                if (kind === 'lan') fetchLanServers(); else fetchPeers();
            } catch (error) {
                displayError(`Failed to save device category: ${error.message}`);
            }
        }

        function togglePeer(peerName) {
            const id = cardId(peerName);
            const body    = document.getElementById('body-' + id);
            const chevron = document.getElementById('chevron-' + id);
            if (!body) return;

            if (expandedPeers.has(peerName)) {
                expandedPeers.delete(peerName);
                body.classList.remove('open');
                chevron.classList.remove('open');
            } else {
                expandedPeers.add(peerName);
                body.classList.add('open');
                chevron.classList.add('open');
            }
        }

        function renderServiceList(containers) {
            if (!containers || containers.length === 0)
                return `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">none discovered</span></div>`;
            const rows = containers.map(c => {
                const version = c.version || parseImageTag(c.image);
                return `<div class="service-row">
                    <span class="service-name">${c.containerName}</span>
                    <span class="service-tag">${version}</span>
                </div>`;
            }).join('');
            return `<div class="detail-row">
                <span class="detail-label">Services</span>
                <div class="service-list">
                    <div class="service-list-header">
                        <span>name</span><span>version</span>
                    </div>
                    ${rows}
                </div>
            </div>`;
        }

        // Maps the domain's MachineStatus enum to a CSS status class. The combination logic
        // (reachability + Docker scrape + runsDocker) lives in MachineStatus.forLanServer on
        // the server; the browser only renders.
        const MACHINE_STATUS_CLASS = {
            'OK':       'status-connected',
            'DEGRADED': 'status-degraded',
            'DOWN':     'status-disconnected',
            'UNKNOWN':  'status-neutral',
        };

        function renderLanServerCard(server) {
            const key = 'lan-server:' + server.name;
            const id  = cardId(key);
            const isExpanded = expandedPeers.has(key);
            const services = lanServerServices[server.name];
            const statusClass = MACHINE_STATUS_CLASS[server.status] || 'status-neutral';
            const dockerSlot = server.runsDocker
                ? { kind: 'docker', title: 'Docker auto-discovery enabled', label: 'Docker' }
                : null;
            const portText = server.runsDocker
                ? `${server.lanAddress}:${server.dockerPort}`
                : server.lanAddress;
            const relayRow = server.relayPeerName
                ? `<div class="detail-row"><span class="detail-label">Relay</span><span class="detail-value">via ${escapeHtml(server.relayPeerName)}</span></div>`
                : `<div class="detail-row"><span class="detail-label">Relay</span><span class="detail-value" style="color:var(--red)">no relay covers ${escapeHtml(server.lanAddress)}</span></div>`;
            const servicesHtml = (() => {
                if (!server.runsDocker) return '';
                if (!server.relayPeerName)
                    return `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">no relay peer covers this server's lanAddress</span></div>`;
                if (!services || services.status === 'UNREACHABLE')
                    return `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">unreachable</span></div>`;
                return renderServiceList(services.containers);
            })();
            // One adaptive setup script per host (#249): shown when the host runs Docker or is
            // relay-anchored (so it has routes to install). Server-anchored / orphan hosts get none.
            const isRelayAnchored = server.relayPeerName && server.relayPeerName !== 'Vaier server';
            const scriptBtn = (server.runsDocker || isRelayAnchored)
                ? `<button class="btn btn-small btn-secondary" onclick="showLanSetupScript('${jsArg(server.name)}')" title="Show this host's setup script — opens the Docker API if it runs Docker and installs routes via its relay peer">Setup script</button>`
                : '';

            return `
            <div class="peer-card">
                <div class="peer-header" onclick="togglePeer('${jsArg(key)}')">
                    <div class="peer-header-left">
                        ${machineIconHtml(deviceCategoryIconKind(server.deviceCategory), 'LAN server (no VPN; behind a relay or in the server\'s subnet) · ' + statusTooltip(statusClass, server.lastSeen), iconStatusClass(statusClass))}
                        <div class="peer-name-block">
                            <div class="peer-name-row">
                                <span class="peer-name">${escapeHtml(server.name)}</span>
                                ${capabilitySlotsHtml([null, dockerSlot])}
                            </div>
                            ${server.description ? `<div class="peer-desc" title="${escapeHtml(server.description)}">${escapeHtml(server.description)}</div>` : ''}
                        </div>
                    </div>
                    <span class="peer-chevron ${isExpanded ? 'open' : ''}" id="chevron-${id}">▼</span>
                </div>
                <div class="peer-body ${isExpanded ? 'open' : ''}" id="body-${id}">
                    <div class="peer-details">
                        <div class="detail-row">
                            <span class="detail-label">Name</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="lan-name-${id}" class="form-input" style="flex:1;min-width:0;max-width:240px"
                                       value="${escapeHtml(server.name)}"
                                       data-original="${escapeHtml(server.name)}"
                                       oninput="onLanServerNameInput('${jsArg(server.name)}')">
                                <button class="btn btn-primary" id="lan-name-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="saveLanServerName('${jsArg(server.name)}')">Save</button>
                            </span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Description</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="lan-desc-${id}" class="form-input" style="flex:1;min-width:0;max-width:320px"
                                       maxlength="200"
                                       value="${escapeHtml(server.description || '')}"
                                       data-original="${escapeHtml(server.description || '')}"
                                       placeholder="e.g. Synology NAS in the closet"
                                       oninput="onLanServerDescriptionInput('${jsArg(server.name)}')">
                                <button class="btn btn-primary" id="lan-desc-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="saveLanServerDescription('${jsArg(server.name)}')">Save</button>
                            </span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Device category</span>
                            ${deviceCategorySelectHtml('lan-cat-' + id, 'lan', server.name, server.deviceCategory, server.deviceCategoryOverridden)}
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">LAN address</span>
                            <span class="detail-value">${escapeHtml(portText)}</span>
                        </div>
                        ${relayRow}
                        <div class="detail-row">
                            <span class="detail-label">Last Seen</span>
                            <span class="detail-value" id="last-seen-detail-${id}">${lastSeenAbsolute(server.lastSeen)}</span>
                        </div>
                        ${servicesHtml}
                    </div>
                    <div class="peer-actions-row">
                        <div class="peer-actions-left">
                            ${scriptBtn}
                        </div>
                        <button class="btn btn-small btn-danger" onclick="deleteLanServer('${jsArg(server.name)}')">Delete</button>
                    </div>
                </div>
            </div>`;
        }

        function renderVaierServerCard() {
            const key = '__vaier-server__';
            const id  = cardId(key);
            const isExpanded = expandedPeers.has(key);
            // The Vaier-server endpoint returns a MachineStatus already computed in the domain.
            const statusClass = MACHINE_STATUS_CLASS[vaierServerStatus] || 'status-neutral';
            const servicesHtml = vaierServerStatus === 'DOWN'
                ? `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">Docker engine unreachable</span></div>`
                : renderServiceList(vaierServerServices);
            // The server's LAN/VPC CIDR comes from VAIER_SERVER_LAN_CIDR or EC2 IMDS (#204). Show
            // it as a read-only row so the operator can see the subnet the server is publishing
            // and registering LAN servers in. Omitted when unresolved (no env, no IMDS).
            const lanCidrHtml = _serverLocation && _serverLocation.lanCidr
                ? `<div class="detail-row">
                       <span class="detail-label">LAN CIDR</span>
                       <span class="detail-value" style="font-family:var(--mono)" title="The IPv4 CIDR the Vaier server itself sits on. Used to route, register, and publish LAN machines on this subnet. Override via the VAIER_SERVER_LAN_CIDR env var.">${escapeHtml(_serverLocation.lanCidr)}</span>
                   </div>`
                : '';
            const placeHtml = _serverLocation && (_serverLocation.city || _serverLocation.country)
                ? `<div class="detail-row">
                       <span class="detail-label">Location</span>
                       <span class="detail-value">${escapeHtml([_serverLocation.city, _serverLocation.country].filter(Boolean).join(', '))}</span>
                   </div>`
                : '';
            const hostHtml = _serverLocation && _serverLocation.publicHost
                ? `<div class="detail-row">
                       <span class="detail-label">Public host</span>
                       <span class="detail-value" style="font-family:var(--mono)">${escapeHtml(_serverLocation.publicHost)}</span>
                   </div>`
                : '';
            return `
            <div class="peer-card">
                <div class="peer-header" onclick="togglePeer('${key}')">
                    <div class="peer-header-left">
                        ${machineIconHtml('vaier', 'Vaier server · ' + statusTooltip(statusClass, null), iconStatusClass(statusClass))}
                        <span class="peer-name">Vaier server</span>
                        ${capabilitySlotsHtml([null, { kind: 'docker', title: 'Vaier server Docker engine', label: 'Docker' }])}
                    </div>
                    <span class="peer-chevron ${isExpanded ? 'open' : ''}" id="chevron-${id}">▼</span>
                </div>
                <div class="peer-body ${isExpanded ? 'open' : ''}" id="body-${id}">
                    <div class="peer-details">
                        ${hostHtml}
                        ${lanCidrHtml}
                        ${placeHtml}
                        ${servicesHtml}
                    </div>
                </div>
            </div>`;
        }

        function renderPeerCard(peer) {
            // Connectivity is decided server-side by the domain rule VpnClient.isConnected().
            const isConnected = !!peer.connected;
            const statusClass = isConnected ? 'status-connected' : 'status-disconnected';
            // tunnelIp, isServer, isClient, isRelay and availableArtifacts come from the domain
            // (GetVpnPeersUseCase / PeerArtifact.forPeerType). The browser only renders.
            const vpnIp      = peer.tunnelIp;
            const id         = cardId(peer.id);
            const isExpanded = expandedPeers.has(peer.id);
            const peerType   = peer.peerType || 'UBUNTU_SERVER';
            const isServer   = peer.isServer;
            const artifacts  = new Set(peer.availableArtifacts || []);

            const services = peerServices[peer.id];
            const servicesHtml = (() => {
                if (!isServer) return '';
                if (!isConnected)
                    return `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">not connected</span></div>`;
                if (!services || services.status === 'UNREACHABLE')
                    return `<div class="detail-row"><span class="detail-label">Services</span><span class="detail-value" style="color:var(--text-dim)">unreachable</span></div>`;
                return renderServiceList(services.containers);
            })();
            const outdated = !!(services && services.wireguardOutdated);
            const expectedImage = services && services.wireguardExpectedImage;
            const outdatedRow = outdated
                ? `<div class="detail-row">
                       <span class="detail-label">WireGuard</span>
                       <span class="detail-value">
                           <span class="badge badge-warning" title="To upgrade, edit the 'image:' tag on the peer's docker-compose.yml to '${expectedImage}' and run 'docker compose up -d' — don't overwrite the whole file with 'compose' if the peer has customizations (network_mode, volumes, TZ).">out of date</span>
                       </span>
                   </div>`
                : '';

            const isRelay  = peer.isRelay;
            const iconKind = peerTypeIconKind(peerType);
            const typeTooltip = ({
                'mobile': 'Mobile client (VPN)',
                'laptop': 'Windows client (VPN)',
                'server': 'VPN server'
            })[iconKind];
            const relaySlot  = isRelay ? { kind: 'relay',  title: `Relay for LAN ${peer.lanCidr}`, label: 'Relay' } : null;
            const dockerSlot = isServer ? { kind: 'docker', title: 'Runs Docker', label: 'Docker' } : null;

            // #202: WireGuard configs are one-shot — the secrets are delivered exactly once,
            // at create time, inside the create-success modal. The row no longer surfaces
            // config/compose/script/qr buttons that would hit (now-gated) GET endpoints; to
            // recover a fresh config the operator regenerates the peer (delete + recreate with
            // the same name/IP/lanCidr/lanAddress/description).

            return `
            <div class="peer-card">
                <div class="peer-header" onclick="togglePeer('${peer.id}')">
                    <div class="peer-header-left">
                        ${machineIconHtml(deviceCategoryIconKind(peer.deviceCategory), typeTooltip + ' · ' + statusTooltip(statusClass, peer.latestHandshake), iconStatusClass(statusClass), 'hdr-icon-' + id)}
                        <div class="peer-name-block">
                            <div class="peer-name-row">
                                <span class="peer-name">${escapeHtml(peer.name)}</span>
                                ${peer.configOutOfDate ? `<span class="config-stale-warn" title="Out-of-date config — this peer's installed config no longer matches what Vaier would generate now (config logic or server inputs such as the endpoint, VPN subnet, or server LAN CIDR have changed). What to do: click Reissue config below (keys preserved), then reinstall the delivered config on the peer.">⚠</span>` : ''}
                                ${capabilitySlotsHtml([relaySlot, dockerSlot])}
                            </div>
                            ${peer.description ? `<div class="peer-desc" title="${escapeHtml(peer.description)}">${escapeHtml(peer.description)}</div>` : ''}
                        </div>
                    </div>
                    <span class="peer-chevron ${isExpanded ? 'open' : ''}" id="chevron-${id}">▼</span>
                </div>
                <div class="peer-body ${isExpanded ? 'open' : ''}" id="body-${id}">
                    <div class="peer-details">
                        <div class="detail-row">
                            <span class="detail-label">Name</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="peer-name-${id}" class="form-input" style="flex:1;min-width:0;max-width:240px"
                                       value="${escapeHtml(peer.name)}"
                                       data-original="${escapeHtml(peer.name)}"
                                       oninput="onPeerNameInput('${peer.id}')">
                                <button class="btn btn-primary" id="peer-name-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="savePeerName('${peer.id}')">Save</button>
                            </span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Description</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="peer-desc-${id}" class="form-input" style="flex:1;min-width:0;max-width:320px"
                                       maxlength="200"
                                       value="${escapeHtml(peer.description || '')}"
                                       data-original="${escapeHtml(peer.description || '')}"
                                       placeholder="e.g. Home media server"
                                       oninput="onDescriptionInput('${peer.id}')">
                                <button class="btn btn-primary" id="peer-desc-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="saveDescription('${peer.id}')">Save</button>
                            </span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Device category</span>
                            ${deviceCategorySelectHtml('peer-cat-' + id, 'peer', peer.id, peer.deviceCategory, peer.deviceCategoryOverridden)}
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">IP Address</span>
                            <span class="detail-value">${vpnIp}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Public Key</span>
                            <span class="detail-value">${peer.publicKey}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Allowed IPs</span>
                            <span class="detail-value">${peer.allowedIps}</span>
                        </div>
                        ${peer.endpointIp ? `
                        <div class="detail-row">
                            <span class="detail-label">Endpoint</span>
                            <span class="detail-value">${peer.endpointIp}:${peer.endpointPort}</span>
                        </div>` : ''}
                        <div class="detail-row">
                            <span class="detail-label">Last Seen</span>
                            <span class="detail-value" id="last-seen-detail-${id}">${lastSeenAbsolute(peer.latestHandshake)}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Rx / Tx</span>
                            <span class="detail-value" id="rxtx-${id}">${formatBytes(peer.transferRx)} / ${formatBytes(peer.transferTx)}</span>
                        </div>
                        ${outdatedRow}
                        ${isServer ? `
                        <div class="detail-row">
                            <span class="detail-label">LAN CIDR</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="lan-cidr-${id}" class="form-input" style="flex:1;min-width:0;max-width:240px"
                                       value="${peer.lanCidr || ''}"
                                       data-original="${peer.lanCidr || ''}"
                                       placeholder="e.g. 192.168.1.0/24"
                                       oninput="onLanCidrInput('${peer.id}')">
                                <button class="btn btn-primary" id="lan-cidr-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="saveLanCidr('${peer.id}')">Save</button>
                            </span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">LAN address</span>
                            <span class="detail-value" style="display:flex;gap:8px;align-items:center;flex-wrap:nowrap">
                                <input type="text" id="lan-addr-${id}" class="form-input" style="flex:1;min-width:0;max-width:240px"
                                       value="${peer.lanAddress || ''}"
                                       data-original="${peer.lanAddress || ''}"
                                       placeholder="e.g. 192.168.1.50"
                                       oninput="onLanAddressInput('${peer.id}')">
                                <button class="btn btn-primary" id="lan-addr-save-${id}"
                                        style="flex-shrink:0" disabled
                                        onclick="saveLanAddress('${peer.id}')">Save</button>
                            </span>
                        </div>` : ''}
                        ${servicesHtml}
                    </div>
                    <div class="peer-actions-row">
                        <div class="peer-actions-left">
                            <button class="btn btn-small ${peer.configOutOfDate ? 'btn-primary' : 'btn-secondary'}" onclick="reissuePeerConfig('${peer.id}')"
                                    title="Reissue — re-render this peer's config from the current logic, keeping its keypair so the live tunnel is untouched. Use it to recover a lost config or refresh one that's out of date (⚠), then reinstall the delivered config on the peer.">Reissue config</button>
                            <button class="btn btn-small btn-secondary" onclick="confirmRegeneratePeer('${peer.id}')"
                                    title="Regenerate — rotate the WireGuard keypair and deliver a fresh config once. Use it only to replace a compromised config; the old config stops working immediately and must be reinstalled on the peer.">Regenerate</button>
                        </div>
                        <button class="btn btn-small btn-danger" onclick="confirmDeletePeer('${peer.id}')">Delete</button>
                    </div>
                </div>
            </div>`;
        }

        function displayPeers(peers) {
            const container = document.getElementById('peers-container');

            const byName = (a, b) => a.name.localeCompare(b.name);

            // peer.isServer / peer.isClient are computed in the domain from MachineType. The
            // browser doesn't enumerate enum constants.
            const serverEntries = [
                ...peers.filter(p => p.isServer).map(p => ({ kind: 'peer', name: p.name, data: p })),
                ...lanServers.map(s => ({ kind: 'lan', name: s.name, data: s }))
            ].sort(byName);
            const clients = peers.filter(p => p.isClient).sort(byName);

            let html = `<div class="section-header">Servers</div>`;
            html += renderVaierServerCard();
            html += serverEntries.map(e =>
                e.kind === 'peer' ? renderPeerCard(e.data) : renderLanServerCard(e.data)
            ).join('');

            if (clients.length > 0) {
                html += `<div class="section-header">Clients</div>`;
                html += clients.map(renderPeerCard).join('');
            }

            container.innerHTML = html;
            refreshPeerMap();
            renderNetworkDiagram();
        }

        function showCreatePeerModal() {
            document.getElementById('createPeerModal').classList.add('active');
            document.getElementById('peerName').value = '';
            document.getElementById('peerDescription').value = '';
            document.getElementById('peerType').value = 'UBUNTU_SERVER';
            document.getElementById('lanCidr').value = '';
            document.getElementById('lanAddress').value = '';
            document.getElementById('lanAddressHint').textContent = '';
            document.getElementById('peerNameHint').textContent = '';
            lanAddressRoutable = null;
            document.getElementById('runsDocker').checked = false;
            document.getElementById('dockerPort').value = '2375';
            // Reset the in-modal LAN scanner: invalidate any in-flight poll chain and clear the picker.
            _modalScanToken++;
            document.getElementById('lanScanModalBtn').textContent = '⌕ Scan LAN for machines';
            document.getElementById('lanScanModalBtn').disabled = false;
            document.getElementById('lanScanModalStatus').textContent = '';
            document.getElementById('lanScanModalList').style.display = 'none';
            document.getElementById('lanScanModalList').innerHTML = '';
            _scanPickedCategory = null;
            _scanPickedIp = null;
            onPeerTypeChange();
        }

        function onPeerTypeChange() {
            const type = document.getElementById('peerType').value;
            const isUbuntuServer = type === 'UBUNTU_SERVER';
            const isLanServer    = type === 'LAN_SERVER';

            // LAN CIDR: only for Ubuntu server (Linux relay-host concept).
            document.getElementById('lanCidrGroup').style.display = isUbuntuServer ? '' : 'none';

            // LAN address: optional for Ubuntu server, required for LAN_SERVER.
            const showLanAddr = isUbuntuServer || isLanServer;
            document.getElementById('lanAddressGroup').style.display = showLanAddr ? '' : 'none';
            document.getElementById('lanAddressLabel').textContent =
                isLanServer ? 'LAN address' : 'LAN address (optional)';
            document.getElementById('lanAddressHelp').style.display = isLanServer ? 'none' : '';

            // In-modal LAN scanner (#246, Enterprise): only for LAN_SERVER — the one type where you
            // discover a remote host behind a relay. An Ubuntu server's LAN address is its own IP.
            syncModalScanControl(isLanServer);

            // Runs Docker / Docker port: only for LAN_SERVER.
            document.getElementById('runsDockerGroup').style.display = isLanServer ? '' : 'none';
            onRunsDockerChange();

            // Live relay-CIDR hint only fires when the LAN address field is visible and the type
            // is LAN_SERVER (the field is informational for Ubuntu server, required for LAN_SERVER).
            updateLanAddressHint();
        }

        function onRunsDockerChange() {
            const isLanServer = document.getElementById('peerType').value === 'LAN_SERVER';
            const runsDocker  = document.getElementById('runsDocker').checked;
            document.getElementById('dockerPortGroup').style.display =
                (isLanServer && runsDocker) ? '' : 'none';
            updateCreatePeerValidity();
        }

        function hideCreatePeerModal() {
            document.getElementById('createPeerModal').classList.remove('active');
            if (lanAnchorTimer) { clearTimeout(lanAnchorTimer); lanAnchorTimer = null; }
        }

        // Machine names are unique across Vaier (#284). The full machine list is already loaded
        // client-side (peers + lanServers), so we flag a clash the instant it's typed — same live
        // red-hint treatment as an unroutable LAN address. The server still enforces it (409).
        function machineNameExists(name) {
            const n = name.trim().toLowerCase();
            if (!n) return false;
            return peers.some(p => (p.name || '').trim().toLowerCase() === n)
                || lanServers.some(s => (s.name || '').trim().toLowerCase() === n);
        }

        function updatePeerNameHint() {
            const hint = document.getElementById('peerNameHint');
            const name = document.getElementById('peerName').value.trim();
            if (!name) {
                hint.textContent = '';
            } else if (machineNameExists(name)) {
                hint.textContent = `A machine named "${name}" already exists.`;
                hint.style.color = 'var(--red)';
            } else {
                hint.textContent = '';
                hint.style.color = 'var(--text-muted)';
            }
            updateCreatePeerValidity();
        }

        // Enable the Add Machine button only when every field is valid, so the operator can't submit
        // a request the server would reject. Re-run from every field's input/change handler.
        function updateCreatePeerValidity() {
            const submitBtn = document.getElementById('createPeerSubmitBtn');
            if (!submitBtn) return;
            const type = document.getElementById('peerType').value;
            const name = document.getElementById('peerName').value.trim();

            let valid = name !== '' && !machineNameExists(name);

            if (valid && type === 'LAN_SERVER') {
                const lanAddress = document.getElementById('lanAddress').value.trim();
                // lanAddressRoutable is the server's verdict on the typed address (null while empty
                // or still being checked) — a LAN server isn't valid until it's confirmed routable.
                if (!lanAddress || lanAddressRoutable !== true) valid = false;
                if (valid && document.getElementById('runsDocker').checked) {
                    const port = parseInt(document.getElementById('dockerPort').value);
                    if (!port || port < 1 || port > 65535) valid = false;
                }
            }
            submitBtn.disabled = !valid;
        }

        // Whether/how a typed LAN address is routable (relay peer or the Vaier server itself) is a
        // domain decision — we ask the server (`GET /lan-servers/lan-anchor`) rather than doing any
        // CIDR math here. Debounced, with a sequence guard so a stale response can't overwrite a newer one.
        let lanAnchorTimer = null;
        let lanAnchorSeq = 0;
        // The server's routability verdict on the typed LAN address: true/false once known, null
        // while empty or still being checked. Gates the Add Machine button for LAN servers.
        let lanAddressRoutable = null;

        function updateLanAddressHint() {
            const hint = document.getElementById('lanAddressHint');
            const type = document.getElementById('peerType').value;
            const ip   = document.getElementById('lanAddress').value.trim();
            if (lanAnchorTimer) { clearTimeout(lanAnchorTimer); lanAnchorTimer = null; }
            lanAddressRoutable = null;
            // Hint is informational only for VPN peers; show routability only for LAN_SERVER.
            if (type !== 'LAN_SERVER' || !ip) { hint.textContent = ''; updateCreatePeerValidity(); return; }
            hint.textContent = 'Checking…';
            hint.style.color = 'var(--text-muted)';
            updateCreatePeerValidity();   // keep the button disabled while the check is in flight
            const seq = ++lanAnchorSeq;
            lanAnchorTimer = setTimeout(async () => {
                let anchor = null;
                try {
                    const resp = await fetch(`/lan-servers/lan-anchor?address=${encodeURIComponent(ip)}`);
                    if (resp.ok) anchor = await resp.json();
                } catch (e) { /* leave anchor null */ }
                if (seq !== lanAnchorSeq) return;   // a newer keystroke superseded this lookup
                if (anchor === null) { hint.textContent = ''; updateCreatePeerValidity(); return; }
                if (anchor.routable) {
                    lanAddressRoutable = true;
                    hint.textContent = `Routes via ${anchor.routedVia}${anchor.cidr ? ` (${anchor.cidr})` : ''}`;
                    hint.style.color = 'var(--text-muted)';
                } else {
                    lanAddressRoutable = false;
                    hint.textContent = `${ip} isn't routable — not inside any relay peer's lanCidr or the Vaier server's own subnet.`;
                    hint.style.color = 'var(--red)';
                }
                updateCreatePeerValidity();
            }, 250);
        }

        async function deleteLanServer(name) {
            if (!confirm(`Remove LAN server "${name}"? Any published services routing to it will also be removed (their DNS records and reverse-proxy routes).`)) return;
            // The delete cascades into published-service cleanup (Traefik route + DNS), which can
            // take a few seconds — show the busy overlay so the operator isn't tempted to click again.
            showBusy(`Removing "${name}"…`);
            try {
                const response = await fetch(`/lan-servers/${encodeURIComponent(name)}`, { method: 'DELETE' });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`LAN server "${name}" removed`);
                fetchLanServers();
            } catch (error) {
                displayError(`Failed to remove LAN server: ${error.message}`);
            } finally {
                hideBusy();
            }
        }

        // Busy indicator (#202) — create + regenerate hit WireGuard / keygen / config writes and
        // can take a few seconds on slow hosts. Without immediate feedback the user assumes
        // the page is dead.
        function showBusy(message) {
            document.getElementById('busyMessage').textContent = message;
            document.getElementById('busyModal').classList.add('active');
        }
        function hideBusy() {
            document.getElementById('busyModal').classList.remove('active');
        }

        async function createPeer() {
            const name = document.getElementById('peerName').value.trim();
            const type = document.getElementById('peerType').value;
            const description = document.getElementById('peerDescription').value.trim() || null;
            if (!name) { displayError('Please enter a name'); return; }

            const submitBtn = document.getElementById('createPeerSubmitBtn');
            const cancelBtn = document.getElementById('createPeerCancelBtn');
            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            submitBtn.textContent = 'Adding…';

            try {
                if (type === 'LAN_SERVER') {
                    const lanAddress = document.getElementById('lanAddress').value.trim();
                    const runsDocker = document.getElementById('runsDocker').checked;
                    const dockerPort = runsDocker
                        ? parseInt(document.getElementById('dockerPort').value)
                        : null;
                    if (!lanAddress) { displayError('Please enter a LAN address'); return; }
                    if (runsDocker && (!dockerPort || dockerPort < 1 || dockerPort > 65535)) {
                        displayError('Please enter a valid Docker port (1-65535)'); return;
                    }
                    // Keep the modal open during the request and close it only on success, so a
                    // rejection (e.g. a 409 name conflict, #284) leaves the operator's input in
                    // place to correct instead of vanishing. The busy overlay stacks on top.
                    // Carry the scanned host's device category into the registration, but only if the
                    // address still matches the picked host (a later manual edit drops the stale value).
                    const deviceCategory = (_scanPickedIp && _scanPickedIp === lanAddress)
                        ? _scanPickedCategory : null;
                    showBusy(`Adding "${name}"…`);
                    const response = await fetch('/lan-servers', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ name, lanAddress, runsDocker, dockerPort, description, deviceCategory })
                    });
                    if (response.status === 400) {
                        displayError(`${lanAddress} isn't routable — not inside any relay peer's lanCidr or the Vaier server's own subnet.`);
                        return;
                    }
                    if (!response.ok) {
                        // Surface the ApiError envelope's message (e.g. a 409 name conflict, #284).
                        const err = await response.json().catch(() => ({ message: `HTTP ${response.status}` }));
                        throw new Error(err.message || `HTTP ${response.status}`);
                    }
                    hideCreatePeerModal();
                    displaySuccess(`LAN server "${name}" added`);
                    fetchLanServers();
                } else {
                    const lanCidr     = document.getElementById('lanCidr').value.trim() || null;
                    const lanAddress  = document.getElementById('lanAddress').value.trim() || null;
                    // Keep the modal open during the request and close it only on success, so a
                    // rejection (e.g. a 409 name conflict, #284) leaves the operator's input in
                    // place to correct instead of vanishing. The busy overlay stacks on top.
                    showBusy(`Adding "${name}"…`);
                    const response = await fetch('/vpn/peers', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ name, peerType: type, lanCidr, lanAddress, description })
                    });
                    if (!response.ok) {
                        // Surface the ApiError envelope's message (e.g. a 409 name conflict, #284).
                        const err = await response.json().catch(() => ({ message: `HTTP ${response.status}` }));
                        throw new Error(err.message || `HTTP ${response.status}`);
                    }
                    hideCreatePeerModal();
                    const createdPeer = await response.json();
                    displaySuccess(`"${createdPeer.name}" added`);
                    showPeerDetails(createdPeer);
                    fetchPeers();
                }
            } catch (error) {
                displayError(`Failed to add machine: ${error.message}`);
            } finally {
                hideBusy();
                cancelBtn.disabled = false;
                submitBtn.textContent = 'Add Machine';
                // Re-evaluate rather than force-enable: if the submit failed and the modal stayed
                // open (e.g. a name now shown as taken), the button stays correctly disabled.
                updateCreatePeerValidity();
            }
        }

        function onLanAddressInput(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`lan-addr-${id}`);
            const btn = document.getElementById(`lan-addr-save-${id}`);
            if (!input || !btn) return;
            btn.disabled = input.value.trim() === (input.dataset.original || '');
        }

        async function saveLanAddress(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`lan-addr-${id}`);
            const btn = document.getElementById(`lan-addr-save-${id}`);
            const lanAddress = input.value.trim();
            btn.disabled = true;
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}/lan-address`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ lanAddress })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                input.dataset.original = lanAddress;
                displaySuccess(`LAN address saved for "${peerDisplayName(peerId)}"`);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to save LAN address: ${error.message}`);
                onLanAddressInput(peerId);
            }
        }

        function onDescriptionInput(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`peer-desc-${id}`);
            const btn = document.getElementById(`peer-desc-save-${id}`);
            if (!input || !btn) return;
            btn.disabled = input.value.trim() === (input.dataset.original || '');
        }

        async function saveDescription(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`peer-desc-${id}`);
            const btn = document.getElementById(`peer-desc-save-${id}`);
            const description = input.value.trim();
            btn.disabled = true;
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}/description`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ description })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                input.dataset.original = description;
                displaySuccess(`Description saved for "${peerDisplayName(peerId)}"`);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to save description: ${error.message}`);
                onDescriptionInput(peerId);
            }
        }

        function onPeerNameInput(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`peer-name-${id}`);
            const btn = document.getElementById(`peer-name-save-${id}`);
            if (!input || !btn) return;
            const v = input.value.trim();
            btn.disabled = v === '' || v === (input.dataset.original || '');
        }

        // Renames the peer's editable display name only — the immutable id (config dir,
        // REST path) never moves. Saving a blank name is rejected by the input guard.
        async function savePeerName(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`peer-name-${id}`);
            const btn = document.getElementById(`peer-name-save-${id}`);
            const newName = input.value.trim();
            if (!newName) return;
            btn.disabled = true;
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ newName })
                });
                if (response.status === 409) {
                    displayError(`A machine named "${newName}" already exists`);
                    onPeerNameInput(peerId);
                    return;
                }
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`Renamed to "${newName}"`);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to rename machine: ${error.message}`);
                onPeerNameInput(peerId);
            }
        }

        function onLanServerNameInput(serverName) {
            const id = cardId('lan-server:' + serverName);
            const input = document.getElementById(`lan-name-${id}`);
            const btn = document.getElementById(`lan-name-save-${id}`);
            if (!input || !btn) return;
            const v = input.value.trim();
            btn.disabled = v === '' || v === (input.dataset.original || '');
        }

        async function saveLanServerName(serverName) {
            const id = cardId('lan-server:' + serverName);
            const input = document.getElementById(`lan-name-${id}`);
            const btn = document.getElementById(`lan-name-save-${id}`);
            const newName = input.value.trim();
            if (!newName) return;
            btn.disabled = true;
            try {
                const response = await fetch(`/lan-servers/${encodeURIComponent(serverName)}`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ newName })
                });
                if (response.status === 409) {
                    displayError(`A machine named "${newName}" already exists`);
                    onLanServerNameInput(serverName);
                    return;
                }
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`Renamed "${serverName}"`);
                fetchLanServers();
            } catch (error) {
                displayError(`Failed to rename LAN server: ${error.message}`);
                onLanServerNameInput(serverName);
            }
        }

        function onLanServerDescriptionInput(serverName) {
            const id = cardId('lan-server:' + serverName);
            const input = document.getElementById(`lan-desc-${id}`);
            const btn = document.getElementById(`lan-desc-save-${id}`);
            if (!input || !btn) return;
            btn.disabled = input.value.trim() === (input.dataset.original || '');
        }

        async function saveLanServerDescription(serverName) {
            const id = cardId('lan-server:' + serverName);
            const input = document.getElementById(`lan-desc-${id}`);
            const btn = document.getElementById(`lan-desc-save-${id}`);
            const description = input.value.trim();
            btn.disabled = true;
            try {
                const response = await fetch(`/lan-servers/${encodeURIComponent(serverName)}/description`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ description })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                input.dataset.original = description;
                displaySuccess(`Description saved for "${serverName}"`);
                fetchLanServers();
            } catch (error) {
                displayError(`Failed to save description: ${error.message}`);
                onLanServerDescriptionInput(serverName);
            }
        }

        function onLanCidrInput(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`lan-cidr-${id}`);
            const btn = document.getElementById(`lan-cidr-save-${id}`);
            if (!input || !btn) return;
            btn.disabled = input.value.trim() === (input.dataset.original || '');
        }

        async function saveLanCidr(peerId) {
            const id = cardId(peerId);
            const input = document.getElementById(`lan-cidr-${id}`);
            const btn = document.getElementById(`lan-cidr-save-${id}`);
            const lanCidr = input.value.trim();
            btn.disabled = true;
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}/lan-cidr`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ lanCidr })
                });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                input.dataset.original = lanCidr;
                displaySuccess(`LAN CIDR saved for "${peerDisplayName(peerId)}"`);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to save LAN CIDR: ${error.message}`);
                onLanCidrInput(peerId);
            }
        }

        /**
         * Builds a download button that serves inline payload (#202) instead of hitting the
         * (now-gated) GET endpoints. The blob is created on click — not pre-allocated — so
         * unused buttons don't churn the GC.
         */
        function inlineDownloadButton(label, filename, mimeType, content, primary) {
            if (!content) return '';
            const cssEscapedFilename = filename.replace(/'/g, "\\'");
            const cls = primary ? 'btn btn-primary' : 'btn btn-secondary';
            return `<button class="${cls}" onclick="downloadInline('${cssEscapedFilename}', '${mimeType}', this)" data-content-ref="${filename}">${label}</button>`;
        }

        // Map of filename → content for the most recent show-once render. Cleared when the
        // modal closes so the secret doesn't linger in memory.
        let _showOncePayloads = {};

        function downloadInline(filename, mimeType, btn) {
            const content = _showOncePayloads[filename];
            if (!content) return;
            const blob = new Blob([content], { type: mimeType });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            // Revoke after the browser has had a chance to start the download.
            setTimeout(() => URL.revokeObjectURL(url), 1500);
        }

        // Which artifact is the 80/20 next step for this peer type (#51). The matching download
        // button gets primary styling; the others stay secondary so the operator's eye lands on
        // the right one without the alternatives being hidden.
        function recommendedArtifactFor(peerType) {
            switch (peerType) {
                case 'MOBILE_CLIENT':  return 'qr-code';
                case 'UBUNTU_SERVER':  return 'setup-script';
                case 'WINDOWS_SERVER': // intentional fall-through — same .conf import path as Windows client
                case 'WINDOWS_CLIENT': return 'wg-config';
                default:               return 'wg-config';
            }
        }

        function showOnceDownloadButtons(peer) {
            const peerName = peer.name;
            const btns = [];
            _showOncePayloads = {};
            const recommended = recommendedArtifactFor(peer.peerType);

            const confName = `${peerName}.conf`;
            _showOncePayloads[confName] = peer.configFile;
            btns.push(inlineDownloadButton('↓ .conf file', confName, 'text/plain', peer.configFile,
                recommended === 'wg-config'));

            if (peer.dockerCompose) {
                _showOncePayloads['docker-compose.yml'] = peer.dockerCompose;
                btns.push(inlineDownloadButton('↓ docker-compose.yml', 'docker-compose.yml',
                    'application/x-yaml', peer.dockerCompose, recommended === 'docker-compose'));
            }
            if (peer.setupScript) {
                const scriptName = `setup-${peerName}.sh`;
                _showOncePayloads[scriptName] = peer.setupScript;
                btns.push(inlineDownloadButton(`↓ ${scriptName}`, scriptName,
                    'application/x-sh', peer.setupScript, recommended === 'setup-script'));
            }
            return btns.join('');
        }

        // Per-type guidance shown at the top of the create-success modal (#51). Keep it short:
        // one sentence on what to do, with a concrete next step the operator can act on right now.
        function gettingStartedHtml(peer) {
            const peerName = escapeHtml(peer.name);
            switch (peer.peerType) {
                case 'MOBILE_CLIENT':
                    return `
                        <div class="getting-started">
                            <div class="getting-started-title">Next step</div>
                            Open the WireGuard mobile app, tap <strong>+</strong> → <strong>Scan from QR code</strong>,
                            and scan the code above. Then toggle the tunnel on.
                        </div>`;
                case 'UBUNTU_SERVER':
                    return `
                        <div class="getting-started">
                            <div class="getting-started-title">Next step</div>
                            Download <code>setup-${peerName}.sh</code> below, copy it to your Ubuntu host,
                            and run <code>bash setup-${peerName}.sh</code>. The script installs Docker,
                            writes the WireGuard config, and starts the tunnel.
                        </div>`;
                case 'WINDOWS_SERVER':
                case 'WINDOWS_CLIENT':
                    return `
                        <div class="getting-started">
                            <div class="getting-started-title">Next step</div>
                            Download <code>${peerName}.conf</code> below, open the
                            <strong>WireGuard for Windows</strong> client, click
                            <strong>Import tunnel(s) from file</strong>, and pick the downloaded file.
                            Activate the tunnel from the same window.
                        </div>`;
                default:
                    return '';
            }
        }

/**
         * Show-once success modal (#202). Renders the WireGuard config text, an inline QR
         * (data: URL from the create response's base64 PNG), and download buttons that build
         * blobs from the inline payload. The five GET endpoints are gated server-side, so the
         * UI never re-fetches: the operator must save these artefacts now or regenerate the
         * peer to get a fresh keypair.
         */
        function showPeerDetails(peer) {
            const qrBlock = peer.qrCodePngBase64
                ? `<div class="qr-canvas-container"><img src="data:image/png;base64,${peer.qrCodePngBase64}" width="256" height="256" alt="WireGuard QR code"></div>
                   <p class="qr-hint">Scan with WireGuard mobile app</p>`
                : '';
            document.getElementById('peerDetailsContent').innerHTML = `
                ${gettingStartedHtml(peer)}
                <div class="show-once-warning">
                    <strong>Save these now — they will not be shown again.</strong>
                    For your security, this config is delivered exactly once and won't be shown again on its own.
                    Lost it? Use <em>Reissue</em> on the peer's row to deliver a fresh copy with the <em>same keys</em> (the live tunnel keeps working).
                    Use <em>Regenerate</em> only to rotate the keypair — that invalidates the old config.
                </div>
                <div class="peer-details">
                    <div class="detail-row"><span class="detail-label">Name</span><span class="detail-value">${escapeHtml(peer.name)}</span></div>
                    <div class="detail-row"><span class="detail-label">IP Address</span><span class="detail-value">${peer.ipAddress}</span></div>
                    <div class="detail-row"><span class="detail-label">Public Key</span><span class="detail-value">${peer.publicKey}</span></div>
                </div>
                ${qrBlock}
                <div class="config-preview">
                    <div class="config-preview-header">
                        <strong>Client Configuration</strong>
                        <button class="btn-copy" id="copyBtn" onclick="copyConfig()">copy</button>
                    </div>
                    <pre id="configPre">${peer.configFile}</pre>
                </div>
                <div style="margin-top:1rem;display:flex;gap:0.5rem;flex-wrap:wrap;">
                    ${showOnceDownloadButtons(peer)}
                </div>`;
            document.getElementById('peerDetailsModal').classList.add('active');
        }

        function hidePeerDetailsModal() {
            document.getElementById('peerDetailsModal').classList.remove('active');
            // Drop the secret-bearing payloads from memory once the modal closes.
            _showOncePayloads = {};
        }

        // --- Setup-script dialog (shared between VPN peer + LAN docker host) ---
        let _scriptDialogDownloadUrl = null;

        function showSetupScriptDialog(opts) {
            document.getElementById('scriptDialogTitle').textContent = opts.title;
            document.getElementById('scriptDialogDesc').textContent  = opts.description || '';
            document.getElementById('scriptDialogCmd').textContent   = opts.curlCommand;
            const copyBtn = document.getElementById('scriptDialogCopyBtn');
            copyBtn.textContent = 'copy';
            copyBtn.classList.remove('copied');
            const note = document.getElementById('scriptDialogAuthNote');
            if (opts.requiresAuth) {
                note.textContent = 'This URL requires a Vaier login — curl from a fresh host will be redirected to the login page. '
                    + 'Use the Download button (your browser sends the auth cookie) and scp the file to the target host instead.';
                note.style.display = '';
            } else {
                note.style.display = 'none';
            }
            _scriptDialogDownloadUrl = opts.downloadUrl;
            document.getElementById('scriptDialogModal').classList.add('active');
        }

        function hideScriptDialog() {
            document.getElementById('scriptDialogModal').classList.remove('active');
        }

        function copyScriptDialogCmd() {
            const text = document.getElementById('scriptDialogCmd').textContent;
            const btn  = document.getElementById('scriptDialogCopyBtn');
            navigator.clipboard.writeText(text).then(() => {
                btn.textContent = 'copied';
                btn.classList.add('copied');
                setTimeout(() => { btn.textContent = 'copy'; btn.classList.remove('copied'); }, 1500);
            }).catch(() => {});
        }

        function scriptDialogDownload() {
            if (_scriptDialogDownloadUrl) window.location.href = _scriptDialogDownloadUrl;
        }

        function showLanSetupScript(name) {
            const url = `${window.location.origin}/lan-servers/${encodeURIComponent(name)}/setup.sh`;
            showSetupScriptDialog({
                title: 'Set up this LAN host',
                description: 'Idempotent — adapts to this host: opens the Docker engine API if it runs '
                    + 'Docker (native + snap) and installs persistent routes to the Vaier server subnet '
                    + '(and other site LANs) via its relay peer. No secrets; served unauthenticated.',
                downloadUrl: url,
                curlCommand: `curl -sSL ${url} | sudo bash`,
                requiresAuth: false,
            });
        }


        let peerToDelete = null;

        function confirmDeletePeer(peerId) {
            peerToDelete = peerId;
            document.getElementById('deletePeerName').textContent = peerDisplayName(peerId);
            document.getElementById('deleteConfirmModal').classList.add('active');
        }

        function hideDeleteConfirmModal() {
            document.getElementById('deleteConfirmModal').classList.remove('active');
            peerToDelete = null;
        }

        async function deletePeer() {
            if (!peerToDelete) return;
            // Capture the id and clear it immediately so a second click can't fire a duplicate
            // delete while the first request is in flight.
            const peerId = peerToDelete;
            const peerName = peerDisplayName(peerId);
            hideDeleteConfirmModal();
            // Deleting a peer cascades into published-service cleanup (Traefik + DNS), a multi-second
            // operation — show the busy overlay so the page clearly looks like it's working.
            showBusy(`Deleting "${peerName}"…`);
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}`, { method: 'DELETE' });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`Peer "${peerName}" deleted`);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to delete peer: ${error.message}`);
            } finally {
                hideBusy();
            }
        }

        // #202: regenerate = delete + recreate with the same name/peerType/lanCidr/lanAddress/
        // description. The WireGuard keypair rotates as a side effect (the recreate generates
        // a fresh one), the show-once marker is cleared (its parent dir is gone), and the
        // create response is the single delivery of the new secret.
        let peerToRegenerate = null;

        function confirmRegeneratePeer(peerId) {
            peerToRegenerate = peerId;
            document.getElementById('regeneratePeerName').textContent = peerDisplayName(peerId);
            document.getElementById('regenerateConfirmModal').classList.add('active');
        }

        function hideRegenerateConfirmModal() {
            document.getElementById('regenerateConfirmModal').classList.remove('active');
            peerToRegenerate = null;
        }

        async function regeneratePeer() {
            if (!peerToRegenerate) return;
            const peerId = peerToRegenerate;
            // Snapshot the inputs we want to preserve before the row vanishes.
            const before = peers.find(x => x.id === peerId);
            if (!before) {
                displayError('Could not find the peer to regenerate.');
                return;
            }
            const recreatePayload = {
                name: before.name,
                peerType: before.peerType,
                lanCidr: before.lanCidr || null,
                lanAddress: before.lanAddress || null,
                description: before.description || null
            };
            hideRegenerateConfirmModal();
            showBusy(`Regenerating "${before.name}"…`);
            try {
                const del = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}`, { method: 'DELETE' });
                if (!del.ok) throw new Error(`delete HTTP ${del.status}`);
                const create = await fetch('/vpn/peers', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(recreatePayload)
                });
                if (!create.ok) throw new Error(`create HTTP ${create.status}`);
                const newPeer = await create.json();
                displaySuccess(`"${newPeer.name}" regenerated — save the new config now`);
                showPeerDetails(newPeer);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to regenerate peer: ${error.message}`);
            } finally {
                hideBusy();
            }
        }

        // #247: reissue = re-render the config from current logic, preserving the keypair (no
        // delete/recreate, no server-side change, live tunnel untouched). The response is one
        // delivery of the freshly rendered config — the operator reinstalls it on the peer.
        async function reissuePeerConfig(peerId) {
            const before = peers.find(x => x.id === peerId);
            const label = before ? before.name : peerDisplayName(peerId);
            showBusy(`Reissuing "${label}"…`);
            try {
                const response = await fetch(`/vpn/peers/${encodeURIComponent(peerId)}/reissue`, { method: 'POST' });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const reissued = await response.json();
                displaySuccess(`"${reissued.name}" reissued — reinstall the new config on the peer`);
                showPeerDetails(reissued);
                fetchPeers();
            } catch (error) {
                displayError(`Failed to reissue config: ${error.message}`);
            } finally {
                hideBusy();
            }
        }

        async function copyConfig() {
            const pre = document.getElementById('configPre');
            const btn = document.getElementById('copyBtn');
            if (!pre || !btn) return;
            try {
                await navigator.clipboard.writeText(pre.textContent);
                btn.textContent = 'copied!';
                btn.classList.add('copied');
                setTimeout(() => { btn.textContent = 'copy'; btn.classList.remove('copied'); }, 2000);
            } catch {
                btn.textContent = 'failed';
                setTimeout(() => { btn.textContent = 'copy'; }, 2000);
            }
        }

        // Close a modal only when the press both starts and ends on its backdrop. A click whose
        // mousedown began inside the dialog — e.g. selecting text in an input and releasing on the
        // backdrop — must NOT close it; that was wiping a half-filled Add Machine form mid-editing.
        let _modalMouseDownTarget = null;
        window.addEventListener('mousedown', e => { _modalMouseDownTarget = e.target; });
        window.onclick = e => {
            if (e.target.classList.contains('modal') && _modalMouseDownTarget === e.target) {
                if (e.target.id === 'peerDetailsModal') hidePeerDetailsModal();
                else e.target.classList.remove('active');
            }
        };

        let _peerMap = null;
        let _markersClusterGroup = null;
        let _serverLocation = null;
        let _activeTab = 'list';

        function switchTab(tab) {
            _activeTab = tab;
            document.querySelectorAll('.tab-button').forEach(b => {
                b.classList.toggle('active', b.dataset.tab === tab);
            });
            document.querySelectorAll('.tab-panel').forEach(p => {
                p.classList.toggle('active', p.id === 'tab-panel-' + tab);
            });
            const fab = document.querySelector('.fab');
            if (fab) fab.style.display = tab === 'list' ? '' : 'none';
            // Leaflet needs to recompute size when its container becomes visible.
            if (tab === 'map' && _peerMap) {
                setTimeout(() => { _peerMap.invalidateSize(); refreshPeerMap(); }, 0);
            }
            // The SVG diagram lays out against its container's pixel size, which is only
            // known once the panel is visible (display:none reports clientWidth 0).
            if (tab === 'diagram') {
                setTimeout(renderNetworkDiagram, 0);
            }
        }

        function setupPeerMap() {
            if (typeof L === 'undefined') return;
            L.Icon.Default.mergeOptions({
                iconUrl: 'vendor/leaflet/images/marker-icon.png',
                iconRetinaUrl: 'vendor/leaflet/images/marker-icon-2x.png',
                shadowUrl: 'vendor/leaflet/images/marker-shadow.png'
            });
            _peerMap = L.map('peer-map', { worldCopyJump: true, zoomControl: true })
                .setView([20, 0], 1);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '© OpenStreetMap',
                maxZoom: 18
            }).addTo(_peerMap);
            _markersClusterGroup = L.markerClusterGroup({
                showCoverageOnHover: false,
                spiderfyOnMaxZoom: true,
                maxClusterRadius: 30
            });
            _peerMap.addLayer(_markersClusterGroup);
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

        function bindHoverPopup(marker, html) {
            marker.bindPopup(html, { closeButton: false, autoPan: false });
            marker.on('mouseover', e => e.target.openPopup());
            marker.on('mouseout',  e => e.target.closePopup());
        }

        // Connectivity is computed server-side (VpnClient.isConnected); peer objects from
        // /vpn/peers and the peers-stats SSE carry the result as `connected`.
        function isPeerConnected(peer) {
            return !!peer.connected;
        }

        function statusKeyForPeer(p) {
            return isPeerConnected(p) ? 'up' : 'down';
        }

        // Maps the domain MachineStatus enum to the map-marker's status key. Same status, same
        // colour — keeps the map markers in lockstep with the LAN-server card icons.
        const MAP_MARKER_STATUS_KEY = {
            'OK':       'up',
            'DEGRADED': 'degraded',
            'DOWN':     'down',
            'UNKNOWN':  'unknown',
        };
        function statusKeyForLanServer(s) {
            return MAP_MARKER_STATUS_KEY[s.status] || 'unknown';
        }

        // Build a Leaflet divIcon marker carrying the same SVG used in the machine-list cards.
        // statusKey = 'up' | 'down' | 'degraded' | 'unknown'; opts.weak / opts.big toggle styles.
        function makeIconMarker(latlng, kind, statusKey, opts) {
            const big  = !!(opts && opts.big);
            const weak = !!(opts && opts.weak);
            const sz   = big ? 36 : 28;
            const cls  = ['map-marker', statusKey];
            if (big)  cls.push('large');
            if (weak) cls.push('weak');
            return L.marker(latlng, {
                icon: L.divIcon({
                    html: `<div class="${cls.join(' ')}">${machineIconSvg(kind)}</div>`,
                    className: '',
                    iconSize: [sz, sz],
                    iconAnchor: [sz / 2, sz / 2]
                }),
                riseOnHover: true
            });
        }

        function refreshPeerMap() {
            if (!_peerMap || !_markersClusterGroup) return;
            _markersClusterGroup.clearLayers();

            const allCoords = [];

            peers.forEach(p => {
                if (p.latitude == null || p.longitude == null) return;
                const statusKey = statusKeyForPeer(p);
                const iconKind = deviceCategoryIconKind(p.deviceCategory);
                const carrierPlace = [p.city, p.country].filter(Boolean).join(', ');

                if (p.isClient) {
                    // Weak marker at the carrier IP — "where the device connects from"
                    const carrierPopup = `<strong>${escapeHtml(p.name)}</strong><br>`
                        + `<span style="color:#888;font-size:0.85em">connecting from</span><br>`
                        + `<span style="font-family:monospace;font-size:0.85em">${escapeHtml(p.endpointIp)}</span>`
                        + (carrierPlace ? `<br>${escapeHtml(carrierPlace)}` : '')
                        + `<br><span style="color:#888;font-size:0.78em">approx. ISP location</span>`;
                    const weak = makeIconMarker([p.latitude, p.longitude], iconKind, statusKey, { weak: true });
                    bindHoverPopup(weak, carrierPopup);
                    _markersClusterGroup.addLayer(weak);
                    allCoords.push([p.latitude, p.longitude]);

                    // Firm marker at the server — "where the device's internet traffic surfaces".
                    // Skip when lat/lon aren't available (geoip MMDB not yet populated).
                    if (_serverLocation && _serverLocation.latitude != null && _serverLocation.longitude != null) {
                        const surfacePlace = [_serverLocation.city, _serverLocation.country].filter(Boolean).join(', ');
                        const surfacePopup = `<strong>${escapeHtml(p.name)}</strong><br>`
                            + `<span style="color:#888;font-size:0.85em">internet via Vaier</span>`
                            + (surfacePlace ? `<br>${escapeHtml(surfacePlace)}` : '');
                        const firm = makeIconMarker([_serverLocation.latitude, _serverLocation.longitude], iconKind, statusKey);
                        bindHoverPopup(firm, surfacePopup);
                        _markersClusterGroup.addLayer(firm);
                    }
                } else {
                    // Server peer: single firm marker at its own endpoint
                    const popup = `<strong>${escapeHtml(p.name)}</strong><br>`
                        + `<span style="font-family:monospace;font-size:0.85em">${escapeHtml(p.endpointIp)}</span>`
                        + (carrierPlace ? `<br>${escapeHtml(carrierPlace)}` : '');
                    const firm = makeIconMarker([p.latitude, p.longitude], iconKind, statusKey);
                    bindHoverPopup(firm, popup);
                    _markersClusterGroup.addLayer(firm);
                    allCoords.push([p.latitude, p.longitude]);
                }
            });

            // LAN_SERVERs: anchored at the relay peer's endpoint, or — when routed through the
            // Vaier server itself (relayPeerName === "Vaier server", matching LanAnchor.VAIER_SERVER_NAME)
            // — at the Vaier server's own location. "Behind <X>" label either way. Closes #182, #204.
            lanServers.forEach(s => {
                if (!s.relayPeerName) return;
                let lat, lon;
                if (s.relayPeerName === 'Vaier server') {
                    if (!_serverLocation || _serverLocation.latitude == null || _serverLocation.longitude == null) return;
                    lat = _serverLocation.latitude; lon = _serverLocation.longitude;
                } else {
                    const relay = peers.find(p => p.name === s.relayPeerName);
                    if (!relay || relay.latitude == null || relay.longitude == null) return;
                    lat = relay.latitude; lon = relay.longitude;
                }
                const popup = `<strong>${escapeHtml(s.name)}</strong><br>`
                    + `<span style="color:#888;font-size:0.85em">Behind ${escapeHtml(s.relayPeerName)}</span><br>`
                    + `<span style="font-family:monospace;font-size:0.85em">${escapeHtml(s.lanAddress)}</span>`
                    + (s.runsDocker ? `<br><span style="color:#888;font-size:0.78em">Docker on :${s.dockerPort}</span>` : '');
                const firm = makeIconMarker([lat, lon], deviceCategoryIconKind(s.deviceCategory), statusKeyForLanServer(s));
                bindHoverPopup(firm, popup);
                _markersClusterGroup.addLayer(firm);
                allCoords.push([lat, lon]);
            });

            if (_serverLocation && _serverLocation.latitude != null && _serverLocation.longitude != null) {
                const place = [_serverLocation.city, _serverLocation.country].filter(Boolean).join(', ');
                const popup = `<strong>Vaier server</strong><br>`
                    + `<span style="font-family:monospace;font-size:0.85em">${escapeHtml(_serverLocation.publicHost)}</span>`
                    + (place ? `<br>${escapeHtml(place)}` : '');
                const m = makeIconMarker([_serverLocation.latitude, _serverLocation.longitude], 'vaier', 'up', { big: true });
                bindHoverPopup(m, popup);
                _markersClusterGroup.addLayer(m);
                allCoords.push([_serverLocation.latitude, _serverLocation.longitude]);
            }

            if (allCoords.length > 0) {
                const bounds = L.latLngBounds(allCoords);
                _peerMap.fitBounds(bounds.pad(0.3), { maxZoom: 5 });
            }
        }

        // Hub-and-spoke network diagram: the Vaier server sits at the centre, every VPN peer
        // radiates from it on an inner ring, and each LAN server branches outward from the relay
        // peer (or the Vaier server) it sits behind. Reuses the same icons, status colours and
        // data (`peers`, `lanServers`, `_serverLocation`) as the List and Map tabs.
        const SVG_NS = 'http://www.w3.org/2000/svg';

        function iconClassFromKey(statusKey) {
            return 'icon-' + statusKey; // 'up'|'down'|'degraded'|'unknown' -> existing colour classes
        }

        function netAddEdge(svg, x1, y1, x2, y2, statusKey) {
            const line = document.createElementNS(SVG_NS, 'line');
            line.setAttribute('x1', x1); line.setAttribute('y1', y1);
            line.setAttribute('x2', x2); line.setAttribute('y2', y2);
            line.setAttribute('class', 'net-edge ' + statusKey);
            svg.appendChild(line);
        }

        function netAddNode(host, x, y, iconKind, statusKey, label, sub, isCenter, isRelay, isDocker) {
            const node = document.createElement('div');
            node.className = 'net-node' + (isCenter ? ' center' : '');
            node.style.left = x + 'px';
            node.style.top = y + 'px';
            node.title = sub ? `${label}\n${sub}` : label;
            // Relay badge bottom-right, Docker badge bottom-left — placed apart so they don't overlap.
            const relayCap = isRelay
                ? `<span class="net-cap" title="Relay — routes LAN traffic for machines behind it">${capabilityIconSvg('relay')}</span>`
                : '';
            const dockerCap = isDocker
                ? `<span class="net-cap left" title="Runs Docker">${capabilityIconSvg('docker')}</span>`
                : '';
            node.innerHTML =
                `<div class="net-icon ${iconClassFromKey(statusKey)}">${machineIconSvg(iconKind)}${relayCap}${dockerCap}</div>`
                + `<div class="net-label">${escapeHtml(label)}</div>`
                + (sub ? `<div class="net-sub">${escapeHtml(sub)}</div>` : '');
            host.appendChild(node);
        }

        function renderNetworkDiagram() {
            const host = document.getElementById('network-diagram');
            if (!host) return;
            const W = host.clientWidth, H = host.clientHeight;
            if (W === 0 || H === 0) return; // panel not visible yet — switchTab re-renders on show

            host.innerHTML = '';
            const edges = document.createElementNS(SVG_NS, 'svg');
            edges.setAttribute('class', 'net-edges');
            host.appendChild(edges);

            const cx = W / 2, cy = H / 2;
            const minDim = Math.min(W, H);
            const ringPeers = minDim * 0.30;
            const ringLan = minDim * 0.44;
            const peerAngles = {}; // peer name -> { x, y, ang }

            // Centre: the Vaier hub. Always shown, even with no peers and no geoip data.
            const hubSub = (_serverLocation && _serverLocation.publicHost) ? _serverLocation.publicHost : '';
            netAddNode(host, cx, cy, 'vaier', 'up', 'Vaier server', hubSub, true, false, false);

            // Inner ring: every VPN peer, spread evenly, an edge back to the hub.
            const n = peers.length;
            peers.forEach((p, i) => {
                const ang = (i / Math.max(n, 1)) * 2 * Math.PI - Math.PI / 2;
                const x = cx + ringPeers * Math.cos(ang);
                const y = cy + ringPeers * Math.sin(ang);
                peerAngles[p.name] = { x, y, ang };
                const statusKey = statusKeyForPeer(p);
                netAddEdge(edges, cx, cy, x, y, statusKey);
                const sub = p.tunnelIp || p.endpointIp || '';
                // Docker-host truth mirrors the List card: a discovered services entry (keyed by the
                // peer's immutable id) with at least one container.
                const svc = peerServices[p.id];
                const isDocker = !!(svc && svc.containers && svc.containers.length > 0);
                netAddNode(host, x, y, deviceCategoryIconKind(p.deviceCategory), statusKey, p.name, sub, false, !!p.isRelay, isDocker);
            });

            // Outer ring: LAN servers branch from the relay peer (or the Vaier server) they sit
            // behind. Multiple machines behind one relay fan out around that relay's direction.
            const behind = {};
            lanServers.forEach(s => {
                if (!s.relayPeerName) return;
                (behind[s.relayPeerName] = behind[s.relayPeerName] || []).push(s);
            });
            Object.keys(behind).forEach(relayName => {
                const servers = behind[relayName];
                let ax, ay, baseAng;
                if (relayName === 'Vaier server') {
                    ax = cx; ay = cy; baseAng = -Math.PI / 2;
                } else if (peerAngles[relayName]) {
                    ax = peerAngles[relayName].x; ay = peerAngles[relayName].y; baseAng = peerAngles[relayName].ang;
                } else {
                    return; // relay peer not in the list — nothing to anchor to
                }
                servers.forEach((s, k) => {
                    const ang = baseAng + (k - (servers.length - 1) / 2) * 0.30;
                    const x = cx + ringLan * Math.cos(ang);
                    const y = cy + ringLan * Math.sin(ang);
                    const statusKey = statusKeyForLanServer(s);
                    netAddEdge(edges, ax, ay, x, y, statusKey);
                    netAddNode(host, x, y, deviceCategoryIconKind(s.deviceCategory), statusKey, s.name, s.lanAddress || '', false, false, s.runsDocker === true);
                });
            });

            if (peers.length === 0 && lanServers.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'net-empty';
                empty.textContent = 'No machines yet — add a VPN peer or LAN server to see the network.';
                host.appendChild(empty);
            }
        }

        // Re-lay-out when the iframe/window resizes while the diagram tab is showing.
        window.addEventListener('resize', () => {
            if (_activeTab === 'diagram') renderNetworkDiagram();
        });

        async function fetchServerLocation() {
            // Always fetch — the response carries both the Map tab's lat/lon and the Vaier-server
            // machine card's LAN CIDR (#204). Either group can be absent (null lat/lon when the
            // geoip MMDB isn't in place; null lanCidr when neither env nor IMDS yields one).
            try {
                const res = await fetch('/vpn/peers/server-location');
                if (!res.ok) return;
                _serverLocation = await res.json();
                if (_peerMap) refreshPeerMap();
                // Re-render the Machines list so the Vaier server card picks up the LAN CIDR.
                if (peers && peers.length) displayPeers(peers);
                // Keep the diagram's hub label in sync even on an empty net (no peers => no displayPeers).
                renderNetworkDiagram();
            } catch (e) {
                console.error('Failed to load server location:', e);
            }
        }

        // LAN scanner (#246) — an Enterprise feature, surfaced inside the Add Machine modal next to
        // the LAN address field. On a Community instance the control stays hidden (we check GET
        // /license once on load); the /lan-scan endpoint is also gated server-side (402).
        //
        // The scan is asynchronous and on demand: the operator clicks "Scan LAN" (POST /lan-scan,
        // which returns immediately), the control shows "Scanning…" and polls GET /lan-scan until the
        // sweep finishes (~20s per LAN), then lists the discovered hosts in a picker. Choosing one
        // fills the LAN address (and toggles "Runs Docker" for a detected Docker host). Opening the
        // modal shows the last cached snapshot without re-scanning.
        const LAN_MACHINE_ROLE_LABELS = {
            DOCKER_HOST: 'Docker host', WEB_UI: 'Web UI', SSH_HOST: 'SSH host',
            PRINTER: 'Printer', UNKNOWN: 'Unknown'
        };

        // Resolved once on load; gates whether the in-modal scan control is shown.
        window._vaierEnterprise = false;
        // Invalidates any in-flight poll chain when the modal is reset/closed.
        let _modalScanToken = 0;

        async function detectEdition() {
            try {
                const license = await (await fetch('/license', { cache: 'no-store' })).json();
                window._vaierEnterprise = license.edition === 'ENTERPRISE';
            } catch (e) { window._vaierEnterprise = false; }
        }

        // Start a background scan, then poll for results.
        async function modalScanLan() {
            try {
                const resp = await fetch('/lan-scan', { method: 'POST' });
                if (resp.status === 402) { displayError('Enterprise licence required for LAN scanning.'); return; }
            } catch (e) { displayError(`Failed to start LAN scan: ${e.message}`); return; }
            refreshModalScanResults(++_modalScanToken);
        }

        // Read the snapshot and render; while SCANNING, re-poll. Stops if the modal closed or a newer
        // scan/modal-open superseded this chain (token guard).
        async function refreshModalScanResults(token) {
            if (token !== _modalScanToken) return;
            if (!document.getElementById('createPeerModal').classList.contains('active')) return;
            const btn = document.getElementById('lanScanModalBtn');
            const status = document.getElementById('lanScanModalStatus');
            const list = document.getElementById('lanScanModalList');
            let snap;
            try { snap = await (await fetch('/lan-scan', { cache: 'no-store' })).json(); }
            catch (e) { return; }
            if (token !== _modalScanToken) return;
            const scanning = snap.status === 'SCANNING';
            btn.disabled = scanning;
            btn.textContent = scanning ? 'Scanning…' : '⌕ Scan LAN for machines';
            if (scanning) {
                status.textContent = 'Scanning relay LANs… (~20s per LAN)';
                setTimeout(() => refreshModalScanResults(token), 3000);
                return;
            }
            if (!snap.lastScanCompleted) { status.textContent = ''; list.style.display = 'none'; return; }
            const machines = (snap.machines || []).slice().sort(compareByIp);
            status.textContent = machines.length
                ? `${machines.length} machine${machines.length === 1 ? '' : 's'} found — pick one below`
                : 'No unregistered machines found.';
            populateScanList(list, machines);
        }

        function compareByIp(a, b) {
            const oct = s => s.ipAddress.split('.').map(Number);
            const x = oct(a), y = oct(b);
            for (let i = 0; i < 4; i++) { if (x[i] !== y[i]) return x[i] - y[i]; }
            return 0;
        }

        // Remembers the device category (and the IP it belongs to) of the host the operator picked
        // from a scan, so it can be carried into the POST /lan-servers body. The IP guard means a
        // later manual edit of the address drops the stale category rather than mis-applying it.
        let _scanPickedCategory = null;
        let _scanPickedIp = null;

        function populateScanList(list, machines) {
            if (!machines.length) { list.style.display = 'none'; list.innerHTML = ''; return; }
            const groups = {};
            machines.forEach(m => { (groups[m.relayAnchor] = groups[m.relayAnchor] || []).push(m); });
            let html = '';
            Object.keys(groups).forEach(anchor => {
                html += `<div class="scan-host-group">${escapeHtml(anchor)}</div>`;
                groups[anchor].forEach(m => {
                    const role = LAN_MACHINE_ROLE_LABELS[m.role] || m.role;
                    const ports = (m.openPorts && m.openPorts.length) ? ' :' + m.openPorts.join(',') : '';
                    const icon = machineIconSvg(deviceCategoryIconKind(m.deviceCategory));
                    const ipEsc   = jsArg(m.ipAddress);
                    const roleEsc = jsArg(m.role || '');
                    const catEsc  = jsArg(m.deviceCategory || '');
                    html += `<div class="scan-host-row" title="${escapeHtml(deviceCategoryLabel(m.deviceCategory))}"
                                  onclick="pickDiscoveredHost('${ipEsc}', '${roleEsc}', '${catEsc}')">
                                <span class="scan-host-icon">${icon}</span>
                                <span class="scan-host-ip">${escapeHtml(m.ipAddress)}</span>
                                <span class="scan-host-meta">— ${escapeHtml(role)}${escapeHtml(ports)}</span>
                            </div>`;
                });
            });
            list.innerHTML = html;
            list.style.display = '';
        }

        // Picking a discovered host fills the address field, toggles Runs Docker for a Docker host,
        // and remembers its device category to carry into the POST /lan-servers body.
        function pickDiscoveredHost(ipAddress, role, deviceCategory) {
            if (!ipAddress) return;
            document.getElementById('lanAddress').value = ipAddress;
            _scanPickedCategory = deviceCategory || null;
            _scanPickedIp = ipAddress;
            if (role === 'DOCKER_HOST'
                    && document.getElementById('peerType').value === 'LAN_SERVER') {
                document.getElementById('runsDocker').checked = true;
                onRunsDockerChange();
            }
            updateLanAddressHint();
        }

        // Show/hide the in-modal scan control and load any existing snapshot. Called from
        // onPeerTypeChange whenever the LAN address field's visibility is recomputed.
        function syncModalScanControl(isLanServer) {
            const row = document.getElementById('lanScanRow');
            const show = isLanServer && window._vaierEnterprise;
            row.style.display = show ? '' : 'none';
            if (show) refreshModalScanResults(++_modalScanToken);
        }

        // (escapeHtml is defined once, near jsArg above — the duplicate here was removed in #273.)

        setupPeerMap();
        fetchPeers();
        fetchVaierServerServices();
        fetchLanServers();
        fetchServerLocation();
        detectEdition();

        const _sse = new EventSource('/vpn/peers/events');
        _sse.addEventListener('peers-updated', () => fetchPeers());
        _sse.addEventListener('lan-servers-updated', () => fetchLanServers());
        _sse.addEventListener('peers-stats', e => {
            try {
                const stats = JSON.parse(e.data);
                let mapNeedsRefresh = false;
                stats.forEach(s => {
                    const id = s.name.replace(/[^a-zA-Z0-9]/g, '_');
                    const iconCls = s.connected ? 'icon-up' : 'icon-down';

                    const hdrIcon        = document.getElementById('hdr-icon-'         + id);
                    const rxtx           = document.getElementById('rxtx-'             + id);
                    const lastSeenDetail = document.getElementById('last-seen-detail-' + id);

                    if (hdrIcon)        {
                        hdrIcon.className = 'machine-icon ' + iconCls;
                        // Keep the hover tooltip's plain-language state + handshake evidence in
                        // sync with the live status, preserving the machine-type prefix (#270).
                        const statusClass = s.connected ? 'status-connected' : 'status-disconnected';
                        const prefix = (hdrIcon.title || '').split(' · ')[0];
                        hdrIcon.title = prefix + ' · ' + statusTooltip(statusClass, s.latestHandshake);
                    }
                    if (rxtx)           { rxtx.textContent = formatBytes(s.transferRx) + ' / ' + formatBytes(s.transferTx); }
                    if (lastSeenDetail) { lastSeenDetail.textContent = lastSeenAbsolute(s.latestHandshake); }

                    // Sync the in-memory peers array so map markers reflect live connection state.
                    // peers-stats keys entries by the peer's immutable id (resolvePeerNameByIp).
                    const cached = peers.find(p => p.id === s.name);
                    if (cached) {
                        const wasConnected = isPeerConnected(cached);
                        cached.latestHandshake = s.latestHandshake;
                        cached.transferRx = s.transferRx;
                        cached.transferTx = s.transferTx;
                        cached.connected = s.connected;
                        if (wasConnected !== s.connected) mapNeedsRefresh = true;
                    }
                });
                if (mapNeedsRefresh) refreshPeerMap();
            } catch (err) {
                console.error('Failed to apply peers-stats update:', err);
            }
        });
        let _sseConnected = false;
        _sse.onopen = () => { if (_sseConnected) fetchPeers(); _sseConnected = true; };
