package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BorgServerSetupScriptTest {

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    @Test
    void generate_startsWithBashShebangAndStrictMode_asRoot() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).startsWith("#!/usr/bin/env bash");
        assertThat(s).contains("set -euo pipefail");
        assertThat(s).contains("id -u"); // root check + uid derivation
    }

    @Test
    void generate_writesADotEnvSoComposeResolvesUidGidWithoutTheShell() {
        // The compose references ${BORG_UID}/${BORG_GID}. If they only live in the exported shell env, a
        // later hand-run `docker compose up -d` on the host substitutes EMPTY values and the container
        // chowns its data to nothing. docker compose auto-loads a .env from the project dir, so write one.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("${DATA_DIR}/.env");
        assertThat(s).contains("BORG_UID=${BORG_UID}");
        assertThat(s).contains("BORG_GID=${BORG_GID}");
    }

    @Test
    void generate_chownsTheSshDirectoryToTheOwner_soAuthorizeKeyCanWriteItsTempAndBackupFiles() {
        // BorgCommand.authorizeKey runs as the SSH owner and creates authorized_keys.tmp and
        // authorized_keys.bak-vaier *inside* <dataDir>/ssh. This script runs as root, so a root-owned 0755
        // ssh/ dir makes those writes fail with permission denied and key trust silently breaks. Chowning
        // authorized_keys alone is not enough — the directory must be writable by the owner too.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("chown \"$OWNER\" \"${DATA_DIR}/ssh\"");
    }

    @Test
    void generate_chownsAuthorizedKeysToTheOwnerSoVaierCanAppendClientKeys() {
        // Vaier appends client public keys to authorized_keys over SSH *as the owner*. The script runs as
        // root, so without an explicit chown the file stays root-owned 0600 until the container's
        // ENSURE_BACKUP_PERMISSIONS happens to chown it — a start-order race that silently breaks key trust.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("chown \"$OWNER\" \"${AUTH_KEYS}\"");
    }

    @Test
    void generate_pinsTheBorgServerImage_neverLatest() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("image: horaceworblehat/borg-server:2.8.6");
        assertThat(s).doesNotContain(":latest");
    }

    @Test
    void generate_mapsTheServerSshPortTo22() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("\"8022:22\"");
    }

    @Test
    void generate_declaresAllThreeVolumeMounts() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s)
            .contains("./ssh:/home/borg/.ssh")
            .contains("./backups:/home/borg/backups")
            .contains("./server_keys:/var/lib/docker-borg");
    }

    @Test
    void generate_setsTheFourBorgServerEnvVars() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s)
            .contains("ENSURE_BACKUP_PERMISSIONS=true")
            .contains("BORG_SERVE_ADDITIONAL_ARGS=");
        // The uid/gid env vars are interpolated by docker compose from the shell environment, not baked.
        assertThat(s)
            .contains("BORG_UID=${BORG_UID}")
            .contains("BORG_GID=${BORG_GID}");
    }

    @Test
    void generate_derivesBorgUidAndGidFromTheOwnerAtRunTime_neverHardcodes1000() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        // The owner is baked as a literal; the uid/gid it resolves to are computed on the host so the
        // borg container chowns its data to the exact SSH user Vaier writes authorized_keys as.
        assertThat(s).contains("OWNER=\"geir\"");
        assertThat(s).contains("id -u \"$OWNER\"");
        assertThat(s).contains("id -g \"$OWNER\"");
        assertThat(s).contains("BORG_UID=\"$(id -u \"$OWNER\")\"");
        assertThat(s).contains("BORG_GID=\"$(id -g \"$OWNER\")\"");
        assertThat(s).contains("export BORG_UID BORG_GID");
        // The old defect: 1000/1000 must never be baked in again.
        assertThat(s).doesNotContain("BORG_UID=1000");
        assertThat(s).doesNotContain("BORG_GID=1000");
    }

    @Test
    void generate_failsLoudlyWhenTheOwnerDoesNotExistOnTheHost() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        // The user must exist before we compute its uid/gid, else the compose would run with an empty env.
        assertThat(s).contains("if ! id \"$OWNER\" >/dev/null 2>&1; then");
        assertThat(s).contains("exit");
    }

    @Test
    void generate_bakesTheProvidedOwnerUsername() {
        String s = BorgServerSetupScript.generate(server(), "someguy");

        assertThat(s).contains("OWNER=\"someguy\"");
    }

    @Test
    void generate_isIdempotent_makesDirsAndNeverUnconditionallyRemoves() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("mkdir -p");
        assertThat(s).contains("$COMPOSE up -d");
        // Safe to re-run: no unconditional destructive removal of the data dir.
        assertThat(s).doesNotContain("rm -rf");
        assertThat(s).doesNotContain("rm -r ");
    }

    @Test
    void generate_fallsBackToLegacyDockerComposeV1_forSynology() {
        // The Synology NAS — the headline backup-server target — ships only the v1 `docker-compose`
        // binary; `docker compose` (the v2 plugin) does not exist there. Hardcoding the v2 form makes the
        // bootstrap fail at the final step on the very host it was designed for.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("docker compose version");   // prefer v2 when present
        assertThat(s).contains("docker-compose");            // fall back to v1
        assertThat(s).contains("$COMPOSE up -d");
    }

    @Test
    void dockerAvailabilityProbe_provesTheDaemonIsReachable_notMerelyThatTheCliExists() {
        // On the Synology the SSH user HAS the docker CLI but is denied on /var/run/docker.sock. A probe
        // that only checks `command -v docker` would report OK and provisioning would then fail. It must
        // touch the daemon, widen PATH, and accept either compose front-end.
        String probe = BorgServerSetupScript.dockerAvailabilityProbe();

        assertThat(probe).contains("docker ps");            // touches the daemon
        assertThat(probe).contains("/usr/local/bin");        // Synology PATH
        assertThat(probe).contains("docker-compose");        // v1 fallback accepted
        assertThat(probe).contains("DOCKER_ABSENT");
    }

    @Test
    void generate_widensPathForHostsThatKeepDockerInUsrLocalBin() {
        // Synology symlinks docker into /usr/local/bin, which a non-interactive `sudo bash` PATH omits.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("/usr/local/bin");
    }

    @Test
    void generate_ensuresAuthorizedKeysExistsWithTrailingNewline() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        // The file is pre-created so a later key append can never concatenate onto a previous key.
        assertThat(s).contains("authorized_keys");
        assertThat(s).contains("touch");
        // Normalises a missing trailing newline before any '>>' append.
        assertThat(s).contains("printf '\\n'");
        assertThat(s).contains("chmod 600");
    }

    @Test
    void generate_usesTheServerDataPathForEveryDirectory() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("DATA_DIR=\"/volume1/docker/borg\"");
        assertThat(s).contains("${DATA_DIR}/docker-compose.yml");
    }

    @Test
    void generate_neverContainsLatestAnywhere() {
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).doesNotContain(":latest");
    }

    @Test
    void generate_rejectsABlankServerDataPath() {
        BackupServer noPath = new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", null, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> BorgServerSetupScript.generate(noPath, "geir"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Slice 8: publishing the container's host keys for clients to pin ---

    @Test
    void generate_publishesTheHostKeysWhereTheSshOwnerCanReadThem() {
        // The container generates fresh SSH host keys under server_keys/ssh (root-owned, drwx------). The
        // script runs as root, so it concatenates the PUBLIC *.pub keys to <dataDir>/ssh/host_keys.pub,
        // chowns it to the SSH owner and makes it 644 so Vaier can read them over SSH and pin them on clients.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("server_keys/ssh");
        // Concatenates the *.pub host keys into the published file.
        assertThat(s).contains("host_keys.pub");
        assertThat(s).contains("*.pub");
        // Handed to the SSH owner and world-readable (they are public keys).
        assertThat(s).contains("chown \"$OWNER\" \"${DATA_DIR}/ssh/host_keys.pub\"");
        assertThat(s).contains("chmod 644 \"${DATA_DIR}/ssh/host_keys.pub\"");
    }

    @Test
    void generate_waitsBoundedForTheEd25519HostKeyBeforePublishing() {
        // The keys only exist once the container has generated them, so the publish waits (bounded) for the
        // ed25519 public key to appear rather than racing the container start.
        String s = BorgServerSetupScript.generate(server(), "geir");

        assertThat(s).contains("ssh_host_ed25519_key.pub");
        // Bounded wait, mirroring the port-open probe — never an unbounded loop.
        assertThat(s).contains("until [ -f");
    }

    @Test
    void generate_neverPublishesAPrivateHostKey() {
        // The private ssh_host_*_key files must never be copied — only their .pub counterparts. Every
        // reference to a host-key file in the script is a .pub file.
        String s = BorgServerSetupScript.generate(server(), "geir");

        // Only the public key file appears; there is no bare private-key path (no "_key " nor "_key\"").
        assertThat(s).contains("ssh_host_ed25519_key.pub");
        assertThat(s).doesNotContain("ssh_host_ed25519_key\"");
        assertThat(s).doesNotContain("ssh_host_ed25519_key ");
        assertThat(s).doesNotContain("ssh_host_rsa_key\"");
    }

    // --- Detached launch (survives the 20 s SSH exec cap) ---

    @Test
    void detachedLaunch_base64DecodesTheScriptThenNohupsItAndEchoesStarted() {
        String script = BorgServerSetupScript.generate(server(), "geir");

        String launch = BorgServerSetupScript.detachedLaunch(script, "provision-nas-borg",
            "/home/geir/.vaier-backup");

        // The work dir is prepared, the script decoded from base64 (so its contents cannot break quoting),
        // made executable, then backgrounded with nohup — only STARTED is echoed so the exec returns fast.
        assertThat(launch)
            .contains("W=/home/geir/.vaier-backup")
            .contains("mkdir -p \"$W\"")
            .contains("base64 -d > \"$W/provision-nas-borg.sh\"")
            .contains("chmod +x \"$W/provision-nas-borg.sh\"")
            .contains("nohup sh -c")
            .contains("& echo STARTED $!");
        // The exit code and output land in the same rc/log files the generic poll/fetch read.
        assertThat(launch).contains("echo \\$? > \\\"$W/provision-nas-borg.rc\\\"");
        assertThat(launch).contains("> \"$W/provision-nas-borg.log\" 2>&1");
        // The raw script never appears inline (it is base64-encoded), so the compose is not run synchronously.
        assertThat(launch).doesNotContain("docker compose up -d");
    }

    // --- Staging the script onto the host (the setup.sh-is-behind-auth fix) ---

    @Test
    void stageScript_base64EncodesTheScript_soItsRawContentsNeverAppearInline() {
        String script = BorgServerSetupScript.generate(server(), "geir");

        String cmd = BorgServerSetupScript.stageScript(script,
            "/home/geir/.vaier-backup/nas-borg-borg-setup.sh");

        // The script is riddled with quotes, $, and heredocs — none of it may appear literally in the
        // command (it is base64-encoded exactly like detachedLaunch does), or a character in it could break
        // the command line.
        assertThat(cmd)
            .doesNotContain("set -euo pipefail")
            .doesNotContain("horaceworblehat/borg-server")
            .doesNotContain("docker-compose.yml");
        String expectedB64 = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        assertThat(cmd).contains(expectedB64);
    }

    @Test
    void stageScript_writesToTheGivenPath_makesItExecutable_andEchoesStaged() {
        String cmd = BorgServerSetupScript.stageScript("#!/bin/sh\necho hi\n",
            "/home/geir/.vaier-backup/nas-borg-borg-setup.sh");

        assertThat(cmd)
            .contains("mkdir -p \"$(dirname '/home/geir/.vaier-backup/nas-borg-borg-setup.sh')\"")
            .contains("base64 -d > '/home/geir/.vaier-backup/nas-borg-borg-setup.sh'")
            .contains("chmod +x '/home/geir/.vaier-backup/nas-borg-borg-setup.sh'")
            .contains("echo STAGED '/home/geir/.vaier-backup/nas-borg-borg-setup.sh'");
    }

    @Test
    void parseStagedPath_readsThePathBackFromTheConfirmation() {
        // The shell strips the single quotes, so the real stdout is `STAGED <path>`.
        assertThat(BorgServerSetupScript.parseStagedPath(
                "STAGED /home/geir/.vaier-backup/nas-borg-borg-setup.sh\n"))
            .contains("/home/geir/.vaier-backup/nas-borg-borg-setup.sh");
        // Tolerant of a shell that kept the quotes.
        assertThat(BorgServerSetupScript.parseStagedPath("STAGED '/tmp/x.sh'"))
            .contains("/tmp/x.sh");
        // No confirmation -> empty, never a throw.
        assertThat(BorgServerSetupScript.parseStagedPath("permission denied")).isEmpty();
        assertThat(BorgServerSetupScript.parseStagedPath(null)).isEmpty();
    }

    @Test
    void stageScript_roundTripsToTheOriginalScriptWhichIsValidBash() throws Exception {
        String script = BorgServerSetupScript.generate(server(), "geir");
        String cmd = BorgServerSetupScript.stageScript(script,
            "/home/geir/.vaier-backup/nas-borg-borg-setup.sh");

        // Decode exactly what the command would write to disk, and prove it is the original script...
        Matcher m = Pattern.compile("printf %s '([A-Za-z0-9+/=]+)' \\| base64 -d").matcher(cmd);
        assertThat(m.find()).isTrue();
        String decoded = new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(script);

        // ...and that the reconstructed file parses as valid bash (bash -n = syntax check, no execution).
        File f = File.createTempFile("staged", ".sh");
        try {
            Files.writeString(f.toPath(), decoded);
            Process p = new ProcessBuilder("bash", "-n", f.getAbsolutePath())
                .redirectErrorStream(true).start();
            assertThat(p.waitFor()).isEqualTo(0);
        } finally {
            f.delete();
        }
    }
}
