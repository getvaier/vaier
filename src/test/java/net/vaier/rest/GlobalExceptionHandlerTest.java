package net.vaier.rest;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.DeleteDnsRecordUseCase;
import net.vaier.application.DeleteDnsZoneUseCase;
import net.vaier.application.GetDnsInfoUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
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
 * advice onto a real, uncaught endpoint via standalone MockMvc — {@code POST /dns/zones}
 * is a void method that catches nothing, so it proves the advice maps exceptions to a
 * uniform envelope. The framework-exception branch is exercised directly because a 5xx
 * MVC exception is awkward to provoke through dispatch.
 */
class GlobalExceptionHandlerTest {

    static final String GENERIC_MESSAGE = "An unexpected error occurred. Please try again.";

    AddDnsZoneUseCase addDnsZoneUseCase = Mockito.mock(AddDnsZoneUseCase.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DnsRestController controller = new DnsRestController(
                Mockito.mock(GetDnsInfoUseCase.class),
                Mockito.mock(AddDnsRecordUseCase.class),
                addDnsZoneUseCase,
                Mockito.mock(DeleteDnsRecordUseCase.class),
                Mockito.mock(DeleteDnsZoneUseCase.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void illegalArgument_isMappedTo400WithUniformEnvelope() throws Exception {
        doThrow(new IllegalArgumentException("Zone name must be a valid domain"))
                .when(addDnsZoneUseCase).addDnsZone(any());

        mockMvc.perform(post("/dns/zones")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"not a domain"}
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("Zone name must be a valid domain"));
    }

    @Test
    void malformedRequestBody_staysClientError_andUsesTheEnvelope() throws Exception {
        // Spring maps an unreadable body to 400. The generic fallback must NOT swallow
        // Spring's own MVC exceptions and turn a client error into a 500 — and the
        // response must still be rendered in the ApiError envelope.
        mockMvc.perform(post("/dns/zones")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{ this is not json"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("Bad Request"));
    }

    @Test
    void unexpectedException_isMappedTo500WithSafeGenericMessage() throws Exception {
        doThrow(new RuntimeException("Route53 timeout at 10.0.0.5 using secret AKIAEXAMPLE"))
                .when(addDnsZoneUseCase).addDnsZone(any());

        mockMvc.perform(post("/dns/zones")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"name":"newzone.com"}
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
    void notFoundException_mappedTo404Envelope() {
        ResponseEntity<ApiError> response =
                new GlobalExceptionHandler().handleNotFound(new net.vaier.domain.NotFoundException("peer gone"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("peer gone");
    }

    @Test
    void conflictException_mappedTo409Envelope() {
        ResponseEntity<ApiError> response =
                new GlobalExceptionHandler().handleConflict(new net.vaier.domain.ConflictException("name taken"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("name taken");
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
}
