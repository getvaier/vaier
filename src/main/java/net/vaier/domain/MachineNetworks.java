package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A point-in-time reading of <b>the IPv4 networks a machine sits on</b>, parsed from
 * {@link #IP_COMMAND} run over SSH — the same channel, and the same 5-minute sweep, that already takes
 * the machine's {@link RemoteDiskUsage disk reading}. It owns the business decisions of how that output
 * is read ({@link #parse(String)}), which interfaces could never be an operator's LAN
 * ({@link #isPseudoInterface(String)}), which single network is <em>the network behind this
 * machine</em> ({@link #lanCandidate()}), and whether the machine has a way out to the internet at all
 * ({@link #defaultRoute()}, #357).
 *
 * <p><b>#333.</b> This exists so an operator is never asked for a CIDR. Typing one asks a homelab user to
 * know CIDR notation, to know which subnet their router hands out, and to understand that getting it
 * wrong either does nothing or severs a host's uplink. Vaier already holds an SSH credential for the
 * machine and already runs {@code df} over that exact connection, so it can simply <em>read</em> the
 * answer and ask the operator the only question that is theirs: should the fleet reach that network?
 *
 * <p><b>Total, like {@code RemoteDiskUsage.parseList}:</b> parsing never throws. A host without
 * {@code ip}, a truncated run, a row in a shape Vaier does not recognise — all yield an empty reading or
 * a skipped row, never a guessed one. A machine Vaier cannot read is a machine Vaier says nothing about.
 *
 * @param networks             every network on an interface that could be an operator's LAN, in the
 *                             order the machine listed them
 * @param defaultRouteInterface the interface the machine's default route leaves by, or null when the
 *                             reading did not say
 */
public record MachineNetworks(List<Network> networks, String defaultRouteInterface) {

    /**
     * One IPv4 address a machine holds, and the network it sits on.
     *
     * @param interfaceName the interface it is configured on, e.g. {@code eth0} — the <b>evidence</b>
     *                      behind a detected LAN: an operator must be able to see where the value came
     *                      from before they route it
     * @param address       the address itself, e.g. {@code 192.168.1.10}
     * @param prefixLength  the network's prefix length in bits, e.g. {@code 24}
     */
    public record Network(String interfaceName, String address, int prefixLength) {

        /** The network this address sits on, as a CIDR — e.g. {@code 192.168.1.0/24}. */
        public String cidr() {
            return Cidr.networkOf(address, prefixLength);
        }
    }

    /**
     * {@code ip -o -4 addr show; ip -o -4 route show default} — how a network reading is taken.
     * One-line ({@code -o}) IPv4 ({@code -4}) output, so every address is exactly one row and the parser
     * never has to reassemble a wrapped record.
     *
     * <p>Both halves ride <b>one</b> exec, because they are one question: an address without knowing
     * which interface carries the default route cannot tell you which of a machine's networks is the one
     * behind it, and the route without the addresses names an interface with no network attached. Asking
     * separately would double the SSH connects the sweep makes for an answer that is only meaningful
     * whole.
     *
     * <p>It lives here, next to {@link #parse}, for the reason {@link RemoteDiskUsage#DF_COMMAND} does:
     * how a reading is <em>taken</em> and how it is <em>read</em> are one decision, and changing either
     * without the other silently changes what Vaier believes.
     */
    public static final String IP_COMMAND = "ip -o -4 addr show; ip -o -4 route show default";

    /**
     * Interface name prefixes that are never an operator's LAN: the loopback, WireGuard and other VPN
     * tunnels, Docker's bridges and the per-container veth pairs, libvirt's bridges, ZeroTier and
     * Tailscale. The direct analogue of {@link RemoteDiskUsage#isPseudoFilesystem} — it decides what
     * Vaier is willing to call a network at all, and therefore what it will ever offer to route.
     *
     * <p>Offering one of these would be worse than saying nothing: routing {@code 172.17.0.0/16} into the
     * fleet because a machine happens to run Docker would collide with the Docker bridge of every other
     * machine in the fleet.
     */
    private static final Set<String> PSEUDO_INTERFACE_PREFIXES = Set.of(
        "lo", "wg", "docker", "br-", "veth", "tailscale", "virbr", "tun", "tap", "zt", "cni",
        "flannel", "kube", "cali", "nebula", "utun", "ham", "ppp", "gre", "sit", "ifb", "dummy");

    /**
     * Whether an interface could never carry an operator's LAN. A <b>domain decision</b>, not a parsing
     * detail: it is the difference between Vaier offering to route the house and offering to route
     * Docker's bridge.
     */
    public static boolean isPseudoInterface(String interfaceName) {
        if (interfaceName == null || interfaceName.isBlank()) {
            return true;
        }
        String name = interfaceName.toLowerCase(Locale.ROOT);
        for (String prefix : PSEUDO_INTERFACE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** What Vaier knows about a machine it has never managed to read: nothing. */
    public static MachineNetworks unknown() {
        return new MachineNetworks(List.of(), null);
    }

    /**
     * Whether this reading tells Vaier <b>nothing</b> — the machine could not be reached, the command
     * failed, or it answered with nothing this class recognises as a network.
     *
     * <p>The counterpart to {@link #unknown()}, and here rather than at a caller for exactly that reason:
     * what "unknown" means must be decided in one place, or a caller spelling it {@code networks().isEmpty()}
     * quietly disagrees the day a reading gains another way of saying nothing. Callers use it to leave a
     * previous, good reading alone rather than overwrite it with a failure.
     */
    public boolean isUnknown() {
        return networks.isEmpty();
    }

    /**
     * Whether this machine has <b>a way out to the internet</b> (#357) — the three-valued reading of the
     * default-route half of {@link #IP_COMMAND}, which was parsed and then thrown away.
     *
     * <p>Deliberately not {@link #lanCandidate()}: that asks which of the machine's networks Vaier could
     * offer to route, and is empty for a full-tunnel peer whose default route leaves by {@code wg0}. Such a
     * machine reaches the world perfectly well. This asks only whether there is a route out at all.
     *
     * <p>A reading that {@link #isUnknown() says nothing} is {@link DefaultRouteStanding#UNKNOWN}, never
     * {@link DefaultRouteStanding#ABSENT} — a machine Vaier could not read has not told it anything, and
     * treating silence as a verdict is how a monitor starts crying wolf.
     */
    public DefaultRouteStanding defaultRoute() {
        if (isUnknown()) {
            return DefaultRouteStanding.UNKNOWN;
        }
        return defaultRouteInterface == null ? DefaultRouteStanding.ABSENT : DefaultRouteStanding.PRESENT;
    }

    /**
     * The interfaces and addresses this reading holds, as one line — the <b>evidence</b> behind anything
     * Vaier says about a machine's networks, in the nudge card and in the email alike. Blank when the
     * reading says nothing.
     */
    public String addressesInOneLine() {
        return networks.stream()
            .map(n -> n.interfaceName() + " " + n.address() + "/" + n.prefixLength())
            .collect(Collectors.joining(", "));
    }

    /** Subject for the missing-default-route alert (#357), sent once when a machine loses its way out. */
    public String missingDefaultRouteSubject(String machineName) {
        return "[Vaier] " + machineName + " has no default route — it cannot reach the internet";
    }

    /** Subject for the all-clear, sent once the machine has a default route again. */
    public String defaultRouteRestoredSubject(String machineName) {
        return "[Vaier] " + machineName + " can reach the internet again";
    }

    /**
     * Body for both the missing-default-route alert and its all-clear. One body, whose words follow the
     * reading it is given — the alert carries the reading that found nothing, the all-clear the fresh one
     * that found a route — so the two mails can never drift apart in how they describe the same machine.
     * {@code baseDomain} builds the Vaier UI link, omitted when it is null or blank.
     */
    public String defaultRouteBody(String machineName, String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        String addresses = addressesInOneLine();
        body.append("Read from the machine: ")
            .append(addresses.isBlank() ? "nothing Vaier could parse" : addresses).append("\n");
        body.append("Default route: ")
            .append(defaultRouteInterface == null ? "none" : "via " + defaultRouteInterface).append("\n");
        if (defaultRoute() == DefaultRouteStanding.ABSENT) {
            body.append("\nImage pulls and updates will fail, and so will anything else on this machine "
                + "that reaches the internet. Everything inside its own network keeps working, which is "
                + "why nothing else here noticed.\n");
            body.append("Vaier can see this, but cannot fix it: the route has to come back on the "
                + "machine.\n");
        }
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }

    /** An address row: {@code 2: eth0    inet 192.168.1.10/24 brd … scope global eth0}. */
    private static final Pattern ADDRESS_ROW = Pattern.compile(
        "^\\d+:\\s+(\\S+?)(?:@\\S+)?\\s+inet\\s+(\\S+?)/(\\d{1,3})\\b.*$");

    /** A default-route row: {@code default via 192.168.1.1 dev eth0 proto dhcp …}. */
    private static final Pattern DEFAULT_ROUTE_ROW = Pattern.compile(
        "^default\\b.*?\\bdev\\s+(\\S+).*$");

    /**
     * Read a machine's networks from {@link #IP_COMMAND} output. Rows on
     * {@link #isPseudoInterface(String) pseudo-interfaces} are dropped, a row in an unrecognised shape is
     * skipped while its siblings are kept, and anything else — no output at all, a shell that said
     * {@code command not found}, half a run — yields {@link #unknown()}.
     */
    public static MachineNetworks parse(String ipOutput) {
        if (ipOutput == null || ipOutput.isBlank()) {
            return unknown();
        }
        List<Network> found = new ArrayList<>();
        String defaultRouteInterface = null;
        for (String rawLine : ipOutput.strip().split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher route = DEFAULT_ROUTE_ROW.matcher(line);
            if (route.matches()) {
                defaultRouteInterface = route.group(1);
                continue;
            }
            Network network = parseAddressRow(line);
            if (network != null && !isPseudoInterface(network.interfaceName())) {
                found.add(network);
            }
        }
        return new MachineNetworks(List.copyOf(found), defaultRouteInterface);
    }

    /** One address row, or null when it will not parse. Never throws. */
    private static Network parseAddressRow(String line) {
        Matcher row = ADDRESS_ROW.matcher(line);
        if (!row.matches()) {
            return null;
        }
        String address = row.group(2);
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(row.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
        // The address and prefix must together name a real network, or this row tells Vaier nothing.
        if (Cidr.networkOf(address, prefixLength) == null) {
            return null;
        }
        return new Network(row.group(1), address, prefixLength);
    }

    /**
     * <b>The network behind this machine</b>, if the reading names one: the network on the interface its
     * default route leaves by. That is the one network a machine is unambiguously "on" — the addresses
     * its own traffic comes from, and the addresses of everything else in the same building.
     *
     * <p>Empty when the reading did not name a default-route interface, or when that interface is one
     * Vaier will never call a LAN — a full-tunnel peer routes by {@code wg0}, and "the network behind it"
     * is then the VPN, which the fleet already reaches. Empty means <em>say nothing</em>; it never means
     * "fall back to the first interface you saw", because a machine with a Docker bridge and a guess is
     * how an operator ends up routing {@code 172.17.0.0/16}.
     */
    public Optional<Network> lanCandidate() {
        if (defaultRouteInterface == null) {
            return Optional.empty();
        }
        return networks.stream()
            .filter(n -> defaultRouteInterface.equals(n.interfaceName()))
            .findFirst();
    }

    /**
     * The address this machine reaches the world by — the address on its default-route interface. This is
     * the value {@link UplinkGuard} judges a CIDR against: route a network containing it into a tunnel on
     * this host and the host loses its own uplink.
     */
    public Optional<String> uplinkAddress() {
        return lanCandidate().map(Network::address);
    }
}
