package net.vaier.adapter.driven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.RecoverySheet;
import net.vaier.domain.SurvivalKit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one promise the survival kit makes that Vaier cannot keep on its own: that a person with the passphrase
 * and nothing else can open it.
 *
 * <p>So this test does not check the envelope against a Java re-implementation of the envelope — that would
 * only prove Vaier agrees with itself. It runs the <em>real</em> {@code openssl} binary, on the literal
 * command the kit prints on its own face, and requires the fleet's keys to come back out. If openssl ever
 * disagrees with what Vaier writes, the failure surfaces here rather than on the day someone has lost
 * everything else.
 */
class OpensslEnvelopeAdapterTest {

    private static final String PASSPHRASE = "correct horse battery staple";

    private final OpensslEnvelopeAdapter adapter = new OpensslEnvelopeAdapter();

    @TempDir
    Path dir;

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void opensslDecryptsWhatVaierEncrypted() throws Exception {
        assumeTrue(opensslPresent(), "openssl is not installed on this machine");
        String plaintext = "every key to every backup\nsecond line\n";

        Files.writeString(dir.resolve("cipher.b64"), adapter.encrypt(plaintext, PASSPHRASE));

        assertThat(run("openssl enc -aes-256-cbc -pbkdf2 -d -a -in cipher.b64 -pass pass:'" + PASSPHRASE + "'"))
            .isEqualTo(plaintext);
    }

    @Test
    void theWrongPassphraseFails_ratherThanReturningSomethingThatLooksLikeAnAnswer() throws Exception {
        assumeTrue(opensslPresent(), "openssl is not installed on this machine");
        Files.writeString(dir.resolve("cipher.b64"), adapter.encrypt("secrets", PASSPHRASE));

        assertThat(exitCodeOf("openssl enc -aes-256-cbc -pbkdf2 -d -a -in cipher.b64 -pass pass:wrong"))
            .isNotZero();
    }

    @Test
    void theCommandPrintedOnTheKitOpensTheKit() throws Exception {
        // The whole artefact, end to end: the header's own instructions, run verbatim, against the file Vaier
        // would have written to a fleet host. `-pass pass:` is appended only because a test has no terminal
        // for openssl to prompt at; everything else is exactly what the operator would type.
        assumeTrue(opensslPresent(), "openssl is not installed on this machine");
        List<BackupRepository> repositories = List.of(
            new BackupRepository("Apalveien-5", "nas-borg", null, "s3cr3t-one", false));
        String kit = SurvivalKit.render(server(), repositories, List.of(), "AAAAkey==", PASSPHRASE,
            Instant.parse("2026-07-23T10:15:30Z"), adapter);
        Files.writeString(dir.resolve(SurvivalKit.FILE_NAME), kit);

        String recovered = run(SurvivalKit.decryptCommand() + " -pass pass:'" + PASSPHRASE + "'");

        assertThat(recovered)
            .isEqualTo(RecoverySheet.render(server(), repositories, List.of(), "AAAAkey=="))
            .contains("s3cr3t-one")
            .contains("ssh://borg@192.168.3.3:8022/home/borg/backups/Apalveien-5");
    }

    private boolean opensslPresent() throws Exception {
        return exitCodeOf("command -v openssl") == 0;
    }

    /** Runs {@code command} in the temp directory and returns its stdout; fails the test on a non-zero exit. */
    private String run(String command) throws Exception {
        Process process = bash(command);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).describedAs("`%s` failed: %s", command, err).isZero();
        return out;
    }

    private int exitCodeOf(String command) throws Exception {
        Process process = bash(command);
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        return process.waitFor();
    }

    private Process bash(String command) throws Exception {
        return new ProcessBuilder("bash", "-c", command).directory(dir.toFile()).start();
    }
}
