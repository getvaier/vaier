package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ConflictException;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.NotFoundException;
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

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", e.getMessage()));
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
