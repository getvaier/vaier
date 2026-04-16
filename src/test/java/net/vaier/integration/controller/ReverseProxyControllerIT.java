package net.vaier.integration.controller;

import net.vaier.application.AddReverseProxyRouteUseCase.ReverseProxyRouteUco;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.integration.base.VaierWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReverseProxyControllerIT extends VaierWebMvcIntegrationBase {

    @Test
    void getAllRoutes_returnsRouteList() throws Exception {
        ReverseProxyRoute route = new ReverseProxyRoute(
                "app-router", "app.example.com", "10.13.13.2", 8080,
                "app-service", null, null, null, null);
        when(getReverseProxyRoutesUseCase.getReverseProxyRoutes()).thenReturn(List.of(route));

        mockMvc.perform(get("/reverse-proxy/routes"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].domainName").value("app.example.com"))
               .andExpect(jsonPath("$[0].address").value("10.13.13.2"))
               .andExpect(jsonPath("$[0].port").value(8080));
    }

    @Test
    void getAllRoutes_returnsEmptyList() throws Exception {
        when(getReverseProxyRoutesUseCase.getReverseProxyRoutes()).thenReturn(List.of());

        mockMvc.perform(get("/reverse-proxy/routes"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void addRoute_delegatesToUseCaseWithCorrectValues() throws Exception {
        mockMvc.perform(post("/reverse-proxy/routes")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "dnsName":"app.example.com",
                             "address":"10.13.13.2",
                             "port":8080,
                             "requiresAuth":true
                           }
                           """))
               .andExpect(status().isOk());

        ArgumentCaptor<ReverseProxyRouteUco> captor = ArgumentCaptor.forClass(ReverseProxyRouteUco.class);
        verify(addReverseProxyRouteUseCase).addReverseProxyRoute(captor.capture());
        assertThat(captor.getValue().dnsName()).isEqualTo("app.example.com");
        assertThat(captor.getValue().address()).isEqualTo("10.13.13.2");
        assertThat(captor.getValue().port()).isEqualTo(8080);
        assertThat(captor.getValue().requiresAuth()).isTrue();
    }

    @Test
    void addRoute_withoutAuth_setsRequiresAuthFalse() throws Exception {
        mockMvc.perform(post("/reverse-proxy/routes")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                           {
                             "dnsName":"app.example.com",
                             "address":"10.13.13.2",
                             "port":8080,
                             "requiresAuth":false
                           }
                           """))
               .andExpect(status().isOk());

        ArgumentCaptor<ReverseProxyRouteUco> captor = ArgumentCaptor.forClass(ReverseProxyRouteUco.class);
        verify(addReverseProxyRouteUseCase).addReverseProxyRoute(captor.capture());
        assertThat(captor.getValue().requiresAuth()).isFalse();
    }

    @Test
    void deleteRoute_delegatesToUseCase() throws Exception {
        mockMvc.perform(delete("/reverse-proxy/routes/app.example.com"))
               .andExpect(status().isOk());

        verify(deleteReverseProxyRouteUseCase).deleteReverseProxyRoute("app.example.com");
    }
}
