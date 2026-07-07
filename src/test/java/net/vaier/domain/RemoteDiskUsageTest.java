package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskUsageTest {

    private static final String DF_OUTPUT =
        "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
            + "/dev/root         61255492 55129943   6125549      90% /\n";

    @Test
    void parse_readsCapacityColumn_fromDfMinusP() {
        Optional<RemoteDiskUsage> usage = RemoteDiskUsage.parse("nas", DF_OUTPUT);

        assertThat(usage).isPresent();
        assertThat(usage.get().machineName()).isEqualTo("nas");
        assertThat(usage.get().usedPercent()).isEqualTo(90);
    }

    @Test
    void parse_blankOrNull_returnsEmpty() {
        assertThat(RemoteDiskUsage.parse("nas", null)).isEmpty();
        assertThat(RemoteDiskUsage.parse("nas", "   ")).isEmpty();
    }

    @Test
    void parse_headerOnly_returnsEmpty() {
        assertThat(RemoteDiskUsage.parse("nas",
            "Filesystem 1024-blocks Used Available Capacity Mounted on")).isEmpty();
    }

    @Test
    void parse_garbageWithNoPercentColumn_returnsEmpty() {
        assertThat(RemoteDiskUsage.parse("nas", "bash: df: command not found")).isEmpty();
    }

    @Test
    void isAbove_isStrictlyGreater() {
        RemoteDiskUsage usage = new RemoteDiskUsage("nas", 85);
        assertThat(usage.isAbove(85)).isFalse();
        assertThat(usage.isAbove(84)).isTrue();
    }

    @Test
    void pressureSubject_namesTheMachineAndPercent() {
        assertThat(new RemoteDiskUsage("nas", 90).pressureSubject())
            .contains("nas").contains("90%");
    }

    @Test
    void recoverySubject_namesTheMachineAndPercent() {
        assertThat(new RemoteDiskUsage("nas", 40).recoverySubject())
            .contains("nas").contains("40%");
    }

    @Test
    void pressureBody_includesMachineThresholdAndUiLink() {
        String body = new RemoteDiskUsage("nas", 90).pressureBody(85, "example.com");
        assertThat(body).contains("nas").contains("90%").contains("85%").contains("https://");
    }
}
