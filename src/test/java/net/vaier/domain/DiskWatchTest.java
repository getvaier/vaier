package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiskWatchTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    // --- the default is watched, and that is not an accident (#325) -------------------------------------
    //
    // The failure this fixes is silence about the disk that matters. A filesystem Vaier has never been told
    // about must nag, not hide: /volume1 appearing on the NAS for the first time is exactly the disk the
    // operator needs to hear about. Muting is a decision someone makes, never a default they inherit.

    @Test
    void aFilesystemNobodyHasConfigured_isWatched_atTheGlobalThreshold() {
        DiskWatch watch = DiskWatch.watchedByDefault(mid("NAS"), "/volume1");

        assertThat(watch.watched()).isTrue();
        assertThat(watch.thresholdPercent()).isNull();
        assertThat(watch.effectiveThreshold(85)).isEqualTo(85);
    }

    @Test
    void aFilesystemWithItsOwnThreshold_overridesTheGlobalOne() {
        DiskWatch watch = new DiskWatch(mid("NAS"), "/", true, 95);

        assertThat(watch.effectiveThreshold(85)).isEqualTo(95);
    }

    @Test
    void aThresholdOutsideOneToOneHundred_isRejected() {
        assertThatThrownBy(() -> new DiskWatch(mid("NAS"), "/", true, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiskWatch(mid("NAS"), "/", true, 101))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWatchWithoutAMachineOrAMountPoint_isRejected() {
        assertThatThrownBy(() -> new DiskWatch(null, "/", true, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiskWatch(mid("NAS"), " ", true, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- resolving a watch for a filesystem -------------------------------------------------------------

    @Test
    void watches_resolveAStoredWatch_byMachineAndMountPoint() {
        DiskWatches watches = new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95),
            new DiskWatch(mid("NAS"), "/volume1", false, null),
            new DiskWatch(mid("Apalveien 5"), "/", true, 70)));

        assertThat(watches.forFilesystem(mid("NAS"), "/").thresholdPercent()).isEqualTo(95);
        assertThat(watches.forFilesystem(mid("NAS"), "/volume1").watched()).isFalse();
        assertThat(watches.forFilesystem(mid("Apalveien 5"), "/").thresholdPercent()).isEqualTo(70);
    }

    @Test
    void watches_resolveAnUnknownFilesystem_toTheWatchedDefault() {
        DiskWatches watches = new DiskWatches(List.of(new DiskWatch(mid("NAS"), "/", true, 95)));

        DiskWatch unseen = watches.forFilesystem(mid("NAS"), "/volume1");
        assertThat(unseen.watched()).isTrue();
        assertThat(unseen.effectiveThreshold(85)).isEqualTo(85);
    }

    @Test
    void watches_doNotLeakAcrossMachines() {
        // "/" is 88% by design on the NAS and an emergency on Apalveien 5. One mount point, two machines,
        // two verdicts — a watch keyed on the mount alone would have muted both.
        DiskWatches watches = new DiskWatches(List.of(new DiskWatch(mid("NAS"), "/", false, null)));

        assertThat(watches.forFilesystem(mid("NAS"), "/").watched()).isFalse();
        assertThat(watches.forFilesystem(mid("Apalveien 5"), "/").watched()).isTrue();
    }

    // --- a watch's identity, and the default, both belong to the domain ---------------------------------

    @Test
    void aWatch_knowsWhichFilesystemItIsFor() {
        // The file adapter replaces a watch by "same machine AND same mount point". That identity is a domain
        // concept, not a line of adapter code — asked in two places it can drift in two directions.
        DiskWatch watch = new DiskWatch(mid("NAS"), "/volume1", true, 90);

        assertThat(watch.isFor(mid("NAS"), "/volume1")).isTrue();
        assertThat(watch.isFor(mid("NAS"), "/")).isFalse();
        assertThat(watch.isFor(mid("Apalveien 5"), "/volume1")).isFalse();
    }

    @Test
    void aWatchWithNoWatchedFlagAtAll_isWatched() {
        // A hand-edited or truncated disk-watches.yml must not be able to silently unwatch a disk. The policy
        // "absent means watched" is the domain's, so the file adapter cannot quietly hold a different one.
        assertThat(DiskWatch.of(mid("NAS"), "/volume1", null, null).watched()).isTrue();
        assertThat(DiskWatch.of(mid("NAS"), "/volume1", false, null).watched()).isFalse();
        assertThat(DiskWatch.of(mid("NAS"), "/volume1", true, 90).thresholdPercent()).isEqualTo(90);
    }

    @Test
    void watches_ofNothing_watchEverythingByDefault() {
        assertThat(new DiskWatches(null).forFilesystem(mid("NAS"), "/volume1").watched()).isTrue();
        assertThat(new DiskWatches(List.of()).forFilesystem(mid("NAS"), "/volume1").watched()).isTrue();
    }
}
