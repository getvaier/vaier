package net.vaier.rest;

import net.vaier.domain.NotFoundException;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NoSftpSubsystemException;
import net.vaier.domain.NoSshServerException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.UnidentifiedDeviceException;
import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.application.GetReverseProxyRoutesUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting tests for {@link GlobalExceptionHandler}. The MockMvc cases wire the
 * advice onto a real, uncaught endpoint via standalone MockMvc — {@code POST /reverse-proxy/routes}
 * is a void method that catches nothing, so it proves the advice maps exceptions to a
 * uniform envelope. The framework-exception branch is exercised directly because a 5xx
 * MVC exception is awkward to provoke through dispatch.
 */
class GlobalExceptionHandlerTest {

    static final String GENERIC_MESSAGE = "An unexpected error occurred. Please try again.";

    AddReverseProxyRouteUseCase addReverseProxyRouteUseCase =
            Mockito.mock(AddReverseProxyRouteUseCase.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReverseProxyRestController controller = new ReverseProxyRestController(
                addReverseProxyRouteUseCase,
                Mockito.mock(DeleteReverseProxyRouteUseCase.class),
                Mockito.mock(GetReverseProxyRoutesUseCase.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void illegalArgument_isMappedTo400WithUniformEnvelope() throws Exception {
        doThrow(new IllegalArgumentException("dnsName must be a valid domain"))
                .when(addReverseProxyRouteUseCase).addReverseProxyRoute(any());

        mockMvc.perform(post("/reverse-proxy/routes")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"dnsName":"not a domain","address":"10.0.0.5","port":8080,"requiresAuth":false}
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("dnsName must be a valid domain"));
    }

    @Test
    void malformedRequestBody_staysClientError_andUsesTheEnvelope() throws Exception {
        // Spring maps an unreadable body to 400. The generic fallback must NOT swallow
        // Spring's own MVC exceptions and turn a client error into a 500 — and the
        // response must still be rendered in the ApiError envelope.
        mockMvc.perform(post("/reverse-proxy/routes")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{ this is not json"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    void unexpectedException_isMappedTo500WithSafeGenericMessage() throws Exception {
        doThrow(new RuntimeException("Traefik write timeout at 10.0.0.5 using secret AKIAEXAMPLE"))
                .when(addReverseProxyRouteUseCase).addReverseProxyRoute(any());

        mockMvc.perform(post("/reverse-proxy/routes")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"dnsName":"app.example.com","address":"10.0.0.5","port":8080,"requiresAuth":false}
                           """))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
               // the safe generic message is the exact contract — and the internal
               // message (with its secret/IP) must NOT leak to the client
               .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE))
               .andExpect(jsonPath("$.message").value(not(containsString("AKIAEXAMPLE"))))
               .andExpect(jsonPath("$.message").value(not(containsString("10.0.0.5"))));
    }

    @Test
    void frameworkException_mappedTo5xx_usesSafeGenericEnvelope_notTheStatusReason() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("write failed at 10.0.0.9 token AKIASECRET"),
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR,
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ApiError body = (ApiError) response.getBody();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(body.message()).doesNotContain("AKIASECRET").doesNotContain("10.0.0.9");
    }

    @Test
    void whenTheResponseIsAlreadyCommitted_noErrorBodyIsWritten_soAStreamedDownloadIsNotCorrupted() {
        // A streaming zip download that fails partway through (#321): its bytes and zip content-type are
        // already on the wire, so there is nothing to write. Trying to serialise an ApiError over a committed
        // zip response throws HttpMessageNotWritableException and buries the real failure. The handler must
        // return null — handled, no body — and let the stream simply end.
        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.setCommitted(true);

        ResponseEntity<ApiError> result = new GlobalExceptionHandler()
                .handleUnexpected(new RuntimeException("SSH dropped mid-stream"), committed);

        assertThat(result).isNull();
    }

    @Test
    void anUncommittedUnexpectedException_stillProducesTheSafe500Envelope() {
        MockHttpServletResponse open = new MockHttpServletResponse();

        ResponseEntity<ApiError> result = new GlobalExceptionHandler()
                .handleUnexpected(new RuntimeException("boom"), open);

        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getBody().message()).isEqualTo(GENERIC_MESSAGE);
    }

    @Test
    void notFoundException_mappedTo404Envelope() {
        ResponseEntity<ApiError> response =
                new GlobalExceptionHandler().handleNotFound(new NotFoundException("peer gone"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("peer gone");
    }

    @Test
    void conflictException_mappedTo409Envelope() {
        ResponseEntity<ApiError> response =
                new GlobalExceptionHandler().handleConflict(new ConflictException("name taken"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("name taken");
    }

    @Test
    void noHostCredentialException_mappedTo424_withMachineNameAndActionableMessage() {
        // A machine with no stored SSH credential is something the operator must configure, not a Vaier fault:
        // 424 Failed Dependency reads as "this needs a credential that isn't there yet". The machine name rides
        // in `detail` so the browser can offer the fix for that exact machine, and the message says what to do.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleNoHostCredential(new NoHostCredentialException("Vaier server"));

        assertThat(response.getStatusCode().value()).isEqualTo(424);
        assertThat(response.getBody().code()).isEqualTo("NO_CREDENTIAL");
        assertThat(response.getBody().detail()).isEqualTo("Vaier server");
        assertThat(response.getBody().message()).isEqualTo(
                "No SSH credential is stored for \"Vaier server\". Add one to browse its files.");
    }

    @Test
    void sshAuthException_mappedTo502_withASafeCheckTheCredentialMessage_notThe500Generic() {
        // A stored credential the host rejects is the far side refusing the login — 502, like an unreadable disk,
        // never a generic 500. The raw message can carry the username and host, so it must NOT leak; the operator
        // is told to check the credential instead.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleSshAuth(new SshAuthException("Authentication failed for geir@10.13.13.6"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("SSH_AUTH_FAILED");
        assertThat(response.getBody().message()).isEqualTo(
                "The SSH credential Vaier holds was rejected — check it.");
        assertThat(response.getBody().message()).doesNotContain("10.13.13.6").doesNotContain("geir");
    }

    @Test
    void noSftpSubsystemException_mappedTo502_namingTheMachineAndTheFix() {
        // #344: a machine whose SSH server has no SFTP subsystem (DietPi's Dropbear) used to dead-end the
        // browse on the generic 500. 502 is the honest status — the far side answered, it simply cannot serve
        // files — and the sentence must name both the machine and the operator's next move. The machine rides
        // in `detail` too, as NoHostCredentialException's does, so the browser can offer the fix for that one.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleNoSftpSubsystem(new NoSftpSubsystemException("Roon loftstue"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("NO_SFTP_SUBSYSTEM");
        assertThat(response.getBody().detail()).isEqualTo("Roon loftstue");
        assertThat(response.getBody().message())
                .contains("Roon loftstue")
                .contains("does not offer SFTP")
                .contains("openssh-sftp-server");
    }

    @Test
    void sshConnectException_mappedTo502_withItsOwnMessage_notThe500Generic() {
        // The reported bug (#344): SshConnectException was unmapped and fell to the Exception catch-all, so
        // every SSH transport failure read as "an unexpected error occurred". Its message names a host and a
        // path the operator is already looking at — safe to return, exactly like DiskUnreadableException's.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleSshConnect(new SshConnectException("Could not list / on 192.168.3.106 (Connection refused)"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("SSH_UNREACHABLE");
        assertThat(response.getBody().message())
                .isEqualTo("Could not list / on 192.168.3.106 (Connection refused)");
    }

    @Test
    void noSshServerException_mappedTo502_namingTheMachineAndTheFix() {
        // Found live: Roon kjøkken with its SSH server deliberately uninstalled used to read as an ordinary
        // "Connection refused" transport failure -- accurate, but it sends the operator looking for a
        // network fault that is not there. 502 is the honest status -- the machine answered the TCP
        // handshake itself -- and the sentence must name both the machine and the actual remedy.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleNoSshServer(new NoSshServerException("Roon kjøkken", 22));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("NO_SSH_SERVER");
        assertThat(response.getBody().detail()).isEqualTo("Roon kjøkken");
        assertThat(response.getBody().message())
                .contains("Roon kjøkken")
                .contains("does not answer SSH")
                .contains("Install and start an SSH server");
    }

    /**
     * The browser's lapsed-claim recovery keys on this exact status: a 403 tells it the claim it holds
     * is dead, so it offers Claim again. Mapped to 400 or a generic 500 it would silently go on showing
     * a device as claimed when it is not — so the status is contract, not an implementation detail.
     */
    @Test
    void unidentifiedDeviceException_mappedTo403_soTheBrowserCanRecoverALapsedClaim() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler()
                .handleUnidentifiedDevice(
                    UnidentifiedDeviceException.becauseNothingIdentifiesTheDevice());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo("UNIDENTIFIED_DEVICE");
        assertThat(response.getBody().message())
                .contains("cannot tell which device")
                .contains("tunnel")
                .contains("device claim");
    }

    @Test
    void hostKeyMismatchException_mappedTo502_withItsOwnActionableMessage() {
        // Found by the sibling audit for #344: HostKeyMismatchException is a RuntimeException the web terminal
        // handles itself, but every REST path that reaches a machine (files, disks, containers) can raise it —
        // and there it fell to the catch-all, hiding a refusal that is either a rebuilt host or a man in the
        // middle behind "an unexpected error occurred". Its message is already written for the operator.
        ResponseEntity<ApiError> response = new GlobalExceptionHandler().handleHostKeyMismatch(
                new HostKeyMismatchException("Roon server", "SHA256:old", "SHA256:new"));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("HOST_KEY_MISMATCH");
        assertThat(response.getBody().message())
                .contains("Roon server")
                .contains("clear its pinned key");
        // #345: the two fingerprints survive as data, not only inside the sentence. The Clear-pinned-key
        // dialog shows both so the operator's assertion ("I changed this machine") is informed — and it
        // must read them, never parse them back out of prose written for a human.
        assertThat(response.getBody().detail()).isEqualTo("pinned=SHA256:old;presented=SHA256:new");
    }

    @Test
    void frameworkException_mappedTo4xx_usesStatusReasonInTheEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("ignored"),
                null, new HttpHeaders(), HttpStatus.METHOD_NOT_ALLOWED,
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        ApiError body = (ApiError) response.getBody();
        assertThat(body.code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(body.message()).isEqualTo("Method Not Allowed");
    }

    /**
     * Ask reached for while no <b>Anthropic API key</b> is stored (#360). Nothing is broken — the one thing
     * Chat needs simply is not there — so it is a {@code 409} carrying the domain's own sentence, which names
     * the fix. A generic {@code 500} would read as "Vaier is broken" and send the operator hunting a fault
     * that does not exist.
     */
    @Test
    void askUnavailableException_mappedTo409_withTheSentenceThatNamesTheFix() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler().handleAskUnavailable(
                new ChatUnavailableException("Chat needs an Anthropic API key. Add one in Settings."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("ASK_UNAVAILABLE");
        assertThat(response.getBody().message())
                .isEqualTo("Chat needs an Anthropic API key. Add one in Settings.");
    }
}
