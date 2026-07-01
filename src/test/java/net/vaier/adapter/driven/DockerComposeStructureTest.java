package net.vaier.adapter.driven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeStructureTest {

    @SuppressWarnings("unchecked")
    private Map<String, String> vaierLabels() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> vaier = (Map<String, Object>) services.get("vaier");
        List<String> labels = (List<String>) vaier.get("labels");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String label : labels) {
            int eq = label.indexOf('=');
            if (eq > 0) {
                byKey.put(label.substring(0, eq), label.substring(eq + 1));
            }
        }
        return byKey;
    }

    // --- Public, viewer-adaptive launchpad: three-tier routing on the console host ---

    @Test
    void publicRouter_servesTheLaunchpadShellAndAssetsWithNoAuthMiddleware() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier-public.rule");
        String mw = labels.get("traefik.http.routers.vaier-public.middlewares");

        // The launchpad shell + assets + public data must be anonymously reachable.
        assertThat(rule).contains("Path(`/`)");
        assertThat(rule).contains("Path(`/launchpad.html`)");
        assertThat(rule).contains("Path(`/styles.css`)");
        assertThat(rule).contains("PathPrefix(`/icon`)");
        assertThat(rule).contains("Path(`/launchpad/services`)");

        // But no admin surface may be whitelisted as public.
        assertThat(rule).doesNotContain("admin.html");
        assertThat(rule).doesNotContain("/access");
        assertThat(rule).doesNotContain("services-authenticated");
        assertThat(rule).doesNotContain("/users/me");

        // Public tier carries the offline middleware only — never any auth link.
        assertThat(mw).isEqualTo("vaier-down");
        assertThat(mw).doesNotContain("oauth2");
        assertThat(mw).doesNotContain("authz");
    }

    @Test
    void identityRouter_carriesOnlyOauth2AuthnForTheViewerAdaptiveEndpoints() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier-identity.rule");
        String mw = labels.get("traefik.http.routers.vaier-identity.middlewares");
        String priority = labels.get("traefik.http.routers.vaier-identity.priority");

        // Exactly the two viewer-adaptive endpoints, nothing else.
        assertThat(rule).contains("Path(`/launchpad/services-authenticated`)");
        assertThat(rule).contains("Path(`/users/me`)");

        // oauth2-authn injects identity when a session exists and 401s anonymous — but NO
        // forced-login redirect (oauth2-signin) and NO admin gate (vaier-authz).
        assertThat(mw).contains("oauth2-authn@file");
        assertThat(mw).contains("vaier-down");
        assertThat(mw).doesNotContain("oauth2-signin");
        assertThat(mw).doesNotContain("vaier-authz");

        // Must out-rank the admin catch-all so these paths aren't swept into the full auth chain.
        assertThat(priority).isEqualTo("250");
    }

    @Test
    void adminCatchAll_stillEnforcesTheFullSocialChainWithAuthz() throws Exception {
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier.rule");
        String mw = labels.get("traefik.http.routers.vaier.middlewares");
        String priority = labels.get("traefik.http.routers.vaier.priority");

        // The catch-all still matches the whole host (admin.html + all admin APIs land here).
        assertThat(rule).isEqualTo("Host(`vaier.${VAIER_DOMAIN}`)");
        // And it still carries the full chain including the admin-enforcing vaier-authz.
        assertThat(mw).contains("oauth2-signin@file");
        assertThat(mw).contains("oauth2-authn@file");
        assertThat(mw).contains("vaier-authz@file");
        // Lowest priority of the real routers, so the specific public/identity/oauth2 routers win.
        assertThat(priority).isEqualTo("100");
    }

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
