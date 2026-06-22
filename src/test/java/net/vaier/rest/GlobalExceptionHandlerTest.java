package net.vaier.rest;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.DeleteDnsRecordUseCase;
import net.vaier.application.DeleteDnsZoneUseCase;
import net.vaier.application.GetDnsInfoUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting tests for {@link GlobalExceptionHandler}. Wires the advice onto a real,
 * uncaught endpoint via standalone MockMvc — {@code POST /dns/zones} is a void method
 * that catches nothing, so it proves the advice maps exceptions to a uniform envelope.
 */
class GlobalExceptionHandlerTest {

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
    void malformedRequestBody_staysClientError_notInternalError() throws Exception {
        // Spring maps an unreadable body to 400. The generic fallback must NOT swallow
        // Spring's own MVC exceptions and turn a client error into a 500.
        mockMvc.perform(post("/dns/zones")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{ this is not json"))
               .andExpect(status().is4xxClientError());
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
               // the internal message (and any secret in it) must NOT leak to the client
               .andExpect(jsonPath("$.message").value(not(containsString("AKIAEXAMPLE"))))
               .andExpect(jsonPath("$.message").value(not(containsString("10.0.0.5"))));
    }
}
