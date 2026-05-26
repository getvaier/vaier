package net.vaier.integration.controller;

import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LaunchpadRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getServices_returnsEmptyListWhenNoServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getServices_returnsMappedServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of(
                new LaunchpadServiceUco("app.example.com", null, "10.0.0.1", LaunchpadVisibility.VISIBLE_ACTIVE, "https://app.example.com", "app", "app", "host=app.example.com", "media server", "grafana/grafana:11.3.0", "11.3.0"),
                new LaunchpadServiceUco("db.example.com", null, "10.0.0.2", LaunchpadVisibility.VISIBLE_ACTIVE, "http://10.0.0.2:8080", "db", "db", "host=db.example.com", "database host")
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("app.example.com"))
               .andExpect(jsonPath("$[0].hostAddress").value("10.0.0.1"))
               .andExpect(jsonPath("$[0].visibility").value("VISIBLE_ACTIVE"))
               .andExpect(jsonPath("$[0].displayName").value("app"))
               .andExpect(jsonPath("$[0].url").value("https://app.example.com"))
               .andExpect(jsonPath("$[0].image").value("grafana/grafana:11.3.0"))
               .andExpect(jsonPath("$[0].version").value("11.3.0"))
               .andExpect(jsonPath("$[1].dnsAddress").value("db.example.com"))
               .andExpect(jsonPath("$[1].url").value("http://10.0.0.2:8080"));
    }

    @Test
    void getServices_returnsInactiveServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of(
                new LaunchpadServiceUco("down.example.com", null, "10.0.0.3", LaunchpadVisibility.VISIBLE_INACTIVE, "https://down.example.com", "down", "down", "host=down.example.com", "down host")
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("down.example.com"))
               .andExpect(jsonPath("$[0].visibility").value("VISIBLE_INACTIVE"));
    }

    @Test
    void getServices_passesRemoteAddrToUseCase_whenNotFromTrustedProxy() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services")
                       .with(request -> {
                           request.setRemoteAddr("203.0.113.10");
                           request.addHeader("X-Forwarded-For", "198.51.100.1");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices(eq("203.0.113.10"), anyBoolean());
    }

    @Test
    void getServices_trustsXForwardedFor_whenRemoteAddrIsInTrustedCidr() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services")
                       .with(request -> {
                           request.setRemoteAddr("172.20.0.5");
                           request.addHeader("X-Forwarded-For", "192.168.3.42");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices(eq("192.168.3.42"), anyBoolean());
    }

    @Test
    void getServices_alwaysPassesAuthenticatedFalse() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services")
                       .with(request -> {
                           request.setRemoteAddr("172.20.0.5");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices(any(), eq(false));
    }

    @Test
    void getServicesAuthenticated_passesAuthenticatedTrue() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services-authenticated")
                       .with(request -> {
                           request.setRemoteAddr("172.20.0.5");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices(any(), eq(true));
    }
}
