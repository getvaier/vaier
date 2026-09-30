package net.vaier.adapter.driven;

import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.MachineType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store mints tickets and codes and holds enrolment requests. Expiry is the domain's decision
 * and is proved in {@code EnrolmentRequestTest}; what is proved here is minting, uniqueness of the
 * codes two waiting phones show, and that an approval survives in the store for a phone whose
 * stream dropped.
 */
class InMemoryEnrolmentRequestStoreTest {

    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String OTHER_KEY = "aFPKMlUyDXQpBQwGA2CqcnMkbQ7yYbCKAsmLcVcLzGA=";

    private final InMemoryEnrolmentRequestStore store = new InMemoryEnrolmentRequestStore();

    @Test
    void open_mintsAnUnguessableTicketAndAFourDigitCode() {
        EnrolmentRequest request = store.open("Ruten", DEVICE_KEY, "windows");

        // 32 random bytes, base64url without padding, is exactly 43 characters — the same shape the
        // Traefik route for the phone's own event stream is anchored to.
        assertThat(request.ticket()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(request.code()).matches("\\d{4}");
        assertThat(request.name()).isEqualTo("Ruten");
        assertThat(request.publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(request.machineType()).isEqualTo(MachineType.WINDOWS_CLIENT);
        assertThat(request.isApproved()).isFalse();
    }

    @Test
    void open_mintsADistinctTicketEveryTime() {
        assertThat(store.open("a", DEVICE_KEY, null).ticket())
            .isNotEqualTo(store.open("b", OTHER_KEY, null).ticket());
    }

    @Test
    void open_neverShowsTwoWaitingPhonesTheSameCode() {
        List<String> codes = IntStream.range(0, EnrolmentRequest.MAX_PENDING)
            .mapToObj(i -> store.open("phone-" + i, DEVICE_KEY, null).code())
            .toList();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    void open_refusesAKeyThatIsNotAWireGuardKey_andStoresNothing() {
        assertThatThrownBy(() -> store.open("Ruten", "not-a-key", null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.livePending()).isEmpty();
    }

    @Test
    void findByCode_andByTicket_findTheSameRequest() {
        EnrolmentRequest request = store.open("Ruten", DEVICE_KEY, null);

        assertThat(store.findByCode(request.code())).contains(request);
        assertThat(store.findByTicket(request.ticket())).contains(request);
    }

    @Test
    void findByCode_andByTicket_areEmptyForAnythingElse() {
        EnrolmentRequest request = store.open("Ruten", DEVICE_KEY, null);
        String freeCode = "0000".equals(request.code()) ? "0001" : "0000";

        assertThat(store.findByCode(freeCode)).isEmpty();
        assertThat(store.findByTicket("made-up-ticket")).isEmpty();
        assertThat(store.findByTicket(null)).isEmpty();
        assertThat(store.findByCode(null)).isEmpty();
    }

    @Test
    void recordApproval_leavesTheRequestForAPhoneWhoseStreamDropped() {
        EnrolmentRequest request = store.open("Ruten", DEVICE_KEY, null);

        store.recordApproval(request.code(), "[Interface]\nAddress = 10.13.13.7/32\n");

        Optional<EnrolmentRequest> found = store.findByTicket(request.ticket());
        assertThat(found).isPresent();
        assertThat(found.get().configFile()).isEqualTo("[Interface]\nAddress = 10.13.13.7/32\n");
        assertThat(found.get().isApproved()).isTrue();
    }

    @Test
    void livePending_neverListsAnApprovedRequest() {
        EnrolmentRequest approved = store.open("Ruten", DEVICE_KEY, null);
        EnrolmentRequest waiting = store.open("Kikkut", OTHER_KEY, null);

        store.recordApproval(approved.code(), "[Interface]");

        assertThat(store.livePending()).extracting(EnrolmentRequest::code)
            .containsExactly(waiting.code());
    }

    @Test
    void recordApproval_onAnUnknownCode_doesNothing() {
        store.recordApproval("0000", "[Interface]");

        assertThat(store.livePending()).isEmpty();
    }

    @Test
    void remove_handsBackWhatItRemoved_soTheRefusalCanReachThePhone() {
        EnrolmentRequest request = store.open("Ruten", DEVICE_KEY, null);

        assertThat(store.remove(request.code())).contains(request);
        assertThat(store.findByTicket(request.ticket())).isEmpty();
        assertThat(store.livePending()).isEmpty();
    }

    @Test
    void remove_anUnknownCode_isANoOp() {
        assertThat(store.remove("0000")).isEmpty();
        assertThat(store.remove(null)).isEmpty();
    }
}
