package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiskUsageTest {

    @Test
    void usedPercent_isTotalMinusUsableOverTotal() {
        // 100 GB total, 25 GB usable -> 75% used
        DiskUsage usage = new DiskUsage("/host", 100L, 25L);

        assertThat(usage.usedPercent()).isEqualTo(75);
    }

    @Test
    void usedPercent_roundsToNearestWholePercent() {
        // 3 usable of 7 total -> 4/7 used = 57.14% -> 57
        DiskUsage usage = new DiskUsage("/host", 7L, 3L);

        assertThat(usage.usedPercent()).isEqualTo(57);
    }

    @Test
    void usedPercent_isZeroWhenTotalIsZero() {
        DiskUsage usage = new DiskUsage("/host", 0L, 0L);

        assertThat(usage.usedPercent()).isEqualTo(0);
    }

    @Test
    void isAbove_trueWhenUsedPercentExceedsThreshold() {
        DiskUsage usage = new DiskUsage("/host", 100L, 10L); // 90% used

        assertThat(usage.isAbove(85)).isTrue();
    }

    @Test
    void isAbove_falseWhenUsedPercentEqualsThreshold() {
        DiskUsage usage = new DiskUsage("/host", 100L, 15L); // 85% used

        assertThat(usage.isAbove(85)).isFalse();
    }

    @Test
    void isAbove_falseWhenUsedPercentBelowThreshold() {
        DiskUsage usage = new DiskUsage("/host", 100L, 50L); // 50% used

        assertThat(usage.isAbove(85)).isFalse();
    }

    @Test
    void usedBytes_isTotalMinusUsable() {
        DiskUsage usage = new DiskUsage("/host", 100L, 30L);

        assertThat(usage.usedBytes()).isEqualTo(70L);
    }

    @Test
    void pressureSubject_announcesDiskGettingFull() {
        DiskUsage usage = new DiskUsage("/host", 100L, 10L); // 90% used

        assertThat(usage.pressureSubject()).isEqualTo("[Vaier] Host disk is 90% full");
    }

    @Test
    void recoverySubject_announcesDiskBackToNormal() {
        DiskUsage usage = new DiskUsage("/host", 100L, 50L); // 50% used

        assertThat(usage.recoverySubject()).isEqualTo("[Vaier] Host disk is back to 50% full");
    }

    @Test
    void pressureBody_includesUsedPercentFreeGbTotalPathAndThreshold() {
        // 100 GiB total, 10 GiB free -> 90% used
        long gib = 1024L * 1024L * 1024L;
        DiskUsage usage = new DiskUsage("/host", 100L * gib, 10L * gib);

        String body = usage.pressureBody(85, "example.com");

        assertThat(body).contains("90%");          // used percent
        assertThat(body).contains("10.0 GB");      // free space, human-readable
        assertThat(body).contains("100.0 GB");     // total
        assertThat(body).contains("/host");        // monitored path
        assertThat(body).contains("85%");          // configured threshold
    }

    @Test
    void pressureBody_includesLinkToVaierWhenDomainProvided() {
        DiskUsage usage = new DiskUsage("/host", 100L, 10L);

        assertThat(usage.pressureBody(85, "example.com")).contains("vaier.example.com");
    }

    @Test
    void pressureBody_omitsLinkWhenDomainBlank() {
        DiskUsage usage = new DiskUsage("/host", 100L, 10L);

        assertThat(usage.pressureBody(85, null)).doesNotContain("https://");
    }
}
