package net.vaier.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vaier.domain.port.ForGeolocatingIps;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the copies go.
 *
 * <p>A survival kit on one machine is a kit with a single point of failure, and the operator is the worst
 * person to choose the machines — it is a question about failure domains they would have to hold in their
 * head, re-answer every time the fleet changes, and would get wrong quietly. Vaier picks, and says why.
 *
 * <p>Two rules doing two jobs. <b>Never two copies behind the same relay peer</b> is the floor, and it holds
 * with no data at all. But it is not sufficient: two relays in the same building satisfy it and would burn
 * together — so where there are more sites than copies, the ones kept are the ones <b>furthest apart on the
 * map</b>. Three copies in one city is one flood, one fire, one burglary away from zero, and it would look
 * like redundancy the entire time.
 */
class SurvivalKitHostsTest {

    private static final String VAIER = "vaier-server";

    /** A fleet Vaier cannot place on the map: separation then rests on the relay rule alone. */
    private static final ForGeolocatingIps NO_GEO = ip -> Optional.empty();

    private static final GeoLocation OSLO = new GeoLocation(59.9139, 10.7522, "Oslo", "NO");
    private static final GeoLocation NEXT_DOOR = new GeoLocation(59.9500, 10.8000, "Oslo", "NO");
    private static final GeoLocation MADRID = new GeoLocation(40.4168, -3.7038, "Madrid", "ES");
    private static final GeoLocation SYDNEY = new GeoLocation(-33.8688, 151.2093, "Sydney", "AU");

    /** Places each site by the public address of its relay, the way the real geolocation adapter does. */
    private ForGeolocatingIps geoOf(Map<String, GeoLocation> byIp) {
        return ip -> Optional.ofNullable(byIp.get(ip));
    }

    private Machine peer(String name, String lanCidr, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "key", "10.13.13.2/32", "88.0.0.1", "51820",
            "now", "0 B", "0 B", lanCidr, null, true, null, category, null);
    }

    /** A peer with its own public address, so it can be given a place on the map. */
    private Machine peerAt(String name, String lanCidr, String endpointIp) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "key", "10.13.13.2/32", endpointIp, "51820",
            "now", "0 B", "0 B", lanCidr, null, true, null, DeviceCategory.SERVER, null);
    }

    private Machine lanServer(String name, String lanAddress, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.LAN_SERVER, null, null, null, null, null, null, null,
            null, lanAddress, false, null, category, null);
    }

    private List<String> namesOf(SurvivalKitHosts.Selection selection) {
        return selection.chosen().stream().map(SurvivalKitHosts.Placement::machineName).toList();
    }

    private String skipReasonFor(SurvivalKitHosts.Selection selection, String machine) {
        return selection.skipped().stream()
            .filter(s -> s.machineName().equals(machine))
            .map(SurvivalKitHosts.Skipped::reason)
            .findFirst().orElseThrow(() -> new AssertionError(machine + " was not skipped"));
    }

    @Test
    void twoMachinesBehindTheSameRelayNeverBothHoldACopy() {
        // Both of these are in the same house, on the same LAN, behind the same peer. Two copies there is
        // one copy, wearing a disguise.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER),
            lanServer("NAS", "192.168.3.3", DeviceCategory.NAS),
            lanServer("nuc", "192.168.3.9", DeviceCategory.SERVER),
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).contains("Colina 27");
        assertThat(namesOf(selection).stream()
            .filter(n -> List.of("Apalveien 5", "NAS", "nuc").contains(n))).hasSize(1);
    }

    @Test
    void aSkippedMachineSaysWhichCopyCrowdedItOut_notJustThatItLost() {
        // "Not chosen" invites the operator to override it. "Already covered by the NAS, in the same house"
        // is a fact they can disagree with.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("NAS", "192.168.3.3", DeviceCategory.NAS),
            lanServer("nuc", "192.168.3.9", DeviceCategory.SERVER),
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(skipReasonFor(selection, "nuc")).contains("Apalveien 5");
    }

    @Test
    void theVaierServerNeverHoldsACopy() {
        // A kit that only exists on the machine it exists to outlive is not a kit.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer(VAIER, "10.0.0.0/24", DeviceCategory.SERVER),
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactly("Colina 27");
        assertThat(skipReasonFor(selection, VAIER)).containsIgnoringCase("Vaier");
    }

    @Test
    void clientMachinesAreNotEligible_becauseAKitOnSomethingCarriedAroundIsNotAKit() {
        // Vaier rewrites kits when passphrases change. A machine that is in a bag at that moment keeps a kit
        // that is silently out of date — the exact failure the printed sheet was rejected for. This reads
        // MachineType, the routing decision, not the icon beside the machine's name.
        Machine laptop = new Machine(MachineId.generate(), "geir-pc", MachineType.WINDOWS_CLIENT, "key", null, null, null, null,
            null, null, null, null, false, null, DeviceCategory.SERVER, true);
        Machine phone = new Machine(MachineId.generate(), "geirs-android", MachineType.MOBILE_CLIENT, "key", null, null, null,
            null, null, null, null, null, false, null, DeviceCategory.SERVER, true);

        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(laptop, phone,
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactly("Colina 27");
        assertThat(skipReasonFor(selection, "geir-pc")).containsIgnoringCase("client machine");
        assertThat(skipReasonFor(selection, "geirs-android")).containsIgnoringCase("client machine");
    }

    @Test
    void theDeviceCategoryDecidesNothing_becauseItIsOnlyAnIcon() {
        // Found against the real fleet: both relay servers are categorised GATEWAY — they are the site
        // gateways — and both Roon machines are MEDIA. Reading the category threw all four out and left the
        // fleet holding a single copy that looked like a working kit. A category an operator picked to get a
        // nicer icon must never cost them a backup.
        Machine gateway = new Machine(MachineId.generate(), "Colina 27", MachineType.UBUNTU_SERVER, "key", null, null, null, null,
            null, null, "192.168.1.0/24", "192.168.1.118", true, null, DeviceCategory.GATEWAY, true);
        Machine dietpi = new Machine(MachineId.generate(), "Roon kjokken", MachineType.LAN_SERVER, null, null, null, null, null,
            null, null, null, "192.168.3.104", false, null, DeviceCategory.MEDIA, true);
        Machine printerShaped = new Machine(MachineId.generate(), "NUC02", MachineType.LAN_SERVER, null, null, null, null, null,
            null, null, null, "192.168.9.9", false, null, DeviceCategory.PRINTER, true);

        SurvivalKitHosts.Selection selection =
            SurvivalKitHosts.select(List.of(gateway, dietpi, printerShaped), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactlyInAnyOrder("Colina 27", "Roon kjokken", "NUC02");
    }

    @Test
    void aFleetWithFewerSitesThanCopiesSaysSo_ratherThanQuietlyDeliveringFewer() {
        // This fleet has exactly two sites, so it gets two copies however many are asked for. Two copies
        // presented as if they were three is the same lie the printed sheet told.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER),
            lanServer("NAS", "192.168.3.3", DeviceCategory.NAS),
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(selection.chosen()).hasSize(2);
        assertThat(selection.fewerCopiesThanIntended()).isTrue();
    }

    @Test
    void withMoreSitesThanCopies_theOnesFurthestApartAreKept() {
        // Two of these four sites are in the same city, twenty minutes apart. They satisfy the relay rule
        // perfectly and would still be lost in the same flood, the same fire, the same burglary. Only one of
        // the pair may keep a copy; the distant sites both keep one.
        List<Machine> fleet = List.of(
            peerAt("oslo-a", "192.168.1.0/24", "88.0.0.1"),
            peerAt("oslo-b", "192.168.2.0/24", "88.0.0.2"),
            peerAt("madrid", "192.168.3.0/24", "88.0.0.3"),
            peerAt("sydney", "192.168.4.0/24", "88.0.0.4"));

        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(fleet, VAIER, geoOf(Map.of(
            "88.0.0.1", OSLO, "88.0.0.2", NEXT_DOOR, "88.0.0.3", MADRID, "88.0.0.4", SYDNEY)));

        assertThat(namesOf(selection)).contains("madrid", "sydney");
        assertThat(namesOf(selection).stream().filter(n -> List.of("oslo-a", "oslo-b").contains(n)))
            .hasSize(1);
    }

    @Test
    void theReasonSaysHowFarApartTheCopiesAre_becauseThatIsTheEvidenceForKeepingThemThere() {
        // "Chosen for separation" is a claim. "2,300 km from the nearest other copy" is a fact the operator
        // can check against what they know about where their machines actually are.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peerAt("oslo-a", "192.168.1.0/24", "88.0.0.1"),
            peerAt("oslo-b", "192.168.2.0/24", "88.0.0.2"),
            peerAt("madrid", "192.168.3.0/24", "88.0.0.3"),
            peerAt("sydney", "192.168.4.0/24", "88.0.0.4")), VAIER, geoOf(Map.of(
                "88.0.0.1", OSLO, "88.0.0.2", NEXT_DOOR, "88.0.0.3", MADRID, "88.0.0.4", SYDNEY)));

        assertThat(selection.chosen()).allSatisfy(p -> assertThat(p.reason()).containsPattern("\\d+ km"));
        // Whichever of the Oslo pair lost — they are within a few km of each other, so which one wins is
        // arbitrary and not worth asserting — is owed the distance that cost it the slot.
        assertThat(selection.skipped()).anySatisfy(s -> {
            assertThat(s.machineName()).startsWith("oslo-");
            assertThat(s.reason()).containsPattern("\\d+ km");
        });
    }

    @Test
    void aFleetVaierCannotPlaceOnTheMapStillGetsItsCopiesSpreadAcrossRelays() {
        // Geolocation is a preference, not a prerequisite. With no map at all the relay rule alone still
        // guarantees three separate sites — it just cannot say how far apart they are.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer("site-a", "192.168.1.0/24", DeviceCategory.SERVER),
            peer("site-b", "192.168.2.0/24", DeviceCategory.SERVER),
            peer("site-c", "192.168.3.0/24", DeviceCategory.SERVER),
            peer("site-d", "192.168.4.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(selection.chosen()).hasSize(SurvivalKitHosts.COPIES);
        assertThat(selection.chosen()).allSatisfy(p -> assertThat(p.reason()).doesNotContain("km"));
    }

    @Test
    void aMachineVaierCannotReachOverSshIsNotEligible_becauseItCouldNeverBeGivenAKit() {
        Machine unreachable = new Machine(MachineId.generate(), "locked-down", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.9.4", false, null, DeviceCategory.SERVER, false);

        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(unreachable,
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactly("Colina 27");
        assertThat(skipReasonFor(selection, "locked-down")).containsIgnoringCase("SSH");
    }

    @Test
    void itStopsAtThreeCopies_andSaysThatIsWhyTheRestWereNotChosen() {
        // More copies is more places to steal one from, for no more survivability.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer("site-a", "192.168.1.0/24", DeviceCategory.SERVER),
            peer("site-b", "192.168.2.0/24", DeviceCategory.SERVER),
            peer("site-c", "192.168.3.0/24", DeviceCategory.SERVER),
            peer("site-d", "192.168.4.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(selection.chosen()).hasSize(SurvivalKitHosts.COPIES);
        assertThat(skipReasonFor(selection, "site-d")).contains("3");
    }

    @Test
    void theRelayItselfKeepsItsSitesCopy_becauseItIsTheMachineThatDefinesTheSite() {
        // Only one machine behind a relay may hold the copy, and the relay is picked on structure rather
        // than on looks: it terminates the tunnel, so whenever the site is reachable at all, it is up.
        // Ranking these three by device category would be prettier and would be an icon deciding a backup.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("some-box", "192.168.3.9", DeviceCategory.GENERIC),
            lanServer("NAS", "192.168.3.3", DeviceCategory.NAS),
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactly("Apalveien 5");
    }

    @Test
    void withNoEligibleMachineAtAll_itChoosesNothingRatherThanSomethingUnsuitable() {
        // Silently relaxing the rules would produce a kit on a laptop and a green tick next to it.
        Machine laptop = new Machine(MachineId.generate(), "geir-pc", MachineType.WINDOWS_CLIENT, "key", null, null, null, null,
            null, null, null, null, false, null, DeviceCategory.SERVER, true);

        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(laptop), VAIER, NO_GEO);

        assertThat(selection.chosen()).isEmpty();
        assertThat(selection.skipped()).hasSize(1);
    }

    @Test
    void aLanServerOnANetworkNoPeerClaims_countsAsItsOwnSite() {
        // Not attributable to a relay is not the same as "same site as everything else unattributed" —
        // merging them would be a guess that quietly costs a copy.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("orphan-a", "10.9.9.4", DeviceCategory.SERVER),
            lanServer("orphan-b", "10.9.8.4", DeviceCategory.SERVER)), VAIER, NO_GEO);

        assertThat(namesOf(selection)).containsExactlyInAnyOrder("orphan-a", "orphan-b");
    }
}
