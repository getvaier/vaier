package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GetPeerConfigService implements GetPeerConfigUseCase {

    private final ForGettingPeerConfigurations peerConfigProvider;

    @Override
    public Optional<PeerConfigResult> getPeerConfig(String peerIdentifier) {
        log.info("Fetching config for peer: {}", peerIdentifier);

        // Try as name first, then as IP
        Optional<ForGettingPeerConfigurations.PeerConfiguration> config;

        if (peerIdentifier.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            config = peerConfigProvider.getPeerConfigByIp(peerIdentifier);
        } else {
            config = peerConfigProvider.getPeerConfigByName(peerIdentifier);
        }

        return config.map(c -> new PeerConfigResult(c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress()));
    }

    @Override
    public Optional<PeerConfigResult> getPeerConfigByIp(String ipAddress) {
        return peerConfigProvider.getPeerConfigByIp(ipAddress)
                .map(c -> new PeerConfigResult(c.name(), c.ipAddress(), c.configContent(), c.peerType(), c.lanCidr(), c.lanAddress()));
    }
}
