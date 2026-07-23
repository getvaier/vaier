package net.vaier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which machines keep a copy of the {@link SurvivalKit}, and why.
 *
 * <p>The operator is the worst person to answer this. It is a question about failure domains — which of these
 * machines share a house, a router, a power strip — that would have to be re-answered every time the fleet
 * changes, and that gets answered wrong quietly. Vaier picks, and shows its reasoning, in the same spirit as
 * a machine nudge: the operator can disagree with a stated reason, but is not asked to derive one.
 *
 * <p><b>The rule that carries the idea:</b> never two copies behind the same relay peer. Three copies in one
 * house is one flood, one fire or one burglary away from zero, and it would read as redundancy the whole
 * time. Everything else here is eligibility — a copy is only useful on a machine that is reachable when a
 * passphrase changes ({@link #isEligible}), because a machine that misses a rewrite keeps a kit that is
 * silently out of date, which is precisely the failure the printed sheet was rejected for.
 *
 * <p>The Vaier server itself is never chosen. A kit that lives only on the machine it exists to outlive is
 * not a kit.
 */
public final class SurvivalKitHosts {

    /**
     * How many copies. Three survives losing a site and still losing a machine; more copies buy no further
     * survivability and add places a copy can be taken from.
     */
    public static final int COPIES = 3;

    private SurvivalKitHosts() {}

    /** A machine that will keep a copy: which machine, the site it stands for, and why it was chosen. */
    public record Placement(String machineName, String site, String reason) {}

    /** A machine that will not keep a copy, and the fact the operator would need to disagree with. */
    public record Skipped(String machineName, String reason) {}

    /** Vaier's answer to "where do the copies go", in full — the choices and everything ruled out. */
    public record Selection(List<Placement> chosen, List<Skipped> skipped) {}

    /**
     * Choose the hosts. {@code vaierMachineName} is the machine Vaier itself runs on, excluded by name; a
     * null or blank value excludes nothing, which is only correct when Vaier genuinely is not one of these
     * machines.
     */
    public static Selection select(List<Machine> machines, String vaierMachineName) {
        List<Placement> chosen = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        // Site → the machine already holding that site's copy, so a later candidate can be told which copy
        // crowded it out rather than merely that it lost.
        Map<String, String> claimedSites = new LinkedHashMap<>();

        for (Machine machine : preferenceOrder(machines)) {
            if (isVaierItself(machine, vaierMachineName)) {
                skipped.add(new Skipped(machine.name(),
                    "this is the Vaier server — a kit kept only on the machine it exists to outlive is not a kit"));
                continue;
            }
            // Why-it-cannot before why-it-was-not-picked, and what it *is* before what Vaier can do with it:
            // "Vaier has no SSH access to the printer" is true and useless, and invites someone to go and
            // fix the SSH access of a printer.
            if (machine.deviceCategory().isAppliance()) {
                skipped.add(new Skipped(machine.name(),
                    "a " + categoryWord(machine) + " is a device, not somewhere a file can be kept"));
                continue;
            }
            if (!machine.deviceCategory().isAlwaysOn()) {
                skipped.add(new Skipped(machine.name(),
                    "a " + categoryWord(machine) + " is not always on, and a machine that is asleep when a "
                        + "passphrase changes keeps a kit that is silently out of date"));
                continue;
            }
            if (!machine.effectiveSshAccess()) {
                skipped.add(new Skipped(machine.name(),
                    "Vaier has no SSH access to it, so it could never be given a kit or a rewritten one"));
                continue;
            }

            String site = siteOf(machine, machines);
            String holder = claimedSites.get(site);
            if (holder != null) {
                skipped.add(new Skipped(machine.name(),
                    "already covered by " + holder + ", behind the same relay — two copies in one place is "
                        + "one copy"));
                continue;
            }
            if (chosen.size() >= COPIES) {
                skipped.add(new Skipped(machine.name(),
                    "3 copies is enough; more places to keep it is more places it can be taken from"));
                continue;
            }

            claimedSites.put(site, machine.name());
            chosen.add(new Placement(machine.name(), site,
                "always on, reachable over SSH, and the only copy behind " + site));
        }
        return new Selection(List.copyOf(chosen), List.copyOf(skipped));
    }

    /**
     * Candidates best-first, so the machine that claims a site is the one most likely to still be running
     * next year: a NAS ahead of a server ahead of anything else, then by name so the same fleet always
     * produces the same answer — a selection that reshuffled on every read would rewrite kits across the
     * fleet for no reason.
     */
    private static List<Machine> preferenceOrder(List<Machine> machines) {
        return machines.stream()
            .sorted(Comparator.comparingInt(SurvivalKitHosts::keeperRank).thenComparing(Machine::name))
            .toList();
    }

    /**
     * How good a keeper a machine is. A NAS wins outright: it is the box whose entire purpose is to be
     * powered on and holding files, which is exactly what a kit needs of its host.
     */
    private static int keeperRank(Machine machine) {
        return switch (machine.deviceCategory()) {
            case NAS -> 0;
            case SERVER -> 1;
            default -> 2;
        };
    }

    /** The device kind, in the words the operator sees it in on the machine list. */
    private static String categoryWord(Machine machine) {
        return machine.deviceCategory().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isVaierItself(Machine machine, String vaierMachineName) {
        return vaierMachineName != null && !vaierMachineName.isBlank()
            && vaierMachineName.equals(machine.name());
    }

    /**
     * The failure domain a machine sits in — what "behind the same relay" means concretely.
     *
     * <p>A VPN peer terminates its own tunnel, so it <em>is</em> a site. A LAN server sits on some peer's
     * LAN, and is attributed to the peer whose {@link Machine#lanCidr()} covers its address. When no peer
     * claims that network — or more than one does, which makes the attribution a guess — the machine counts
     * as its own site: merging unattributable machines would silently cost a copy on the strength of an
     * assumption.
     */
    private static String siteOf(Machine machine, List<Machine> machines) {
        if (machine.type().isVpnPeer()) {
            return machine.name();
        }
        List<Machine> relays = machines.stream()
            .filter(m -> m.type().isVpnPeer() && covers(m.lanCidr(), machine.lanAddress()))
            .toList();
        return relays.size() == 1 ? relays.get(0).name() : machine.name();
    }

    /**
     * Whether {@code cidr} covers {@code address}, compared on the network prefix only — Vaier's LANs are
     * byte-aligned home networks, and full mask arithmetic here would be precision the inputs do not have.
     */
    private static boolean covers(String cidr, String address) {
        if (cidr == null || address == null) {
            return false;
        }
        int slash = cidr.indexOf('/');
        String network = slash < 0 ? cidr : cidr.substring(0, slash);
        return prefixOf(network) != null && prefixOf(network).equals(prefixOf(address));
    }

    /** The first three octets of a dotted-quad, or null when it is not one. */
    private static String prefixOf(String address) {
        String[] octets = address.split("\\.");
        return octets.length == 4 ? octets[0] + "." + octets[1] + "." + octets[2] : null;
    }
}
