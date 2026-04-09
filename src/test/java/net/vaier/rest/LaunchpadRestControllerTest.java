package net.vaier.rest;

import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.Server.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaunchpadRestControllerTest {

    @Mock
    GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    @InjectMocks
    LaunchpadRestController controller;

    @Test
    void getServices_returnsLaunchpadServices() {
        var services = List.of(
            new LaunchpadServiceUco("app.example.com", "10.0.0.1", State.OK),
            new LaunchpadServiceUco("db.example.com", "10.0.0.2", State.OK)
        );
        when(getLaunchpadServicesUseCase.getLaunchpadServices()).thenReturn(services);

        List<LaunchpadServiceUco> result = controller.getServices();

        assertThat(result).isEqualTo(services);
    }
}
