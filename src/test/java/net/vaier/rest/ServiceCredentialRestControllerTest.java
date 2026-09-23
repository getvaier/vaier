package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.ClearSharedServiceCredentialUseCase;
import net.vaier.application.GetServiceCredentialsUseCase;
import net.vaier.application.RemovePersonalServiceCredentialUseCase;
import net.vaier.application.SetPersonalServiceCredentialUseCase;
import net.vaier.application.SetSharedServiceCredentialUseCase;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Role;
import net.vaier.domain.ServiceCredential;
import net.vaier.domain.ServiceCredentials;
import net.vaier.rest.ServiceCredentialRestController.CredentialRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceCredentialRestControllerTest {

    @Mock GetServiceCredentialsUseCase getServiceCredentialsUseCase;
    @Mock SetSharedServiceCredentialUseCase setSharedServiceCredentialUseCase;
    @Mock ClearSharedServiceCredentialUseCase clearSharedServiceCredentialUseCase;
    @Mock SetPersonalServiceCredentialUseCase setPersonalServiceCredentialUseCase;
    @Mock RemovePersonalServiceCredentialUseCase removePersonalServiceCredentialUseCase;

    @InjectMocks ServiceCredentialRestController controller;

    @Test
    void list_saysWhoIsSignedInAsWhom_andNeverCarriesAPassword() throws Exception {
        ReverseProxyRoute openhab = ReverseProxyRoute.builder().name("openhab-router")
            .domainName("openhab.example.com").middlewares(AuthMode.SOCIAL.authMiddlewareNames()).build();
        when(getServiceCredentialsUseCase.getServiceCredentials()).thenReturn(ServiceCredentials.empty()
            .withShared("openhab.example.com", new ServiceCredential("house", "shared-secret-pw"), List.of(openhab))
            .withPersonal("openhab.example.com", "turid@example.com", new ServiceCredential("turid", "turids-secret-pw"),
                List.of(openhab), List.of(AccessEntry.builder().email("turid@example.com").role(Role.USER).build())));

        String json = new ObjectMapper().writeValueAsString(controller.list());

        assertThat(json).isEqualTo("{\"openhab.example.com\":{\"sharedUsername\":\"house\","
            + "\"people\":[{\"email\":\"turid@example.com\",\"username\":\"turid\"}]}}");
    }

    @Test
    void writesHandTheirPathAndBodyToTheUseCases() {
        controller.setShared("openhab.example.com", new CredentialRequest("house", "pw"));
        controller.clearShared("openhab.example.com");
        controller.setPersonal("openhab.example.com", "turid@example.com", new CredentialRequest("turid", "pw2"));
        controller.removePersonal("openhab.example.com", "turid@example.com");

        verify(setSharedServiceCredentialUseCase).setSharedServiceCredential("openhab.example.com", "house", "pw");
        verify(clearSharedServiceCredentialUseCase).clearSharedServiceCredential("openhab.example.com");
        verify(setPersonalServiceCredentialUseCase)
            .setPersonalServiceCredential("openhab.example.com", "turid@example.com", "turid", "pw2");
        verify(removePersonalServiceCredentialUseCase)
            .removePersonalServiceCredential("openhab.example.com", "turid@example.com");
    }
}
