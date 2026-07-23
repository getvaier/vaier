package net.vaier.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the copies go.
 *
 * <p>A survival kit on one machine is a kit with a single point of failure, and the operator is the worst
 * person to choose the machines — it is a question about failure domains they would have to hold in their
 * head, re-answer every time the fleet changes, and would get wrong quietly. Vaier picks, and says why.
 *
 * <p>The one rule that carries the whole idea: <b>never two copies behind the same relay peer</b>. Three
 * copies in one house is one flood, one fire, one burglary away from zero copies, and it would look like
 * redundancy the entire time.
 */
class SurvivalKitHostsTest {

    private static final String VAIER = "vaier-server";

    private Machine peer(String name, String lanCidr, DeviceCategory category) {
        return new Machine(name, MachineType.UBUNTU_SERVER, "key", "10.13.13.2/32", "88.0.0.1", "51820",
            "now", "0 B", "0 B", lanCidr, null, true, null, category, null);
    }

    private Machine lanServer(String name, String lanAddress, DeviceCategory category) {
        return new Machine(name, MachineType.LAN_SERVER, null, null, null, null, null, null, null,
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
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER);

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
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER)), VAIER);

        assertThat(skipReasonFor(selection, "nuc")).contains("NAS");
    }

    @Test
    void theVaierServerNeverHoldsACopy() {
        // A kit that only exists on the machine it exists to outlive is not a kit.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            peer(VAIER, "10.0.0.0/24", DeviceCategory.SERVER),
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER);

        assertThat(namesOf(selection)).containsExactly("Colina 27");
        assertThat(skipReasonFor(selection, VAIER)).containsIgnoringCase("Vaier");
    }

    @Test
    void laptopsAndPhonesAndAppliancesAreNotEligible_becauseAKitOnAThingThatSleepsIsNotAKit() {
        // Vaier rewrites kits when passphrases change. A machine that is asleep at that moment keeps a kit
        // that is silently out of date — the exact failure the printed sheet was rejected for.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("geir-laptop", "192.168.3.20", DeviceCategory.LAPTOP),
            lanServer("hallway-printer", "192.168.1.11", DeviceCategory.PRINTER),
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER);

        assertThat(namesOf(selection)).containsExactly("Colina 27");
        assertThat(skipReasonFor(selection, "geir-laptop")).containsIgnoringCase("always on");
        // Not "Vaier has no SSH access to it", which is true of a printer and would send someone off to
        // arrange SSH access for a printer.
        assertThat(skipReasonFor(selection, "hallway-printer")).containsIgnoringCase("printer");
    }

    @Test
    void aMachineVaierCannotReachOverSshIsNotEligible_becauseItCouldNeverBeGivenAKit() {
        Machine unreachable = new Machine("locked-down", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.9.4", false, null, DeviceCategory.SERVER, false);

        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(unreachable,
            peer("Colina 27", "192.168.1.0/24", DeviceCategory.SERVER)), VAIER);

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
            peer("site-d", "192.168.4.0/24", DeviceCategory.SERVER)), VAIER);

        assertThat(selection.chosen()).hasSize(SurvivalKitHosts.COPIES);
        assertThat(skipReasonFor(selection, "site-d")).contains("3");
    }

    @Test
    void storageMachinesAreChosenAhead_ofTheGeneralPurposeOnesAtTheSameSite() {
        // Between a NAS, a random box and the relay they both sit behind, the NAS is the one still plugged
        // in and spinning next year — and only one of the three may hold the site's copy.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("some-box", "192.168.3.9", DeviceCategory.GENERIC),
            lanServer("NAS", "192.168.3.3", DeviceCategory.NAS),
            peer("Apalveien 5", "192.168.3.0/24", DeviceCategory.SERVER)), VAIER);

        assertThat(namesOf(selection)).containsExactly("NAS");
    }

    @Test
    void withNoEligibleMachineAtAll_itChoosesNothingRatherThanSomethingUnsuitable() {
        // Silently relaxing the rules would produce a kit on a laptop and a green tick next to it.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("geir-laptop", "192.168.3.20", DeviceCategory.LAPTOP)), VAIER);

        assertThat(selection.chosen()).isEmpty();
        assertThat(selection.skipped()).hasSize(1);
    }

    @Test
    void aLanServerOnANetworkNoPeerClaims_countsAsItsOwnSite() {
        // Not attributable to a relay is not the same as "same site as everything else unattributed" —
        // merging them would be a guess that quietly costs a copy.
        SurvivalKitHosts.Selection selection = SurvivalKitHosts.select(List.of(
            lanServer("orphan-a", "10.9.9.4", DeviceCategory.SERVER),
            lanServer("orphan-b", "10.9.8.4", DeviceCategory.SERVER)), VAIER);

        assertThat(namesOf(selection)).containsExactlyInAnyOrder("orphan-a", "orphan-b");
    }
}
