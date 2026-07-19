package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Vaier says it did when the operator asked it to check (#57 slice 3).
 *
 * <p>Two facts, and both are honest ones: whether the registries were actually asked this time, and whether
 * anything moved. A button that always reported "checked!" would be the same class of lie as the stale mark it
 * exists to clear.
 */
class UpdateCheckOutcomeTest {

    private static final Instant AT = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void aCheckThatChangedNothing_saysSo_butStillSaysItChecked() {
        // The commonest outcome by far, and the one the UI must not dress up. The operator pulled, Vaier
        // already agreed, and "nothing new" is a real answer rather than a failure.
        Map<String, UpdateAvailability> same = Map.of("redis:7.2", UpdateAvailability.UP_TO_DATE);

        UpdateCheckOutcome outcome = UpdateCheckOutcome.checked(same, same, AT);

        assertThat(outcome.checked()).isTrue();
        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.lastCheckedAt()).isEqualTo(AT);
    }

    @Test
    void aCheckThatClearedTheMark_isAChange() {
        // The story this slice was built for: the mark said update available, they pulled, they clicked.
        UpdateCheckOutcome outcome = UpdateCheckOutcome.checked(
            Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE),
            Map.of("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE), AT);

        assertThat(outcome.changed()).isTrue();
    }

    @Test
    void aVerdictAppearingOrVanishingIsAChangeToo() {
        // A container started or stopped between sweeps: the page is now wrong in a way a repaint fixes.
        assertThat(UpdateCheckOutcome.checked(
            Map.of(), Map.of("redis:7.2", UpdateAvailability.UP_TO_DATE), AT).changed()).isTrue();
        assertThat(UpdateCheckOutcome.checked(
            Map.of("redis:7.2", UpdateAvailability.UP_TO_DATE), Map.of(), AT).changed()).isTrue();
    }

    @Test
    void onlyAChangeIsWorthPushingToOpenExplorers() {
        // The push exists to repaint. Publishing when nothing moved would wake every open Explorer in the
        // fleet to redraw the identical page — and the clicking browser learns "nothing new" from its own
        // response, so it needs no event to be told.
        Map<String, UpdateAvailability> same = Map.of("redis:7.2", UpdateAvailability.UP_TO_DATE);

        assertThat(UpdateCheckOutcome.checked(same, same, AT).worthPublishing()).isFalse();
        assertThat(UpdateCheckOutcome.checked(
            same, Map.of("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE), AT).worthPublishing()).isTrue();
    }

    @Test
    void aCoalescedCheck_neverClaimsToHaveChecked() {
        // THE honesty rule of the floor. Vaier refused to ask the registries, so it does not get to say it
        // asked. It reports when it last really looked and lets the operator judge.
        UpdateCheckOutcome outcome = UpdateCheckOutcome.coalesced(AT);

        assertThat(outcome.checked()).isFalse();
        assertThat(outcome.lastCheckedAt()).isEqualTo(AT);
    }

    @Test
    void aCoalescedCheck_changedNothingAndPushesNothing() {
        // It did not look, so it cannot have found anything, and there is nothing to repaint.
        UpdateCheckOutcome outcome = UpdateCheckOutcome.coalesced(AT);

        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.worthPublishing()).isFalse();
    }
}
