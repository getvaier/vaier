package net.vaier.application.service;

import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForGettingVpnClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetVpnClientsServiceTest {

    @Mock ForGettingVpnClients forGettingVpnClients;
    @InjectMocks GetVpnClientsService service;

    @Test
    void getClients_delegatesToPort() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        assertThat(service.getClients()).containsExactly(client);
    }

    @Test
    void getClients_returnsEmptyListWhenNoClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.getClients()).isEmpty();
    }
}
