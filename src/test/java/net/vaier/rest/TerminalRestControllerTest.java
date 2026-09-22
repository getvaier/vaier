package net.vaier.rest;

import net.vaier.application.EndTerminalSessionUseCase;
import net.vaier.application.ListPersistentShellsUseCase;
import net.vaier.domain.MachineId;
import net.vaier.domain.RunningShell;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shells already running on a machine (#322): Vaier creates persistent shells but could never enumerate
 * them, so a pane id lost anywhere — a cleared browser, another device — was a shell lost forever.
 */
class TerminalRestControllerTest {

    private final ListPersistentShellsUseCase listPersistentShells = mock(ListPersistentShellsUseCase.class);
    private final EndTerminalSessionUseCase endTerminalSession = mock(EndTerminalSessionUseCase.class);
    private final TerminalRestController controller =
        new TerminalRestController(listPersistentShells, endTerminalSession);
    private static final String M1 = "3826a934-1d23-4bc0-9f1e-0c2d4e6f8a10";

    @Test
    void listShells_saysWhatEachShellRuns_andHowLongInSeconds() {
        when(listPersistentShells.listShells(MachineId.of(M1))).thenReturn(List.of(
            new RunningShell("p1", "claude", Duration.ofHours(3), Duration.ofMinutes(19), false)));

        List<TerminalRestController.RunningShellResponse> body = controller.listShells(M1);

        assertThat(body).hasSize(1);
        assertThat(body.get(0).paneId()).isEqualTo("p1");
        assertThat(body.get(0).running()).isEqualTo("claude");
        assertThat(body.get(0).ageSeconds()).isEqualTo(10800);
        assertThat(body.get(0).sinceAttachedSeconds()).isEqualTo(1140);
        assertThat(body.get(0).attached()).isFalse();
    }

    @Test
    void endShell_endsThatPanesShell_andReturnsNoContent() {
        var response = controller.endShell(M1, "p1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(endTerminalSession).endTerminal(MachineId.of(M1), "p1");
    }
}
