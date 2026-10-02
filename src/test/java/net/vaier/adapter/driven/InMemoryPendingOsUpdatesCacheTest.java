package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.PendingOsUpdates;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPendingOsUpdatesCacheTest {

    private static final MachineId NAS = TestMachineIds.of("NAS");
    private static final MachineId ROON = TestMachineIds.of("Roon server");

    @Test
    void keepsOneReadingPerMachine_handsBackTheOneItReplaced_andForgetsMachinesThatLeft() {
        InMemoryPendingOsUpdatesCache cache = new InMemoryPendingOsUpdatesCache();

        assertThat(cache.record(new PendingOsUpdates(NAS, 2, 0))).isEmpty();
        assertThat(cache.record(new PendingOsUpdates(NAS, 3, 1))).contains(new PendingOsUpdates(NAS, 2, 0));
        cache.record(new PendingOsUpdates(ROON, 1, 0));
        cache.retainOnly(Set.of(NAS));

        assertThat(cache.getAll()).containsExactly(new PendingOsUpdates(NAS, 3, 1));
    }
}
