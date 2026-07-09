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

    @Test
    void parsesABetaPreReleaseByItsMajorMinorPatchPrefix() {
        // The borg-2 line currently only ships as a beta ("borg 2.0.0b20"). The pre-release suffix must not
        // make it fail to parse (which would silently look "absent"): it parses to its numeric prefix, major
        // 2, so the version guard can catch the client/server incompatibility rather than miss it.
        Optional<BorgVersion> beta = BorgVersion.parse("borg 2.0.0b20");
        assertThat(beta).isPresent();
        assertThat(beta.get().major()).isEqualTo(2);
        assertThat(beta.get()).isEqualTo(new BorgVersion(2, 0, 0));
    }

    @Test
    void isCompatibleWhenMajorsMatch() {
        // Borg 2 changed the repo format and protocol, so compatibility is major-equality, not ">=".
        BorgVersion client128 = new BorgVersion(1, 2, 8);
        // A 1.2 client and a 1.4 server share a major -> compatible (the fleet's real case).
        assertThat(client128.isCompatibleWith(new BorgVersion(1, 4, 3))).isTrue();
        // A 1.2 client cannot talk to a borg-2 server -> incompatible.
        assertThat(client128.isCompatibleWith(new BorgVersion(2, 0, 0))).isFalse();
        assertThat(client128.isCompatibleWith(new BorgVersion(2, 6, 0))).isFalse();
        // Two borg-2 versions share a major -> compatible.
        assertThat(new BorgVersion(2, 0, 0).isCompatibleWith(new BorgVersion(2, 1, 0))).isTrue();
    }

    @Test
    void compatibleIsFalseWhenEitherVersionIsUnknown() {
        // Honest by construction: an unknown version on either side is never optimistically "compatible".
        Optional<BorgVersion> known = Optional.of(new BorgVersion(1, 2, 8));
        Optional<BorgVersion> alsoKnown = Optional.of(new BorgVersion(1, 4, 3));
        Optional<BorgVersion> unknown = Optional.empty();

        assertThat(BorgVersion.compatible(known, alsoKnown)).isTrue();
        assertThat(BorgVersion.compatible(known, unknown)).isFalse();
        assertThat(BorgVersion.compatible(unknown, alsoKnown)).isFalse();
        assertThat(BorgVersion.compatible(unknown, unknown)).isFalse();
        // A known client and a known-but-incompatible server is false, of course.
        assertThat(BorgVersion.compatible(known, Optional.of(new BorgVersion(2, 0, 0)))).isFalse();
    }
}
