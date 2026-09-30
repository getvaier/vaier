package net.vaier.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JoinRequestNoticeTest {

    private static final String KEY = "Cdd32h4brltAwRS22xopgiyeyXUNv202FMgAoj1Hgio=";

    @Test
    void subject_namesThePhoneAndTheCode_soItCanBeMatchedFromTheInbox() {
        JoinRequestNotice notice = JoinRequestNotice.from(
            EnrolmentRequest.open("Ruten", KEY, null, "4821", "ticket", 0L), 0L);

        assertThat(notice.subject()).isEqualTo("[Vaier] Ruten wants to join — code 4821");
    }

    @Test
    void body_linksTheSameApprovalAddressThePhoneItselfUses() {
        // One address for approving from anywhere: the mail's link is the phone's "approve it here".
        JoinRequestNotice notice = JoinRequestNotice.from(
            EnrolmentRequest.open("Ruten", KEY, null, "4821", "ticket", 0L), 0L);

        assertThat(notice.body("example.com"))
            .contains("Ruten is asking to join Vaier and is showing the code 4821.")
            .contains("waits 10 more minutes")
            .contains("https://vaier.example.com/explorer.html?approve=4821");
    }

    @Test
    void body_withoutADomain_carriesNoLink() {
        JoinRequestNotice notice = JoinRequestNotice.from(
            EnrolmentRequest.open("Ruten", KEY, null, "4821", "ticket", 0L), 0L);

        assertThat(notice.body(null)).doesNotContain("https://");
    }

    @Test
    void minutesLeft_neverReadsZero_whileTheRequestIsStillLive() {
        long opened = 0L;
        long nearTheEnd = EnrolmentRequest.TTL.toMillis() - 10_000;

        assertThat(JoinRequestNotice.from(EnrolmentRequest.open("Ruten", KEY, null, "4821", "t", opened), nearTheEnd)
            .minutesLeft()).isEqualTo(1);
    }
}
