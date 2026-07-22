package net.vaier.domain;

import net.vaier.domain.PublishableService.PublishableSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublishableServiceOwnerTest {

    @Test
    void peerServiceIsOwnedByThePeer() {
        PublishableService svc = new PublishableService(PublishableSource.PEER, "alice",
            "10.13.13.2", "grafana", 3000, null, false);
        assertThat(svc.ownerMachineName("Vaier server", Map.of())).contains("alice");
    }

    @Test
    void vaierServerServiceIsOwnedByTheVaierServer() {
        PublishableService svc = new PublishableService(PublishableSource.VAIER_SERVER, null,
            "127.0.0.1", "dozzle", 8080, null, false);
        assertThat(svc.ownerMachineName("Vaier server", Map.of())).contains("Vaier server");
    }

    @Test
    void lanServerServiceIsOwnedByWhicheverMachineBearsItsAddress() {
        PublishableService svc = new PublishableService(PublishableSource.LAN_SERVER, "colina27",
            "192.168.1.113", "pool", 80, null, false);
        assertThat(svc.ownerMachineName("Vaier server", Map.of("192.168.1.113", "pool-controller")))
            .contains("pool-controller");
        assertThat(svc.ownerMachineName("Vaier server", Map.of())).isEmpty();
    }
}
