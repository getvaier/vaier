package net.vaier.application.service;

import net.vaier.domain.port.ForResolvingPeerNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveVpnPeerNameServiceTest {

    @Mock ForResolvingPeerNames forResolvingPeerNames;
    @InjectMocks ResolveVpnPeerNameService service;

    @Test
    void resolvePeerNameByIp_delegatesToPort() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");

        assertThat(service.resolvePeerNameByIp("10.13.13.2")).isEqualTo("alice");
    }

    @Test
    void resolvePeerNameByIp_returnsNullWhenNotFound() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.99")).thenReturn(null);

        assertThat(service.resolvePeerNameByIp("10.13.13.99")).isNull();
    }
}
