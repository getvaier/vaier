package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void validateUsername_rejectsBlank(String username) {
        assertThatThrownBy(() -> User.validateUsername(username))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void validateUsername_acceptsValid() {
        assertThatCode(() -> User.validateUsername("alice")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validatePassword_rejectsBlank(String password) {
        assertThatThrownBy(() -> User.validatePassword(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ab", "short"})
    void validatePassword_rejectsTooShort(String password) {
        assertThatThrownBy(() -> User.validatePassword(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void validatePassword_acceptsEightOrMoreChars() {
        assertThatCode(() -> User.validatePassword("password")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateEmail_rejectsBlank(String email) {
        assertThatThrownBy(() -> User.validateEmail(email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local.com", "no-domain@", "no-tld@foo"})
    void validateEmail_rejectsInvalidFormat(String email) {
        assertThatThrownBy(() -> User.validateEmail(email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void validateEmail_acceptsValidFormat() {
        assertThatCode(() -> User.validateEmail("alice@example.com")).doesNotThrowAnyException();
    }
}
