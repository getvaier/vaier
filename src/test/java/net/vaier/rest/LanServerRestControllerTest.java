package net.vaier.rest;

import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase.Reachability;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.domain.LanServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerRestControllerTest {

    @Mock RegisterLanServerUseCase registerLanServerUseCase;
    @Mock DeleteLanServerUseCase deleteLanServerUseCase;
    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock GetLanServerReachabilityUseCase reachabilityUseCase;

    @InjectMocks
    LanServerRestController controller;

    @Test
    void register_runsDockerTrueWithDockerPort_delegatesToUseCase() {
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, 2375);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375);
    }

    @Test
    void register_runsDockerFalseWithoutDockerPort_delegatesToUseCase() {
        var request = new LanServerRestController.RegisterRequest("printer", "192.168.3.20", false, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("printer", "192.168.3.20", false, null);
    }

    @Test
    void register_runsDockerTrueWithoutDockerPort_returns400() {
        doThrow(new IllegalArgumentException("dockerPort is required"))
            .when(registerLanServerUseCase).register("nas", "192.168.3.50", true, null);
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_useCaseThrowsIllegalArgument_returns400() {
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(registerLanServerUseCase).register("nas", "10.99.99.99", true, 2375);
        var request = new LanServerRestController.RegisterRequest("nas", "10.99.99.99", true, 2375);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void delete_callsUseCase() {
        ResponseEntity<Void> response = controller.delete("nas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deleteLanServerUseCase).delete("nas");
    }

    @Test
    void list_returnsRegisteredServersWithRelayNameAndReachability() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5"),
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("nas")).thenReturn(Reachability.UNKNOWN);
        when(reachabilityUseCase.getReachability("printer")).thenReturn(Reachability.OK);

        var response = controller.list();

        assertThat(response).hasSize(2);
        assertThat(response.get(0).name()).isEqualTo("nas");
        assertThat(response.get(0).lanAddress()).isEqualTo("192.168.3.50");
        assertThat(response.get(0).runsDocker()).isTrue();
        assertThat(response.get(0).dockerPort()).isEqualTo(2375);
        assertThat(response.get(0).relayPeerName()).isEqualTo("apalveien5");
        assertThat(response.get(0).reachability()).isEqualTo("UNKNOWN");
        assertThat(response.get(1).name()).isEqualTo("printer");
        assertThat(response.get(1).runsDocker()).isFalse();
        assertThat(response.get(1).dockerPort()).isNull();
        assertThat(response.get(1).reachability()).isEqualTo("OK");
    }
}
