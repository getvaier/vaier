package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Mailed confirmation</b>: a Chat action an errand proposed, mailed to the operator as one link that
 * works once, for a day.
 */
class MailedConfirmationTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Operator GEIR = Operator.of("geir@example.com");

    private static ActionProposal lift() {
        return ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);
    }

    /** 32 random bytes, URL-safe; only its digest is kept, so the file on disk opens nothing. */
    @Test
    void mintingGivesAFreshUnguessableToken_andKeepsOnlyItsDigest() {
        MailedConfirmation.Minted a = MailedConfirmation.mint(lift(), GEIR, NOW);
        MailedConfirmation.Minted b = MailedConfirmation.mint(lift(), GEIR, NOW);

        assertThat(a.token()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(b.token());
        assertThat(a.confirmation().tokenDigest()).isNotEqualTo(a.token()).doesNotContain(a.token());
        assertThat(a.confirmation().opensWith(a.token())).isTrue();
        assertThat(a.confirmation().opensWith(b.token())).isFalse();
        assertThat(a.confirmation().operator()).isEqualTo(GEIR);
        assertThat(a.confirmation().mailedAtEpochMs()).isEqualTo(NOW);
    }

    @Test
    void theMailSaysWhatWouldHappen_andCarriesTheLink_andMarvinIsToldItIsNotDone() {
        MailedConfirmation.Minted minted = MailedConfirmation.mint(lift(), GEIR, NOW);

        assertThat(minted.recipient()).isEqualTo("geir@example.com");
        assertThat(minted.subject()).isEqualTo("Marvin asks: Lift the block on 203.0.113.9.");
        assertThat(minted.body(" example.com "))
            .contains("Lift the block on 203.0.113.9.")
            .contains("https://vaier.example.com/chat/approvals/" + minted.token())
            .contains("Nothing happens unless you say yes");
        assertThat(minted.confirmation().toolResult())
            .contains("Lift the block on 203.0.113.9.")
            .contains("Nothing has happened yet");
    }

    /** Nobody signed in has no address, and a confirmation nobody can receive is refused before it is kept. */
    @Test
    void anOperatorWithoutAnAddressCannotBeAsked() {
        assertThatThrownBy(() -> MailedConfirmation.mint(lift(), Operator.of(null), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Nobody is signed in with an address to ask, so this cannot be proposed by mail.");
    }
}
