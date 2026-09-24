package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockDurationTest {

    @Test
    void of_acceptsExactlyTheFourOfferedChoices() {
        assertThat(BlockDuration.of("1h")).isEqualTo(BlockDuration.ONE_HOUR);
        assertThat(BlockDuration.of("4h")).isEqualTo(BlockDuration.FOUR_HOURS);
        assertThat(BlockDuration.of("24h")).isEqualTo(BlockDuration.TWENTY_FOUR_HOURS);
        assertThat(BlockDuration.of("7d")).isEqualTo(BlockDuration.SEVEN_DAYS);
    }

    // #349: the operator chose "no permanent option" so a hand block self-heals like everything else the
    // Security view already does. Nothing wider than 7 days is a choice at all.
    @Test
    void of_rejectsAnythingOutsideTheFourChoices_thereIsDeliberatelyNoPermanentOption() {
        assertThatThrownBy(() -> BlockDuration.of("permanent")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockDuration.of("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockDuration.of("30d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockDuration.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlockDuration.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // cscli's own duration flag is parsed by Go's time.ParseDuration, which knows "h" but not "d" —
    // so "7 days" is expressed to CrowdSec as 168 hours, never as a literal "7d" it cannot read.
    @Test
    void cscliDuration_expressesSevenDaysInHoursBecauseCscliHasNoDayUnit() {
        assertThat(BlockDuration.SEVEN_DAYS.cscliDuration()).isEqualTo("168h");
        assertThat(BlockDuration.ONE_HOUR.cscliDuration()).isEqualTo("1h");
        assertThat(BlockDuration.FOUR_HOURS.cscliDuration()).isEqualTo("4h");
        assertThat(BlockDuration.TWENTY_FOUR_HOURS.cscliDuration()).isEqualTo("24h");
    }

    @Test
    void label_readsAsAPersonWouldSayIt() {
        assertThat(BlockDuration.ONE_HOUR.label()).isEqualTo("1 hour");
        assertThat(BlockDuration.FOUR_HOURS.label()).isEqualTo("4 hours");
        assertThat(BlockDuration.TWENTY_FOUR_HOURS.label()).isEqualTo("24 hours");
        assertThat(BlockDuration.SEVEN_DAYS.label()).isEqualTo("7 days");
    }
}
