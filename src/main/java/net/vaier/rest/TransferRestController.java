package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetTransfersUseCase;
import net.vaier.application.StartTransferUseCase;
import net.vaier.domain.Transfer;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * The Explorer's Clipboard, server side (#321, slice 2): start a cross-machine {@link Transfer}, read the
 * ones in flight (plus a capped settled tail), and stream their progress. Every path here is
 * non-whitelisted, so it sits under the admin auth chain like every other machine endpoint — moving a
 * fleet's files is never anonymous.
 *
 * <p>The relay itself lives in {@link TransferRunner}; the controller only reaches it through the
 * {@code *UseCase} seams. A bad request (a non-absolute path, or a no-op onto a file's own live coordinate)
 * surfaces as {@link IllegalArgumentException} from the domain → {@code 400} via
 * {@link GlobalExceptionHandler}. The destination is always the present — the request carries a time
 * coordinate only for the <em>source</em> (a restore), never the destination.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TransferRestController {

    private final StartTransferUseCase startTransfer;
    private final GetTransfersUseCase getTransfers;
    private final ForSubscribingToEvents forSubscribingToEvents;

    /**
     * Start a transfer. {@code destPath} is the destination <em>directory</em> the item is copied into; the
     * item keeps its basename. Returns the {@code RUNNING} transfer at once — it settles later, and the
     * browser learns the outcome over {@link #transferEvents()}.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> start(@RequestBody StartTransferRequest request) {
        log.info("Transfer requested: {}:{} (at {}) -> {}:{}",
            LogSafe.forLog(request.sourceMachine()), LogSafe.forLog(request.sourcePath()),
            LogSafe.forLog(request.at()), LogSafe.forLog(request.destMachine()),
            LogSafe.forLog(request.destPath()));
        Transfer transfer = startTransfer.startTransfer(request.sourceMachine(), request.sourcePath(),
            request.at(), request.destMachine(), request.destPath());
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }

    /** Live and recently-settled transfers, so the browser can repaint the Clipboard on load or reconnect. */
    @GetMapping("/transfers")
    public ResponseEntity<List<TransferResponse>> list() {
        return ResponseEntity.ok(getTransfers.getTransfers().stream().map(TransferResponse::from).toList());
    }

    /**
     * The Clipboard's SSE stream. The frontend never polls: it opens this and reacts to pushed
     * {@code transfer-progress} and {@code transfer-settled} events. Mirrors {@code /backup-jobs/events}.
     */
    @GetMapping(value = "/transfers/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter transferEvents() {
        return forSubscribingToEvents.subscribe(TransferRunner.TRANSFERS_TOPIC);
    }

    /**
     * A request to start a transfer. {@code at} names the source archive for a restore, or is null for a
     * live-source copy; there is no destination time coordinate — you cannot paste into the past.
     */
    record StartTransferRequest(String sourceMachine, String sourcePath, String at,
                                String destMachine, String destPath) {}

    /** A transfer as the browser sees it — the same shape the POST returns and the GET lists. */
    record TransferResponse(String id, String sourceMachine, String sourcePath, String destMachine,
                            String destPath, String state, long bytesCopied, Long totalBytes, String error) {
        static TransferResponse from(Transfer t) {
            return new TransferResponse(t.id(), t.sourceMachine(), t.sourcePath(), t.destMachine(),
                t.destPath(), t.state().name(), t.bytesCopied(), t.totalBytes(), t.error());
        }
    }
}
