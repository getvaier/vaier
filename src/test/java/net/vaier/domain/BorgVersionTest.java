package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BorgVersionTest {

    @Test
    void parsesAndReportsSupport() {
        // A modern borg (>= 1.2, where `borg compact` lands) parses and is supported.
        Optional<BorgVersion> v128 = BorgVersion.parse("borg 1.2.8");
        assertThat(v128).isPresent();
        assertThat(v128.get()).isEqualTo(new BorgVersion(1, 2, 8));
        assertThat(v128.get().isSupported()).isTrue();

        // A trailing newline and extra whitespace are tolerated.
        assertThat(BorgVersion.parse("borg 1.2.8\n")).contains(new BorgVersion(1, 2, 8));

        // A two-part version (no patch) parses with patch 0.
        assertThat(BorgVersion.parse("borg 1.4")).contains(new BorgVersion(1, 4, 0));

        // A 2.x is comfortably supported.
        assertThat(BorgVersion.parse("borg 2.0.0")).get()
            .extracting(BorgVersion::isSupported).isEqualTo(true);

        // Pre-1.2 lacks `borg compact` and is NOT supported.
        Optional<BorgVersion> v111 = BorgVersion.parse("borg 1.1.18");
        assertThat(v111).isPresent();
        assertThat(v111.get().isSupported()).isFalse();
        assertThat(BorgVersion.parse("borg 1.0.9").get().isSupported()).isFalse();

        // Garbage / non-borg output is empty, never an exception.
        assertThat(BorgVersion.parse("")).isEmpty();
        assertThat(BorgVersion.parse(null)).isEmpty();
        assertThat(BorgVersion.parse("command not found")).isEmpty();
        assertThat(BorgVersion.parse("borg: not found")).isEmpty();
    }
}
