package net.vaier.rest;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.BlockNotLiftedException;
import net.vaier.domain.ClaudeSignInFailedException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.DiskUnreadableException;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NoSftpSubsystemException;
import net.vaier.domain.NoSshServerException;
import net.vaier.domain.ArchivesUnreadableException;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.UnidentifiedDeviceException;
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
     * Vaier could not read a repository's archives. {@code 502}, not {@code 500}: the failure is upstream —
     * borg, on the machine, talking to the backup server — and Vaier is relaying its words rather than
     * having gone wrong itself. Emphatically not an empty {@code 200}, which is what it used to be, and
     * which reads as "this repository holds nothing" on the screen an operator opens to get a file back.
     */
    @ExceptionHandler(ArchivesUnreadableException.class)
    public ResponseEntity<ApiError> handleArchivesUnreadable(ArchivesUnreadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiError.of("ARCHIVES_UNREADABLE", e.getMessage()));
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

    /**
     * Vaier cannot tell which device is reporting its position: no peer's tunnel IP, no device claim.
     * {@code 403} rather than {@code 404} — the caller is authenticated, it simply has not established
     * which of the operator's devices it is, and the fix is to claim this browser.
     */
    @ExceptionHandler(UnidentifiedDeviceException.class)
    public ResponseEntity<ApiError> handleUnidentifiedDevice(UnidentifiedDeviceException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError.of("UNIDENTIFIED_DEVICE", e.getMessage()));
    }

    /**
     * <b>Chat</b> was reached for while no <b>Anthropic API key</b> is stored (#360). A state conflict, like
     * every other {@code 409} here: nothing is broken, the one thing Chat needs is simply not there yet. It
     * carries the domain's own sentence, which names the fix, and a code of its own so the pane can offer
     * the Settings field rather than printing prose.
     */
    @ExceptionHandler(ChatUnavailableException.class)
    public ResponseEntity<ApiError> handleAskUnavailable(ChatUnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("ASK_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", e.getMessage()));
    }

    /**
     * A machine with no stored SSH credential is a configuration gap the operator must close, not a Vaier
     * fault: {@code 424 Failed Dependency} reads as "this needs a credential that isn't there yet". The
     * machine name rides in {@code detail} so the browser can offer the fix for that exact machine, and the
     * message says what to do. Registered explicitly — otherwise it falls through to the generic {@code 500}
     * and the browse dead-ends on "an unexpected error occurred", telling the operator nothing they can act on.
     */
    @ExceptionHandler(NoHostCredentialException.class)
    public ResponseEntity<ApiError> handleNoHostCredential(NoHostCredentialException e) {
        String message = "No SSH credential is stored for \"" + e.machineName() + "\". Add one to browse its files.";
        return ResponseEntity.status(HttpStatus.FAILED_DEPENDENCY)
                .body(ApiError.of("NO_CREDENTIAL", message, e.machineName()));
    }

    /**
     * A stored credential the host rejects is the far side refusing the login — {@code 502}, like an
     * unreadable disk, never a generic {@code 500}. The raw message can carry the SSH user and host, so it is
     * logged server-side but never returned; the operator is told to check the credential Vaier holds instead.
     */
    @ExceptionHandler(SshAuthException.class)
    public ResponseEntity<ApiError> handleSshAuth(SshAuthException e) {
        log.warn("SSH auth rejected by host: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("SSH_AUTH_FAILED", "The SSH credential Vaier holds was rejected — check it."));
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
     * The machine answers SSH and simply cannot serve files — its SSH server has no SFTP subsystem (#344).
     * {@code 502}: the far side answered, so the fault is neither Vaier's nor the operator's request; it is a
     * fact about that machine. A code of its own because the operator's next move is unlike any other
     * failure's — install a package, or change SSH servers. The sentence is the domain's own, remedy and
     * all: what to do about a machine is knowledge about the machine, so this handler maps status and code
     * and adds no words of its own. The machine rides in {@code detail}, as
     * {@link NoHostCredentialException}'s does, so the browser can offer the fix for that exact machine.
     * Registered explicitly: unmapped, this was the generic {@code 500} that made Vaier look broken while
     * it knew precisely what was wrong.
     */
    @ExceptionHandler(NoSftpSubsystemException.class)
    public ResponseEntity<ApiError> handleNoSftpSubsystem(NoSftpSubsystemException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("NO_SFTP_SUBSYSTEM", e.getMessage(), e.machineName()));
    }

    /**
     * Vaier could not reach a machine over SSH at all — unreachable, or a handshake that timed out.
     * {@code 502} for the same reason an unreadable disk is: the failure is on the far side of Vaier. The
     * message is returned as it stands, like {@link DiskUnreadableException}'s — it names a host and a path
     * the operator is already looking at on screen, so there is nothing in it they do not already know.
     * (Deliberately unlike {@link SshAuthException} below, whose raw text can carry the SSH user.)
     *
     * <p>Registered explicitly because it was not, and every SSH transport failure therefore reached the
     * browser as "an unexpected error occurred" — the exact dead-end the README promises the Explorer never
     * takes.
     */
    @ExceptionHandler(SshConnectException.class)
    public ResponseEntity<ApiError> handleSshConnect(SshConnectException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("SSH_UNREACHABLE", e.getMessage()));
    }

    /**
     * The machine is up and answered the TCP handshake — it actively <b>refused</b> the connection, rather
     * than timing out — but nothing is listening on its SSH port at all. Found live: Roon kjøkken with its
     * SSH server deliberately uninstalled reads as an ordinary {@code SshConnectException} ("Connection
     * refused") unless the domain is asked, which sends the operator hunting a network fault that is not
     * there. {@code 502} for the same reason every far-side refusal is; the sentence is the domain's own,
     * remedy included, so the Explorer and the web terminal both say the same actionable thing rather than
     * one of them inventing its own words for it.
     */
    @ExceptionHandler(NoSshServerException.class)
    public ResponseEntity<ApiError> handleNoSshServer(NoSshServerException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("NO_SSH_SERVER", e.getMessage(), e.machineName()));
    }

    /**
     * A Claude sign-in could not be carried out on a machine — Claude Code is not installed there, or the
     * CLI never printed an authorization URL Vaier could read.
     *
     * <p>The second case is why this mapping exists rather than letting the catch-all take it. Reading a
     * URL out of a program's terminal output is screen-scraping, and when screen-scraping breaks the
     * operator has to be told exactly that, together with the way round it that always works — opening a
     * terminal on the machine and running {@code claude} by hand. A generic {@code 500} would hide the one
     * sentence that makes the failure recoverable. {@code 502}, like every other far-side refusal, and the
     * message is the domain's own; it never carries a URL, a code or a token, because a sign-in produces
     * nothing Vaier is permitted to keep, let alone put in an error.
     */
    @ExceptionHandler(ClaudeSignInFailedException.class)
    public ResponseEntity<ApiError> handleClaudeSignInFailed(ClaudeSignInFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("CLAUDE_SIGN_IN_FAILED", e.getMessage()));
    }

    /**
     * The machine presented a host key that is not the one Vaier pinned. The web terminal has always shown
     * this on its own socket; every other path that reaches a machine — files, disks, containers — went
     * through the catch-all instead, hiding a refusal that is either a rebuilt host or a man in the middle
     * behind a generic {@code 500}. {@code 502}, like the other far-side refusals, carrying the domain's own
     * message: it names the machine, both fingerprints and the way out (clear the pin and reconnect).
     *
     * <p>The fingerprints also travel in {@code detail} as data (#345). The message states them, but it
     * states them inside a sentence written for a person — and the <b>Clear pinned key</b> dialog has to
     * <em>show</em> both, side by side, so the operator asserting "I changed this machine" is asserting
     * something informed. A client that had to recover them by parsing prose would break the first time
     * the wording changed. Both are public host-key fingerprints, safe to hand an authenticated operator.
     */
    @ExceptionHandler(HostKeyMismatchException.class)
    public ResponseEntity<ApiError> handleHostKeyMismatch(HostKeyMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("HOST_KEY_MISMATCH", e.getMessage(),
                        "pinned=" + e.getPinnedFingerprint() + ";presented=" + e.getPresentedFingerprint()));
    }

    /**
     * Vaier asked CrowdSec to let an address back in and could not tell that it had (#329 Slice 3c).
     * {@code 502} like every other far-side refusal: the engine that owns the ban is on the other side of
     * Vaier. Mapped explicitly, and carrying the domain's own sentence, because the generic {@code 500}
     * would read as "Vaier is broken" to an operator whose real question is the far narrower "am I back
     * in?" — and because the one thing this must never be is silence that looks like success.
     */
    @ExceptionHandler(BlockNotLiftedException.class)
    public ResponseEntity<ApiError> handleBlockNotLifted(BlockNotLiftedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("BLOCK_NOT_LIFTED", e.getMessage()));
    }

    /**
     * Vaier could not find out who CrowdSec is currently keeping out (#329 Slice 3). {@code 502} like its
     * sibling above: the engine that owns the bans is on the far side of Vaier.
     *
     * <p>What this replaces is worse than a wrong status code. The read used to swallow its failures and
     * answer {@code 200 []}, which the security view renders as "Nobody is blocked right now" — so a Vaier
     * that could not reach CrowdSec told the operator their fleet was unattacked. Found live: the first read
     * after a container restart failed cold while {@code cscli} listed eleven active decisions. A security
     * screen may say "I could not ask"; it may never say "all clear" on that evidence.
     */
    @ExceptionHandler(BlockDecisionsUnreadableException.class)
    public ResponseEntity<ApiError> handleBlockDecisionsUnreadable(BlockDecisionsUnreadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of("BLOCK_DECISIONS_UNREADABLE", e.getMessage()));
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

    /**
     * The catch-all. One special case matters for streaming responses (#321): a directory download that fails
     * partway through has <em>already</em> committed its bytes and its {@code application/zip} content-type to
     * the wire. There is nothing left to write — serialising an {@link ApiError} over a committed zip response
     * only throws {@code HttpMessageNotWritableException}, which buries the real failure and cannot reach the
     * browser anyway. So when the response is already committed, the handler returns {@code null} (handled, no
     * body) and lets the stream end; otherwise it reports the usual safe, generic {@code 500}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("Request failed after the response was committed; ending the stream ({})", e.toString());
            return null;
        }
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
