package net.vaier.rest;

import net.vaier.application.DeleteLanDockerHostUseCase;
import net.vaier.application.GetLanDockerHostsUseCase;
import net.vaier.application.GetLanDockerHostsUseCase.LanDockerHostView;
import net.vaier.application.RegisterLanDockerHostUseCase;
import net.vaier.domain.LanDockerHost;
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
class LanDockerHostRestControllerTest {

    @Mock RegisterLanDockerHostUseCase registerLanDockerHostUseCase;
    @Mock DeleteLanDockerHostUseCase deleteLanDockerHostUseCase;
    @Mock GetLanDockerHostsUseCase getLanDockerHostsUseCase;

    @InjectMocks
    LanDockerHostRestController controller;

    @Test
    void register_validRequest_delegatesToUseCase() {
        var request = new LanDockerHostRestController.RegisterRequest("nas", "192.168.3.50", 2375);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanDockerHostUseCase).register("nas", "192.168.3.50", 2375);
    }

    @Test
    void register_useCaseThrowsIllegalArgument_returns400() {
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(registerLanDockerHostUseCase).register("nas", "10.99.99.99", 2375);
        var request = new LanDockerHostRestController.RegisterRequest("nas", "10.99.99.99", 2375);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void delete_callsUseCase() {
        ResponseEntity<Void> response = controller.delete("nas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deleteLanDockerHostUseCase).delete("nas");
    }

    @Test
    void list_returnsRegisteredHostsWithRelayName() {
        when(getLanDockerHostsUseCase.getAll()).thenReturn(List.of(
            new LanDockerHostView(new LanDockerHost("nas", "192.168.3.50", 2375), "apalveien5")
        ));

        var response = controller.list();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).name()).isEqualTo("nas");
        assertThat(response.get(0).hostIp()).isEqualTo("192.168.3.50");
        assertThat(response.get(0).port()).isEqualTo(2375);
        assertThat(response.get(0).relayPeerName()).isEqualTo("apalveien5");
    }
}
