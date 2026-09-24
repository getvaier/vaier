package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BreachAttemptRollupTest {

    private static final BlockDecision DECISION_1 = BlockDecision.builder()
        .id(1L).scenario("crowdsecurity/http-probing").sourceIp("1.2.3.4").type("ban").duration("3h59m48s")
        .build();
    private static final BlockDecision DECISION_2 = BlockDecision.builder()
        .id(2L).scenario("crowdsecurity/http-crawl-non_statics").sourceIp("5.6.7.8").type("ban")
        .duration("4h0m0s").build();
    private static final BlockDecision ENRICHED_DECISION = BlockDecision.builder()
        .id(3L).scenario("crowdsecurity/http-probing").sourceIp("195.178.110.155").type("ban")
        .duration("3h0m40s").country("BG").asnOrg("Techoff Srv Limited")
        .build();

    private static final BlockDecision CREDENTIAL_ATTACK = BlockDecision.builder()
        .id(4L).scenario("crowdsecurity/ssh-bf").sourceIp("9.9.9.9").type("ban").duration("4h0m0s")
        .build();

    private static final TrustedNetworks TRUSTED =
        TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of("192.168.3.0/24"));

    // --- what earns a place in the rollup (the narrowing) -------------------------------------------

    /**
     * The operator's real day-one traffic: blind HTTP scanning, over and over. CrowdSec banning it is
     * CrowdSec working, and none of it reaches the inbox.
     */
    @Test
    void from_leavesOutBlindScanningEntirely() {
        BreachAttemptRollup rollup = BreachAttemptRollup.from(
            List.of(DECISION_1, DECISION_2, ENRICHED_DECISION), TRUSTED);

        assertThat(rollup.decisions()).isEmpty();
        assertThat(rollup.worthSending()).isFalse();
    }

    @Test
    void from_keepsACredentialAttack() {
        BreachAttemptRollup rollup = BreachAttemptRollup.from(
            List.of(DECISION_1, CREDENTIAL_ATTACK), TRUSTED);

        assertThat(rollup.decisions()).containsExactly(CREDENTIAL_ATTACK);
    }

    /**
     * A ban on the operator's own network is a lockout warning, and only that. Folding it into something
     * subject-lined "Breach attempt" would tell them they are under attack when in fact their own
     * allowlist has failed — a mail that actively misleads is worse than the one it replaced.
     */
    @Test
    void from_neverClaimsTheOperatorsOwnNetworkIsABreachAttempt() {
        BlockDecision ownNetwork = BlockDecision.builder()
            .id(5L).scenario("crowdsecurity/ssh-bf").sourceIp("10.13.13.6").type("ban").duration("4h0m0s")
            .build();

        assertThat(BreachAttemptRollup.from(List.of(ownNetwork), TRUSTED).decisions()).isEmpty();
    }

    /**
     * #349: an address the operator blocked themselves is not a breach attempt however its reason marker
     * happens to read to {@link ThreatKind} — mailing "somebody is trying to break in" about a block the
     * operator placed on purpose would be a straightforwardly false thing to send them, exactly like the
     * lockout case above.
     */
    @Test
    void from_neverClaimsTheOperatorsOwnHandBlockIsABreachAttempt() {
        BlockDecision handBlock = BlockDecision.builder()
            .id(6L).scenario(BlockDecision.handBlockReason("admin@example.com"))
            .sourceIp("203.0.113.9").type("ban").duration("4h0m0s").build();

        assertThat(BreachAttemptRollup.from(List.of(handBlock), TRUSTED).decisions()).isEmpty();
    }

    @Test
    void worthSending_isFalseWhenEmpty() {
        assertThat(new BreachAttemptRollup(List.of()).worthSending()).isFalse();
    }

    @Test
    void worthSending_isTrueWhenNotEmpty() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).worthSending()).isTrue();
    }

    @Test
    void subject_namesTheSingleDecision() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).subject())
            .isEqualTo("[Vaier] Breach attempt: " + DECISION_1.label());
    }

    @Test
    void subject_countsMultipleDecisions() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1, DECISION_2)).subject())
            .isEqualTo("[Vaier] Breach attempt: 2 new block decisions");
    }

    @Test
    void body_listsEveryDecision() {
        String body = new BreachAttemptRollup(List.of(DECISION_1, DECISION_2)).body(null);

        assertThat(body).contains(DECISION_1.label()).contains(DECISION_2.label());
    }

    @Test
    void body_saysWhereTheAttemptCameFromWhenCrowdSecKnows() {
        String body = new BreachAttemptRollup(List.of(ENRICHED_DECISION)).body(null);

        assertThat(body).contains("195.178.110.155 (BG · Techoff Srv Limited)");
    }

    @Test
    void body_staysCleanWhenCrowdSecKnowsNoOrigin() {
        String body = new BreachAttemptRollup(List.of(DECISION_1)).body(null);

        assertThat(body).contains("1.2.3.4 — crowdsecurity/http-probing").doesNotContain("(  )", "()");
    }

    /**
     * The body has to say why this one arrived when the operator knows most blocks never do — otherwise
     * the narrowing is invisible and the mail reads like the noise it replaced.
     */
    @Test
    void body_saysThisWasACredentialAttackAndThatRoutineBlocksAreNotMailed() {
        String body = new BreachAttemptRollup(List.of(CREDENTIAL_ATTACK)).body(null);

        assertThat(body).contains("credential attack").contains("Security view");
    }

    @Test
    void body_omitsTheUiLinkWhenBaseDomainIsBlank() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).body(" ")).doesNotContain("Vaier UI");
    }

    @Test
    void body_includesTheUiLinkWhenBaseDomainIsGiven() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).body("example.com"))
            .contains("https://vaier.example.com/");
    }
}
