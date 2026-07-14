package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ConflictException;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.PermissionDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates uncaught exceptions from any controller into a uniform {@link ApiError}
 * envelope so the web UI can always show the operator <em>what went wrong</em> instead
 * of a bare status code or a leaked stack trace.
 *
 * <p>Domain validation throughout Vaier signals bad input by throwing
 * {@link IllegalArgumentException} with an operator-readable message — those messages
 * are surfaced verbatim as {@code 400}. Anything else is an unexpected failure: it is
 * logged in full server-side and reported to the client as a safe, generic {@code 500}
 * (the original message may contain hostnames, IPs, or credentials and must not leak).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring's own MVC exceptions
 * (malformed body, wrong method, unsupported media type, …) keep their correct {@code 4xx}
 * statuses rather than being collapsed into {@code 500} — but they are still rendered in
 * the same {@link ApiError} envelope via {@link #handleExceptionInternal}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred. Please try again.";

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * The path is a real path on the machine — {@code df} and the web terminal both see {@code /volume2} on
     * the NAS — but it lies above the root the machine's SFTP subsystem is chrooted into, so no SFTP call can
     * ever reach it. A {@code 400} carrying its own sentence, which names both the path and the root the
     * operator should ask under.
     *
     * <p>Mapped explicitly, ahead of the {@link IllegalArgumentException} it extends, so it arrives with a
     * code of its own: "you asked about the wrong half of this machine" is a different answer from "that is
     * not a path", and the browser is entitled to tell them apart. What it must <b>never</b> become is an
     * empty directory — answering "I cannot reach that" with "there is nothing there" is the very lie #326
     * exists to end.
     */
    @ExceptionHandler(PathOutsideSftpRootException.class)
    public ResponseEntity<ApiError> handlePathOutsideSftpRoot(PathOutsideSftpRootException e) {
        return ResponseEntity.badRequest().body(ApiError.of("PATH_OUTSIDE_SFTP_ROOT", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", e.getMessage()));
    }

    /**
     * The <em>machine</em> refused the read, not Vaier — the SSH user is not allowed to see that path. This is
     * an ordinary state of a fleet whose SSH users are deliberately not root, so it must read as "you cannot
     * read this", never as a Vaier fault.
     */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiError> handlePermissionDenied(PermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("PERMISSION_DENIED", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", e.getMessage()));
    }

    /**
     * Vaier reached for a machine's disk and could not read it — an asleep machine, a {@code df} that
     * exited non-zero, output that will not parse. Mapped explicitly to {@code 502} so it carries its own
     * sentence ("Vaier could not read the disk on ...") instead of falling through to the generic
     * {@code 500} "An unexpected error occurred", which would tell the operator nothing they can act on.
     * The gateway status is the honest one: the fault is on the far side of Vaier, not in it.
     */
    @ExceptionHandler(DiskUnreadableException.class)
    public ResponseEntity<ApiError> handleDiskUnreadable(DiskUnreadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("DISK_UNREADABLE", e.getMessage()));
    }

    /**
     * The last-admin invariant is a state conflict, mapped to {@code 409} carrying the operator-safe
     * message so the Access page can explain why the revoke/demotion was refused. Registered
     * explicitly because {@link LastAdminException} is an {@link IllegalStateException} — which
     * otherwise falls through to the generic {@code 500} handler.
     */
    @ExceptionHandler(LastAdminException.class)
    public ResponseEntity<ApiError> handleLastAdmin(LastAdminException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception serving request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE));
    }

    /**
     * Renders the framework's own handled MVC exceptions in the {@link ApiError} envelope.
     * Client errors ({@code 4xx}) keep Spring's status and reason phrase (operator-safe,
     * and never the raw exception text, which can carry parser/internal detail). Server
     * errors ({@code 5xx} — e.g. a response that fails to serialise) are treated exactly
     * like any other unexpected failure: logged in full and reported with the same safe
     * generic {@link #INTERNAL_ERROR_MESSAGE}, not the bare status reason.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled framework exception serving request", ex);
            return ResponseEntity.status(statusCode).headers(headers)
                    .body(ApiError.of(INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE));
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = (status != null) ? status.name() : "ERROR";
        String message = (status != null) ? status.getReasonPhrase() : "Request could not be processed.";
        return ResponseEntity.status(statusCode).headers(headers).body(ApiError.of(code, message));
    }
}
