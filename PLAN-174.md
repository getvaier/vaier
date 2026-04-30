# Plan — #174 (gateway peer for routing all client traffic)

Pre-flight rollback SHA: `ac4e9ed6a06e455239d764571e2b76252a160960`

## Approach: A — bundle #170 into the same branch

The `ip_forward` + iptables NAT primitive is shared between #170 (relay-peer LAN) and #174
(gateway peer egress). Splitting them would duplicate the install-script block and force
merge churn, so they ship together.

## Implementation order (strict TDD per CLAUDE.md)

1. **#170 first — install-script forwarding primitive for `lanCidr`.**
   - `VpnServiceTest`: failing test — generated setup script contains `ip_forward` sysctl
     + iptables FORWARD/POSTROUTING block when peer was created with `lanCidr`; block is
     absent when `lanCidr` is null/blank. All `iptables` lines must be idempotent
     (`-C ... || -A ...`).
   - Implementation: thread `lanCidr` and `vpnSubnet` into `VpnService.generateScript`,
     emit the forwarding block conditionally.

2. **Domain model — server-side AllowedIPs computation.**
   - `WireGuardPeerConfigTest` (or new `ServerAllowedIpsTest`): pure function returning
     `<ip>/32` | `<ip>/32, <lanCidr>` | `<ip>/32, 0.0.0.0/0` | `<ip>/32, <lanCidr>, 0.0.0.0/0`
     based on `(ipAddress, lanCidr, isGateway)`.
   - `VaierConfig`: add `gatewayPeerName`, `gatewayDns` (default `1.1.1.1`).

3. **VpnService — gateway toggle.**
   - New use case `SetGatewayPeerUseCase` (`set` / `clear`), implemented in `VpnService`.
   - Mutates wg0.conf via `wg set <iface> peer <pubkey> allowed-ips <new>` + `wg-quick save`.
   - Validates the peer exists; rejects unknown peer with 400.
   - Toggling the gateway un-sets the previous one (reverts that peer to `/32` ± `lanCidr`).
   - Tests: `VpnServiceTest` with mocked ports.

4. **Per-client `routeAllTrafficViaGateway` flag.**
   - Persisted in the per-peer `# VAIER:` JSON header
     (`WireguardConfigFileAdapter.VaierMetadata`).
   - New port method `ForUpdatingPeerConfigurations.setRouteAllTrafficViaGateway(name, bool)`.
   - Tests: `WireguardConfigFileAdapterTest` round-trip.

5. **Setup script — second mode for gateway peer.**
   - When `routeAllTrafficViaGateway=true`, drop the unconditional `sed` rewrite of
     `AllowedIPs = 0.0.0.0/0` (current behaviour at `VpnService.java:589` re-rewrites to the
     VPN subnet — split tunnel). Inject `DNS = <gatewayDns>` into the `[Interface]` block.
   - When the peer being installed **is** the gateway, append `ip_forward` +
     `iptables -t nat -A POSTROUTING -o "$EGRESS_IFACE" -j MASQUERADE` (no CIDR filter).
   - `EGRESS_IFACE=$(ip route show default | awk '/default/ {print $5; exit}')` — same
     detection in both gateway and relay (#170) modes; works whether the iface has a public
     IP or sits behind upstream NAT.

6. **REST.**
   - `PUT /vpn/settings/gateway-peer { peerName | null }` → 200 / 400 if peer unknown.
   - `PUT /vpn/peers/{name}/route-via-gateway { enabled }` → 200 / 400 if no gateway set.
   - GET `/vpn/peers` gains `routeAllTrafficViaGateway`; settings endpoint exposes
     `gatewayPeerName` + `gatewayDns`.

7. **UI** (`vpn-peers.html`).
   - Per-row "Gateway" radio (single-select; clicking the current one un-sets).
   - Edit-peer panel: "Route all traffic via gateway" checkbox, disabled with tooltip when
     no gateway is set.
   - Settings: `gatewayDns` text input.

8. **Docs.**
   - `README.md`: feature row + small section on "Route all traffic via VPN".
   - `PRD.md`: mark item ✅, note caveats (V1 single-gateway, IPv4 only).

## Rollback plan

- Pre-flight SHA captured: `ac4e9ed6a06e455239d764571e2b76252a160960`. Recovery:
  `git reset --hard ac4e9ed6a06e455239d764571e2b76252a160960` + rebuild + redeploy.
- No `git push` during the change (per memory rule "No git push during working hours").
- Server WG state safety: before the first toggle, snapshot `wg0.conf` to `wg0.conf.bak`.
  Vaier admin path is via Traefik on the docker bridge, **not** wg0, so the admin UI stays
  reachable even if wg0 routing is borked. "Clear gateway" in the UI reverts the change.
- Client blast radius: only freshly fetched configs / freshly run install scripts are
  affected. Existing peers keep working.
- Gate: `mvn test` must pass; deploy with `docker build ... -t getvaier/vaier:latest`
  + `docker compose up -d --force-recreate vaier`; user verifies before commit.

## Out of scope (per issue)

Multiple gateways, per-client gateway selection, IPv6 default route, kill-switch on the
client, gateway health monitoring.
