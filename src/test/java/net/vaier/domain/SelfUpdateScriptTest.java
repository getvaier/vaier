package net.vaier.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script Vaier runs on its own host to replace itself.
 *
 * <p>Everything here exists because of one fact: the process asking for the update dies in the middle of it.
 * A container cannot recreate itself — the moment {@code docker compose up -d} replaces it, whatever was
 * driving the update is gone. So the work is handed to the host, detached, and it has to be able to finish,
 * judge itself and undo itself with nobody watching.
 *
 * <p>Which makes the rollback the feature, not a nicety. If a bad image comes up, the thing that is down is
 * the thing an operator would use to fix it. The script therefore records what was running before it touched
 * anything, and puts it back if the new one does not answer.
 */
class SelfUpdateScriptTest {

    private static final String DIR = "/home/ubuntu/vaier";

    @Test
    void itRecordsWhatWasRunning_beforeItChangesAnything() {
        // Rollback is only possible if the previous image was pinned by digest first. A tag is not enough:
        // `getvaier/vaier:latest` means something different after the pull, so rolling "back" to it would
        // roll forward to the broken image again.
        String script = SelfUpdateScript.generate(DIR, "vaier", "run-1", 90);

        int record = script.indexOf("PREVIOUS_IMAGE=");
        int pull = script.indexOf("compose pull");
        assertThat(record).as("the running image is captured").isPositive();
        assertThat(record).as("and captured before the pull").isLessThan(pull);
        assertThat(script).as("by digest, since the tag will move under us").contains(".RepoDigests");
    }

    @Test
    void itHealthChecksItself_andRollsBackWhenTheNewImageDoesNotAnswer() {
        String script = SelfUpdateScript.generate(DIR, "vaier", "run-1", 90);

        // Not a TCP probe: the endpoint that answers is the one that reports the version, so "it came up" and
        // "it is the build we asked for" are the same check.
        assertThat(script).as("it waits for the new container to answer").contains("/settings/version");
        assertThat(script).as("bounded — a hang must not wait forever").contains("90");
        assertThat(script).as("and puts the old image back when it does not")
            .contains("$PREVIOUS_IMAGE");
        assertThat(script).contains("ROLLED_BACK");
    }

    @Test
    void itLeavesItsOwnAccountOnTheHost_becauseNobodyIsListeningWhenItFinishes() {
        // Vaier is restarting while this runs, so there is no in-memory state to settle against and no open
        // connection to report to: the result has to outlive both processes. Vaier reads this file when it
        // comes back up.
        String script = SelfUpdateScript.generate(DIR, "vaier", "run-1", 90);

        assertThat(script).contains(SelfUpdateScript.RESULT_FILE);
        assertThat(script).as("the outcome is written for every path out").contains("UPGRADED");
        assertThat(script).contains("FAILED");
    }

    @Test
    void itRunsDetached_soItSurvivesTheContainerItIsReplacing() {
        // Launched over SSH from inside the container being replaced. Without detaching, killing the
        // container kills the update halfway — the worst possible moment.
        String launch = SelfUpdateScript.launch(DIR, "run-1");

        assertThat(launch).contains("nohup");
        assertThat(launch).contains("setsid");
        assertThat(launch).endsWith("&");
    }

    @Test
    void theComposeProjectDirectoryIsQuoted() {
        // The directory is operator-configurable and lands in a shell command. Vaier quotes every path it
        // hands to a shell (see BorgCommand); this is no different.
        assertThat(SelfUpdateScript.generate("/home/my server/vaier", "vaier", "run-1", 90))
            .contains("'/home/my server/vaier'");
    }

    @Test
    void itBringsTheWholeStackToTheCommitTheNewImageWasBuiltFrom(@TempDir Path tmp) throws Exception {
        // #343: an image-only update left a changed compose file, init container or sign-in template behind.
        // The runtime files come from the commit the pulled image carries, not from main, or the two disagree.
        record Row(String why, String revision, String compose, String detail, boolean synced) {}
        for (Row row : List.of(
            new Row("a release image names its commit", "abc123", "new", "stack@abc123", true),
            // A locally built image carries no commit: nothing to match the files to, so they stay put.
            new Row("a local build names none", "", "old", "stack-not-synced", false))) {
            Host host = Host.at(tmp.resolve(row.synced() ? "synced" : "local"), row.revision(), true, true);

            String result = host.run();
            String calls = host.callLog();

            assertThat(host.file("docker-compose.yml")).as(row.why()).isEqualTo(row.compose());
            assertThat(result).as(row.why()).contains("run-1 UPGRADED").contains(row.detail());
            assertThat(calls).as(row.why()).contains("compose up -d\n")
                .contains("compose up -d --force-recreate --no-deps wireguard-masquerade")
                .doesNotContain("--remove-orphans");
            if (row.synced()) {
                assertThat(calls).contains("raw.githubusercontent.com/getvaier/vaier/abc123/install.sh")
                    .contains("installer VAIER_REF=abc123");
                assertThat(calls.indexOf("compose pull vaier")).isLessThan(calls.indexOf("installer"));
                assertThat(calls.indexOf("installer")).isLessThan(calls.indexOf("compose up -d\n"));
            } else {
                assertThat(calls).as(row.why()).doesNotContain("install.sh");
            }
        }
    }

    @Test
    void aFailedUpdatePutsTheOldStackBack_filesAndImageTogether(@TempDir Path tmp) throws Exception {
        // Once the compose file can change, the rollback target is the image AND the files it ran with.
        record Row(String why, boolean installerWorks, boolean answers, String outcome) {}
        for (Row row : List.of(
            new Row("the new Vaier never answers", true, false, "ROLLED_BACK"),
            // A half-extracted tarball must not be left for the next `up` to find.
            new Row("the sync itself fails", false, true, "FAILED"))) {
            Host host = Host.at(tmp.resolve(row.outcome()), "abc123", row.installerWorks(), row.answers());

            String result = host.run();

            assertThat(host.file("docker-compose.yml")).as(row.why()).isEqualTo("old");
            assertThat(host.file("offline/default.conf")).as(row.why()).isEqualTo("old");
            assertThat(host.exists("offline/added-by-release")).as(row.why()).isFalse();
            assertThat(result).as(row.why()).contains("run-1 " + row.outcome());
            assertThat(host.callLog()).as(row.why())
                .contains("tag getvaier/vaier@sha256:old getvaier/vaier:latest");
        }
    }

    @Test
    void itNeverNamesOperatorState() {
        // .env and every runtime dir are the operator's, exactly as install.sh leaves them alone.
        String script = SelfUpdateScript.generate(DIR, "vaier", "run-1", 90);
        for (String state : List.of(".env", "vaier/config", "wireguard/config", "traefik/config", "traefik/acme",
                "dex/config", "oauth2/config", "crowdsec/config", "crowdsec/data", "traefik/logs", "geoip")) {
            assertThat(script).as(state).doesNotContain(state);
        }
    }

    @Test
    void theCommitItReadsIsTheOneTheReleaseBuildStamps() throws IOException {
        // Renamed on one side only, every update would quietly fall back to image-only.
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String workflow = Files.readString(Path.of(".github/workflows/build-deploy.yml"));

        assertThat(dockerfile).contains("LABEL " + SelfUpdateScript.REVISION_LABEL + "=\"${VAIER_REVISION}\"");
        assertThat(workflow).contains("--build-arg VAIER_REVISION=${{ github.sha }}");
    }

    /** A host with fake docker and curl, and a compose project whose runtime files are all "old". */
    private record Host(Path dir, Path home, Path bin, Path calls) {

        static Host at(Path root, String revision, boolean installerWorks, boolean answers) throws IOException {
            Path dir = Files.createDirectories(root.resolve("vaier"));
            Path home = Files.createDirectories(root.resolve("home"));
            Path bin = Files.createDirectories(root.resolve("bin"));
            Files.writeString(dir.resolve("docker-compose.yml"), "old");
            Files.createDirectories(dir.resolve("offline"));
            Files.writeString(dir.resolve("offline/default.conf"), "old");

            executable(bin.resolve("docker"), """
                #!/bin/bash
                echo "$*" >> "$CALLS"
                case "$*" in
                  "compose ps -q"*) echo cid1 ;;
                  "inspect --format {{index .RepoDigests 0}}"*) echo getvaier/vaier@sha256:old ;;
                  "inspect --format {{.Config.Image}}"*) echo getvaier/vaier:latest ;;
                  "image inspect"*) echo "%s" ;;
                  "inspect -f"*) echo 172.20.0.9 ;;
                esac
                exit 0
                """.formatted(revision));
            executable(root.resolve("installer"), """
                #!/bin/bash
                echo "installer VAIER_REF=$VAIER_REF" >> "$CALLS"
                echo new > docker-compose.yml
                echo new > offline/default.conf
                echo new > offline/added-by-release
                exit %d
                """.formatted(installerWorks ? 0 : 1));
            executable(bin.resolve("curl"), """
                #!/bin/bash
                echo "curl $*" >> "$CALLS"
                case "$*" in
                  *install.sh*) while [ $# -gt 0 ]; do [ "$1" = -o ] && cp "%s" "$2"; shift; done; exit 0 ;;
                  *settings/version*) exit %d ;;
                esac
                exit 0
                """.formatted(root.resolve("installer"), answers ? 0 : 7));
            return new Host(dir, home, bin, root.resolve("calls"));
        }

        String run() throws Exception {
            Path script = home.resolve("update.sh");
            Files.writeString(script, SelfUpdateScript.generate(dir.toString(), "vaier", "run-1", 1));
            assertThat(new ProcessBuilder("bash", "-n", script.toString()).start().waitFor())
                .as("the script parses").isZero();
            ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true)
                .redirectOutput(home.resolve("out").toFile());
            pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            pb.environment().put("HOME", home.toString());
            pb.environment().put("CALLS", calls.toString());
            assertThat(pb.start().waitFor(30, TimeUnit.SECONDS)).as("the script finishes").isTrue();
            return Files.readString(home.resolve(".vaier-upgrade/last-upgrade"));
        }

        String callLog() throws IOException {
            return Files.readString(calls);
        }

        String file(String path) throws IOException {
            return Files.readString(dir.resolve(path)).strip();
        }

        boolean exists(String path) {
            return Files.exists(dir.resolve(path));
        }

        private static void executable(Path path, String content) throws IOException {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            path.toFile().setExecutable(true);
        }
    }
}
