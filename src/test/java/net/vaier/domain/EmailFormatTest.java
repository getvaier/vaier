package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "user@example.com",
        "geir.eilertsen@gmail.com",
        "a@b.co"
    })
    void isValid_acceptsWellFormedAddresses(String email) {
        assertThat(EmailFormat.isValid(email)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "not-an-email",
        "missing@domain",
        "spaces in@example.com",
        "two@@example.com",
        "@example.com"
    })
    void isValid_rejectsMalformedAddresses(String email) {
        assertThat(EmailFormat.isValid(email)).isFalse();
    }
}
