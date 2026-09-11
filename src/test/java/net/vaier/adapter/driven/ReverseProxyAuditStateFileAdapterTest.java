package net.vaier.adapter.driven;

import net.vaier.domain.ReverseProxyAuditState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseProxyAuditStateFileAdapterTest {

    @TempDir Path tempDir;

    ReverseProxyAuditStateFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReverseProxyAuditStateFileAdapter(tempDir.toString());
    }

    @Test
    void nothingIsOutstandingBeforeAnythingIsWritten() {
        assertThat(adapter.find()).isEmpty();
    }

    @Test
    void whatAdminsWereToldSurvivesTheProcess() {
        adapter.save(new ReverseProxyAuditState(Set.of("UNREFERENCED_MIDDLEWARE:a-orphan")));

        ReverseProxyAuditStateFileAdapter afterRestart =
            new ReverseProxyAuditStateFileAdapter(tempDir.toString());

        assertThat(afterRestart.find()).isPresent();
        assertThat(afterRestart.find().orElseThrow().notifiedSignature())
            .containsExactly("UNREFERENCED_MIDDLEWARE:a-orphan");
    }

    @Test
    void savingAgainReplacesWhatWasThere() {
        adapter.save(new ReverseProxyAuditState(Set.of("UNREFERENCED_MIDDLEWARE:a-orphan")));
        adapter.save(new ReverseProxyAuditState(Set.of("UNROUTED_SERVICE:a-service")));

        assertThat(adapter.find().orElseThrow().notifiedSignature())
            .containsExactly("UNROUTED_SERVICE:a-service");
    }

    @Test
    void clearingLeavesNothingOutstanding() {
        adapter.save(new ReverseProxyAuditState(Set.of("UNREFERENCED_MIDDLEWARE:a-orphan")));

        adapter.clear();

        assertThat(adapter.find()).isEmpty();
    }

    @Test
    void anUnreadableFileReadsAsNothingOutstanding() throws IOException {
        // Erring towards a repeated alert rather than towards silence — the right direction for a latch.
        Files.writeString(tempDir.resolve("reverse-proxy-audit.yml"), "\t: not : yaml : [");

        assertThat(adapter.find()).isEmpty();
    }
}
