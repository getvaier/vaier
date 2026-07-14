package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SFTP root (#326): where a machine's SFTP subsystem thinks the filesystem begins, and therefore the
 * one rule that turns a jail-relative path into the machine's real coordinate and back.
 *
 * <p>This is a decision, not a translation — it is what makes the Explorer's paths comparable with borg's
 * source paths, with {@code df} and with the operator's own terminal — so it lives in the domain, and the
 * only inputs it needs are the two answers the two channels give to the same question: <em>where is the SSH
 * user's home?</em>
 */
class SftpRootTest {

    private static final Instant WHEN = Instant.parse("2026-07-14T10:15:30Z");

    // --- resolving -------------------------------------------------------------------------------------

    @Test
    void anOrdinaryHost_whereBothChannelsAgree_isNotJailed() {
        // The regression that matters most: on a normal Linux host nothing about today's behaviour changes.
        SftpRoot root = SftpRoot.resolve("/home/geir", "/home/geir").orElseThrow();

        assertThat(root.jailed()).isFalse();
        assertThat(root.path()).isEqualTo("/");
        assertThat(root.toJailPath("/home/geir/docs")).isEqualTo("/home/geir/docs");
        assertThat(root.toTruePath("/home/geir/docs")).isEqualTo("/home/geir/docs");
    }

    @Test
    void aChrootedSftpSubsystem_rootsAtTheDifferenceBetweenTheTwoHomes() {
        // The NAS: DSM chroots the SFTP subsystem to /volume1 but not the exec channel, so the same home is
        // /volume1/homes/geir over exec and /homes/geir over SFTP. The difference IS the jail.
        SftpRoot root = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThat(root.jailed()).isTrue();
        assertThat(root.path()).isEqualTo("/volume1");
    }

    @Test
    void homesThatDoNotLineUp_refuseToResolve_ratherThanGuessAPrefix() {
        // A wrong prefix would corrupt every path in the tree, so a home the SFTP channel does not end the
        // exec one with is not an invitation to invent a mapping.
        assertThat(SftpRoot.resolve("/home/geir", "/srv/data")).isEmpty();
        assertThat(SftpRoot.resolve("/volume1/homes/geir", "/homes/kari")).isEmpty();
    }

    @Test
    void aHomeThatIsNotAnAbsolutePath_refusesToResolve() {
        assertThat(SftpRoot.resolve("home/geir", "/home/geir")).isEmpty();
        assertThat(SftpRoot.resolve("/home/geir", "geir")).isEmpty();
        assertThat(SftpRoot.resolve(null, "/home/geir")).isEmpty();
        assertThat(SftpRoot.resolve("/home/geir", "  ")).isEmpty();
    }

    @Test
    void theHomesAreNormalisedBeforeTheyAreCompared() {
        Optional<SftpRoot> root = SftpRoot.resolve("/volume1/homes/geir/", "/homes/./geir");

        assertThat(root).isPresent();
        assertThat(root.get().path()).isEqualTo("/volume1");
    }

    // --- mapping ---------------------------------------------------------------------------------------

    @Test
    void aTruePath_mapsToTheJailRelativePath_theSftpChannelSpeaks() {
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThat(nas.toJailPath("/volume1")).isEqualTo("/");
        assertThat(nas.toJailPath("/volume1/homes")).isEqualTo("/homes");
        assertThat(nas.toJailPath("/volume1/homes/geir")).isEqualTo("/homes/geir");
    }

    @Test
    void aJailRelativePath_mapsBackToTheMachinesTrueCoordinate() {
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThat(nas.toTruePath("/")).isEqualTo("/volume1");
        assertThat(nas.toTruePath("/homes")).isEqualTo("/volume1/homes");
        assertThat(nas.toTruePath("/homes/geir")).isEqualTo("/volume1/homes/geir");
    }

    @Test
    void aPathOutsideTheJail_isRefusedWithASentenceTheOperatorCanAct_on() {
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThatThrownBy(() -> nas.toJailPath("/volume2"))
            .isInstanceOf(PathOutsideSftpRootException.class)
            .isInstanceOf(IllegalArgumentException.class)   // an argument this machine cannot serve — a 400
            .hasMessageContaining("/volume2")
            .hasMessageContaining("not reachable over SFTP")
            .hasMessageContaining("rooted at /volume1");
    }

    @Test
    void theRootsOwnParent_isOutsideTheJail_neverASilentSlash() {
        // "/" on the NAS is not the SFTP root, it is above it. Answering it with the jail's contents would be
        // exactly the lie this whole change exists to stop.
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThatThrownBy(() -> nas.toJailPath("/"))
            .isInstanceOf(PathOutsideSftpRootException.class);
    }

    @Test
    void aSiblingSharingThePrefixesLetters_isOutsideTheJail() {
        // /volume10 starts with the characters of /volume1 but is a different mount. A prefix match that is
        // not a path-segment match would silently browse the wrong filesystem.
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        assertThatThrownBy(() -> nas.toJailPath("/volume10/photos"))
            .isInstanceOf(PathOutsideSftpRootException.class);
    }

    @Test
    void anUnjailedRoot_holdsEveryPath() {
        SftpRoot none = SftpRoot.resolve("/home/geir", "/home/geir").orElseThrow();

        assertThat(none.toJailPath("/")).isEqualTo("/");
        assertThat(none.toJailPath("/etc/hosts")).isEqualTo("/etc/hosts");
    }

    // --- anchoring the entries --------------------------------------------------------------------------

    @Test
    void anEntryReadOverSftp_isAnchoredToItsTrueCoordinate() {
        SftpRoot nas = SftpRoot.resolve("/volume1/homes/geir", "/homes/geir").orElseThrow();

        List<FileEntry> anchored = nas.anchor(List.of(
            FileEntry.in("/homes", "geir", true, 4096, WHEN),
            FileEntry.in("/homes", "notes.txt", false, 120, WHEN)));

        assertThat(anchored).extracting(FileEntry::path)
            .containsExactly("/volume1/homes/geir", "/volume1/homes/notes.txt");
        assertThat(anchored).extracting(FileEntry::name).containsExactly("geir", "notes.txt");
        assertThat(anchored.getFirst().directory()).isTrue();
        assertThat(anchored.getLast().sizeBytes()).isEqualTo(120);
        assertThat(anchored.getLast().modified()).isEqualTo(WHEN);
    }

    @Test
    void onAnUnjailedMachine_anchoringChangesNothing() {
        SftpRoot none = SftpRoot.resolve("/home/geir", "/home/geir").orElseThrow();
        List<FileEntry> entries = List.of(FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN));

        assertThat(none.anchor(entries)).isEqualTo(entries);
    }

    @Test
    void theRootOfAMachineVaierCouldNotProbe_isTheFilesystemRoot_andIsNotJailed() {
        assertThat(SftpRoot.NONE.jailed()).isFalse();
        assertThat(SftpRoot.NONE.path()).isEqualTo("/");
        assertThat(SftpRoot.NONE.toJailPath("/etc")).isEqualTo("/etc");
    }

    // --- locating the home inside a jail that will not say where it is ---------------------------------
    //
    // The NAS does not answer the question the suffix match needs. Its SFTP subsystem canonicalises "." to
    // "/" — the jail root itself, which says nothing about where that root is on the machine. So the home
    // has to be *found* inside the jail instead: the SSH user's home is the one directory both channels
    // genuinely share, and these are the names the jailed half might know it by.

    @Test
    void jailCandidates_areTheTrueHomeAndEachShorterTailOfIt_longestFirst() {
        // /volume1/homes/geir on the machine is /homes/geir inside a jail rooted at /volume1 — so ask for
        // the longest name first, and the first one the jail can see is the least jail that explains it.
        assertThat(SftpRoot.jailCandidates("/volume1/homes/geir"))
            .containsExactly("/volume1/homes/geir", "/homes/geir", "/geir");
    }

    @Test
    void jailCandidates_beginWithTheTrueHomeItself_soAnUnjailedMachineCanNeverBeMadeToLookJailed() {
        // The safety property of the whole search. On a machine with no jail the very first candidate is the
        // home itself, it is visible, and it resolves to NONE — no shorter tail is ever tried, so no ordinary
        // machine can be given a jail it does not have.
        assertThat(SftpRoot.jailCandidates("/home/geir")).first().isEqualTo("/home/geir");
        assertThat(SftpRoot.resolve("/home/geir", "/home/geir").orElseThrow()).isEqualTo(SftpRoot.NONE);
    }

    @Test
    void jailCandidates_neverOfferTheRoot_becauseEveryMachineWouldMatchIt() {
        // "/" exists on every machine, jailed or not. Offering it would let any machine match at the last
        // resort and be handed a jail equal to its whole home — the exact wrong guess.
        assertThat(SftpRoot.jailCandidates("/home/geir")).containsExactly("/home/geir", "/geir");
        assertThat(SftpRoot.jailCandidates("/geir")).containsExactly("/geir");
    }
}
