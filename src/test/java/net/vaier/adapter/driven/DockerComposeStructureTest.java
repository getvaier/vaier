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
        // avatar.js is loaded by the public launchpad shell; it must be anonymously reachable too,
        // or a non-admin viewer 403s on it, VaierAvatar never loads, and the topbar breaks.
        assertThat(rule).contains("Path(`/avatar.js`)");
        assertThat(rule).contains("PathPrefix(`/icon`)");
        assertThat(rule).contains("Path(`/launchpad/services`)");
        // The launchpad's public live-update stream — signal-only, so anonymous viewers get live
        // tile refreshes without the private-subdomain payload the full SSE stream carries.
        assertThat(rule).contains("Path(`/launchpad/events`)");

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

        // The viewer-adaptive endpoints: the two data/identity APIs plus the launchpad's live-update
        // SSE stream. The SSE payload carries service subdomains, so it belongs behind oauth2-authn
        // (authenticated non-admins get it; anonymous get a clean 401) — never on the public tier.
        assertThat(rule).contains("Path(`/launchpad/services-authenticated`)");
        assertThat(rule).contains("Path(`/users/me`)");
        assertThat(rule).contains("Path(`/published-services/events`)");

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

    // --- #305 follow-up: Dex OIDC broker federates Google + GitHub behind oauth2-proxy ---

    @Test
    @SuppressWarnings("unchecked")
    void dex_isMandatoryVersionPinnedInfrastructure_onPort5556() throws Exception {
        // Dex is the identity broker behind oauth2-proxy (federates Google + GitHub). Like
        // oauth2-proxy it is the sole auth path, so it is mandatory infrastructure (no profile),
        // version-pinned (no floating :latest), and Traefik routes to it on Dex's HTTP port 5556.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dex = (Map<String, Object>) services.get("dex");

        assertThat(dex).as("dex service must exist").isNotNull();
        assertThat((String) dex.get("image"))
            .as("dex image must be version-pinned").contains("dexidp/dex:v2.45.1");
        assertThat(dex).as("dex must always start — no profile gate").doesNotContainKey("profiles");

        List<String> labels = (List<String>) dex.get("labels");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String label : labels) {
            int eq = label.indexOf('=');
            if (eq > 0) {
                byKey.put(label.substring(0, eq), label.substring(eq + 1));
            }
        }
        assertThat(byKey.get("traefik.http.services.dex.loadbalancer.server.port"))
            .as("Traefik must route to Dex on its HTTP port 5556").isEqualTo("5556");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_isMandatoryInfrastructure_notBehindAProfile() throws Exception {
        // dex-init renders Dex's config (mirrors oauth2-proxy-init). It must always run so Dex has
        // a config on every start — no profile gate.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");

        assertThat(dexInit).as("dex-init service must exist").isNotNull();
        assertThat(dexInit).as("dex-init must always run — no profile gate").doesNotContainKey("profiles");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyAlphaRender_pointsAtTheDexIssuer_notGoogleDirect() throws Exception {
        // oauth2-proxy no longer talks to Google directly — it federates through Dex via a generic
        // OIDC provider. The rendered alpha.yaml lives in the gitignored runtime dir, so the
        // committed source of truth is the heredoc oauth2-proxy-init renders it from.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("provider must be generic oidc, brokered by Dex").contains("provider: oidc");
        assertThat(render).as("issuer must be Dex").contains("issuerURL: https://dex.$${VAIER_DOMAIN}");
        assertThat(render).as("must no longer talk to Google directly").doesNotContain("provider: google");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyRender_allowListsConnectorIdSoTheSelectorJumpsStraightToTheProvider() throws Exception {
        // The two sign-in buttons pass connector_id=google|github. oauth2-proxy only forwards a
        // user-supplied login param when it matches an `allow` rule — without it, Dex would show its
        // own second chooser instead of jumping straight to the picked provider. Guard the allow-list.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("connector_id must be an allow-listed login param")
            .contains("name: connector_id, allow:");
        assertThat(render).contains("value: google").contains("value: github");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexRender_inlinesAllSecrets_becauseDexHasNoFileBasedSecretOption() throws Exception {
        // Dex honours clientSecretFile on only a handful of connectors — NOT the google/github/oidc
        // ones — and staticClients have no file option at all. Referencing a file silently yields an
        // empty secret ("client_secret is missing"). So all three secrets render inline into the
        // 0600, dex-owned, gitignored config.yaml. Guard against a regression back to file refs.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");
        String render = String.join("\n", (List<String>) dexInit.get("command"));

        assertThat(render).as("static client secret inlined").contains("secret: $${VAIER_DEX_CLIENT_SECRET}");
        assertThat(render).as("google connector secret inlined").contains("clientSecret: $${VAIER_OIDC_GOOGLE_CLIENT_SECRET}");
        assertThat(render).as("github connector secret inlined").contains("clientSecret: $${VAIER_OIDC_GITHUB_CLIENT_SECRET}");
        assertThat(render).as("Dex connectors/clients cannot read a secret from a file")
            .doesNotContain("clientSecretFile").doesNotContain("secretFile");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2ProxyRender_extractsFederatedClaimsLeavesSoTheProviderHeadersPopulate() throws Exception {
        // oauth2-proxy only injects a claim into a header if it is first extracted into the session
        // via additionalClaims, stored under the FULL dotted key; the injection then does a flat
        // lookup of that same key. Without the two federated_claims leaves here, X-Auth-Request-
        // Connector[-Uid] ship empty and the Users provider badge + photo never populate. The strings
        // in additionalClaims must be byte-identical to the claimSource.claim values.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("federated:id scope is the Dex-side prerequisite for federated_claims")
            .contains("scope: openid email profile federated:id");
        assertThat(render).as("both federated_claims leaves must be extracted for injection")
            .contains("additionalClaims: [name, federated_claims.connector_id, federated_claims.user_id]");
        assertThat(render).as("connector header injected from the connector_id leaf")
            .contains("X-Auth-Request-Connector, values: [{claimSource: {claim: federated_claims.connector_id}}]");
        assertThat(render).as("connector uid header injected from the user_id leaf")
            .contains("X-Auth-Request-Connector-Uid, values: [{claimSource: {claim: federated_claims.user_id}}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpEntrypointRedirectsToHttps_soBareHostnameVisitsDoNotHitABare404() throws Exception {
        // Every Vaier router is bound to the `websecure` (:443) entrypoint only. A browser given a
        // schemeless hostname (`vaier.example.com`) requests `http://` on :80 — which matches no
        // router and gets Traefik's default "404 page not found". The `web` entrypoint must globally
        // redirect to `websecure`. This coexists with the Let's Encrypt HTTP-01 challenge that also
        // lives on `web`: Traefik serves the ACME challenge at higher priority than the redirect.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");
        List<String> command = (List<String>) traefik.get("command");

        assertThat(command)
            .as("http://<host> must 308 to https, not fall through to Traefik's default 404")
            .contains("--entrypoints.web.http.redirections.entrypoint.to=websecure")
            .contains("--entrypoints.web.http.redirections.entrypoint.scheme=https");

        // The ACME HTTP-01 challenge must still run on `web` — the redirect doesn't replace it.
        assertThat(command).contains("--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web");
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
