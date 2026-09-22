package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.EndTerminalSessionUseCase;
import net.vaier.application.ListPersistentShellsUseCase;
import net.vaier.domain.MachineId;
import net.vaier.domain.RunningShell;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The persistent shells already running on a machine, and the way to end one from outside its own window.
 * The shells themselves are opened over the WebSocket ({@link TerminalWebSocketHandler}); this is the read
 * beside it that lets a window say what else is running on the machine it is looking at (#322).
 */
@RestController
@RequestMapping("/machines/{machineId}/shells")
@RequiredArgsConstructor
@Slf4j
public class TerminalRestController {

    private final ListPersistentShellsUseCase listPersistentShellsUseCase;
    private final EndTerminalSessionUseCase endTerminalSessionUseCase;

    public record RunningShellResponse(String paneId, String running, long ageSeconds, long sinceAttachedSeconds,
                                       boolean attached) {
        static RunningShellResponse of(RunningShell s) {
            return new RunningShellResponse(s.paneId(), s.running(), s.age().toSeconds(),
                s.sinceAttached().toSeconds(), s.attached());
        }
    }

    @GetMapping
    public List<RunningShellResponse> listShells(@PathVariable String machineId) {
        return listPersistentShellsUseCase.listShells(MachineId.of(machineId)).stream()
            .map(RunningShellResponse::of)
            .toList();
    }

    @DeleteMapping("/{paneId}")
    public ResponseEntity<Void> endShell(@PathVariable String machineId, @PathVariable String paneId) {
        log.info("Ending shell {} on {} from outside its window", LogSafe.forLog(paneId), LogSafe.forLog(machineId));
        endTerminalSessionUseCase.endTerminal(MachineId.of(machineId), paneId);
        return ResponseEntity.noContent().build();
    }
}
