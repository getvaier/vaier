package net.vaier.adapter.driven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePeerConfigRetrievalTrackerTest {

    @TempDir Path configDir;

    FilePeerConfigRetrievalTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new FilePeerConfigRetrievalTracker();
        ReflectionTestUtils.setField(tracker, "wireguardConfigPath", configDir.toString());
    }

    @Test
    void markViewedIfNotAlready_firstCall_returnsTrueAndCreatesMarker() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));

        boolean firstView = tracker.markViewedIfNotAlready("laptop");

        assertThat(firstView).isTrue();
        assertThat(Files.exists(configDir.resolve("laptop").resolve("laptop.conf.viewed"))).isTrue();
    }

    @Test
    void markViewedIfNotAlready_secondCall_returnsFalse() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));

        tracker.markViewedIfNotAlready("laptop");
        boolean secondView = tracker.markViewedIfNotAlready("laptop");

        assertThat(secondView).isFalse();
    }

    @Test
    void markViewedIfNotAlready_unknownPeer_throws() {
        assertThatThrownBy(() -> tracker.markViewedIfNotAlready("ghost"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    void isAlreadyViewed_returnsFalseBeforeMarking() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));

        assertThat(tracker.isAlreadyViewed("laptop")).isFalse();
    }

    @Test
    void isAlreadyViewed_returnsTrueAfterMarking() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));
        tracker.markViewedIfNotAlready("laptop");

        assertThat(tracker.isAlreadyViewed("laptop")).isTrue();
    }

    @Test
    void isAlreadyViewed_unknownPeer_returnsFalse() {
        assertThat(tracker.isAlreadyViewed("ghost")).isFalse();
    }

    @Test
    void markViewedIfNotAlready_markerSurvivesAcrossNewTrackerInstance() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));
        tracker.markViewedIfNotAlready("laptop");

        FilePeerConfigRetrievalTracker fresh = new FilePeerConfigRetrievalTracker();
        ReflectionTestUtils.setField(fresh, "wireguardConfigPath", configDir.toString());

        assertThat(fresh.isAlreadyViewed("laptop")).isTrue();
        assertThat(fresh.markViewedIfNotAlready("laptop")).isFalse();
    }

    @Test
    void markerLivesInsidePeerDirectory_soPeerDeletionAlsoCleansIt() throws IOException {
        Path peerDir = configDir.resolve("laptop");
        Files.createDirectories(peerDir);

        tracker.markViewedIfNotAlready("laptop");

        Path marker = peerDir.resolve("laptop.conf.viewed");
        assertThat(marker).exists();
        // Sibling of the .conf file — the existing delete flow already removes the peer dir.
        assertThat(marker.getParent()).isEqualTo(peerDir);
    }

    // --- resetViewed (#247): a Reissue re-opens the one-shot budget ---

    @Test
    void resetViewed_deletesMarkerAndReEnablesAFreshView() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));
        tracker.markViewedIfNotAlready("laptop");
        assertThat(tracker.isAlreadyViewed("laptop")).isTrue();

        tracker.resetViewed("laptop");

        assertThat(tracker.isAlreadyViewed("laptop")).isFalse();
        assertThat(tracker.markViewedIfNotAlready("laptop")).isTrue();
    }

    @Test
    void resetViewed_isANoOpWhenNotYetViewed() throws IOException {
        Files.createDirectories(configDir.resolve("laptop"));

        tracker.resetViewed("laptop");

        assertThat(tracker.isAlreadyViewed("laptop")).isFalse();
    }
}
