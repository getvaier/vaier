// Extracted from vpn-peers.html (#273). Classic script — runs in global scope so the page's
// inline on* handlers keep resolving. Modularisation tracked as follow-up slices of #273.
        let peers = [];
        let peerServices = {};
        let vaierServerServices = [];
        let vaierServerStatus = 'UNKNOWN'; // domain MachineStatus enum value
        let lanServers = [];
        let lanServerServices = {};
        // Published services (Topology tab, #infrastructure slice 1; machine cards, slice 2). Same
        // DTO the Services page discovers; we client-side join each one onto its host machine.
        let publishedServices = [];
        // hostKey -> [service] index (slice 2), rebuilt from publishedServices on each list render so
        // every machine card can show the published routes it hosts. Keyed like topologyServicesByHost
        // ('__hub__', a peer name, or a LAN-server name).
        let _publishedByHost = {};
        // Discoverable-but-unpublished containers, the "+ Publish" candidates folded into the same
        // Services list (slice 2c). publishableServices is the raw /publishable feed; _candidatesByHost
        // is the per-machine index built in displayPeers (ignored + already-published entries dropped).
        let publishableServices = [];
        let _candidatesByHost = {};
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

        // Published services for the Topology tab (#infrastructure slice 1). Loaded alongside
        // peers/lanServers and refreshed on the same triggers; the diagram joins each service
        // onto its host machine node (see topologyServicesByHost). Failures are non-fatal — the
        // diagram simply renders without service nodes.
        async function fetchPublishedServices() {
            try {
                const response = await fetch('/published-services/discover', { cache: 'no-store' });
                if (response.ok) {
                    publishedServices = await response.json();
                    // Re-render the list so each machine card's Services subsection (slice 2) picks
                    // up the change, and the diagram if it's the visible tab. Skip the list re-render
                    // while an input inside a card is focused, so an SSE-driven refresh can't wipe an
                    // in-progress edit out from under the operator (a later event reconciles).
                    if (peers && peers.length && !isEditingMachineCard()) displayPeers(peers);
                    if (_activeTab === 'topology') renderNetworkDiagram();
                }
            } catch (error) {
                console.error('Failed to load published services:', error);
            }
        }

        // The discoverable "+ Publish" candidates (slice 2c). Loaded alongside published services and
        // refreshed on the same triggers; displayPeers indexes them per machine.
        async function fetchPublishable() {
            try {
                const response = await fetch('/published-services/publishable', { cache: 'no-store' });
                if (response.ok) {
                    publishableServices = await response.json();
                    if (peers && peers.length && !isEditingMachineCard()) displayPeers(peers);
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
            const authBadge  = s.authenticated
                ? `<span class="pub-badge pub-auth" title="Authelia authentication required">auth</span>`
                : `<span class="pub-badge pub-noauth" title="Public — no authentication required">no auth</span>`;
            return `<div class="published-entry">
                <div class="published-item" onclick="togglePublished('${jsArg(uniqueName)}')">
                    <span class="pub-status icon-${statusKey}" title="${statusKey}"></span>
                    <span class="pub-name" title="${escapeHtml(display)}">${escapeHtml(label)}</span>
                    ${authBadge}
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
                    <input type="checkbox" id="pub-auth-${id}" ${s.authenticated ? 'checked' : ''}
                        style="accent-color:var(--accent);cursor:pointer" title="Require Authelia authentication to reach this service."
                        onchange="setPublishedAuth('${dns}','${path}',this.checked)"></span></div>
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

        // A discoverable container that isn't published yet — a "+ Publish" row in the Services list
        // (slice 2c). Not expandable; the button opens the publish modal pre-filled from its dataset.
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
            </div>`;
        }

        // One Services section per machine (slice 2c): published routes (editable) first, then the
        // host's discoverable-but-unpublished containers as "+ Publish" rows. Replaces both the old
        // discovered-containers list and the published-only subsection.
        function renderServicesSubsection(hostKey) {
            const published  = (_publishedByHost[hostKey] || []).slice()
                .sort((a, b) => (a.launchpadAlias || a.shortName || a.name || '')
                    .localeCompare(b.launchpadAlias || b.shortName || b.name || ''));
            const candidates = (_candidatesByHost[hostKey] || []).slice()
                .sort((a, b) => a.containerName.localeCompare(b.containerName) || a.port - b.port);
            if (published.length === 0 && candidates.length === 0) return '';
            const rows = published.map(renderPublishedRow).join('') + candidates.map(renderCandidateRow).join('');
            return `<div class="detail-row">
                <span class="detail-label">Services</span>
                <div class="published-list">${rows}</div>
            </div>`;
        }

        async function deletePublishedService(dnsAddress, pathPrefix) {
            const label = pathPrefix ? `${dnsAddress}${pathPrefix}` : dnsAddress;
            if (!confirm(`Delete published service "${label}"?\n\nThis removes its reverse-proxy route and DNS record.`)) return;
            try {
                const base = `/published-services/${encodeURIComponent(dnsAddress)}`;
                const url  = pathPrefix ? `${base}?pathPrefix=${encodeURIComponent(pathPrefix)}` : base;
                const response = await fetch(url, { method: 'DELETE' });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                displaySuccess(`Deleted ${label}`);
                await fetchPublishedServices();
            } catch (e) {
                displayError(`Failed to delete ${label}: ${e.message}`);
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
            body.classList.toggle('open');
            const chevron = body.previousElementSibling
                && body.previousElementSibling.querySelector('.pub-chevron');
            if (chevron) chevron.classList.toggle('open');
        }

        async function patchPublishedService(dnsAddress, pathPrefix, patch) {
            const base = `/published-services/${encodeURIComponent(dnsAddress)}`;
            const url  = pathPrefix ? `${base}?pathPrefix=${encodeURIComponent(pathPrefix)}` : base;
            const r = await fetch(url, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(patch),
            });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
        }

        function pubFlashOk(input) {
            input.classList.add('save-ok');
            setTimeout(() => input.classList.remove('save-ok'), 900);
        }

        async function setPublishedAuth(dnsAddress, pathPrefix, requiresAuth) {
            try { await patchPublishedService(dnsAddress, pathPrefix, { requiresAuth }); await fetchPublishedServices(); }
            catch (e) { displayError(`Failed to update authentication: ${e.message}`); }
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

        // Publish a discovered container as a reverse-proxy route (slice 2c) — the container-mode
        // half of the old Services-page publish modal, opened from a "+ Publish" candidate row.
        function showPublishModalForCandidate(btn) {
            document.getElementById('publishAddress').value         = btn.dataset.address;
            document.getElementById('publishPort').value            = btn.dataset.port;
            document.getElementById('publishSubdomain').value       = btn.dataset.subdomain || '';
            document.getElementById('publishPathPrefix').value      = '';
            document.getElementById('publishRequiresAuth').checked  = false;
            document.getElementById('publishDirectUrlEnabled').checked = true;
            document.getElementById('publishRootRedirectPath').value = btn.dataset.redirect || '';
            document.getElementById('publishAdvanced').open = !!btn.dataset.redirect;
            document.getElementById('publishServiceLabel').textContent =
                `${btn.dataset.container} (${btn.dataset.address}:${btn.dataset.port})`;
            document.getElementById('publishModal').classList.add('active');
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
            const subdomain = document.getElementById('publishSubdomain').value.trim();
            if (!subdomain) { alert('Please enter a subdomain'); return; }
            const body = {
                address:          document.getElementById('publishAddress').value,
                port:             parseInt(document.getElementById('publishPort').value),
                subdomain,
                pathPrefix:       document.getElementById('publishPathPrefix').value.trim() || null,
                requiresAuth:     document.getElementById('publishRequiresAuth').checked,
                directUrlDisabled: !document.getElementById('publishDirectUrlEnabled').checked,
                rootRedirectPath: document.getElementById('publishRootRedirectPath').value.trim() || null,
            };
            const submitBtn = document.getElementById('publishSubmitBtn');
            const cancelBtn = document.getElementById('publishCancelBtn');
            submitBtn.disabled = cancelBtn.disabled = true;
            submitBtn.textContent = 'Publishing…';
            try {
                const r = await fetch('/published-services/publish', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
                });
                if (!r.ok) { alert(`Failed to publish.\n\n${await explainPublishError(r)}`); return; }
                hidePublishModal();
                // The publish is async (DNS propagation + Traefik). Surface progress as a toast; the
                // published-services SSE (publish-traefik-active / -rolled-back / -dns-timeout) reconciles.
                displaySuccess(`Publishing ${subdomain}… DNS can take up to a minute to propagate.`);
                fetchPublishable();
            } catch (e) {
                alert(`Failed to publish. Vaier could not be reached (${e.message}).`);
            } finally {
                submitBtn.disabled = cancelBtn.disabled = false;
                submitBtn.textContent = 'Publish';
            }
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

            // Rebuild the "+ Publish" candidate index (slice 2c): discoverable containers grouped by
            // machine, dropping ones the operator ignored and ones already published (matched on the
            // backend address+port a published route points at).
            _candidatesByHost = {};
            publishableServices.forEach(c => {
                if (c.ignored) return;
                if (publishedServices.some(p => p.hostAddress === c.address && p.hostPort === c.port)) return;
                const key = publishableHostKey(c);
                if (!key) return;
                (_candidatesByHost[key] = _candidatesByHost[key] || []).push(c);
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

        // LAN-server deletion goes through the shared "Delete Machine" modal (confirmDeleteLanServer
        // → confirmDeleteMachine), same as peers — see the delete-machine block further down.

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
            showBusy(`Deleting "${name}"…`);
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
            // Cytoscape lays out against its container's pixel size, only known once the panel is
            // visible (display:none reports clientWidth 0). Resize + fit in case it was built while
            // hidden or the viewport changed since it last showed.
            if (tab === 'topology') {
                setTimeout(() => {
                    renderNetworkDiagram();
                    if (_cy) { _cy.resize(); _cy.fit(undefined, 40); }
                }, 0);
            }
        }

        // Topology force graph (Cytoscape + cola). The Vaier hub, every VPN peer, each LAN server,
        // and every published service are nodes; cola runs a continuous physics simulation (nodes
        // repel, edges pull) so the graph self-spaces, reflows live as you drag, and re-settles when
        // the fleet changes — replacing the old fixed-radius SVG fan that crowded badly. Reuses the
        // same icons, status colours and data
        // (`peers`, `lanServers`, `publishedServices`, `_serverLocation`) as the List/Map tabs.
        // See buildTopologyElements / renderNetworkDiagram below.
        let _cy = null;          // the Cytoscape instance (created lazily once the panel is visible)
        let _topoSig = null;     // signature of the current node set; re-layout only when it changes
        let _topoTip = null;     // floating hover-tooltip element
        let _topoLayout = null;  // the running cola layout handle (continuous physics)

        // A published service's health colour mirrors the Services page: state OK -> green,
        // UNKNOWN -> grey (host not yet probed), anything else (UNREACHABLE) -> red. Same
        // 'up'|'down'|'unknown' status-key convention as the peer/LAN nodes.
        function statusKeyForService(s) {
            if (s.state === 'OK') return 'up';
            if (s.state === 'UNKNOWN') return 'unknown';
            return 'down';
        }

        // Pure join: each published service hangs off exactly one host machine node in the
        // diagram (#infrastructure slice 1). Mirrors the nesting rule the List/launchpad use —
        //   isLanService           -> the LAN server named lanServerName
        //   empty/blank hostName    -> the Vaier hub (centre node, key '__hub__')
        //   otherwise               -> the VPN peer whose name === hostName
        // Returns a map of hostKey -> [services]. hostKey is '__hub__' for the centre, the LAN
        // server name for LAN services, or the peer name for peer-hosted services. The caller
        // drops any hostKey it can't resolve to a node, so a service on an absent machine is
        // simply not drawn (no crash).
        // serviceLocation -> machine-icon kind. Defined locally because the Machines page does
        // not load published-services.js (which has its own copy); the slice-3 page merge dedupes.
        function serviceTypeIcon(service) {
            switch (service.serviceLocation) {
                case 'LAN_SERVICE':  return 'lan';
                case 'VAIER_SERVER': return 'vaier';
                default:             return 'server';
            }
        }

        function topologyServicesByHost(services) {
            const byHost = {};
            (services || []).forEach(s => {
                let key;
                if (s.isLanService) {
                    key = s.lanServerName;
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

        // Resolve the theme palette from CSS custom properties so the graph tracks the stylesheet
        // (Cytoscape's own stylesheet can't read var()). Concrete fallbacks mirror styles.css.
        function topoPalette() {
            const cs = getComputedStyle(document.documentElement);
            const v = (name, fb) => (cs.getPropertyValue(name).trim() || fb);
            return {
                up:       v('--green',     '#4ec9b0'),
                down:     v('--red',       '#f48771'),
                degraded: v('--yellow',    '#dcdcaa'),
                unknown:  v('--text-dim',  '#5a5a5a'),
                node:     v('--bg-panel',  '#252526'),
                edge:     v('--text-muted','#858585'),
                text:     v('--text',      '#d4d4d4'),
                accent:   v('--accent',    '#4fc3f7'),
            };
        }

        function topoStatusColour(pal, statusKey) {
            return pal[statusKey] || pal.unknown;
        }

        // machineIconSvg(kind) paints with currentColor; bake a concrete colour in and encode it as
        // a data URI Cytoscape can use as a node background-image. machineIconSvg omits the xmlns
        // (fine for inline SVG, but a data-URI image won't render without it), so inject it here.
        function topoIconUri(kind, colour) {
            const svg = machineIconSvg(kind)
                .replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ')
                .replace(/currentColor/g, colour);
            return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
        }

        // Build the Cytoscape element list from peers / lanServers / publishedServices. An edge is
        // only added when both endpoints exist as nodes, so a service (or LAN server) whose host
        // isn't drawn is simply skipped rather than producing a dangling edge.
        function buildTopologyElements(pal) {
            const els = [];
            const hasNode = id => els.some(e => e.data.id === id && !e.data.source);

            const hubSub = (_serverLocation && _serverLocation.publicHost) ? _serverLocation.publicHost : '';
            els.push({ data: { id: '__hub__', label: 'Vaier server', type: 'hub', status: 'up',
                               icon: topoIconUri('vaier', pal.up), sub: hubSub } });

            peers.forEach(p => {
                const sk = statusKeyForPeer(p);
                const id = 'peer:' + p.name;
                els.push({ data: { id, label: p.name, type: 'machine', status: sk,
                                   icon: topoIconUri(deviceCategoryIconKind(p.deviceCategory), topoStatusColour(pal, sk)),
                                   sub: p.tunnelIp || p.endpointIp || '' } });
                els.push({ data: { id: 'e:hub>' + id, source: '__hub__', target: id, status: sk } });
            });

            lanServers.forEach(s => {
                if (!s.relayPeerName) return;
                const srcId = s.relayPeerName === 'Vaier server' ? '__hub__' : 'peer:' + s.relayPeerName;
                if (!hasNode(srcId)) return; // relay peer not drawn — nothing to anchor to
                const sk = statusKeyForLanServer(s);
                const id = 'lan:' + s.name;
                els.push({ data: { id, label: s.name, type: 'machine', status: sk,
                                   icon: topoIconUri(deviceCategoryIconKind(s.deviceCategory), topoStatusColour(pal, sk)),
                                   sub: s.lanAddress || '' } });
                els.push({ data: { id: 'e:' + srcId + '>' + id, source: srcId, target: id, status: sk } });
            });

            // Each published service hangs off the machine that hosts it. hostKey is '__hub__', a
            // peer name, or a LAN-server name (see topologyServicesByHost); resolve it to a node id.
            const byHost = topologyServicesByHost(publishedServices);
            Object.keys(byHost).forEach(hostKey => {
                let hostId = null;
                if (hostKey === '__hub__') hostId = '__hub__';
                else if (hasNode('peer:' + hostKey)) hostId = 'peer:' + hostKey;
                else if (hasNode('lan:' + hostKey)) hostId = 'lan:' + hostKey;
                if (!hostId) return; // host machine not in the diagram — skip its services
                byHost[hostKey].forEach((s, i) => {
                    const sk = statusKeyForService(s);
                    const id = 'svc:' + hostKey + ':' + i;
                    els.push({ data: { id, type: 'service', status: sk,
                                       label: s.launchpadAlias || s.shortName || s.name || '',
                                       icon: topoIconUri(serviceTypeIcon(s), topoStatusColour(pal, sk)),
                                       sub: s.authenticated ? 'Auth required' : 'Public (no auth)' } });
                    els.push({ data: { id: 'e:' + hostId + '>' + id, source: hostId, target: id, status: sk } });
                });
            });
            return els;
        }

        function topoStyle(pal) {
            const nodeBorder = st => ({ selector: `node[status="${st}"]`, style: { 'border-color': topoStatusColour(pal, st) } });
            const edgeColour = st => ({ selector: `edge[status="${st}"]`, style: { 'line-color': topoStatusColour(pal, st) } });
            return [
                { selector: 'node', style: {
                    'background-color': pal.node,
                    'background-image': 'data(icon)',
                    'background-fit': 'none',
                    'background-width': '55%',
                    'background-height': '55%',
                    'border-width': 2.5,
                    'width': 44, 'height': 44,
                    'label': 'data(label)',
                    'color': pal.text,
                    'font-size': 10,
                    'text-valign': 'bottom',
                    'text-halign': 'center',
                    'text-margin-y': 4,
                    'text-max-width': 92,
                    'text-wrap': 'ellipsis',
                    'min-zoomed-font-size': 7,
                } },
                { selector: 'node[type="hub"]',     style: { 'width': 66, 'height': 66, 'font-size': 12, 'border-color': pal.up, 'border-width': 3 } },
                { selector: 'node[type="service"]', style: { 'width': 28, 'height': 28, 'font-size': 9, 'border-width': 2 } },
                nodeBorder('up'), nodeBorder('down'), nodeBorder('degraded'), nodeBorder('unknown'),
                { selector: 'edge', style: {
                    'width': 1.6,
                    'curve-style': 'bezier',
                    'line-color': pal.edge,
                    'opacity': 0.55,
                    'target-arrow-shape': 'none',
                } },
                edgeColour('up'), edgeColour('degraded'),
                { selector: 'edge[status="down"]',    style: { 'line-color': pal.down, 'line-style': 'dashed' } },
                { selector: 'edge[status="unknown"]', style: { 'line-style': 'dashed', 'opacity': 0.4 } },
                { selector: 'node:selected', style: { 'border-color': pal.accent, 'border-width': 3 } },
            ];
        }

        // cola is a continuous force simulation: it keeps iterating (infinite:true), so dragging a
        // node tugs its neighbours through the springs in real time. Services hug their host (short
        // edges) while peers sit further from the hub. randomize is only used for a fresh layout —
        // resumes continue from current positions so the graph doesn't teleport. fit is handled
        // manually (an infinite layout never emits layoutstop).
        function topoLayout(randomize) {
            return {
                name: 'cola',
                animate: true,
                infinite: true,
                fit: false,
                randomize: randomize === true,
                handleDisconnected: true,
                nodeSpacing: 14,
                edgeLength: e => (e.target().data('type') === 'service' ? 60 : 130),
            };
        }

        // (Re)start the continuous cola simulation. Stops any prior run first so we never stack two
        // infinite layouts. Fits once after a short settle (infinite layouts never self-fit).
        function startTopoLayout(randomize) {
            if (!_cy) return;
            if (_topoLayout) { try { _topoLayout.stop(); } catch (e) { /* already stopped */ } }
            _topoLayout = _cy.layout(topoLayout(randomize));
            _topoLayout.run();
            setTimeout(() => { if (_cy && _activeTab === 'topology') _cy.fit(undefined, 40); }, 700);
        }

        function ensureTopoTip() {
            if (_topoTip) return _topoTip;
            _topoTip = document.createElement('div');
            _topoTip.className = 'topo-tip';
            _topoTip.style.display = 'none';
            document.body.appendChild(_topoTip);
            return _topoTip;
        }

        // Lightweight hover tooltip — Cytoscape has no built-in one. Follows the cursor; carries the
        // node label and its sub-line (tunnel/LAN address, or a service's auth state).
        function wireTopoTips() {
            const tip = ensureTopoTip();
            const place = evt => {
                const oe = evt.originalEvent;
                if (!oe) return;
                tip.style.left = (oe.clientX + 14) + 'px';
                tip.style.top  = (oe.clientY + 14) + 'px';
            };
            _cy.on('mouseover', 'node', evt => {
                const d = evt.target.data();
                tip.innerHTML = `<strong>${escapeHtml(d.label || '')}</strong>`
                    + (d.sub ? `<br><span>${escapeHtml(d.sub)}</span>` : '');
                tip.style.display = 'block';
                place(evt);
            });
            _cy.on('mousemove', 'node', place);
            _cy.on('mouseout', 'node', () => { tip.style.display = 'none'; });
            _cy.on('tapstart pan zoom', () => { tip.style.display = 'none'; });
        }

        // Build (or refresh) the Cytoscape topology graph. A status-only refresh (same node set)
        // updates node/edge data in place — no re-layout — so the periodic poll and SSE updates
        // recolour the graph without teleporting it. A changed node set rebuilds and re-runs cola.
        function renderNetworkDiagram() {
            const host = document.getElementById('network-diagram');
            if (!host) return;
            if (host.clientWidth === 0 || host.clientHeight === 0) return; // panel not visible yet
            if (typeof cytoscape === 'undefined') return;                  // vendor lib missing

            const pal = topoPalette();
            const elements = buildTopologyElements(pal);
            const sig = elements.filter(e => !e.data.source).map(e => e.data.id).sort().join('|');

            if (_cy && sig === _topoSig) {
                _cy.batch(() => {
                    elements.forEach(e => {
                        const el = _cy.getElementById(e.data.id);
                        if (el && el.nonempty()) el.data(e.data);
                    });
                });
                return;
            }

            _topoSig = sig;
            if (!_cy) {
                _cy = cytoscape({
                    container: host,
                    elements,
                    style: topoStyle(pal),
                    minZoom: 0.25, maxZoom: 3, wheelSensitivity: 0.2,
                });
                wireTopoTips();
            } else {
                _cy.elements().remove();
                _cy.add(elements);
                _cy.style(topoStyle(pal));
            }
            startTopoLayout(true);
        }

        // Keep Cytoscape sized to its container when the window/iframe resizes while showing.
        window.addEventListener('resize', () => {
            if (_activeTab === 'topology' && _cy) { _cy.resize(); _cy.fit(undefined, 40); }
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
        fetchPublishedServices();
        fetchPublishable();
        detectEdition();

        const _sse = new EventSource('/vpn/peers/events');
        _sse.addEventListener('peers-updated', () => fetchPeers());
        _sse.addEventListener('lan-servers-updated', () => fetchLanServers());

        // Keep the Topology tab's service layer fresh: published-services health/changes arrive on
        // their own SSE stream (#infrastructure slice 1). A separate EventSource keeps this seam
        // independent of the peers stream — same DTO the Services page consumes.
        const _servicesSse = new EventSource('/published-services/events');
        _servicesSse.addEventListener('service-updated', () => { fetchPublishedServices(); fetchPublishable(); });
        // Publish progress (slice 2c): a publish is async (DNS propagation + Traefik). React to its
        // terminal events so the new route appears, or a failure is surfaced, without a manual refresh.
        _servicesSse.addEventListener('publish-traefik-active', e => {
            displaySuccess(`Published ${e.data}`);
            fetchPublishedServices(); fetchPublishable();
        });
        _servicesSse.addEventListener('publish-rolled-back', e => {
            displayError(`Publishing "${e.data}" was rolled back — nothing was left half-configured. You can try again.`);
            fetchPublishable();
        });
        _servicesSse.addEventListener('publish-dns-timeout', e => {
            displayError(`DNS propagation timed out for "${e.data}". The record was created but didn't resolve in time; try again.`);
            fetchPublishable();
        });
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
