package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trust-on-first-use as a decision the target itself makes. Both the web terminal and the Explorer
 * connect to machines, and both must pin an unpinned host by exactly the same rule — so the rule lives
 * here, once, rather than as a hand-rolled predicate in each service.
 */
class SshTargetTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    private static SshTarget target(String pinnedFingerprint) {
        return SshTarget.on("10.13.13.6",
            new HostCredential(mid("nuc"), "root", AuthMethod.PASSWORD, "pw", null, false), pinnedFingerprint);
    }

    @Test
    void anUnpinnedTarget_needsPinning_whenTheHostPresentedAKey() {
        assertThat(target(null).needsPinning("SHA256:fresh")).isTrue();
    }

    @Test
    void anAlreadyPinnedTarget_isNeverRepinned() {
        assertThat(target("SHA256:pinned").needsPinning("SHA256:pinned")).isFalse();
    }

    @Test
    void aMismatchIsNotAPinning_itIsARefusal() {
        // The adapter refuses a changed key outright; a mismatch must never quietly re-pin over the old one.
        assertThat(target("SHA256:pinned").needsPinning("SHA256:different")).isFalse();
    }

    @Test
    void aHostThatPresentedNoKey_pinsNothing() {
        assertThat(target(null).needsPinning(null)).isFalse();
    }
}
