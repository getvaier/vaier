package net.vaier.domain;

import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Bundle</b> (#360): files on one machine that Ask offers as a download card. Nothing is copied or
 * written anywhere — the zip is built while it downloads — so the whole of what Vaier holds is the list of
 * paths, what they add up to, and an hour.
 */
class BundleTest {

    private static final MachineId NAS = MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862");
    private static final long NOW = 1_700_000_000_000L;

    private static Bundle pictures() {
        return Bundle.offer(NAS, "NAS", List.of("/volume1/photo/2025/09/10/a.jpg", "/volume1/photo/2025/09/10/b.jpg"),
            "pictures 2025-09-10", NOW);
    }

    @Test
    void aBundleCarriesItsPaths_itsZipName_andWhenItWasOffered() {
        Bundle bundle = pictures();

        assertThat(bundle.id()).isNotBlank();
        assertThat(bundle.machineId()).isEqualTo(NAS);
        assertThat(bundle.paths()).containsExactly("/volume1/photo/2025/09/10/a.jpg", "/volume1/photo/2025/09/10/b.jpg");
        assertThat(bundle.name()).isEqualTo("pictures-2025-09-10.zip");
        assertThat(bundle.offeredAtEpochMs()).isEqualTo(NOW);
    }

    /** The zip's name is the operator's to read in a downloads folder, so it is plain and always a .zip. */
    @Test
    void theNameIsMadeSafe_andDefaultsToTheMachine() {
        assertThat(Bundle.offer(NAS, "NAS", List.of("/a"), "  Family photos/2025 (Sept) ", NOW).name())
            .isEqualTo("Family-photos-2025-Sept.zip");
        assertThat(Bundle.offer(NAS, "NAS", List.of("/a"), "already.zip", NOW).name()).isEqualTo("already.zip");
        assertThat(Bundle.offer(NAS, "NAS", List.of("/a"), null, NOW).name()).isEqualTo("NAS-files.zip");
        assertThat(Bundle.offer(NAS, "Roon kjøkken", List.of("/a"), "", NOW).name()).isEqualTo("Roon-kj-kken-files.zip");
    }

    @Test
    void pathsMustBeAbsolute_neverClimb_andAreNotRepeated() {
        assertThatThrownBy(() -> Bundle.offer(NAS, "NAS", List.of(), "x", NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Say which files.");
        assertThatThrownBy(() -> Bundle.offer(NAS, "NAS", List.of("photo/a.jpg"), "x", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Every path must be absolute, as run_on_machine printed it: photo/a.jpg is not.");
        assertThatThrownBy(() -> Bundle.offer(NAS, "NAS", List.of("/volume1/../etc/shadow"), "x", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/volume1/../etc/shadow");
        assertThat(Bundle.offer(NAS, "NAS", List.of(" /a ", "/a", "/b"), "x", NOW).paths()).containsExactly("/a", "/b");
    }

    /** The model hands paths one per line; blank lines and stray spaces are nothing. */
    @Test
    void pathsComeOnePerLine() {
        assertThat(Bundle.pathsOf(" /a\n\n/b \n")).containsExactly("/a", "/b");
        assertThat(Bundle.pathsOf(null)).isEmpty();
    }

    /** What the card says: files and bytes, and "at least" when a directory's tree was not walked. */
    @Test
    void sizingCountsFilesAndBytes_andSaysAtLeastWhenADirectoryIsInside() {
        Bundle files = pictures().sized(List.of(new RemoteStat(false, 1_500_000), new RemoteStat(false, 2_600_000)));
        assertThat(files.fileCount()).isEqualTo(2);
        assertThat(files.totalBytes()).isEqualTo(4_100_000);
        assertThat(files.describe()).isEqualTo("2 files, 4.1 MB");

        Bundle withDirectory = pictures().sized(List.of(new RemoteStat(false, 800), new RemoteStat(true, 4096)));
        assertThat(withDirectory.describe()).isEqualTo("2 entries, one of them a whole directory, at least 800 B");

        assertThat(Bundle.offer(NAS, "NAS", List.of("/a"), "x", NOW).sized(List.of(new RemoteStat(false, 3_000_000_000L)))
            .describe()).isEqualTo("1 file, 3.0 GB");
    }

    @Test
    void aBundleLivesAnHour() {
        Bundle bundle = pictures();

        assertThat(bundle.expired(NOW + Bundle.TTL.toMillis() - 1)).isFalse();
        assertThat(bundle.expired(NOW + Bundle.TTL.toMillis())).isTrue();
        assertThat(bundle.requireLive(NOW)).isSameAs(bundle);
        assertThatThrownBy(() -> bundle.requireLive(NOW + Bundle.TTL.toMillis()))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("That download has expired; ask again.");
    }

    /** The download is the Explorer's own selection zip, so a bundle is a selection of coordinates. */
    @Test
    void itIsASelectionOfCoordinatesInThePresent() {
        assertThat(pictures().coordinates()).containsExactly(
            new Selection.Coordinate(NAS, "NAS", "/volume1/photo/2025/09/10/a.jpg", null),
            new Selection.Coordinate(NAS, "NAS", "/volume1/photo/2025/09/10/b.jpg", null));
    }

    @Test
    void theToolResultSaysItIsOffered_andThatNothingWasWritten() {
        Bundle bundle = pictures().sized(List.of(new RemoteStat(false, 1_000_000), new RemoteStat(false, 1_000_000)));

        assertThat(bundle.toolResult())
            .contains("pictures-2025-09-10.zip").contains("2 files, 2.0 MB")
            .contains("download card").contains("lives for an hour").contains("Nothing was copied or written");
    }
}
