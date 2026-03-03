package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.SetupVpnNetworkUseCase;
import net.vaier.domain.port.ForManagingVpnNetwork;
import net.vaier.domain.port.ForRestartingContainers;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SetupVpnNetworkService implements SetupVpnNetworkUseCase {

    private final ForManagingVpnNetwork vpnNetworkManager;
    private final ForRestartingContainers containerRestarter;

    @Override
    public void setupNatRules() {
        log.info("Setting up NAT rules");
        vpnNetworkManager.ensureNatRulesActive();
        containerRestarter.restartContainer("wireguard");
        log.info("NAT rules configured and WireGuard restarted");
    }
}
