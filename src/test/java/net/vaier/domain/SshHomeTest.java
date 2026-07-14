package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSH user's home on a machine, as the <em>exec</em> channel reports it — the one primitive two
 * different parts of Vaier need to reach a host with (#326).
 *
 * <p>The backup work dir has probed for it since the backups shipped; the Explorer now needs the very same
 * answer to work out where a machine's SFTP subsystem is rooted. Two probes would be two ways to ask a
 * machine the same question, and this project has been bitten before by code paths disagreeing about a
 * machine — so the probe and the rule for reading its answer live here, once.
 */
class SshHomeTest {

    private static CommandResult result(int exitCode, String stdout, boolean timedOut) {
        return new CommandResult(exitCode, stdout, "", timedOut, "SHA256:x");
    }

    @Test
    void theProbePrintsHomeWithNoTrailingNewline_soTheResolvedPathIsExact() {
        assertThat(SshHome.PROBE_COMMAND).isEqualTo("printf %s \"$HOME\"");
    }

    @Test
    void readsTheAbsoluteHomeOutOfASuccessfulProbe() {
        assertThat(SshHome.in(result(0, "/home/geir", false))).contains("/home/geir");
    }

    @Test
    void trimsWhatTheShellPaddedTheAnswerWith() {
        assertThat(SshHome.in(result(0, "  /volume1/homes/geir\n", false))).contains("/volume1/homes/geir");
    }

    @Test
    void aProbeThatFailed_answersWithNoHomeAtAll_ratherThanAGuess() {
        assertThat(SshHome.in(result(1, "/home/geir", false))).isEmpty();
        assertThat(SshHome.in(result(0, "/home/geir", true))).isEmpty();
        assertThat(SshHome.in(result(0, null, false))).isEmpty();
        assertThat(SshHome.in(result(0, "  ", false))).isEmpty();
        assertThat(SshHome.in(null)).isEmpty();
    }

    @Test
    void aHomeThatIsNotAnAbsolutePath_isNoHome() {
        // A shell that answered with "$HOME" literally, or with a relative path, has told us nothing usable.
        assertThat(SshHome.in(result(0, "$HOME", false))).isEmpty();
        assertThat(SshHome.in(result(0, "home/geir", false))).isEmpty();
    }

    // --- the same home, named physically ---------------------------------------------------------------

    @Test
    void thePhysicalProbe_asksForTheHomeWithItsSymlinksResolved() {
        // $HOME on the NAS is /var/services/homes/geir — a DSM symlink onto /volume1/homes/geir. A jail is a
        // *physical* subtree of the filesystem, so a symlinked home can never line up with one: the Explorer
        // must ask for the home the machine really keeps, not the name it advertises.
        assertThat(SshHome.PHYSICAL_PROBE_COMMAND).isEqualTo("cd \"$HOME\" && pwd -P");
    }

    @Test
    void aPhysicalProbeThatCannotEnterTheHome_answersNothing_ratherThanGuessing() {
        // `cd` failing is a non-zero exit, and an unusable answer is no answer — the machine's paths are then
        // left exactly as they are.
        assertThat(SshHome.in(new CommandResult(1, "", "cd: no such file", false, "SHA256:x"))).isEmpty();
    }
}
