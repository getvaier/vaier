package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** May Ask be offered at all? One decision, one place (#360). */
class ChatAvailabilityTest {

    @Test
    void askIsAvailableOnceAnAnthropicApiKeyIsStored() {
        ChatAvailability availability = ChatAvailability.of(Optional.of(
            VaierConfig.builder().anthropicApiKey("sk-ant-api03-the-key").build()));

        assertThat(availability.available()).isTrue();
    }

    @Test
    void askIsUnavailableWithoutAKey() {
        assertThat(ChatAvailability.of(Optional.of(VaierConfig.builder().build())).available()).isFalse();
        assertThat(ChatAvailability.of(Optional.of(
            VaierConfig.builder().anthropicApiKey("   ").build())).available()).isFalse();
    }

    /** A Vaier that has never been configured has no key either, and must not pretend otherwise. */
    @Test
    void askIsUnavailableWhenThereIsNoConfigurationAtAll() {
        assertThat(ChatAvailability.of(Optional.empty()).available()).isFalse();
    }

    @Test
    void requireAvailable_passesQuietlyWhenAskIsAvailable() {
        ChatAvailability.of(Optional.of(VaierConfig.builder().anthropicApiKey("sk-ant").build()))
            .requireAvailable();
    }

    /** The refusal names the fix, because "unavailable" on its own tells the operator nothing to do. */
    @Test
    void requireAvailable_refusesWithASentenceTheOperatorCanActOn() {
        assertThatThrownBy(() -> ChatAvailability.of(Optional.empty()).requireAvailable())
            .isInstanceOf(ChatUnavailableException.class)
            .hasMessage("Chat needs an Anthropic API key. Add one in Settings and Vaier can answer "
                + "questions about your fleet.");
    }
}
