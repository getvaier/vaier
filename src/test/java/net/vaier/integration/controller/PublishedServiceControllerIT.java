package net.vaier.integration.controller;

import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.PublishPeerServiceUseCase.PublishStatus;
import net.vaier.application.UpdatePublishedServiceUseCase.PublishedServicePatch;
import net.vaier.domain.DnsState;
import net.vaier.domain.Server.State;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublishedServiceControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void publishService_validationFailure_returnsUniformApiError() throws Exception {
        doThrow(new IllegalArgumentException("A route already exists on app.example.com"))
                .when(publishPeerServiceUseCase).publishService(
                        any(), anyInt(), any(), anyBoolean(), any(), anyBoolean(), any());

        mockMvc.perform(post("/published-services/publish")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"address":"10.13.13.2","port":8080,"subdomain":"app"}
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("A route already exists on app.example.com"));
    }

    @Test
    void publishLanService_validationFailure_returnsUniformApiError() throws Exception {
        doThrow(new IllegalArgumentException("Unknown machine: ghost"))
                .when(publishLanServiceUseCase).publishLanService(
                        any(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), any(), any());

        mockMvc.perform(post("/published-services/lan")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"subdomain":"x","machineName":"ghost","port":80,"protocol":"http"}
                           """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
               .andExpect(jsonPath("$.message").value("Unknown machine: ghost"));
    }

    @Test
    void discover_returnsPublishedServices() throws Exception {
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of(
                new PublishedServiceUco(
                        "app", "app.example.com", DnsState.OK,
                        "10.13.13.2", 8080, State.OK, false, null, false)
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
                             "rootRedirectPath":"/dashboard",
                             "directUrlDisabled":true
                           }
                           """))
               .andExpect(status().isOk());

        verify(publishPeerServiceUseCase).publishService(
                "10.13.13.2", 8080, "app", true, "/dashboard", true, null);
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

        verify(publishPeerServiceUseCase).publishService("10.13.13.2", 8080, "app", false, null, false, null);
    }

    @Test
    void deleteService_delegatesToUseCase() throws Exception {
        mockMvc.perform(delete("/published-services/app.example.com"))
               .andExpect(status().isOk());

        verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
    }

    @Test
    void update_singleBooleanField_appliesOnlyThatField() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"requiresAuth":true}
                           """))
               .andExpect(status().isOk());

        verify(updatePublishedServiceUseCase).updateService("app.example.com", null,
            new PublishedServicePatch(true, null, null, null, null, null, null));
    }

    @Test
    void update_pathBasedRoute_passesPathPrefix() throws Exception {
        mockMvc.perform(patch("/published-services/svc.example.com")
                       .param("pathPrefix", "/grafana")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"hiddenFromLaunchpad":true}
                           """))
               .andExpect(status().isOk());

        verify(updatePublishedServiceUseCase).updateService("svc.example.com", "/grafana",
            new PublishedServicePatch(null, null, true, null, null, null, null));
    }

    @Test
    void update_multipleFields_passesAllSetValues() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "requiresAuth": true,
                             "directUrlDisabled": false,
                             "hiddenFromLaunchpad": true,
                             "rootRedirectPath": "/dashboard",
                             "launchpadAlias": "Grafana Prod",
                             "versionEndpoint": "/status",
                             "versionProperty": "build"
                           }
                           """))
               .andExpect(status().isOk());

        verify(updatePublishedServiceUseCase).updateService("app.example.com", null,
            new PublishedServicePatch(true, false, true, "/dashboard", "Grafana Prod", "/status", "build"));
    }

    @Test
    void update_emptyStringClearsStringField() throws Exception {
        mockMvc.perform(patch("/published-services/app.example.com")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"launchpadAlias":""}
                           """))
               .andExpect(status().isOk());

        verify(updatePublishedServiceUseCase).updateService("app.example.com", null,
            new PublishedServicePatch(null, null, null, null, "", null, null));
    }

    @Test
    void update_absentFieldsArriveAsNull() throws Exception {
        mockMvc.perform(patch("/published-services/svc.example.com")
                       .param("pathPrefix", "/grafana")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {"versionEndpoint":"/sys/metrics?name[]=system_info","versionProperty":"display"}
                           """))
               .andExpect(status().isOk());

        verify(updatePublishedServiceUseCase).updateService("svc.example.com", "/grafana",
            new PublishedServicePatch(null, null, null, null, null,
                "/sys/metrics?name[]=system_info", "display"));
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
