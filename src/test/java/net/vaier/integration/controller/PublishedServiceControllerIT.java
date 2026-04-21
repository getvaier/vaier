package net.vaier.integration.controller;

import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase.PublishStatus;
import net.vaier.domain.DnsState;
import net.vaier.domain.Server.State;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublishedServiceControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void discover_returnsPublishedServices() throws Exception {
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of(
                new PublishedServiceUco(
                        "app", "app.example.com", DnsState.OK,
                        "10.13.13.2", 8080, State.OK, false, false, null, false)
        ));

        mockMvc.perform(get("/published-services/discover"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("app"))
               .andExpect(jsonPath("$[0].dnsAddress").value("app.example.com"))
               .andExpect(jsonPath("$[0].hostPort").value(8080));
    }

    @Test
    void discover_returnsEmptyList() throws Exception {
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of());

        mockMvc.perform(get("/published-services/discover"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void publishService_delegatesToUseCaseWithAllFields() throws Exception {
        mockMvc.perform(post("/published-services/publish")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "address":"10.13.13.2",
                             "port":8080,
                             "subdomain":"app",
                             "requiresAuth":true,
                             "rootRedirectPath":"/dashboard"
                           }
                           """))
               .andExpect(status().isOk());

        verify(publishPeerServiceUseCase).publishService(
                "10.13.13.2", 8080, "app", true, "/dashboard");
    }

    @Test
    void publishService_withoutOptionalFields_delegatesWithNullRedirectPath() throws Exception {
        mockMvc.perform(post("/published-services/publish")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "address":"10.13.13.2",
                             "port":8080,
                             "subdomain":"app",
                             "requiresAuth":false,
                             "rootRedirectPath":null
                           }
                           """))
               .andExpect(status().isOk());

        verify(publishPeerServiceUseCase).publishService("10.13.13.2", 8080, "app", false, null);
    }

    @Test
    void deleteService_delegatesToUseCase() throws Exception {
        mockMvc.perform(delete("/published-services/app.example.com"))
               .andExpect(status().isOk());

        verify(deletePublishedServiceUseCase).deleteService("app.example.com");
    }

    @Test
    void setAuth_togglesAuthentication() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com/auth")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"requiresAuth":true}
                           """))
               .andExpect(status().isOk());

        verify(toggleServiceAuthUseCase).setAuthentication("app.example.com", true);
    }

    @Test
    void setDirectUrlDisabled_togglesFlag() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com/direct-url-disabled")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"directUrlDisabled":true}
                           """))
               .andExpect(status().isOk());

        verify(toggleServiceDirectUrlDisabledUseCase).setDirectUrlDisabled("app.example.com", true);
    }

    @Test
    void setRedirect_updatesRootRedirectPath() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com/redirect")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"rootRedirectPath":"/dashboard"}
                           """))
               .andExpect(status().isOk());

        verify(editServiceRedirectUseCase).setRootRedirectPath("app.example.com", "/dashboard");
    }

    @Test
    void getPublishStatus_returnsStatusFields() throws Exception {
        when(publishPeerServiceUseCase.getPublishStatus("app"))
                .thenReturn(new PublishStatus(true, false));

        mockMvc.perform(get("/published-services/app/status"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.dnsPropagated").value(true))
               .andExpect(jsonPath("$.traefikActive").value(false));
    }

    @Test
    void ignoreService_delegatesToUseCase() throws Exception {
        mockMvc.perform(post("/published-services/publishable/ignore")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"key":"peer1:myapp:8080"}
                           """))
               .andExpect(status().isOk());

        verify(ignorePublishableServiceUseCase).ignoreService("peer1:myapp:8080");
    }

    @Test
    void unignoreService_delegatesToUseCase() throws Exception {
        mockMvc.perform(post("/published-services/publishable/unignore")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"key":"peer1:myapp:8080"}
                           """))
               .andExpect(status().isOk());

        verify(unignorePublishableServiceUseCase).unignoreService("peer1:myapp:8080");
    }
}
