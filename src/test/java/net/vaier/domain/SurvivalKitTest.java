package net.vaier.domain;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import net.vaier.domain.port.ForEncryptingSurvivalKits;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kit that outlives the Vaier server.
 *
 * <p>A printed recovery sheet was rejected for one reason: it goes stale the moment a passphrase changes, and
 * a stale sheet is worse than none, because you believe you are covered. The kit replaces it with something
 * Vaier can rewrite — an encrypted file it distributes across the fleet. What the operator keeps shrinks from
 * a list that rots to one passphrase that does not.
 *
 * <p>Two properties make it work, and both are tested here: the contents are never in the clear (the file
 * lives on machines that may be compromised), and opening it needs nothing but {@code openssl} (needing Vaier
 * to read Vaier's own recovery kit would restore the exact circularity it exists to break).
 */
class SurvivalKitTest {

    /** Stands in for the real cipher: reversible, obviously not encryption, and marks what it touched. */
    private static final ForEncryptingSurvivalKits FAKE_CIPHER = (plaintext, passphrase) ->
        Base64.getEncoder().encodeToString(("[" + passphrase + "]" + plaintext).getBytes());

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo(String name, String pass) {
        return new BackupRepository(name, "nas-borg", null, pass, false);
    }

    /** The marker as it actually terminates the header: alone on its own line. */
    private String markerLine() {
        return "\n" + SurvivalKit.BEGIN_MARKER + "\n";
    }

    private String kit() {
        return SurvivalKit.render(server(), List.of(repo("Apalveien-5", "s3cr3t-one")), List.of(),
            "AAAAkey==", "correct-horse-battery-staple", Instant.parse("2026-07-23T10:15:30Z"), FAKE_CIPHER);
    }

    @Test
    void noSecretAppearsInTheClear_becauseTheKitLivesOnMachinesThatMayBeCompromised() {
        // The whole reason plaintext copies on N hosts were rejected: compromising any single host would
        // otherwise hand over every key to every backup in the fleet.
        String kit = kit();

        assertThat(kit).doesNotContain("s3cr3t-one");
        assertThat(kit).doesNotContain("AAAAkey==");
        assertThat(kit).doesNotContain("correct-horse-battery-staple");
    }

    @Test
    void theInstructionsAreInTheClear_becauseTheyAreUselessToAnAttackerAndVitalToTheOperator() {
        // A kit whose instructions are inside the kit is a locked box with the key inside it.
        String kit = kit();

        assertThat(kit).contains("openssl enc -aes-256-cbc -pbkdf2");
        assertThat(kit).contains("-iter " + ForEncryptingSurvivalKits.PBKDF2_ITERATIONS);
        assertThat(kit).contains(SurvivalKit.FILE_NAME);
        assertThat(kit.indexOf("openssl")).isLessThan(kit.indexOf(markerLine()));
    }

    @Test
    void theMarkerTextAppearsTwice_andOnlyTheOneOnItsOwnLineEndsTheHeader() {
        // The printed command quotes the marker inside its sed pattern, so the text occurs before the real
        // marker does. sed's anchors (^...$) are what keep it from cutting the file at the instructions —
        // which is only actually proven by running the real command, in OpensslEnvelopeAdapterTest.
        String kit = kit();

        assertThat(kit.indexOf(SurvivalKit.BEGIN_MARKER)).isLessThan(kit.indexOf(markerLine()));
        assertThat(kit.split("\n(?=" + SurvivalKit.BEGIN_MARKER + "\n)")).hasSize(2);
    }

    @Test
    void thePayloadFollowsTheMarker_andIsNothingButTheCiphertext() {
        // The printed command strips everything through the marker and pipes the rest straight into openssl,
        // so a single stray character after it breaks recovery.
        String kit = kit();
        String afterMarker = kit.substring(kit.indexOf(markerLine()) + markerLine().length()).strip();

        assertThat(afterMarker).isEqualTo(FAKE_CIPHER.encrypt(
            RecoverySheet.render(server(), List.of(repo("Apalveien-5", "s3cr3t-one")), List.of(), "AAAAkey=="),
            "correct-horse-battery-staple"));
    }

    @Test
    void itSaysWhenItWasWritten_soAKitThatStoppedBeingUpdatedCanBeSeenToHaveStopped() {
        // Staleness is the failure this whole design exists to prevent; it has to be visible without
        // decrypting, on a machine that no longer has a Vaier to ask.
        assertThat(kit()).contains("2026-07-23");
    }

    @Test
    void itSaysHowMuchItCovers_withoutNamingAnythingItCovers() {
        // Enough for the operator to spot a kit written before half the fleet existed; not a plaintext
        // inventory of the fleet's machines, sitting on a machine in it.
        String kit = SurvivalKit.render(server(),
            List.of(repo("one", "p"), repo("two", "p"), repo("three", "p")), List.of(), "k",
            "pass", Instant.parse("2026-07-23T10:15:30Z"), FAKE_CIPHER);

        assertThat(kit).contains("3 repositories");
        assertThat(kit).doesNotContain("three");
    }

    @Test
    void aKitWithNoPassphraseIsRefused_ratherThanWrittenWithoutOne() {
        // Falling back to "no passphrase" would produce a file that looks exactly like a protected kit and
        // hands over the fleet to anyone who opens it. There is no safe default here.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            SurvivalKit.render(server(), List.of(repo("r", "p")), List.of(), "k", "  ",
                Instant.parse("2026-07-23T10:15:30Z"), FAKE_CIPHER))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
