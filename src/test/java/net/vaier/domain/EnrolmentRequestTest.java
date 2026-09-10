package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An enrolment request is the whole anonymous surface of #359 slice 1b: a phone nobody has approved
 * yet, holding a ticket, showing a join code. Every judgement it makes — is it still live, is this
 * the ticket it issued, may another one be opened, which code is free — is a decision, so each one
 * lives here on the record and is proved here.
 */
class EnrolmentRequestTest {

    /** A real, structurally valid WireGuard public key, as the app would present it. */
    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String TICKET = "ZmFrZS10aWNrZXQtdmFsdWUtZm9yLXRoZS10ZXN0cy0xMjM";
    private static final long NOW = 1_000_000L;

    private static EnrolmentRequest open() {
        return EnrolmentRequest.open("Ruten", DEVICE_KEY, "4821", TICKET, NOW);
    }

    // --- the request is judged while it is still anonymous, before anything is stored ---

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-key",
        "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=; rm -rf /",
        "$(id)",
        "c2hvcnQ="
    })
    void open_refusesAKeyThatIsNotAWireGuardKey(String malicious) {
        assertThatThrownBy(() -> EnrolmentRequest.open("Ruten", malicious, "4821", TICKET, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void open_refusesANameThatSlugsToNothing() {
        assertThatThrownBy(() -> EnrolmentRequest.open("  ", DEVICE_KEY, "4821", TICKET, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void open_keepsTheNameVerbatimAndTheTrimmedKey() {
        EnrolmentRequest request = EnrolmentRequest.open("Geir's phone", " " + DEVICE_KEY + "\n",
            "4821", TICKET, NOW);

        assertThat(request.name()).isEqualTo("Geir's phone");
        assertThat(request.publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(request.code()).isEqualTo("4821");
        assertThat(request.ticket()).isEqualTo(TICKET);
        assertThat(request.expiresAtEpochMs()).isEqualTo(NOW + EnrolmentRequest.TTL.toMillis());
        assertThat(request.configFile()).isNull();
    }

    @Test
    void theTtlIsTenMinutes() {
        assertThat(EnrolmentRequest.TTL.toMinutes()).isEqualTo(10);
    }

    // --- liveness: expiry is exclusive, as a setup token's is ---

    @Test
    void isLive_untilItsExpiryInstant() {
        EnrolmentRequest request = open();
        long expiresAt = NOW + EnrolmentRequest.TTL.toMillis();

        assertThat(request.isLive(NOW)).isTrue();
        assertThat(request.isLive(expiresAt - 1)).isTrue();
        assertThat(request.isLive(expiresAt)).isFalse();
        assertThat(request.isLive(expiresAt + 1)).isFalse();
    }

    @Test
    void secondsLeft_countsDownAndNeverGoesNegative() {
        EnrolmentRequest request = open();

        assertThat(request.secondsLeft(NOW)).isEqualTo(600);
        assertThat(request.secondsLeft(NOW + 60_000)).isEqualTo(540);
        assertThat(request.secondsLeft(NOW + EnrolmentRequest.TTL.toMillis() + 5_000)).isZero();
    }

    // --- the ticket is the only thing that gates delivery ---

    @Test
    void authorizes_onlyTheTicketItIssued() {
        EnrolmentRequest request = open();

        assertThat(request.authorizes(TICKET, NOW)).isTrue();
        assertThat(request.authorizes("some-other-ticket", NOW)).isFalse();
        assertThat(request.authorizes(null, NOW)).isFalse();
    }

    @Test
    void authorizes_noLongerOnceItHasExpired() {
        EnrolmentRequest request = open();

        assertThat(request.authorizes(TICKET, NOW + EnrolmentRequest.TTL.toMillis())).isFalse();
    }

    // --- how many phones may wait at once ---

    @Test
    void atMostFiveRequestsMayWaitAtOnce() {
        assertThat(EnrolmentRequest.MAX_PENDING).isEqualTo(5);

        assertThat(EnrolmentRequest.mayOpenAnother(0)).isTrue();
        assertThat(EnrolmentRequest.mayOpenAnother(4)).isTrue();
        assertThat(EnrolmentRequest.mayOpenAnother(5)).isFalse();
        assertThat(EnrolmentRequest.mayOpenAnother(6)).isFalse();
    }

    // --- the join code: four digits, never one already on another phone's screen ---

    @Test
    void pickCode_isAlwaysFourDigits() {
        assertThat(EnrolmentRequest.pickCode(Set.of(), () -> 7)).isEqualTo("0007");
        assertThat(EnrolmentRequest.pickCode(Set.of(), () -> 4821)).isEqualTo("4821");
    }

    @Test
    void pickCode_survivesARandomSourceOutsideTheCodeSpace() {
        assertThat(EnrolmentRequest.pickCode(Set.of(), () -> 10_042)).isEqualTo("0042");
        assertThat(EnrolmentRequest.pickCode(Set.of(), () -> -1)).isEqualTo("9999");
    }

    @Test
    void pickCode_stepsPastACodeAnotherWaitingPhoneIsShowing() {
        // Two phones showing 4821 is the one thing the code exists to prevent — the operator would
        // have no way to tell which of them they were approving.
        assertThat(EnrolmentRequest.pickCode(Set.of("4821"), () -> 4821)).isEqualTo("4822");
        assertThat(EnrolmentRequest.pickCode(Set.of("4821", "4822"), () -> 4821)).isEqualTo("4823");
    }

    @Test
    void pickCode_wrapsAroundTheEndOfTheCodeSpace() {
        assertThat(EnrolmentRequest.pickCode(Set.of("9999"), () -> 9999)).isEqualTo("0000");
    }

    // --- what a ticket holder is owed ---

    @Test
    void verdictFor_isPendingWhileNobodyHasApprovedIt() {
        EnrolmentVerdict verdict = open().verdictFor(TICKET, NOW);

        assertThat(verdict.isPending()).isTrue();
        assertThat(verdict.configFile()).isNull();
    }

    @Test
    void verdictFor_carriesTheConfigOnceApproved() {
        EnrolmentRequest approved = open().approved("[Interface]\nAddress = 10.13.13.7/32\n");

        EnrolmentVerdict verdict = approved.verdictFor(TICKET, NOW);

        assertThat(verdict.isApproved()).isTrue();
        assertThat(verdict.configFile()).isEqualTo("[Interface]\nAddress = 10.13.13.7/32\n");
    }

    @Test
    void verdictFor_isNothingForAnyTicketButItsOwn() {
        EnrolmentRequest approved = open().approved("[Interface]");

        assertThat(approved.verdictFor("someone-elses-ticket", NOW).isGone()).isTrue();
    }

    @Test
    void verdictFor_isNothingOnceTheRequestHasExpired_evenWhenItWasApproved() {
        // Deliberately looser than "burned on delivery": a phone whose stream dropped mid-approval
        // reconnects and is served again. Re-delivery only ever reaches the ticket holder, and the
        // window closes at the TTL like everything else here.
        EnrolmentRequest approved = open().approved("[Interface]");

        assertThat(approved.verdictFor(TICKET, NOW + EnrolmentRequest.TTL.toMillis()).isGone()).isTrue();
    }

    @Test
    void approved_keepsEverythingElseIncludingWhenItDies() {
        EnrolmentRequest approved = open().approved("[Interface]");

        assertThat(approved.code()).isEqualTo("4821");
        assertThat(approved.ticket()).isEqualTo(TICKET);
        assertThat(approved.name()).isEqualTo("Ruten");
        assertThat(approved.publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(approved.expiresAtEpochMs()).isEqualTo(open().expiresAtEpochMs());
        assertThat(approved.isApproved()).isTrue();
        assertThat(open().isApproved()).isFalse();
    }

    // --- which phone the model or the operator meant (#360 slice 2) --------------------------------------

    @Test
    void aWaitingPhoneIsFoundByTheCodeItIsShowing() {
        EnrolmentRequest ruten = new EnrolmentRequest("4417", "t1", "Ruten", "pk1", 1L, "cfg1");
        EnrolmentRequest other = new EnrolmentRequest("9021", "t2", "Other", "pk2", 1L, "cfg2");

        assertThat(EnrolmentRequest.byCode(List.of(other, ruten), "4417")).isSameAs(ruten);
        assertThat(EnrolmentRequest.byCode(List.of(other, ruten), " 4417 ")).isSameAs(ruten);
    }

    @Test
    void aCodeNoPhoneIsShowingIsRefusedInWords() {
        assertThatThrownBy(() -> EnrolmentRequest.byCode(List.of(), "9999"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No phone is waiting with join code 9999.");
        assertThatThrownBy(() -> EnrolmentRequest.byCode(List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which code.");
    }
}
