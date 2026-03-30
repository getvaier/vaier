package net.vaier.application.service;

import net.vaier.domain.port.ForManagingVpnNetwork;
import net.vaier.domain.port.ForRestartingContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SetupVpnNetworkServiceTest {

    @Mock
    ForManagingVpnNetwork forManagingVpnNetwork;

    @Mock
    ForRestartingContainers forRestartingContainers;

    @InjectMocks
    SetupVpnNetworkService service;

    @Test
    void setupNatRules_ensuresNatActiveBeforeRestartingWireguard() {
        service.setupNatRules();

        InOrder order = inOrder(forManagingVpnNetwork, forRestartingContainers);
        order.verify(forManagingVpnNetwork).ensureNatRulesActive();
        order.verify(forRestartingContainers).restartContainer("wireguard");
    }
}
