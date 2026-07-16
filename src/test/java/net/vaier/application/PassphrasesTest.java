package net.vaier.application;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PassphrasesTest {

    @Test
    void strongIs32AlphanumericCharacters() {
        String passphrase = Passphrases.strong();
        assertThat(passphrase).hasSize(32);
        assertThat(passphrase).matches("[A-Za-z0-9]{32}");
    }

    @Test
    void strongIsNotDeterministic() {
        // A hundred draws must not all collide — a strong secret is generated fresh each time.
        long distinct = IntStream.range(0, 100).mapToObj(i -> Passphrases.strong()).distinct().count();
        assertThat(distinct).isEqualTo(100);
    }
}
