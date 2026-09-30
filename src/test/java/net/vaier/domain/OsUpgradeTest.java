package net.vaier.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForSendingAdminNotification;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An <b>OS upgrade</b>: whether Vaier may install a machine's pending OS updates at all, the command it runs
 * for the machine's package manager, and how the run reads as an outcome.
 */
class OsUpgradeTest {

    private static final MachineId MACHINE = TestMachineIds.of("colina27");

    private final ForRunningSshCommands ssh = mock(ForRunningSshCommands.class);
    private final ForTrackingHostKeys hostKeys = mock(ForTrackingHostKeys.class);

    private static final SshTarget TARGET =
        new SshTarget("10.13.13.3", 22, "geir", AuthMethod.PASSWORD, "secret", null, null, MACHINE);

    private static CommandResult said(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:pinned");
    }

    private static final SshTarget KEY_TARGET =
        new SshTarget("10.13.13.3", 22, "geir", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", null, null, MACHINE);

    private OsUpgrade probed(String probeOutput) {
        return probed(probeOutput, TARGET, 1);
    }

    /** {@code passwordSudoExit}: how {@code sudo -S} answers the login password on stdin. */
    private OsUpgrade probed(String probeOutput, SshTarget target, int passwordSudoExit) {
        reset(ssh);
        when(ssh.run(target, OsUpgrade.PROBE_COMMAND)).thenReturn(said(probeOutput));
        when(ssh.run(target, OsUpgrade.PASSWORD_SUDO_PROBE, OsUpgrade.PASSWORD_SUDO_PROBE_TIMEOUT, "secret"))
            .thenReturn(new CommandResult(passwordSudoExit, "", "", false, "SHA256:pinned"));
        return OsUpgrade.of(MACHINE, "Colina 27", target, ssh, hostKeys);
    }

    /**
     * Root comes from logging in as uid 0, from passwordless sudo, or — for a password login — from sudo taking
     * that login password on stdin, never on a command line. Anything else is refused by name.
     */
    @Test
    void itUpgradesOnlyWhereVaierCanGetRoot_andSaysWhyNotWhereItCannot() {
        OsUpgrade asRoot = probed("user=root uid=0\npm=apt\n");
        assertThat(asRoot.upgradeCommand()).isEqualTo("export DEBIAN_FRONTEND=noninteractive && apt-get update"
            + " && apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade --with-new-pkgs");
        assertThat(asRoot.rebootRequiredCommand())
            .isEqualTo("test -f /var/run/reboot-required && echo REBOOT_REQUIRED; true");

        assertThat(probed("user=geir uid=1000\nsudo=yes\npm=apt\npm=dnf\n").upgradeCommand())
            .as("apt wins where both are present, and sudo wraps the whole line")
            .isEqualTo("sudo -n sh -c 'export DEBIAN_FRONTEND=noninteractive && apt-get update"
                + " && apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade --with-new-pkgs'");
        OsUpgrade fedora = probed("user=geir uid=1000\nsudo=yes\npm=dnf\n");
        assertThat(fedora.upgradeCommand()).isEqualTo("sudo -n sh -c 'dnf -y upgrade'");
        assertThat(fedora.rebootRequiredCommand()).contains("dnf needs-restarting -r").contains("REBOOT_REQUIRED");
        verify(hostKeys, atLeastOnce()).pin(MACHINE, "SHA256:pinned");

        OsUpgrade withPassword = probed("user=geir uid=1000\npm=dnf\n", TARGET, 0);
        assertThat(withPassword.upgradeCommand()).isEqualTo("sudo -S -p '' sh -c 'dnf -y upgrade'")
            .doesNotContain("secret");
        when(ssh.run(TARGET, withPassword.upgradeCommand(), OsUpgrade.UPGRADE_TIMEOUT, "secret"))
            .thenReturn(said("Nothing to do.\n"));
        when(ssh.run(TARGET, withPassword.rebootRequiredCommand())).thenReturn(said(""));
        assertThat(withPassword.carryOut(TARGET, ssh).upgraded()).as("the password rides stdin").isTrue();

        record Row(String probe, SshTarget target, String refusal) {}
        for (Row row : new Row[] {
            new Row("user=geir uid=1000\npm=apt\n", TARGET, "Vaier logs in to Colina 27 as geir, who cannot use "
                + "sudo with or without the login password, so it cannot install OS updates there. Log Vaier in "
                + "as root, or give geir sudo."),
            new Row("user=geir uid=1000\npm=apt\n", KEY_TARGET, "Vaier logs in to Colina 27 as geir, who cannot "
                + "use sudo without a password, so it cannot install OS updates there. Log Vaier in as root, or "
                + "give geir passwordless sudo."),
            // No password on file: nothing to offer sudo.
            new Row("user=geir uid=1000\npm=apt\n", new SshTarget("10.13.13.3", 22, "geir", AuthMethod.PASSWORD,
                null, null, null, MACHINE), "Vaier logs in to Colina 27 as geir, who cannot use sudo without a "
                + "password, so it cannot install OS updates there. Log Vaier in as root, or give geir "
                + "passwordless sudo."),
            new Row("user=admin uid=1024\nsudo=yes\n", TARGET, "Colina 27 has neither apt nor dnf, the only "
                + "package managers Vaier installs OS updates with."),
            new Row("", TARGET, "Vaier could not ask Colina 27 how it installs OS updates."),
            new Row("user=root uid=0\npm=apt\nbusy=yes\n", TARGET, "Colina 27 is already installing updates. "
                + "Try again when it is done."),
        }) {
            assertThatThrownBy(() -> probed(row.probe(), row.target(), 1)).as(row.probe())
                .isInstanceOf(ConflictException.class).hasMessage(row.refusal());
        }
    }

    /** The upgrade may take many minutes; the outcome counts what changed and says whether a reboot is due. */
    @Test
    void carryingItOut_readsTheCountAndTheRebootIntoOneSentence() {
        record Row(String pm, CommandResult upgrade, String reboot, boolean upgraded, String sentence) {}
        for (Row row : new Row[] {
            new Row("apt", said("Reading package lists...\n12 upgraded, 1 newly installed, 0 to remove and 3 not "
                + "upgraded.\n"), "REBOOT_REQUIRED\n", true,
                "Colina 27 installed 13 package updates. It needs a reboot to finish; Vaier did not reboot it."),
            new Row("apt", said("0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.\n"), "", true,
                "Colina 27 had no OS updates to install."),
            new Row("dnf", said("Transaction Summary\n======\nUpgrade  1 Package\n\nComplete!\n"), "", true,
                "Colina 27 installed 1 package update."),
            new Row("dnf", said("Last metadata expiration check: 0:01:02 ago.\nDependencies resolved.\n"
                + "Nothing to do.\nComplete!\n"), "", true, "Colina 27 had no OS updates to install."),
            new Row("apt", new CommandResult(100, "", "E: Could not get lock /var/lib/dpkg/lock-frontend\n", false,
                "SHA256:pinned"), "", false, "Installing OS updates on Colina 27 failed. The host said: "
                + "E: Could not get lock /var/lib/dpkg/lock-frontend"),
            new Row("apt", new CommandResult(-1, "", "", true, "SHA256:pinned"), "", false,
                "Installing OS updates on Colina 27 took over 30 minutes, so Vaier stopped waiting. It may still "
                + "be running there."),
        }) {
            OsUpgrade upgrade = probed("user=root uid=0\npm=" + row.pm() + "\n");
            when(ssh.run(TARGET, upgrade.upgradeCommand(), OsUpgrade.UPGRADE_TIMEOUT)).thenReturn(row.upgrade());
            when(ssh.run(TARGET, upgrade.rebootRequiredCommand())).thenReturn(said(row.reboot()));

            OsUpgrade.Settlement settled = upgrade.carryOut(TARGET, ssh);

            assertThat(settled.upgraded()).as(row.sentence()).isEqualTo(row.upgraded());
            assertThat(settled.sentence()).isEqualTo(row.sentence());
        }

        OsUpgrade upgrade = probed("user=root uid=0\npm=apt\n");
        when(ssh.run(TARGET, upgrade.upgradeCommand(), OsUpgrade.UPGRADE_TIMEOUT))
            .thenThrow(new SshConnectException("Could not run a command on 10.13.13.3 (Connection refused)"));
        OsUpgrade.Settlement unreachable = upgrade.carryOut(TARGET, ssh);
        assertThat(unreachable.upgraded()).isFalse();
        assertThat(unreachable.sentence()).isEqualTo("Vaier could not reach Colina 27 to install its OS updates.");
        assertThat(unreachable.diagnostic()).contains("Connection refused");
    }

    /** It settles on the fleet stream the Explorer holds open, and only a failure is mailed. */
    @Test
    void aSettlementIsAnnouncedOnTheFleetStream_andOnlyAFailureIsMailed() throws Exception {
        OsUpgrade upgrade = probed("user=root uid=0\npm=apt\n");
        OsUpgrade.Settlement settled = new OsUpgrade.Settlement(false, "It said \"no\".\nTwice.", "diag");
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        upgrade.announce(settled, events);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(events).publish(eq("vpn-peers"), eq("os-upgrade-settled"), json.capture());
        JsonNode payload = new ObjectMapper().readTree(json.getValue());
        assertThat(payload.get("machineId").asText()).isEqualTo(MACHINE.value());
        assertThat(payload.get("upgraded").asBoolean()).isFalse();
        assertThat(payload.get("message").asText()).isEqualTo("It said \"no\".\nTwice.");

        ForSendingAdminNotification mail = mock(ForSendingAdminNotification.class);
        upgrade.mailIfFailed(new OsUpgrade.Settlement(true, "Colina 27 installed 3 package updates.", null), mail);
        verifyNoInteractions(mail);
        upgrade.mailIfFailed(settled, mail);
        verify(mail).sendToAdmins(eq("OS updates failed on Colina 27"), eq(settled.sentence()), any());
    }
}
