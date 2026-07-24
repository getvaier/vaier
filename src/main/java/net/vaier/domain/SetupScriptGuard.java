package net.vaier.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The preamble every generated setup script carries: a set of refusals that stop the script when it is
 * running somewhere it was not generated for. A pure, IO-free generator in the same family as
 * {@link PeerSetupScript} and {@link LanServerSetupScript}.
 *
 * <p>A setup script reconfigures the machine it runs on — it installs Docker, rewrites the daemon
 * config, deletes and recreates network interfaces, and edits the firewall. Vaier delivers it as a
 * copy-and-paste one-liner (the <b>setup link</b>), so the one thing standing between the script and
 * the wrong machine is which terminal window the operator pasted into. On 2026-07-23 that was the
 * Vaier staging server: the script took its stack down, rewrote {@code /etc/docker/daemon.json},
 * exposed the Docker API, and routed the host's own subnet into a tunnel that could never come up —
 * severing the box from its own default gateway.
 *
 * <p>Four refusals, each earning its place from a distinct way the accident could happen:
 * <ol>
 *   <li><b>Never on a Vaier server.</b> Vaier's own host is never a target of a machine setup script.</li>
 *   <li><b>Never on a machine stamped as a different one.</b> Every successful run records its machine
 *       name at {@value #STAMP_PATH}; a later script for another machine refuses.</li>
 *   <li><b>Never route the host's own network into the tunnel.</b> If a CIDR the script would send
 *       through WireGuard contains the address on the host's default-route interface, the script would
 *       blackhole its own uplink. This one is a real bug on <em>every</em> target, not only a misfire:
 *       a peer created for a machine that shares the Vaier server's subnet hits it legitimately.</li>
 *   <li><b>Never on a host lacking the address Vaier recorded</b>, when Vaier knows one.</li>
 * </ol>
 *
 * <p>The checks are non-interactive by necessity — the script arrives on stdin through a pipe, so
 * there is nothing to prompt on. {@code VAIER_FORCE=1} is the deliberate override.
 */
public final class SetupScriptGuard {

    private SetupScriptGuard() {}

    /** Opening line of the guard block, so callers (and tests) can assert it precedes any mutation. */
    public static final String MARKER = "# === Vaier setup-script guard ===";

    /** Where a completed setup records which machine this host is. */
    public static final String STAMP_PATH = "/etc/vaier/machine";

    /**
     * The guard block, to be emitted before the first mutating line of a setup script.
     *
     * @param machineName    the machine this script was generated for, as the operator named it
     * @param tunneledCidrs  CIDRs the script routes into the VPN; empty to skip the self-blackhole check
     * @param expectedAddress the address Vaier has recorded for the machine, or {@code null} when
     *                        Vaier cannot know it yet (a peer being set up for the first time)
     */
    public static String preamble(String machineName, List<String> tunneledCidrs, String expectedAddress) {
        String machine = shellQuote(machineName);
        boolean checkRoutes = tunneledCidrs != null && !tunneledCidrs.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append(MARKER).append("\n");
        sb.append("# This script reconfigures the machine it runs on. It was generated for one specific\n");
        sb.append("# machine and refuses to run anywhere else. Override (at your own risk): VAIER_FORCE=1\n");
        sb.append("VAIER_MACHINE=").append(machine).append("\n");
        sb.append("vaier_refuse() {\n");
        sb.append("    echo \"\" >&2\n");
        sb.append("    echo \"REFUSING TO RUN: $1\" >&2\n");
        sb.append("    echo \"  This script was generated for the machine \\\"$VAIER_MACHINE\\\".\" >&2\n");
        sb.append("    echo \"  Nothing has been changed on this host.\" >&2\n");
        sb.append("    echo \"  If this really is the right machine, re-run it with VAIER_FORCE=1.\" >&2\n");
        sb.append("    exit 3\n");
        sb.append("}\n");
        if (checkRoutes) sb.append(cidrHelpers());

        sb.append("if [ \"${VAIER_FORCE:-0}\" != \"1\" ]; then\n");

        // 1 — the Vaier server is never the target of a machine setup script. Two probes: the running
        // container, and the install on disk (which still catches a server whose stack is down).
        sb.append("    if command -v docker >/dev/null 2>&1 \\\n");
        sb.append("            && docker ps --format '{{.Image}}' 2>/dev/null | grep -q 'getvaier/vaier'; then\n");
        sb.append("        vaier_refuse \"this host is running Vaier itself (a getvaier/vaier container).\"\n");
        sb.append("    fi\n");
        sb.append("    if grep -qs 'getvaier/vaier' \"$HOME/vaier/docker-compose.yml\"; then\n");
        sb.append("        vaier_refuse \"this host has a Vaier server installed at \\$HOME/vaier.\"\n");
        sb.append("    fi\n");

        // 2 — a host that already completed a setup knows which machine it is.
        sb.append("    if [ -r ").append(STAMP_PATH).append(" ]; then\n");
        sb.append("        vaier_stamped=\"$(cat ").append(STAMP_PATH).append(")\"\n");
        sb.append("        if [ \"$vaier_stamped\" != \"$VAIER_MACHINE\" ]; then\n");
        sb.append("            vaier_refuse \"this host is already set up as the machine \\\"$vaier_stamped\\\".\"\n");
        sb.append("        fi\n");
        sb.append("    fi\n");

        // 3 — the uplink check: never tunnel the network this host is reachable on.
        if (checkRoutes) {
            sb.append("    vaier_def_if=\"$(ip route show default 2>/dev/null | awk '{print $5; exit}')\"\n");
            sb.append("    if [ -n \"$vaier_def_if\" ]; then\n");
            sb.append("        vaier_def_ip=\"$(ip -4 -o addr show dev \"$vaier_def_if\" 2>/dev/null \\\n");
            sb.append("            | awk '{print $4}' | cut -d/ -f1 | head -1)\"\n");
            sb.append("        for vaier_cidr in");
            for (String cidr : tunneledCidrs) sb.append(" ").append(shellQuote(cidr));
            sb.append("; do\n");
            sb.append("            if [ -n \"$vaier_def_ip\" ] && vaier_in_cidr \"$vaier_def_ip\" \"$vaier_cidr\"; then\n");
            sb.append("                vaier_refuse \"this host ($vaier_def_ip on $vaier_def_if) is inside $vaier_cidr,");
            sb.append(" which this script routes into the VPN — it would cut the host off its own network.\"\n");
            sb.append("            fi\n");
            sb.append("        done\n");
            sb.append("    fi\n");
        }

        // 4 — the address Vaier recorded for the machine, when it knows one.
        if (expectedAddress != null && !expectedAddress.isBlank()) {
            String address = expectedAddress.trim();
            sb.append("    if ! ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 \\\n");
            sb.append("            | grep -qx ").append(shellQuote(address)).append("; then\n");
            sb.append("        vaier_refuse \"this host does not hold the address ").append(address);
            sb.append(" that Vaier has recorded for \\\"$VAIER_MACHINE\\\".\"\n");
            sb.append("    fi\n");
        }

        sb.append("fi\n");
        sb.append("# === end Vaier setup-script guard ===\n");
        return sb.toString();
    }

    /**
     * Records which machine this host is, so a script generated for a different machine refuses to run
     * here later. Emitted at the end of a successful setup. Best-effort: a host that will not let the
     * stamp be written is still a set-up host, so failure must never fail the run.
     */
    public static String stamp(String machineName) {
        String machine = shellQuote(machineName);
        return "\n# Record which machine this host is (see the setup-script guard).\n"
            + "( sudo mkdir -p /etc/vaier 2>/dev/null || mkdir -p /etc/vaier 2>/dev/null ) || true\n"
            + "printf '%s\\n' " + machine + " | sudo tee " + STAMP_PATH + " >/dev/null 2>&1 \\\n"
            + "    || printf '%s\\n' " + machine + " > " + STAMP_PATH + " 2>/dev/null || true\n";
    }

    /**
     * Shell helpers for IPv4-in-CIDR containment, kept separate so they can be exercised directly.
     * Pure bash arithmetic — no python, no ipcalc, nothing a minimal host might lack.
     */
    public static String cidrHelpers() {
        return """
            vaier_ip_to_int() {
                local IFS=.
                local -a vaier_octets
                read -r -a vaier_octets <<< "$1"
                echo $(( (${vaier_octets[0]} << 24) + (${vaier_octets[1]} << 16) \\
                    + (${vaier_octets[2]} << 8) + ${vaier_octets[3]} ))
            }
            vaier_in_cidr() {
                local vaier_ip="$1" vaier_cidr_arg="$2" vaier_net vaier_bits vaier_mask
                vaier_net="${vaier_cidr_arg%/*}"
                vaier_bits="${vaier_cidr_arg#*/}"
                if [ "$vaier_bits" = "$vaier_cidr_arg" ]; then vaier_bits=32; fi
                vaier_mask=$(( 0xFFFFFFFF ^ ((1 << (32 - vaier_bits)) - 1) ))
                [ $(( $(vaier_ip_to_int "$vaier_ip") & vaier_mask )) \\
                    -eq $(( $(vaier_ip_to_int "$vaier_net") & vaier_mask )) ]
            }
            """;
    }

    /**
     * The CIDRs a peer's config will actually send through the tunnel: the client-side
     * {@code AllowedIPs}, after the split-tunnel rewrite {@link PeerSetupScript} performs on a
     * full-tunnel line. IPv6 entries are dropped — nothing here routes them.
     */
    public static List<String> tunneledCidrs(String wgConfig, String vpnSubnet) {
        if (wgConfig == null || wgConfig.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : wgConfig.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, "AllowedIPs", 0, "AllowedIPs".length())) continue;
            int equals = trimmed.indexOf('=');
            if (equals < 0) continue;
            String value = trimmed.substring(equals + 1).trim();
            // PeerSetupScript seds a full-tunnel line down to the VPN subnet, so that — not
            // 0.0.0.0/0 — is what this host will really route.
            if (value.contains("0.0.0.0/0")) {
                if (vpnSubnet != null && !vpnSubnet.isBlank()) out.add(vpnSubnet.trim());
                continue;
            }
            for (String part : value.split(",")) {
                String cidr = part.trim();
                if (cidr.isEmpty() || cidr.contains(":")) continue;
                out.add(cidr);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Renders operator-typed text as a single-quoted shell literal. A machine name reaches the
     * generated script as data and must never become shell — see the injection rules already applied
     * to borg paths and {@code wg set} arguments.
     */
    private static String shellQuote(String raw) {
        return "'" + (raw == null ? "" : raw).replace("'", "'\\''") + "'";
    }
}
