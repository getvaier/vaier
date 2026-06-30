package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingIdentityTest {

    @Test
    void notificationSubject_announcesAnAccessRequestAwaitingApproval() {
        PendingIdentity identity = new PendingIdentity("newcomer@example.com");

        assertThat(identity.notificationSubject()).isEqualTo("[Vaier] New access request awaiting approval");
    }

    @Test
    void notificationBody_namesTheEmailAndLinksToTheAccessPage() {
        PendingIdentity identity = new PendingIdentity("newcomer@example.com");

        String body = identity.notificationBody("example.com");

        assertThat(body).contains("newcomer@example.com");
        assertThat(body).contains("awaiting approval");
        assertThat(body).contains("https://vaier.example.com/admin.html#users");
    }

    @Test
    void notificationBody_omitsLinkWhenDomainAbsent() {
        PendingIdentity identity = new PendingIdentity("newcomer@example.com");

        String body = identity.notificationBody(null);

        assertThat(body).contains("newcomer@example.com");
        assertThat(body).doesNotContain("https://");
    }
}
