package net.vaier.rest;

import net.vaier.application.DeleteHostCredentialUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.SaveHostCredentialUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HostCredentialRestControllerTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Mock SaveHostCredentialUseCase saveHostCredentialUseCase;
    @Mock GetHostCredentialUseCase getHostCredentialUseCase;
    @Mock DeleteHostCredentialUseCase deleteHostCredentialUseCase;

    @InjectMocks HostCredentialRestController controller;

    @Test
    void put_savesCredentialBuiltFromPathAndBody_returnsRedactedView() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "PASSWORD", "s3cret", null);

        ResponseEntity<HostCredentialRestController.CredentialResponse> response =
            controller.save("nas", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().hasSecret()).isTrue();
        // The controller hands on the machine's name and the operator's draft; turning that pair into a
        // credential keyed by identity is the application's decision, not the controller's.
        ArgumentCaptor<net.vaier.domain.SshCredentialDraft> captor =
            ArgumentCaptor.forClass(net.vaier.domain.SshCredentialDraft.class);
        verify(saveHostCredentialUseCase).saveHostCredential(eq("nas"), captor.capture());
        net.vaier.domain.SshCredentialDraft saved = captor.getValue();
        assertThat(saved.username()).isEqualTo("admin");
        assertThat(saved.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(saved.secret()).isEqualTo("s3cret");
    }

    @Test
    void put_invalidAuthMethod_propagatesIllegalArgument() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "BANANA", "s3cret", null);

        assertThatThrownBy(() -> controller.save("nas", request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    /**
     * A blank secret is still rejected, just no longer by the controller: it assembles a draft and the
     * application turns that into the credential, where the domain's invariant lives. The controller's
     * job here is not to swallow it — the exception must reach GlobalExceptionHandler as a 400.
     */
    void put_blankSecret_propagatesIllegalArgumentFromTheUseCase() {
        var request = new HostCredentialRestController.SaveCredentialRequest(
            "admin", "PASSWORD", "  ", null);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("secret must not be blank"))
            .when(saveHostCredentialUseCase).saveHostCredential(eq("nas"), any());

        assertThatThrownBy(() -> controller.save("nas", request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_present_returnsRedactedView() {
        when(getHostCredentialUseCase.getHostCredential("nas"))
            .thenReturn(Optional.of(new HostCredentialView(mid("nas"), "admin", AuthMethod.PASSWORD, true)));

        ResponseEntity<HostCredentialRestController.CredentialResponse> response = controller.get("nas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().username()).isEqualTo("admin");
        assertThat(response.getBody().hasSecret()).isTrue();
    }

    @Test
    void get_absent_returns404() {
        when(getHostCredentialUseCase.getHostCredential("ghost")).thenReturn(Optional.empty());

        assertThat(controller.get("ghost").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_returns204() {
        ResponseEntity<Void> response = controller.delete("nas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(deleteHostCredentialUseCase).deleteHostCredential("nas");
    }

    @Test
    void put_thenGet_responseBodyNeverCarriesSecretBytes() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(put("/machines/nas/ssh-credential")
                .contentType("application/json")
                .content("""
                    {"username":"admin","authMethod":"PRIVATE_KEY",
                     "secret":"-----BEGIN KEY-----secret-body-----END KEY-----","passphrase":"topsecretphrase"}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-body"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("topsecretphrase"))))
            .andExpect(jsonPath("$.hasSecret").value(true));

        when(getHostCredentialUseCase.getHostCredential("nas"))
            .thenReturn(Optional.of(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true)));

        mockMvc.perform(get("/machines/nas/ssh-credential"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasSecret").value(true))
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-body"))));
    }
}
