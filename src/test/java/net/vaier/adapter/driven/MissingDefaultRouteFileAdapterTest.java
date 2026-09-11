package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MissingDefaultRouteFileAdapterTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final MachineId NUC = TestMachineIds.of("nuc");

    @TempDir
    Path configDir;

    private MissingDefaultRouteFileAdapter adapter() {
        return new MissingDefaultRouteFileAdapter(configDir.toString());
    }

    @Test
    void anAbsentFile_isTheHealthyState_notAnError() {
        assertThat(adapter().wasAlerted(APALVEIEN)).isFalse();
    }

    @Test
    void theLatchSurvivesARestart() {
        // The reason this port exists at all: the operator deploys several times a day, and a latch held in
        // a field would re-send the same email after every one of them.
        adapter().markAlerted(APALVEIEN);

        assertThat(adapter().wasAlerted(APALVEIEN)).isTrue();
    }

    @Test
    void markAlerted_isIdempotent_andOneMachineIsNeverAnother() {
        MissingDefaultRouteFileAdapter adapter = adapter();
        adapter.markAlerted(APALVEIEN);
        adapter.markAlerted(APALVEIEN);

        assertThat(adapter.wasAlerted(APALVEIEN)).isTrue();
        assertThat(adapter.wasAlerted(NUC)).isFalse();
    }

    @Test
    void clear_isPersisted_soAMachineThatBreaksAgainIsAlertedAgain() {
        adapter().markAlerted(APALVEIEN);
        adapter().markAlerted(NUC);

        adapter().clear(APALVEIEN);

        assertThat(adapter().wasAlerted(APALVEIEN)).isFalse();
        assertThat(adapter().wasAlerted(NUC)).isTrue();
    }

    @Test
    void aMalformedFile_isNotAnError_itJustMeansNoLatchYet() throws Exception {
        // Tolerant on load like the other file adapters, and the tolerance errs the safe way: losing the
        // latch means re-alerting about a machine that is genuinely broken, never going quiet about it.
        Files.writeString(configDir.resolve("missing-default-routes.yml"), "\t: not: yaml: at: all\n[");

        assertThat(adapter().wasAlerted(APALVEIEN)).isFalse();
    }
}
