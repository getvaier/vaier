package net.vaier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vaier.domain.port.ForGeolocatingIps;

/**
 * Which machines keep a copy of the {@link SurvivalKit}, and why.
 *
 * <p>The operator is the worst person to answer this. It is a question about failure domains — which of these
 * machines share a house, a router, a power strip, a city — that would have to be re-answered every time the
 * fleet changes, and that gets answered wrong quietly. Vaier picks, and shows its reasoning, in the same
 * spirit as a machine nudge: the operator can disagree with a stated reason, but is not asked to derive one.
 *
 * <p><b>Two rules, and they do different jobs.</b> Never two copies behind the same relay peer is the hard
 * floor: it needs no data beyond the fleet's own shape, and it holds when geolocation is unavailable. But it
 * is not enough on its own — two relays in the same building satisfy it and would burn together. So where
 * there are more sites than copies, the sites kept are the ones <em>furthest apart on the map</em>: Vaier
 * seeds with the two most distant and then repeatedly adds whichever site is furthest from everything already
 * chosen. Three copies within one city is one fire, one flood, one burglary away from none, and it would read
 * as redundancy the whole time.
 *
 * <p>The Vaier server itself is never chosen. A kit that lives only on the machine it exists to outlive is
 * not a kit — a copy is written there too, but as an extra, never as one of these.
 */
public final class SurvivalKitHosts {

    /**
     * How many copies. Three survives losing a site and still losing a machine — the second is not a
     * disaster but the ordinary case of a host that was off when a passphrase changed and kept a stale kit.
     * More copies buy no further survivability and put more ciphertext in the world to be ground against
     * offline, which matters because OpenSSL's default PBKDF2 work factor is not large.
     */
    public static final int COPIES = 3;

    private SurvivalKitHosts() {}

    /** A machine that will keep a copy: which machine, the site it stands for, and why it was chosen. */
    public record Placement(String machineName, String site, String reason) {}

    /** A machine that will not keep a copy, and the fact the operator would need to disagree with. */
    public record Skipped(String machineName, String reason) {}

    /** Vaier's answer to "where do the copies go", in full — the choices and everything ruled out. */
    public record Selection(List<Placement> chosen, List<Skipped> skipped) {

        /**
         * Whether the fleet could not supply {@link #COPIES} separated copies — almost always because it has
         * fewer sites than that, since only one machine behind each relay may hold one. Worth saying out
         * loud: two copies presented as though they were three is the same lie the printed sheet told.
         */
        public boolean fewerCopiesThanIntended() {
            return chosen.size() < COPIES;
        }
    }

    /** One failure domain: the relay everything in it sits behind, its best keeper, and where on Earth it is. */
    private record Site(String name, Machine keeper, Optional<GeoLocation> location, List<Machine> others) {}

    /**
     * Choose the hosts. {@code vaierMachineName} is the machine Vaier itself runs on, excluded by name; a
     * null or blank value excludes nothing, which is only correct when Vaier genuinely is not one of these
     * machines. {@code geo} places each site by its relay's public address — a site whose relay has no
     * current endpoint simply has no location, and separation for it falls back to the relay rule alone.
     */
    public static Selection select(List<Machine> machines, String vaierMachineName, ForGeolocatingIps geo) {
        List<Skipped> skipped = new ArrayList<>();
        List<Machine> eligible = new ArrayList<>();
        for (Machine machine : preferenceOrder(machines)) {
            ineligibility(machine, vaierMachineName)
                .ifPresentOrElse(reason -> skipped.add(new Skipped(machine.name(), reason)),
                    () -> eligible.add(machine));
        }

        List<Site> sites = sitesOf(eligible, machines, geo);
        List<Site> kept = disperse(sites);

        List<Placement> chosen = new ArrayList<>();
        for (Site site : sites) {
            if (kept.contains(site)) {
                chosen.add(new Placement(site.keeper().name(), site.name(), chosenReason(site, kept)));
            } else {
                skipped.add(new Skipped(site.keeper().name(), notKeptReason(site, kept)));
            }
            // Everything else behind this relay loses to its keeper, chosen or not — one copy per site.
            site.others().forEach(other -> skipped.add(new Skipped(other.name(),
                "already covered by " + site.keeper().name()
                    + ", behind the same relay — two copies in one place is one copy")));
        }
        return new Selection(List.copyOf(chosen), List.copyOf(skipped));
    }

    /**
     * Why a machine cannot keep a kit at all, or empty when it can.
     *
     * <p>Nothing here consults {@link DeviceCategory}. A category is an icon the operator picked, orthogonal
     * to everything Vaier actually does, and it must never decide anything: reading it once ruled out both of
     * a real fleet's relay servers, which are categorised as gateways because that is what they are, and left
     * the fleet holding a single copy that looked like a working kit. {@link MachineType} is the authoritative
     * signal — it is the routing decision — and SSH access is the operator's own explicit word.
     */
    private static Optional<String> ineligibility(Machine machine, String vaierMachineName) {
        if (vaierMachineName != null && !vaierMachineName.isBlank()
            && vaierMachineName.equals(machine.name())) {
            return Optional.of(
                "this is the Vaier server — it keeps its own copy, but a kit kept only on the machine it "
                    + "exists to outlive is not a kit, so it never takes one of the three");
        }
        if (!machine.type().isServerType()) {
            return Optional.of("it is a client machine, carried and shut and switched off — a machine that "
                + "is away when a passphrase changes keeps a kit that is silently out of date");
        }
        if (!machine.effectiveSshAccess()) {
            return Optional.of(
                "Vaier has no SSH access to it, so it could never be given a kit or a rewritten one");
        }
        return Optional.empty();
    }

    /** Group the eligible machines into failure domains, each with the one machine that keeps its copy. */
    private static List<Site> sitesOf(List<Machine> eligible, List<Machine> all, ForGeolocatingIps geo) {
        Map<String, List<Machine>> grouped = new LinkedHashMap<>();
        Map<String, Machine> relays = new LinkedHashMap<>();
        for (Machine machine : eligible) {
            Machine relay = relayFor(machine, all);
            grouped.computeIfAbsent(relay.name(), k -> new ArrayList<>()).add(machine);
            relays.putIfAbsent(relay.name(), relay);
        }
        List<Site> sites = new ArrayList<>();
        grouped.forEach((name, members) -> {
            Machine keeper = keeperOf(members, relays.get(name));
            sites.add(new Site(name, keeper, locate(relays.get(name), geo),
                members.stream().filter(m -> !m.equals(keeper)).toList()));
        });
        return List.copyOf(sites);
    }

    /**
     * The one machine at a site that keeps its copy: the relay itself when it is eligible, otherwise the
     * first by name.
     *
     * <p>The relay is preferred on structure, not on what it looks like. It is the machine that <em>defines</em>
     * this failure domain — it terminates the tunnel, so whenever the site is reachable at all it is the
     * machine that is up. Ranking candidates by device category would be prettier ("a NAS is surely the most
     * durable") and would be exactly the mistake of letting an icon decide something.
     */
    private static Machine keeperOf(List<Machine> members, Machine relay) {
        return members.contains(relay) ? relay : members.get(0);
    }

    /** Where a site is: its relay's public address on the map, or nothing when it has no current one. */
    private static Optional<GeoLocation> locate(Machine relay, ForGeolocatingIps geo) {
        if (geo == null || relay.endpointIp() == null || relay.endpointIp().isBlank()) {
            return Optional.empty();
        }
        return geo.locate(relay.endpointIp());
    }

    /**
     * The sites that keep a copy: the most widely separated {@link #COPIES} of them.
     *
     * <p>Greedy max-min dispersion — seed with the two furthest apart, then repeatedly take whichever
     * remaining site is furthest from its nearest already-chosen neighbour. Exhaustive search would be
     * defensible at these sizes, but the greedy answer is the same one for any fleet a person owns, and this
     * one is readable. Sites Vaier cannot place on the map take any slots left over: they are still distinct
     * relays, which is a real guarantee, just not a measurable one.
     */
    private static List<Site> disperse(List<Site> sites) {
        if (sites.size() <= COPIES) {
            return sites;
        }
        List<Site> located = sites.stream().filter(s -> s.location().isPresent()).toList();
        List<Site> chosen = new ArrayList<>();

        if (located.size() >= 2) {
            Site[] furthest = furthestPair(located);
            chosen.add(furthest[0]);
            chosen.add(furthest[1]);
            while (chosen.size() < COPIES && chosen.size() < located.size()) {
                chosen.add(furthestFromAllOf(located, chosen));
            }
        } else {
            chosen.addAll(located);
        }
        for (Site site : sites) {
            if (chosen.size() >= COPIES) {
                break;
            }
            if (!chosen.contains(site)) {
                chosen.add(site);
            }
        }
        return chosen;
    }

    /** The two sites with the greatest distance between them; ties go to the better keepers, which come first. */
    private static Site[] furthestPair(List<Site> located) {
        Site[] best = {located.get(0), located.get(1)};
        double bestDistance = -1;
        for (int i = 0; i < located.size(); i++) {
            for (int j = i + 1; j < located.size(); j++) {
                double distance = distanceBetween(located.get(i), located.get(j));
                if (distance > bestDistance) {
                    bestDistance = distance;
                    best = new Site[] {located.get(i), located.get(j)};
                }
            }
        }
        return best;
    }

    /** The remaining site whose nearest already-chosen neighbour is furthest away. */
    private static Site furthestFromAllOf(List<Site> located, List<Site> chosen) {
        Site best = null;
        double bestNearest = -1;
        for (Site candidate : located) {
            if (chosen.contains(candidate)) {
                continue;
            }
            double nearest = nearestDistance(candidate, chosen);
            if (nearest > bestNearest) {
                bestNearest = nearest;
                best = candidate;
            }
        }
        return best;
    }

    private static double distanceBetween(Site a, Site b) {
        return a.location().isPresent() && b.location().isPresent()
            ? a.location().get().distanceKmTo(b.location().get()) : -1;
    }

    /** The distance to the closest of {@code others}, or -1 when nothing there can be measured against. */
    private static double nearestDistance(Site site, List<Site> others) {
        return others.stream()
            .filter(o -> !o.equals(site))
            .mapToDouble(o -> distanceBetween(site, o))
            .filter(d -> d >= 0)
            .min().orElse(-1);
    }

    private static String chosenReason(Site site, List<Site> kept) {
        double nearest = nearestDistance(site, kept);
        if (nearest < 0) {
            return "a server Vaier can reach over SSH, and the only copy behind " + site.name();
        }
        return "a server Vaier can reach over SSH, and " + kilometres(nearest)
            + " from the nearest other copy — far enough that one disaster cannot take both";
    }

    private static String notKeptReason(Site site, List<Site> kept) {
        double nearest = nearestDistance(site, kept);
        if (nearest < 0) {
            return COPIES + " copies is enough; more places to keep it is more places it can be taken from";
        }
        return COPIES + " copies is enough, and this site is " + kilometres(nearest)
            + " from one that keeps a copy — too close to be worth a fourth";
    }

    /** A distance an operator reads, not a measurement: whole kilometres, and never a misleading "0 km". */
    private static String kilometres(double km) {
        return km < 1 ? "less than a kilometre" : Math.round(km) + " km";
    }

    /**
     * Candidates by name, so the same fleet always produces the same answer — a selection that reshuffled on
     * every read would rewrite kits across the fleet for no reason. There is no quality ranking here: which
     * machine at a site keeps the copy is settled structurally, in {@link #keeperOf}.
     */
    private static List<Machine> preferenceOrder(List<Machine> machines) {
        return machines.stream().sorted(Comparator.comparing(Machine::name)).toList();
    }

    /**
     * The relay a machine sits behind — what "the same failure domain" means concretely, and what gives the
     * site its place on the map.
     *
     * <p>A VPN peer terminates its own tunnel, so it <em>is</em> its own relay. A LAN server sits on some
     * peer's LAN, and is attributed to the peer whose {@link Machine#lanCidr()} covers its address. When no
     * peer claims that network — or more than one does, which makes the attribution a guess — the machine
     * stands alone: merging unattributable machines would silently cost a copy on the strength of an
     * assumption.
     */
    private static Machine relayFor(Machine machine, List<Machine> machines) {
        if (machine.type().isVpnPeer()) {
            return machine;
        }
        List<Machine> relays = machines.stream()
            .filter(m -> m.type().isVpnPeer() && covers(m.lanCidr(), machine.lanAddress()))
            .toList();
        return relays.size() == 1 ? relays.get(0) : machine;
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
