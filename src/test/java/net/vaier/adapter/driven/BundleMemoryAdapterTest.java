package net.vaier.adapter.driven;

import net.vaier.domain.Bundle;
import net.vaier.domain.MachineId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bundles are ephemeral: held in memory for their hour, found as often as the link is clicked. */
class BundleMemoryAdapterTest {

    private static final MachineId NAS = MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862");
    private static final long NOW = 1_700_000_000_000L;

    private final BundleMemoryAdapter adapter = new BundleMemoryAdapter();

    @Test
    void aHeldBundleIsFoundAgainAndAgain() {
        Bundle bundle = Bundle.offer(NAS, "NAS", List.of("/a"), "x", NOW);
        adapter.hold(bundle);

        assertThat(adapter.find(bundle.id())).contains(bundle);
        assertThat(adapter.find(bundle.id())).contains(bundle);
        assertThat(adapter.find("no-such")).isEmpty();
    }

    @Test
    void holdingANewBundleSweepsOutTheExpiredOnes() {
        Bundle old = Bundle.offer(NAS, "NAS", List.of("/a"), "x", NOW - Bundle.TTL.toMillis() - 1);
        adapter.hold(old);
        adapter.hold(Bundle.offer(NAS, "NAS", List.of("/b"), "y", NOW), NOW);

        assertThat(adapter.find(old.id())).isEmpty();
    }
}
