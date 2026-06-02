package net.vaier.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSafeTest {

    @Test
    void replacesNewlinesAndCarriageReturnsWithSingleLineSafeMarker() {
        assertThat(LogSafe.forLog("a\nb\rc")).isEqualTo("a_b_c");
    }

    @Test
    void collapsesCrlfPairIntoOneMarker() {
        assertThat(LogSafe.forLog("line1\r\nline2")).isEqualTo("line1_line2");
    }

    @Test
    void leavesOrdinaryValuesUntouched() {
        assertThat(LogSafe.forLog("media-server_01")).isEqualTo("media-server_01");
    }

    @Test
    void isNullSafe() {
        assertThat(LogSafe.forLog(null)).isNull();
    }
}
