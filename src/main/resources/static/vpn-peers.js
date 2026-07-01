// Extracted from vpn-peers.html (#273). Classic script — runs in global scope so the page's
// inline on* handlers keep resolving. Modularisation tracked as follow-up slices of #273.
        let peers = [];
        // Whether the per-service Social auth mode is offered (Google OAuth configured server-side,
        // #305). Loaded once from /settings/config; gates the Social option in the auth-mode picker.
        let _socialAuthAvailable = false;
        // Per-service access rules: host -> [allowed groups] (any-of). An absent host (or empty list)
        // means any signed-in, approved user may reach it. Shown/edited in a Social service's editor.
        let _serviceAccessRules = {};
        // Group names already assigned to access entries — the "Allowed groups" picker's suggestions,
        // mirroring how the Users page derives its group suggestions from the access entries.
        let _accessGroupSuggestions = [];
        let peerServices = {};
        let vaierServerStatus = 'UNKNOWN'; // domain MachineStatus enum value
        let lanServers = [];
        // Published services (Topology tab, #infrastructure slice 1; machine cards, slice 2). Same
        // DTO the Services page discovers; we client-side join each one onto its host machine.
        let publishedServices = [];
        // hostKey -> [service] index (slice 2), rebuilt from publishedServices on each list render so
        // every machine card can show the published routes it hosts. Keyed like topologyServicesByHost
        // ('__hub__', a peer name, or a LAN-server name).
        let _publishedByHost = {};
        // Discoverable-but-unpublished containers, the "+ Publish" candidates folded into the same
        // Services list (slice 2c). publishableServices is the raw /publishable feed; _candidatesByHost
        // is the per-machine index built in displayPeers (already-published entries dropped). Operator-
        // ignored candidates are split into _ignoredCandidatesByHost so a card can reveal them on demand
        // (slice 3 ignore/unignore parity); expandedIgnored tracks which host keys are showing them.
        let publishableServices = [];
        let _candidatesByHost = {};
        let _ignoredCandidatesByHost = {};
        const expandedIgnored = new Set();
        let lastFetchSuccessful = false;

        const expandedPeers = new Set();
        // Which published-service rows are expanded into their inline editor (slice 2b). Keyed by the
        // service's unique name (dnsAddress + pathPrefix); persists across re-renders like expandedPeers.
        const expandedPublished = new Set();

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
        }

        // Published services for the machine cards' Services subsection (#infrastructure slice 2).
        // Loaded alongside peers/lanServers and refreshed on the same triggers; each service is
        // grouped onto its host machine (see topologyServicesByHost / _publishedByHost). Failures
        // are non-fatal — the cards simply render without their Services rows.
        async function fetchPublishedServices() {
            try {
                const response = await fetch('/published-services/discover', { cache: 'no-store' });
                if (response.ok) {
                    publishedServices = await response.json();
                    // Re-render the list so each machine card's Services subsection (slice 2) picks
                    // up the change, and the diagram if it's the visible tab. Skip the list re-render
                    // while an input inside a card is focused, so an SSE-driven refresh can't wipe an
                    // in-progress edit out from under the operator (a later event reconciles).
                    // Gate only on editing, not peer count — a hub/LAN-only deployment (zero peers)
                    // still has machine cards whose Services sections must pick up the change.
                    if (!isEditingMachineCard()) displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load published services:', error);
            }
        }

        // Deployment-level config the page needs: currently just whether Social auth is on offer.
        // Fetched once at load; re-renders so already-open editors pick up the Social option.
        async function fetchAppConfig() {
            try {
                const response = await fetch('/settings/config', { cache: 'no-store' });
                if (response.ok) {
                    const cfg = await response.json();
                    _socialAuthAvailable = !!cfg.socialAuthAvailable;
                    if (!isEditingMachineCard()) displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load app config:', error);
            }
        }

        // Per-service access rules for Social services (host -> any-of allowed groups). Loaded once at
        // start and refreshed after each edit; re-renders so an open Social editor shows the live rule.
        async function fetchServiceAccessRules() {
            try {
                const response = await fetch('/access/services', { cache: 'no-store' });
                if (response.ok) {
                    _serviceAccessRules = await response.json();
                    if (!isEditingMachineCard()) displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load service access rules:', error);
            }
        }

        // Known group names for the "Allowed groups" picker's suggestions — derived from the groups
        // already assigned across access entries (groups live only on the access entries). Non-fatal.
        async function fetchAccessGroupSuggestions() {
            try {
                const response = await fetch('/access', { cache: 'no-store' });
                if (response.ok) {
                    const entries = await response.json();
                    const set = new Set();
                    entries.forEach(e => (e.groups || []).forEach(g => set.add(g)));
                    _accessGroupSuggestions = [...set].sort();
                }
            } catch (error) {
                console.error('Failed to load access group suggestions:', error);
            }
        }

        // The discoverable "+ Publish" candidates (slice 2c). Loaded alongside published services and
        // refreshed on the same triggers; displayPeers indexes them per machine.
        async function fetchPublishable() {
            try {
                const response = await fetch('/published-services/publishable', { cache: 'no-store' });
                if (response.ok) {
                    publishableServices = await response.json();
                    // Gate only on editing, not peer count (see fetchPublishedServices) so candidates
                    // surface on hub/LAN-only deployments too.
                    if (!isEditingMachineCard()) displayPeers(peers);
                }
            } catch (error) {
                console.error('Failed to load publishable services:', error);
            }
        }

        // Map a publishable candidate to the machine card that should host it. The candidate's peerName
        // is the sanitized WireGuard name, not the display name, so we match on address instead:
        // VAIER_SERVER -> hub; a peer by tunnel IP; else a LAN server by lanAddress.
        function publishableHostKey(c) {
            if (c.source === 'VAIER_SERVER') return '__hub__';
            const peer = peers.find(p => p.tunnelIp === c.address);
            if (peer) return peer.name;
            const lan = lanServers.find(s => s.lanAddress === c.address);
            if (lan) return lan.name;
            return null;
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

        // The published reverse-proxy routes and "+ Publish" candidates are rendered per machine by
        // renderServicesSubsection (slice 2c); the old discovered-containers renderServiceList was
        // folded into it. Published routes come from the _publishedByHost index keyed by machine name.
        function renderPublishedRow(s) {
            const statusKey  = statusKeyForService(s);                // up | down | unknown
            const display    = `${s.dnsAddress}${s.pathPrefix || ''}`;
            const label      = s.launchpadAlias || s.shortName || s.name || display;
            const url        = `https://${display}`;
            const isSelf     = s.dnsAddress.startsWith('vaier.');
            const dnsOk      = s.dnsState === 'OK';
            const uniqueName = display;
            const id         = cardId('pub:' + uniqueName);
            const isOpen     = expandedPublished.has(uniqueName);
            // The ↗ link and ✕ button live inside the clickable header, so they stop propagation to
            // avoid also toggling the editor.
            const linkOpen   = (dnsOk && !isSelf)
                ? `<a class="pub-open" href="${encodeURI(url)}" target="_blank" rel="noopener" title="Open ${escapeHtml(display)}" onclick="event.stopPropagation()">↗</a>`
                : '';
            // The badge names the gateway in front of the service, not just "gated y/n", so the
            // operator sees at a glance which login a tile leads to. Fall back to the legacy
            // authenticated flag for routes that predate authMode (e.g. Docker-label basicAuth).
            const mode = s.authMode || (s.authenticated ? 'social' : 'none');
            const authBadge = mode === 'social'
                ? `<span class="pub-badge pub-social" title="Social login (Google) required">social</span>`
                : `<span class="pub-badge pub-noauth" title="Public — no authentication required">no auth</span>`;
            // A Social service with a non-empty access rule is restricted to specific groups (#access
            // rules); flag it so the operator sees at a glance it's not open to every approved user.
            const restrictedBadge = (mode === 'social' && (_serviceAccessRules[s.dnsAddress] || []).length > 0)
                ? `<span class="pub-badge pub-restricted" title="Restricted to specific groups">restricted</span>`
                : '';
            return `<div class="published-entry">
                <div class="published-item" role="button" tabindex="0"
                     aria-expanded="${isOpen ? 'true' : 'false'}"
                     onclick="togglePublished('${jsArg(uniqueName)}')"
                     onkeydown="if((event.key==='Enter'||event.key===' ')&&event.target===this){event.preventDefault();togglePublished('${jsArg(uniqueName)}')}">
                    <span class="pub-status icon-${statusKey}" title="${statusKey}"></span>
                    <span class="pub-name" title="${escapeHtml(display)}">${escapeHtml(label)}</span>
                    ${authBadge}
                    ${restrictedBadge}
                    ${linkOpen}
                    <button class="pub-del" title="Delete this published service"
                            onclick="event.stopPropagation(); deletePublishedService('${jsArg(s.dnsAddress)}','${jsArg(s.pathPrefix || '')}')">✕</button>
                    <span class="pub-chevron ${isOpen ? 'open' : ''}">▼</span>
                </div>
                <div class="published-editor ${isOpen ? 'open' : ''}" id="pub-body-${id}">
                    ${renderPublishedEditor(s, id)}
                </div>
            </div>`;
        }

        // The inline editor for one published service (slice 2b) — the services-page card body folded
        // onto the machine card. Read-only context rows (URL/DNS/Host/Version) plus the editable
        // controls. Checkboxes apply immediately; text fields auto-save on blur (Enter blurs).
        function renderPublishedEditor(s, id) {
            const display = `${s.dnsAddress}${s.pathPrefix || ''}`;
            const url     = `https://${display}`;
            const isSelf  = s.dnsAddress.startsWith('vaier.');
            const dnsOk   = s.dnsState === 'OK';
            const dns = jsArg(s.dnsAddress), path = jsArg(s.pathPrefix || '');
            const urlVal = (dnsOk && !isSelf)
                ? `<a class="pub-open" href="${encodeURI(url)}" target="_blank" rel="noopener">${escapeHtml(display)}</a>`
                : `<span style="color:var(--text-dim);font-family:var(--mono)">${escapeHtml(display)}</span>`;
            const versionRow = (s.image || s.version)
                ? `<div class="detail-row"><span class="detail-label">Version</span><span class="detail-value" style="font-family:var(--mono)">${[s.image && escapeHtml(s.image), s.version && escapeHtml(s.version)].filter(Boolean).join(' · ')}</span></div>`
                : '';
            return `
                <div class="detail-row"><span class="detail-label">URL</span><span class="detail-value">${urlVal}</span></div>
                <div class="detail-row"><span class="detail-label">DNS</span><span class="detail-value">${escapeHtml(s.dnsState)}</span></div>
                <div class="detail-row"><span class="detail-label">Host</span><span class="detail-value">${escapeHtml(s.hostAddress)}:${s.hostPort}</span></div>
                ${versionRow}
                <div class="detail-row"><span class="detail-label">Auth</span><span class="detail-value">
                    ${renderAuthModePicker(s, id, dns, path)}</span></div>
                ${(s.authMode || (s.authenticated ? 'social' : 'none')) === 'social' ? renderAllowedGroupsRow(s, id) : ''}
                <div class="detail-row"><span class="detail-label">Display name</span><span class="detail-value">
                    <input type="text" id="pub-alias-${id}" class="form-input" style="width:100%;max-width:240px"
                        value="${escapeHtml(s.launchpadAlias || '')}" data-original="${escapeHtml(s.launchpadAlias || '')}" placeholder="(default)"
                        onkeydown="if(event.key==='Enter')this.blur()"
                        onblur="savePublishedField('${dns}','${path}','pub-alias-${id}','launchpadAlias')"></span></div>
                <details class="published-advanced">
                    <summary>Advanced</summary>
                    <div class="detail-row"><span class="detail-label">Redirect</span><span class="detail-value">
                        <input type="text" id="pub-redir-${id}" class="form-input" style="width:100%;max-width:240px"
                            value="${escapeHtml(s.rootRedirectPath || '')}" data-original="${escapeHtml(s.rootRedirectPath || '')}" placeholder="e.g. /dashboard"
                            onkeydown="if(event.key==='Enter')this.blur()"
                            onblur="savePublishedField('${dns}','${path}','pub-redir-${id}','rootRedirectPath')"></span></div>
                    <div class="detail-row"><span class="detail-label">Version endpoint</span><span class="detail-value" style="display:flex;gap:6px">
                        <input type="text" id="pub-ve-${id}" class="form-input" style="flex:2;min-width:0;max-width:170px"
                            value="${escapeHtml(s.versionEndpoint || '')}" data-original="${escapeHtml(s.versionEndpoint || '')}" placeholder="/sys/metrics"
                            onkeydown="if(event.key==='Enter')this.blur()"
                            onblur="savePublishedVersionEndpoint('${dns}','${path}','${id}')">
                        <input type="text" id="pub-vp-${id}" class="form-input" style="flex:1;min-width:0;max-width:100px"
                            value="${escapeHtml(s.versionProperty || '')}" data-original="${escapeHtml(s.versionProperty || '')}" placeholder="property"
                            onkeydown="if(event.key==='Enter')this.blur()"
                            onblur="savePublishedVersionEndpoint('${dns}','${path}','${id}')"></span></div>
                    <div class="detail-row"><span class="detail-label">Direct LAN URL</span><span class="detail-value">
                        <input type="checkbox" id="pub-dul-${id}" ${s.directUrlDisabled ? '' : 'checked'}
                            style="accent-color:var(--accent);cursor:pointer" title="Link the launchpad directly to the LAN URL when the caller shares the peer's NAT."
                            onchange="setPublishedDirectUrlDisabled('${dns}','${path}',!this.checked)"></span></div>
                    <div class="detail-row"><span class="detail-label">Launchpad</span><span class="detail-value">
                        <input type="checkbox" id="pub-lp-${id}" ${s.hiddenFromLaunchpad ? '' : 'checked'}
                            style="accent-color:var(--accent);cursor:pointer" title="Show a tile for this service on the launchpad."
                            onchange="setPublishedHidden('${dns}','${path}',!this.checked)"></span></div>
                </details>`;
        }

        // The per-service auth-mode picker (#305): which gateway sits in front of the service. The
        // Social option is offered only when Google OAuth is configured (_socialAuthAvailable) — but
        // a route already on Social always keeps its option visible so the mode can be read and changed.
        function renderAuthModePicker(s, id, dns, path) {
            const mode = s.authMode || (s.authenticated ? 'social' : 'none');
            const showSocial = _socialAuthAvailable || mode === 'social';
            const opt = (value, label) =>
                `<option value="${value}" ${mode === value ? 'selected' : ''}>${label}</option>`;
            return `<select id="pub-authmode-${id}" class="form-input" style="width:100%;max-width:240px"
                        title="Which login a visitor must pass to reach this service."
                        onchange="setPublishedAuthMode('${dns}','${path}',this.value)">
                ${opt('none', 'Public — no sign-in')}
                ${showSocial ? opt('social', 'Social (Google)') : ''}
            </select>`;
        }

        // The per-service access rule for a Social service (#access rules): an any-of chip picker of
        // the groups a signed-in visitor must satisfy. Empty means any approved user. Edits apply
        // immediately. Suggestions come from groups already assigned to access entries, plus this
        // rule's own groups; free-typing a brand-new group is allowed. Only shown in Social mode.
        function renderAllowedGroupsRow(s, id) {
            const host   = s.dnsAddress;
            const groups = _serviceAccessRules[host] || [];
            const chips  = groups.length
                ? groups.map(g =>
                    `<span class="chip">${escapeHtml(g)}<button type="button" class="chip-remove" title="Remove group"
                          onclick="removeAllowedGroup('${jsArg(host)}','${jsArg(g)}')">×</button></span>`).join('')
                : `<span class="chip-empty">Any signed-in, approved user</span>`;
            const suggestions = [...new Set([..._accessGroupSuggestions, ...groups])].sort();
            const options = suggestions.map(g => `<option value="${escapeHtml(g)}">`).join('');
            return `<div class="detail-row"><span class="detail-label">Allowed groups</span><span class="detail-value">
                <div class="groups-picker">
                    <div class="chip-list">${chips}</div>
                    <div class="chip-input-row">
                        <input type="text" class="form-input" id="pub-agroup-input-${id}"
                               list="pub-agroup-list-${id}" placeholder="Add group…" autocomplete="off"
                               onkeydown="if(event.key==='Enter'){event.preventDefault();addAllowedGroup('${jsArg(host)}','${id}')}">
                        <button type="button" class="btn btn-small btn-secondary"
                                onclick="addAllowedGroup('${jsArg(host)}','${id}')">Add</button>
                    </div>
                    <datalist id="pub-agroup-list-${id}">${options}</datalist>
                    <span class="pub-hint">Leave empty — any signed-in, approved user can reach this.
                        Otherwise a visitor needs at least one of these groups.</span>
                </div></span></div>`;
        }

        // A discoverable container that isn't published yet — a "+ Publish" row in the Services list
        // (slice 2c). Not expandable; the button opens the publish modal pre-filled from its dataset.
        // The Ignore button hides it from the candidate list (slice 3 ignore/unignore parity).
        function renderCandidateRow(c) {
            return `<div class="published-item candidate" title="Discovered container — not published yet">
                <span class="pub-status icon-unknown"></span>
                <span class="pub-name">${escapeHtml(c.containerName)}<span class="cand-port">:${c.port}</span></span>
                <button class="btn btn-small btn-primary pub-publish"
                        data-address="${escapeHtml(c.address)}" data-port="${c.port}"
                        data-container="${escapeHtml(c.containerName)}"
                        data-subdomain="${escapeHtml(c.suggestedSubdomain || '')}"
                        data-redirect="${escapeHtml(c.rootRedirectPath || '')}"
                        onclick="showPublishModalForCandidate(this)">+ Publish</button>
                <button class="cand-ignore" title="Hide this container from the candidate list"
                        onclick="ignoreCandidate('${jsArg(c.ignoreKey)}')">Ignore</button>
            </div>`;
        }

        // An operator-ignored candidate, shown dimmed only when its host's hidden section is expanded
        // (slice 3). Unignore restores it to the "+ Publish" list.
        function renderIgnoredCandidateRow(c) {
            return `<div class="published-item candidate ignored" title="Hidden from the candidate list">
                <span class="pub-status icon-unknown"></span>
                <span class="pub-name">${escapeHtml(c.containerName)}<span class="cand-port">:${c.port}</span></span>
                <button class="cand-unignore" title="Show this container in the candidate list again"
                        onclick="unignoreCandidate('${jsArg(c.ignoreKey)}')">Unignore</button>
            </div>`;
        }

        // One Services section per machine (slice 2c/3): published routes (editable) first, then the
        // host's discoverable-but-unpublished containers as "+ Publish" rows, then a collapsible
        // "N hidden" line revealing operator-ignored candidates. Replaces both the old discovered-
        // containers list and the published-only subsection.
        function renderServicesSubsection(hostKey) {
            const published  = (_publishedByHost[hostKey] || []).slice()
                .sort((a, b) => (a.launchpadAlias || a.shortName || a.name || '')
                    .localeCompare(b.launchpadAlias || b.shortName || b.name || ''));
            const byContainer = (a, b) => a.containerName.localeCompare(b.containerName) || a.port - b.port;
            const candidates = (_candidatesByHost[hostKey] || []).slice().sort(byContainer);
            const ignored    = (_ignoredCandidatesByHost[hostKey] || []).slice().sort(byContainer);
            if (published.length === 0 && candidates.length === 0 && ignored.length === 0) return '';
            let rows = published.map(renderPublishedRow).join('') + candidates.map(renderCandidateRow).join('');
            if (ignored.length > 0) {
                const open = expandedIgnored.has(hostKey);
                rows += `<div class="cand-ignored-toggle" role="button" tabindex="0"
                              aria-expanded="${open ? 'true' : 'false'}"
                              onclick="toggleIgnoredCandidates('${jsArg(hostKey)}')"
                              onkeydown="if((event.key==='Enter'||event.key===' ')&&event.target===this){event.preventDefault();toggleIgnoredCandidates('${jsArg(hostKey)}')}">${open ? '▾' : '▸'} ${ignored.length} hidden</div>`;
                if (open) rows += ignored.map(renderIgnoredCandidateRow).join('');
            }
            return `<div class="detail-row">
                <span class="detail-label">Services</span>
                <div class="published-list">${rows}</div>
            </div>`;
        }

        // Ignore/unignore a discovered candidate (slice 3 parity). The flip is applied optimistically
        // because fetchPublishable rescrapes every host and takes seconds; the refetch reconciles.
        async function ignoreCandidate(key) {
            applyCandidateIgnored(key, true);
            try {
                const r = await fetch('/published-services/publishable/ignore', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ key }),
                });
                if (!r.ok) throw new Error(await apiErrorMessage(r));
            } catch (e) { displayError(`Failed to hide service: ${e.message}`); }
            fetchPublishable();
        }

        async function unignoreCandidate(key) {
            applyCandidateIgnored(key, false);
            try {
                const r = await fetch('/published-services/publishable/unignore', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ key }),
                });
                if (!r.ok) throw new Error(await apiErrorMessage(r));
            } catch (e) { displayError(`Failed to restore service: ${e.message}`); }
            fetchPublishable();
        }

        function applyCandidateIgnored(key, ignored) {
            const c = publishableServices.find(s => s.ignoreKey === key);
            if (!c) return;
            c.ignored = ignored;
            if (!isEditingMachineCard()) displayPeers(peers);
        }

        function toggleIgnoredCandidates(hostKey) {
            if (expandedIgnored.has(hostKey)) expandedIgnored.delete(hostKey);
            else expandedIgnored.add(hostKey);
            if (!isEditingMachineCard()) displayPeers(peers);
        }

        // Pull the backend's ApiError { message } out of a failed response so toasts are actionable
        // ("Subdomain already in use") instead of bare "HTTP 400"; falls back to the status code.
        async function apiErrorMessage(response) {
            try { const p = await response.json(); if (p && p.message) return p.message; } catch (e) { /* no/!json body */ }
            return `HTTP ${response.status}`;
        }

        // The published-service pending a delete-modal confirmation: { dnsAddress, pathPrefix, label }.
        let _pendingPublishedDelete = null;

        function deletePublishedService(dnsAddress, pathPrefix) {
            const label = pathPrefix ? `${dnsAddress}${pathPrefix}` : dnsAddress;
            // The backend keeps the DNS CNAME if any sibling route still uses this host
            // (PublishingService.deleteService -> hasSiblingOnHost), so only promise DNS removal when
            // this is the last route on dnsAddress. A sibling is another route on the same host.
            const hasSibling = publishedServices.some(p =>
                p.dnsAddress === dnsAddress && (p.pathPrefix || '') !== (pathPrefix || ''));
            _pendingPublishedDelete = { dnsAddress, pathPrefix, label };
            document.getElementById('deletePublishedLabel').textContent = label;
            document.getElementById('deletePublishedDnsNote').textContent = hasSibling
                ? `This removes its reverse-proxy route. The DNS record stays — other routes still use ${dnsAddress}.`
                : 'This removes its reverse-proxy route and DNS record.';
            document.getElementById('deletePublishedModal').classList.add('active');
        }

        function hideDeletePublishedModal() {
            document.getElementById('deletePublishedModal').classList.remove('active');
            _pendingPublishedDelete = null;
        }

        async function confirmDeletePublishedService() {
            if (!_pendingPublishedDelete) return;
            // Capture and clear immediately so a double-click can't fire two deletes.
            const { dnsAddress, pathPrefix, label } = _pendingPublishedDelete;
            hideDeletePublishedModal();
            // Deleting tears down the Traefik route (and the DNS record when it's the last on the host),
            // a multi-second operation — show the busy overlay so the page clearly looks like it's working.
            showBusy(`Deleting "${label}"…`, 'Tearing down the reverse-proxy route, and the DNS record when this was the last route on the host.');
            try {
                const base = `/published-services/${encodeURIComponent(dnsAddress)}`;
                const url  = pathPrefix ? `${base}?pathPrefix=${encodeURIComponent(pathPrefix)}` : base;
                const response = await fetch(url, { method: 'DELETE' });
                if (!response.ok) throw new Error(await apiErrorMessage(response));
                displaySuccess(`Deleted ${label}`);
                await fetchPublishedServices();
            } catch (e) {
                displayError(`Failed to delete ${label}: ${e.message}`);
            } finally {
                hideBusy();
            }
        }

        // True when an input/textarea inside a machine card holds focus — used to defer SSE/poll
        // re-renders so they can't wipe an in-progress edit (slice 2b).
        function isEditingMachineCard() {
            const a = document.activeElement;
            const c = document.getElementById('peers-container');
            return !!a && !!c && c.contains(a) && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA');
        }

        function togglePublished(uniqueName) {
            if (expandedPublished.has(uniqueName)) expandedPublished.delete(uniqueName);
            else expandedPublished.add(uniqueName);
            const id = cardId('pub:' + uniqueName);
            const body = document.getElementById('pub-body-' + id);
            if (!body) return;
            const open = body.classList.toggle('open');
            const header = body.previousElementSibling;
            if (header) {
                header.setAttribute('aria-expanded', open ? 'true' : 'false');
                const chevron = header.querySelector('.pub-chevron');
                if (chevron) chevron.classList.toggle('open', open);
            }
        }

        async function patchPublishedService(dnsAddress, pathPrefix, patch) {
            const base = `/published-services/${encodeURIComponent(dnsAddress)}`;
            const url  = pathPrefix ? `${base}?pathPrefix=${encodeURIComponent(pathPrefix)}` : base;
            const r = await fetch(url, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(patch),
            });
            if (!r.ok) throw new Error(await apiErrorMessage(r));
        }

        function pubFlashOk(input) {
            input.classList.add('save-ok');
            setTimeout(() => input.classList.remove('save-ok'), 900);
        }

        async function setPublishedAuthMode(dnsAddress, pathPrefix, authMode) {
            try { await patchPublishedService(dnsAddress, pathPrefix, { authMode }); await fetchPublishedServices(); }
            catch (e) { displayError(`Failed to update authentication: ${e.message}`); }
        }

        // Persist a Social service's access rule (its any-of allowed groups). An empty list clears the
        // rule server-side, meaning any signed-in, approved user may reach the service.
        async function putServiceAccessRule(host, groups) {
            const r = await fetch('/access/services/' + encodeURIComponent(host) + '/groups', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ groups }),
            });
            if (!r.ok) throw new Error(await apiErrorMessage(r));
        }

        // Add a group to a Social service's allowed-groups rule. Edits apply immediately (like the
        // auth-mode picker) — no separate save step, so the visible chips are always the live rule.
        async function addAllowedGroup(host, id) {
            const input = document.getElementById('pub-agroup-input-' + id);
            if (!input) return;
            const value = (input.value || '').trim();
            input.value = '';
            if (!value) return;
            const current = (_serviceAccessRules[host] || []);
            if (current.some(g => g.toLowerCase() === value.toLowerCase())) return;
            try {
                await putServiceAccessRule(host, current.concat(value));
                await fetchServiceAccessRules();
                await fetchAccessGroupSuggestions();
                if (!isEditingMachineCard()) displayPeers(peers);
            } catch (e) { displayError(`Failed to update allowed groups: ${e.message}`); }
        }

        async function removeAllowedGroup(host, group) {
            const next = (_serviceAccessRules[host] || []).filter(g => g !== group);
            try {
                await putServiceAccessRule(host, next);
                await fetchServiceAccessRules();
                if (!isEditingMachineCard()) displayPeers(peers);
            } catch (e) { displayError(`Failed to update allowed groups: ${e.message}`); }
        }

        async function setPublishedDirectUrlDisabled(dnsAddress, pathPrefix, directUrlDisabled) {
            try { await patchPublishedService(dnsAddress, pathPrefix, { directUrlDisabled }); await fetchPublishedServices(); }
            catch (e) { displayError(`Failed to update direct LAN URL setting: ${e.message}`); }
        }

        async function setPublishedHidden(dnsAddress, pathPrefix, hiddenFromLaunchpad) {
            try { await patchPublishedService(dnsAddress, pathPrefix, { hiddenFromLaunchpad }); await fetchPublishedServices(); }
            catch (e) { displayError(`Failed to update launchpad visibility: ${e.message}`); }
        }

        // Auto-save a single text field (display name / redirect) on blur, no-op when unchanged.
        async function savePublishedField(dnsAddress, pathPrefix, inputId, field) {
            const input = document.getElementById(inputId);
            if (!input) return;
            const value = input.value.trim();
            if (value === (input.dataset.original || '')) return;
            try {
                await patchPublishedService(dnsAddress, pathPrefix, { [field]: value });
                input.dataset.original = value;
                pubFlashOk(input);
                await fetchPublishedServices();
            } catch (e) { displayError(`Failed to save: ${e.message}`); }
        }

        async function savePublishedVersionEndpoint(dnsAddress, pathPrefix, id) {
            const ep = document.getElementById('pub-ve-' + id);
            const pr = document.getElementById('pub-vp-' + id);
            if (!ep || !pr) return;
            const endpoint = ep.value.trim(), property = pr.value.trim();
            if (endpoint === (ep.dataset.original || '') && property === (pr.dataset.original || '')) return;
            try {
                await patchPublishedService(dnsAddress, pathPrefix, { versionEndpoint: endpoint, versionProperty: property });
                ep.dataset.original = endpoint; pr.dataset.original = property;
                pubFlashOk(ep); pubFlashOk(pr);
                await fetchPublishedServices();
            } catch (e) { displayError(`Failed to save version endpoint: ${e.message}`); }
        }

        // Reset the publish modal's shared (mode-independent) fields to defaults.
        function resetPublishFormCommon() {
            document.getElementById('publishSubdomain').value        = '';
            document.getElementById('publishPathPrefix').value       = '';
            document.getElementById('publishRequiresAuth').checked   = false;
            document.getElementById('publishDirectUrlEnabled').checked = true;
            document.getElementById('publishRootRedirectPath').value = '';
            document.getElementById('publishAdvanced').open = false;
        }

        // Publish a discovered container as a reverse-proxy route (slice 2c) — the container-mode
        // half of the old Services-page publish modal, opened from a "+ Publish" candidate row.
        function showPublishModalForCandidate(btn) {
            const modal = document.getElementById('publishModal');
            modal.dataset.mode = 'container';
            document.getElementById('publishModalHeader').textContent = 'Publish service';
            resetPublishFormCommon();
            document.getElementById('publishAddress').value         = btn.dataset.address;
            document.getElementById('publishPort').value            = btn.dataset.port;
            document.getElementById('publishSubdomain').value       = btn.dataset.subdomain || '';
            document.getElementById('publishRootRedirectPath').value = btn.dataset.redirect || '';
            document.getElementById('publishAdvanced').open = !!btn.dataset.redirect;
            document.getElementById('publishServiceLabel').textContent =
                `${btn.dataset.container} (${btn.dataset.address}:${btn.dataset.port})`;
            modal.classList.add('active');
            document.getElementById('publishSubdomain').focus();
        }

        // Manually publish a bare host:port on a LAN host as a reverse-proxy route (slice 3b) — the
        // LAN-mode half of the old Services-page publish modal. The host (machineName) and its relay
        // come from the LAN-server card; the operator supplies port, protocol and subdomain.
        function showPublishLanModal(machineName, relayPeerName, lanAddress) {
            const modal = document.getElementById('publishModal');
            modal.dataset.mode = 'lan';
            document.getElementById('publishModalHeader').textContent = 'Publish LAN service';
            resetPublishFormCommon();
            document.getElementById('publishLanMachine').value  = machineName;
            document.getElementById('publishLanPort').value     = '';
            document.getElementById('publishLanProtocol').value = 'http';
            document.getElementById('publishLanLabel').textContent =
                `${machineName} (${lanAddress}) via ${relayPeerName}`;
            modal.classList.add('active');
            document.getElementById('publishSubdomain').focus();
        }

        function hidePublishModal() {
            document.getElementById('publishModal').classList.remove('active');
        }

        // Turn a failed publish response into a one-line explanation. On a 400/409 the backend sends
        // an ApiError envelope { message }; otherwise fall back to status-keyed guidance.
        async function explainPublishError(response) {
            let reason = '';
            try { const p = await response.json(); if (p && p.message) reason = p.message; } catch (e) { /* no/!json body */ }
            if (response.status === 400) return reason || 'The request was rejected — check the subdomain and path prefix.';
            if (response.status === 409) return reason || 'That subdomain or path is already in use. Pick a different one.';
            if (response.status >= 500) return 'Something went wrong on the server while publishing. Check the Vaier logs.';
            return reason || `Unexpected response (HTTP ${response.status}).`;
        }

        async function submitPublish() {
            const mode = document.getElementById('publishModal').dataset.mode || 'container';
            const subdomain = document.getElementById('publishSubdomain').value.trim();
            if (!subdomain) { alert('Please enter a subdomain'); return; }
            // Fields shared by both publish flows. The mode only changes which endpoint we hit and which
            // host/port fields it carries (a discovered container's address vs a manual LAN host:port).
            const common = {
                subdomain,
                pathPrefix:        document.getElementById('publishPathPrefix').value.trim() || null,
                directUrlDisabled: !document.getElementById('publishDirectUrlEnabled').checked,
                rootRedirectPath:  document.getElementById('publishRootRedirectPath').value.trim() || null,
            };
            const requiresAuth = document.getElementById('publishRequiresAuth').checked;
            let endpoint, body;
            if (mode === 'lan') {
                const port = parseInt(document.getElementById('publishLanPort').value, 10);
                if (!Number.isInteger(port) || port < 1 || port > 65535) {
                    alert('Enter a valid port (1–65535).'); return;
                }
                endpoint = '/published-services/lan';
                // The LAN API field is requireAuth (singular), not requiresAuth — quirk preserved.
                body = { ...common, machineName: document.getElementById('publishLanMachine').value,
                         port, protocol: document.getElementById('publishLanProtocol').value, requireAuth: requiresAuth };
            } else {
                // The port comes from a hidden field populated when the modal opens; validate it
                // explicitly so a missing/tampered value fails here with a clear message instead of
                // serialising to a null port and surfacing as a confusing backend validation error.
                const port = parseInt(document.getElementById('publishPort').value, 10);
                if (!Number.isInteger(port) || port < 1 || port > 65535) {
                    alert('Invalid service port — reopen the publish dialog and try again.'); return;
                }
                endpoint = '/published-services/publish';
                body = { ...common, address: document.getElementById('publishAddress').value, port, requiresAuth };
            }
            const submitBtn = document.getElementById('publishSubmitBtn');
            const cancelBtn = document.getElementById('publishCancelBtn');
            submitBtn.disabled = cancelBtn.disabled = true;
            submitBtn.textContent = 'Publishing…';
            try {
                const r = await fetch(endpoint, {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
                });
                if (!r.ok) { alert(`Failed to publish.\n\n${await explainPublishError(r)}`); return; }
                hidePublishModal();
                // The publish is async (DNS create -> propagation -> Traefik). Show a live progress
                // card that the published-services SSE steps advance to success or failure.
                addPublishProgress(subdomain);
                fetchPublishable();
            } catch (e) {
                alert(`Failed to publish. Vaier could not be reached (${e.message}).`);
            } finally {
                submitBtn.disabled = cancelBtn.disabled = false;
                submitBtn.textContent = 'Publish';
            }
        }

        // Publish progress cards (#infrastructure slice 3 — parity with the old Services page's
        // processing section). A publish runs async server-side and reports via the published-services
        // SSE: publish-dns-created -> publish-dns-propagated -> publish-traefik-active (success), or
        // publish-dns-timeout / publish-rolled-back (failure). Each in-flight publish gets a floating,
        // non-blocking card keyed by subdomain that advances through three steps.
        const PUB_PROG_ICONS = { pending: '·', active: '⏳', done: '✓', failed: '✕' };

        function addPublishProgress(subdomain) {
            const host = document.getElementById('publishProgress');
            if (!host) return;
            const id = cardId(subdomain);
            if (document.getElementById('pubprog-' + id)) return; // already tracking this one
            const card = document.createElement('div');
            card.className = 'pub-prog-card';
            card.id = 'pubprog-' + id;
            card.innerHTML = `
                <div class="pub-prog-head">
                    <span class="pub-prog-title"></span>
                    <button class="pub-prog-close" title="Dismiss" onclick="removePublishProgress('${jsArg(subdomain)}')">✕</button>
                </div>
                <div class="pub-prog-steps">
                    <div class="pub-prog-step active" data-step="dns-create"><span class="pp-icon">⏳</span><span>DNS record created</span></div>
                    <div class="pub-prog-step" data-step="dns-propagate"><span class="pp-icon">·</span><span>DNS propagation</span></div>
                    <div class="pub-prog-step" data-step="traefik"><span class="pp-icon">·</span><span>Reverse-proxy route</span></div>
                </div>
                <div class="pub-prog-msg"></div>`;
            card.querySelector('.pub-prog-title').textContent = subdomain; // textContent — never inject
            host.appendChild(card);
        }

        function setPublishStep(subdomain, stepKey, state) {
            const card = document.getElementById('pubprog-' + cardId(subdomain));
            if (!card) return;
            const step = card.querySelector('.pub-prog-step[data-step="' + stepKey + '"]');
            if (!step) return;
            step.classList.remove('active', 'done', 'failed');
            if (state !== 'pending') step.classList.add(state);
            step.querySelector('.pp-icon').textContent = PUB_PROG_ICONS[state] || '·';
        }

        function completePublishProgress(subdomain) {
            setPublishStep(subdomain, 'traefik', 'done');
            const card = document.getElementById('pubprog-' + cardId(subdomain));
            if (!card) return;
            card.classList.add('done');
            const msg = card.querySelector('.pub-prog-msg');
            if (msg) msg.textContent = 'Published — live now.';
            setTimeout(() => removePublishProgress(subdomain), 3500);
        }

        function failPublishProgress(subdomain, message) {
            const card = document.getElementById('pubprog-' + cardId(subdomain));
            if (!card) return;
            card.classList.add('failed');
            // Mark whichever step was in flight as the one that failed.
            const step = card.querySelector('.pub-prog-step.active')
                || card.querySelector('.pub-prog-step:not(.done)');
            if (step) {
                step.classList.remove('active');
                step.classList.add('failed');
                step.querySelector('.pp-icon').textContent = PUB_PROG_ICONS.failed;
            }
            const msg = card.querySelector('.pub-prog-msg');
            if (msg) msg.textContent = message;
            setTimeout(() => removePublishProgress(subdomain), 12000);
        }

        function removePublishProgress(subdomain) {
            const card = document.getElementById('pubprog-' + cardId(subdomain));
            if (card) card.remove();
        }

        // Rebuild progress cards for any publish already in flight when the page (re)loads, so a
        // refresh mid-publish doesn't lose the indicator. Mirrors the old page's fetchPending.
        async function fetchPublishProgress() {
            try {
                const r = await fetch('/published-services/pending');
                if (!r.ok) return;
                (await r.json()).forEach(p => {
                    addPublishProgress(p.subdomain);
                    setPublishStep(p.subdomain, 'dns-create', 'done');
                    if (p.dnsPropagated) {
                        setPublishStep(p.subdomain, 'dns-propagate', 'done');
                        setPublishStep(p.subdomain, 'traefik', 'active');
                    } else {
                        setPublishStep(p.subdomain, 'dns-propagate', 'active');
                    }
                });
            } catch (e) { /* ignore — progress is best-effort */ }
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
            // One adaptive setup script per host (#249): shown when the host runs Docker or is
            // relay-anchored (so it has routes to install). Server-anchored / orphan hosts get none.
            const isRelayAnchored = server.relayPeerName && server.relayPeerName !== 'Vaier server';
            const scriptBtn = (server.runsDocker || isRelayAnchored)
                ? `<button class="btn btn-small btn-secondary" onclick="showLanSetupScript('${jsArg(server.name)}')" title="Show this host's setup script — opens the Docker API if it runs Docker and installs routes via its relay peer">Setup script</button>`
                : '';
            // Manual LAN publish (#infrastructure slice 3b): publish a bare host:port on this LAN host
            // as a reverse-proxy route. Only offered when a relay covers it — without one the route
            // can't be reached, and the publish would fail backend validation.
            const publishLanBtn = server.relayPeerName
                ? `<button class="btn btn-small btn-secondary" onclick="showPublishLanModal('${jsArg(server.name)}','${jsArg(server.relayPeerName)}','${jsArg(server.lanAddress)}')" title="Publish a port on this LAN host as a reverse-proxy route">+ Publish LAN port</button>`
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
                        ${renderServicesSubsection(server.name)}
                    </div>
                    <div class="peer-actions-row">
                        <div class="peer-actions-left">
                            ${scriptBtn}
                            ${publishLanBtn}
                        </div>
                        <button class="btn btn-small btn-danger" onclick="confirmDeleteLanServer('${jsArg(server.name)}')">Delete</button>
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
                        ${renderServicesSubsection('__hub__')}
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
                        ${renderServicesSubsection(peer.name)}
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

            // Rebuild the published-services-by-host index (slice 2) so each card shows its routes.
            _publishedByHost = topologyServicesByHost(publishedServices);

            // Rebuild the "+ Publish" candidate index (slice 2c/3): discoverable containers grouped by
            // machine, dropping ones already published (matched on the backend address+port a published
            // route points at). Ignored candidates go into a separate index so each card can reveal them
            // on demand (slice 3 — ignore/unignore parity). Precompute the published address|port pairs
            // into a Set so the membership test is O(1) — the whole pass is linear, not O(publishable×published).
            const publishedHostPorts = new Set(publishedServices.map(p => p.hostAddress + '|' + p.hostPort));
            _candidatesByHost = {};
            _ignoredCandidatesByHost = {};
            publishableServices.forEach(c => {
                if (publishedHostPorts.has(c.address + '|' + c.port)) return;
                const key = publishableHostKey(c);
                if (!key) return;
                const index = c.ignored ? _ignoredCandidatesByHost : _candidatesByHost;
                (index[key] = index[key] || []).push(c);
            });

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

        // LAN-server deletion goes through the shared "Delete Machine" modal (confirmDeleteLanServer
        // → confirmDeleteMachine), same as peers — see the delete-machine block further down.

        // Busy indicator (#202) — create + regenerate hit WireGuard / keygen / config writes and
        // can take a few seconds on slow hosts. Without immediate feedback the user assumes
        // the page is dead.
        // sub defaults to the peer-creation detail (create/regenerate/reissue); delete flows pass their
        // own so the overlay never claims to be "generating keypair…" while it's tearing something down.
        const BUSY_SUB_DEFAULT = 'This can take a few seconds — generating keypair, writing config, and reloading WireGuard.';
        function showBusy(message, sub) {
            document.getElementById('busyMessage').textContent = message;
            document.getElementById('busySub').textContent = sub || BUSY_SUB_DEFAULT;
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
                // Published services are joined onto machine cards client-side by the resolved
                // LAN-server name (topologyServicesByHost keys on lanServerName, computed server-side).
                // A rename shifts that key, so refetch the service layer too — otherwise the stale
                // publishedServices still carry the old name and the renamed card shows no services (#300).
                fetchLanServers();
                fetchPublishedServices();
                fetchPublishable();
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
            const cls = primary ? 'btn btn-primary' : 'btn btn-secondary';
            // filename and label derive from the operator-controlled peer name. jsArg neutralises
            // JS-string + HTML-attribute breakouts for the onclick args; escapeHtml guards the label.
            // (downloadInline keys off _showOncePayloads by filename, so no data attribute is needed.)
            return `<button class="${cls}" onclick="downloadInline('${jsArg(filename)}', '${jsArg(mimeType)}', this)">${escapeHtml(label)}</button>`;
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
                    <pre id="configPre">${escapeHtml(peer.configFile)}</pre>
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


        // Pending machine deletion, routed through the shared "Delete Machine" modal.
        // { kind: 'peer' | 'lan', key, name } — peers are keyed by id, LAN servers by name.
        let _pendingMachineDelete = null;

        function confirmDeletePeer(peerId) {
            openDeleteMachineModal({ kind: 'peer', key: peerId, name: peerDisplayName(peerId) });
        }

        function confirmDeleteLanServer(name) {
            openDeleteMachineModal({ kind: 'lan', key: name, name });
        }

        function openDeleteMachineModal(pending) {
            _pendingMachineDelete = pending;
            document.getElementById('deletePeerName').textContent = pending.name;
            document.getElementById('deleteConfirmModal').classList.add('active');
        }

        function hideDeleteConfirmModal() {
            document.getElementById('deleteConfirmModal').classList.remove('active');
            _pendingMachineDelete = null;
        }

        async function confirmDeleteMachine() {
            if (!_pendingMachineDelete) return;
            // Capture the target and clear it immediately so a second click can't fire a duplicate
            // delete while the first request is in flight.
            const { kind, key, name } = _pendingMachineDelete;
            hideDeleteConfirmModal();
            // Deleting a machine cascades into published-service cleanup (Traefik + DNS), a
            // multi-second operation — show the busy overlay so the page clearly looks like it's working.
            showBusy(`Deleting "${name}"…`, 'Removing the machine and any published services routing to it (Traefik + DNS).');
            const url = kind === 'lan'
                ? `/lan-servers/${encodeURIComponent(key)}`
                : `/vpn/peers/${encodeURIComponent(key)}`;
            try {
                const response = await fetch(url, { method: 'DELETE' });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`${kind === 'lan' ? 'LAN server' : 'Peer'} "${name}" removed`);
                if (kind === 'lan') fetchLanServers(); else fetchPeers();
            } catch (error) {
                displayError(`Failed to delete: ${error.message}`);
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

        // _peerMap and _markersClusterGroup are declared in vpn-peers-map.js (which owns and assigns
        // them); this file only reads _peerMap. _serverLocation is shared with the map + diagram.
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
        }

        // A published service's health colour mirrors the Services page: state OK -> green,
        // UNKNOWN -> grey (host not yet probed), anything else (UNREACHABLE) -> red. Same
        // 'up'|'down'|'unknown' status-key convention as the peer/LAN nodes.
        function statusKeyForService(s) {
            if (s.state === 'OK') return 'up';
            if (s.state === 'UNKNOWN') return 'unknown';
            return 'down';
        }

        function topologyServicesByHost(services) {
            const byHost = {};
            (services || []).forEach(s => {
                let key;
                if (s.isLanService) {
                    // lanServerName can be null when no registered LAN server matches the address
                    // (see GetPublishedServicesUseCase); fall back to the relay peer (hostName) so the
                    // route stays visible instead of vanishing from the graph and the card section.
                    key = s.lanServerName || s.hostName;
                } else if (!s.hostName || !String(s.hostName).trim() || s.hostName === 'Vaier server') {
                    // Blank or the hub's display name both anchor on the centre node ('__hub__').
                    key = '__hub__';
                } else {
                    key = s.hostName;
                }
                if (!key) return;
                (byHost[key] = byHost[key] || []).push(s);
            });
            return byHost;
        }

        async function fetchServerLocation() {
            // Always fetch — the response carries both the Map tab's lat/lon and the Vaier-server
            // machine card's LAN CIDR (#204). Either group can be absent (null lat/lon when the
            // geoip MMDB isn't in place; null lanCidr when neither env nor IMDS yields one).
            try {
                const res = await fetch('/vpn/peers/server-location');
                if (!res.ok) return;
                _serverLocation = await res.json();
                if (_peerMap) refreshPeerMap();
                // Re-render the Machines list so the Vaier server card picks up the LAN CIDR — even
                // on an empty net (zero peers), where the hub/LAN cards still need the update.
                displayPeers(peers);
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
        fetchAppConfig();
        fetchPeers();
        fetchVaierServerServices();
        fetchLanServers();
        fetchServerLocation();
        fetchPublishedServices();
        fetchPublishable();
        fetchServiceAccessRules();
        fetchAccessGroupSuggestions();
        fetchPublishProgress();
        detectEdition();

        const _sse = new EventSource('/vpn/peers/events');
        _sse.addEventListener('peers-updated', () => fetchPeers());
        // A LAN-server change (rename/register/delete) can shift the name-based join that hangs
        // published services off machine cards, so refresh the service layer alongside the servers
        // (#300) — same reason saveLanServerName refetches both.
        _sse.addEventListener('lan-servers-updated', () => { fetchLanServers(); fetchPublishedServices(); fetchPublishable(); });

        // Keep the machine cards' Services rows fresh: published-services health/changes arrive on
        // their own SSE stream (#infrastructure slice 1). A separate EventSource keeps this seam
        // independent of the peers stream — same DTO the Services page consumes.
        const _servicesSse = new EventSource('/published-services/events');
        _servicesSse.addEventListener('service-updated', () => { fetchPublishedServices(); fetchPublishable(); });
        // Publish progress (slice 3): a publish is async (DNS create -> propagation -> Traefik). Advance
        // the floating progress card through each step so the operator sees what's happening, and
        // reconcile the list/diagram on the terminal events. addPublishProgress is idempotent, so an
        // event arriving before submitPublish created the card (or after a reload) still shows progress.
        _servicesSse.addEventListener('publish-dns-created', e => {
            addPublishProgress(e.data);
            setPublishStep(e.data, 'dns-create', 'done');
            setPublishStep(e.data, 'dns-propagate', 'active');
        });
        _servicesSse.addEventListener('publish-dns-propagated', e => {
            setPublishStep(e.data, 'dns-propagate', 'done');
            setPublishStep(e.data, 'traefik', 'active');
        });
        _servicesSse.addEventListener('publish-traefik-active', e => {
            completePublishProgress(e.data);
            fetchPublishedServices(); fetchPublishable();
        });
        _servicesSse.addEventListener('publish-rolled-back', e => {
            failPublishProgress(e.data, 'Rolled back — nothing was left half-configured. You can try again.');
            fetchPublishable();
        });
        _servicesSse.addEventListener('publish-dns-timeout', e => {
            failPublishProgress(e.data, "DNS didn't resolve in time. The record was created but didn't propagate; try again.");
            fetchPublishable();
        });
        _sse.addEventListener('peers-stats', e => {
            try {
                const stats = JSON.parse(e.data);
                let mapNeedsRefresh = false;
                stats.forEach(s => {
                    // s.name is the peer's id (the WireGuard dir name, e.g. "Colina-27") — the stats
                    // payload field is misnamed; resolvePeerNameByIp returns the dir name. Build the
                    // DOM id through the same cardId the cards use (cardId(peer.id)) so they agree.
                    const id = cardId(s.name);
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
