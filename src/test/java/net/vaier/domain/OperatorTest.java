package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who is asking (#360 slice 3): the signed-in email, as the key a conversation is kept under. */
class OperatorTest {

    @Test
    void anOperatorIsKeyedByTheirEmail_caseAndSpaceBlind() {
        assertThat(Operator.of("Geir@Example.com").key()).isEqualTo("geir@example.com");
        assertThat(Operator.of("  geir@example.com ").key()).isEqualTo("geir@example.com");
        assertThat(Operator.of("geir@example.com")).isEqualTo(Operator.of("GEIR@example.com"));
    }

    /** No header means no gate in front of Vaier; one shared conversation is then the honest thing. */
    @Test
    void nobodySignedInIsTheOneOperator() {
        assertThat(Operator.of(null).key()).isEqualTo("operator");
        assertThat(Operator.of("  ").key()).isEqualTo("operator");
    }

    @Test
    void theFileNameIsSafeForAnyEmail() {
        assertThat(Operator.of("geir.eilertsen+x@example.com").fileName()).isEqualTo("geir_eilertsen_x_example_com");
        assertThat(Operator.of("../../etc/passwd").fileName()).isEqualTo("etc_passwd");
    }
}
