package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import net.vaier.application.GetLaunchpadServicesUseCase;
import net.vaier.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.vaier.domain.Server.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaunchpadRestControllerTest {

    @Mock
    GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;

    @InjectMocks
    LaunchpadRestController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "trustedProxyCidr", "172.20.0.0/16");
    }

    @Test
    void getServices_returnsLaunchpadServices() {
        var services = List.of(
            new LaunchpadServiceUco("app.example.com", "10.0.0.1", State.OK, null),
            new LaunchpadServiceUco("db.example.com", "10.0.0.2", State.OK, null)
        );
        when(getLaunchpadServicesUseCase.getLaunchpadServices(any())).thenReturn(services);

        List<LaunchpadServiceUco> result = controller.getServices(mock(HttpServletRequest.class));

        assertThat(result).isEqualTo(services);
    }

    @Test
    void getServices_trustsXForwardedFor_whenRequestComesFromTraefikSubnet() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.20.0.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.3.42");

        controller.getServices(request);

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("192.168.3.42");
    }

    @Test
    void getServices_usesFirstHop_whenXForwardedForHasMultipleIps() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.20.0.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.3.42, 10.0.0.1");

        controller.getServices(request);

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("192.168.3.42");
    }

    @Test
    void getServices_ignoresSpoofedXForwardedFor_whenRequestNotFromTraefikSubnet() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.99");

        controller.getServices(request);

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("203.0.113.99");
    }

    @Test
    void getServices_noXForwardedForHeader_fallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("172.20.0.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        controller.getServices(request);

        verify(getLaunchpadServicesUseCase).getLaunchpadServices("172.20.0.5");
    }
}
