package net.vaier.integration.controller;

import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.LaunchpadLiveness;
import net.vaier.domain.LaunchpadVisibility;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LaunchpadRestControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getServices_returnsEmptyListWhenNoServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getServices_returnsMappedServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), any())).thenReturn(List.of(
                new LaunchpadServiceUco("app.example.com", null, "10.0.0.1", LaunchpadVisibility.VISIBLE_ACTIVE, LaunchpadLiveness.LIVE, "https://app.example.com", "app", "app", "host=app.example.com", "media server", "grafana/grafana:11.3.0", "11.3.0", true),
                new LaunchpadServiceUco("db.example.com", null, "10.0.0.2", LaunchpadVisibility.VISIBLE_ACTIVE, LaunchpadLiveness.LIVE, "http://10.0.0.2:8080", "db", "db", "host=db.example.com", "database host")
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("app.example.com"))
               .andExpect(jsonPath("$[0].hostAddress").value("10.0.0.1"))
               .andExpect(jsonPath("$[0].visibility").value("VISIBLE_ACTIVE"))
               .andExpect(jsonPath("$[0].liveness").value("LIVE"))
               .andExpect(jsonPath("$[0].displayName").value("app"))
               .andExpect(jsonPath("$[0].url").value("https://app.example.com"))
               .andExpect(jsonPath("$[0].image").value("grafana/grafana:11.3.0"))
               .andExpect(jsonPath("$[0].version").value("11.3.0"))
               .andExpect(jsonPath("$[0].callerOnHostLan").value(true))
               .andExpect(jsonPath("$[1].callerOnHostLan").value(false))
               .andExpect(jsonPath("$[1].dnsAddress").value("db.example.com"))
               .andExpect(jsonPath("$[1].url").value("http://10.0.0.2:8080"));
    }

    @Test
    void getServices_returnsInactiveServices() throws Exception {
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any(), any())).thenReturn(List.of(
                new LaunchpadServiceUco("down.example.com", null, "10.0.0.3", LaunchpadVisibility.VISIBLE_INACTIVE, LaunchpadLiveness.OFFLINE, "https://down.example.com", "down", "down", "host=down.example.com", "down host")
        ));

        mockMvc.perform(get("/launchpad/services"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].dnsAddress").value("down.example.com"))
               .andExpect(jsonPath("$[0].visibility").value("VISIBLE_INACTIVE"))
               .andExpect(jsonPath("$[0].liveness").value("OFFLINE"));
    }

}
