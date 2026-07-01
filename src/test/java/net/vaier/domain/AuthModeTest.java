package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModeTest {

    @Test
    void wireValue_isTheLowercaseToken() {
        assertThat(AuthMode.NONE.wireValue()).isEqualTo("none");
        assertThat(AuthMode.SOCIAL.wireValue()).isEqualTo("social");
    }

    @Test
    void fromString_parsesKnownTokensCaseInsensitively() {
        assertThat(AuthMode.fromString("none")).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromString("SOCIAL")).isEqualTo(AuthMode.SOCIAL);
    }

    @Test
    void fromString_unknownOrBlankDefaultsToSocial_soAuthIsNeverAccidentallyDropped() {
        assertThat(AuthMode.fromString(null)).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromString("")).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromString("nonsense")).isEqualTo(AuthMode.SOCIAL);
        // A legacy "authelia" token now falls through to the safe default rather than a dropped mode.
        assertThat(AuthMode.fromString("authelia")).isEqualTo(AuthMode.SOCIAL);
    }

    @Test
    void fromBoolean_mapsLegacyRequiresAuthToggle() {
        assertThat(AuthMode.fromRequiresAuth(true)).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromRequiresAuth(false)).isEqualTo(AuthMode.NONE);
    }

    @Test
    void authMiddlewareNames_describeTheChainEachModeNeeds() {
        assertThat(AuthMode.NONE.authMiddlewareNames()).isEmpty();
        // The proven step-1 chain order: serve the sign-in page on 401, authenticate, then authorize.
        assertThat(AuthMode.SOCIAL.authMiddlewareNames())
            .containsExactly("oauth2-signin", "oauth2-authn", "vaier-authz");
    }

    @Test
    void allAuthMiddlewareNames_unionAcrossEveryMode_soAModeSwitchCanStripThePriorChain() {
        assertThat(AuthMode.allAuthMiddlewareNames())
            .containsExactlyInAnyOrder("oauth2-signin", "oauth2-authn", "vaier-authz");
    }

    @Test
    void fromMiddlewareNames_readsTheModeBackOffARoutersChain() {
        assertThat(AuthMode.fromMiddlewareNames(null)).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromMiddlewareNames(List.of("vaier-errors"))).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromMiddlewareNames(List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors")))
            .isEqualTo(AuthMode.SOCIAL);
    }

    @Test
    void isSocial_isTrueOnlyForSocial() {
        assertThat(AuthMode.SOCIAL.isSocial()).isTrue();
        assertThat(AuthMode.NONE.isSocial()).isFalse();
    }
}
