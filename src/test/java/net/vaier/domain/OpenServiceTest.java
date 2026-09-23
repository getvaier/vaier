package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenServiceTest {

    @Test
    void theMailSaysWhereTheHoleIs_andBothWaysToCloseIt() {
        OpenService rack = new OpenService("rack-router", "rack.example.com", "/ui");

        assertThat(rack.subject()).isEqualTo("[Vaier] rack.example.com is open to anyone");
        assertThat(rack.body("example.com"))
            .contains("https://rack.example.com/ui")
            .contains("Put Vaier's sign-in in front")
            .contains("This is meant to be public")
            .contains("https://vaier.example.com");
    }
}
