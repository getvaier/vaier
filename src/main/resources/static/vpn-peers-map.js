// Map view for the Machines page (#273 slice 3): Leaflet setup + marker rendering.
// Classic script loaded before vpn-peers.js (its init calls setupPeerMap at load) and after
// vpn-peers-helpers.js, whose escapeHtml / machineIconSvg / deviceCategoryIconKind it uses.
// Owns the Leaflet map state below; reads peers / lanServers / _serverLocation, declared in
// vpn-peers.js, via the shared classic-script global scope.

// Map state lives here (this file assigns it) so the assignment isn't an implicit global; switchTab
// and the refresh triggers in vpn-peers.js read _peerMap from the shared scope.
let _peerMap = null;
let _markersClusterGroup = null;

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

