package net.vaier.adapter.driven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeStructureTest {

    @Test
    @SuppressWarnings("unchecked")
    void autheliaAndRedisAreDecommissioned_noLongerInTheStack() throws Exception {
        // Every gated service moved to social login (#305). Authelia and its Redis session store
        // are removed from the running stack, along with their init sidecars.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        assertThat(services)
            .as("Authelia and Redis are decommissioned and must not appear in the stack")
            .doesNotContainKeys("authelia", "authelia-init", "redis", "redis-init");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2Proxy_isMandatoryInfrastructure_notBehindAProfile() throws Exception {
        // With Authelia gone, oauth2-proxy is the sole auth gateway — it must always start with
        // `docker compose up -d`, so neither it nor its init may be gated behind the `social` profile.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> oauth2Proxy = (Map<String, Object>) services.get("oauth2-proxy");
        Map<String, Object> oauth2ProxyInit = (Map<String, Object>) services.get("oauth2-proxy-init");

        assertThat(oauth2Proxy).as("oauth2-proxy must always start").doesNotContainKey("profiles");
        assertThat(oauth2ProxyInit).as("oauth2-proxy-init must always start").doesNotContainKey("profiles");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wireguardMasquerade_usesInterfaceNameAgnosticRuleForVpnEgress() throws Exception {
        // The linuxserver/wireguard wg0.conf PostUp masquerades only on `-o eth+`. On a
        // Vaier server whose primary NIC is not named eth* (e.g. AWS EC2's ens5, or when
        // wireguard runs with host networking) that rule is a silent no-op, so traffic
        // from a LAN behind a server peer that egresses a non-WG interface keeps its
        // original source and the destination has no return route — it drops. #248.
        //
        // The wireguard-masquerade sidecar must therefore install a name-agnostic rule
        // that masquerades anything leaving a non-wg0 interface (`! -o wg0`), regardless
        // of the kernel's name for that interface.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> masquerade = (Map<String, Object>) services.get("wireguard-masquerade");
        List<String> entrypoint = (List<String>) masquerade.get("entrypoint");
        String script = String.join("\n", entrypoint);

        assertThat(script)
            .as("masquerade sidecar must NAT VPN egress by NOT matching wg0, not by guessing the NIC name")
            .contains("! -o wg0 -j MASQUERADE");
    }

}
