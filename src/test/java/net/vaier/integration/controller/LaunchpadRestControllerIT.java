package net.vaier.integration.controller;

import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.Server.State;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LaunchpadRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getServices_returnsEmptyListWhenNoServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getServices_returnsMappedServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(List.of(
                new LaunchpadServiceUco("app.example.com", "10.0.0.1", State.OK, null),
                new LaunchpadServiceUco("db.example.com", "10.0.0.2", State.OK, "http://10.0.0.2:8080")
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("app.example.com"))
               .andExpect(jsonPath("$[0].hostAddress").value("10.0.0.1"))
               .andExpect(jsonPath("$[0].state").value("OK"))
               .andExpect(jsonPath("$[0].directUrl").doesNotExist())
               .andExpect(jsonPath("$[1].dnsAddress").value("db.example.com"))
               .andExpect(jsonPath("$[1].directUrl").value("http://10.0.0.2:8080"));
    }

    @Test
    void getServices_returnsUnreachableServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(List.of(
                new LaunchpadServiceUco("down.example.com", "10.0.0.3", State.UNREACHABLE, null)
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("down.example.com"))
               .andExpect(jsonPath("$[0].state").value("UNREACHABLE"));
    }

    @Test
    void getServices_passesRemoteAddrToUseCase_whenNotFromTrustedProxy() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services")
                       .with(request -> {
                           request.setRemoteAddr("203.0.113.10");
                           request.addHeader("X-Forwarded-For", "198.51.100.1");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("203.0.113.10");
    }

    @Test
    void getServices_trustsXForwardedFor_whenRemoteAddrIsInTrustedCidr() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services")
                       .with(request -> {
                           request.setRemoteAddr("172.20.0.5");
                           request.addHeader("X-Forwarded-For", "192.168.3.42");
                           return request;
                       }))
               .andExpect(status().isOk());

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("192.168.3.42");
    }
}
