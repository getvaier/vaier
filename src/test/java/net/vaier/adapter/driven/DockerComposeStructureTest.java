package net.vaier.adapter.driven;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.vaier.domain.AuthMode;
import net.vaier.domain.IdentityProvider;
import net.vaier.domain.ProviderCredentials;
import net.vaier.domain.SignInSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @Test
    @SuppressWarnings("unchecked")
    void vaier_receivesEveryProviderClientId() throws Exception {
        // ConfigResolver.isSocialAuthAvailable reads both; a GitHub-only install once read as "no provider".
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> vaier = (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get("vaier");
        // #264: whether each secret is set, never the secret, so Settings can say which pair .env holds.
        Map<String, Object> env = (Map<String, Object>) vaier.get("environment");
        assertThat(env).containsKeys("VAIER_OIDC_GOOGLE_CLIENT_ID", "VAIER_OIDC_GITHUB_CLIENT_ID")
            .doesNotContainKeys("VAIER_OIDC_GOOGLE_CLIENT_SECRET", "VAIER_OIDC_GITHUB_CLIENT_SECRET")
            .containsEntry("VAIER_OIDC_GOOGLE_CLIENT_SECRET_PRESENT", "${VAIER_OIDC_GOOGLE_CLIENT_SECRET:+yes}")
            .containsEntry("VAIER_OIDC_GITHUB_CLIENT_SECRET_PRESENT", "${VAIER_OIDC_GITHUB_CLIENT_SECRET:+yes}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dockerProxy_letsVaierStartOnlyTheTwoSignInRenderers() throws Exception {
        // #264: Settings re-runs dex-init and oauth2-proxy-init. Starting an existing one-shot whose command
        // Vaier cannot change (create stays denied) only re-runs a fixed renderer; any other start would let
        // RCE in Vaier run whatever container it could find. haproxy's `-m reg` is an unanchored search.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        String template = (String) ((Map<String, Object>) ((Map<String, Object>) compose.get("configs"))
            .get("haproxy_template")).get("content");
        List<String> startRules = template.lines().map(String::strip)
            .filter(l -> l.startsWith("http-request deny") && l.contains("/start")).toList();
        assertThat(startRules).as("one deny rule for starts").hasSize(1);
        assertThat(template).contains("http-request deny if METH_POST { path -m end /containers/create }");

        Matcher acl = Pattern.compile("(!?)\\{ path -m reg -i (\\S+) \\}").matcher(startRules.get(0));
        record Acl(boolean negated, Pattern pattern) {}
        List<Acl> acls = new ArrayList<>();
        while (acl.find()) {
            acls.add(new Acl(!acl.group(1).isEmpty(),
                Pattern.compile(acl.group(2).replace("$$", "$"), Pattern.CASE_INSENSITIVE)));
        }
        record Row(String path, boolean denied) {}
        for (Row row : List.of(
                new Row("/containers/dex-init/start", false),
                new Row("/v1.43/containers/oauth2-proxy-init/start", false),
                new Row("/containers/vaier/start", true),
                new Row("/v1.43/containers/wireguard/start", true),
                new Row("/containers/dex-init-evil/start", true),
                new Row("/containers/evil-dex-init/start", true),
                new Row("/x/containers/dex-init/start", true))) {
            boolean denied = acls.stream().allMatch(a -> a.pattern().matcher(row.path()).find() != a.negated());
            assertThat(denied).as(row.path()).isEqualTo(row.denied());
        }
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

        // Public tier carries the offline middleware and the #258 frame guard — never any auth link.
        assertThat(mw).isEqualTo("vaier-down,vaier-frame-guard@file");
        assertThat(mw).doesNotContain("oauth2");
        assertThat(mw).doesNotContain("authz");
    }

    @Test
    void publicRouter_handsOutTheAndroidAppBeforeAPhoneCanPossiblySignIn() throws Exception {
        // #359: the phone fetches the Vaier app, then signs in from it and enrols. Behind the auth chain
        // that is a locked door with the key behind it. The package carries no secret — the same signed
        // file for every visitor — so it is the one download that belongs on the anonymous tier.
        String rule = vaierLabels().get("traefik.http.routers.vaier-public.rule");

        assertThat(rule).contains("Path(`/app/android/vaier.apk`)");
        // One file, exactly — never a prefix that could grow into serving the whole /app directory.
        assertThat(rule).doesNotContain("PathPrefix(`/app");
    }

    @Test
    void enrolmentRouter_opensExactlyTheThreeRoutesAPhoneNeedsWithoutASession() throws Exception {
        // #359 slice 1b: a phone joins before anyone has signed in on it, and leaves after nobody is
        // signed in on it either. Three routes, each anchored, and nothing else.
        Map<String, String> labels = vaierLabels();
        String rule = labels.get("traefik.http.routers.vaier-enrolment.rule");

        assertThat(rule).contains("Host(`vaier.${VAIER_DOMAIN}`)");
        assertThat(rule).contains("Path(`/vpn/enrolments`) && Method(`POST`)");
        // 43 characters is exactly 32 random bytes in base64url without padding — the ticket's shape.
        assertThat(rule).contains("PathRegexp(`^/vpn/enrolments/[A-Za-z0-9_-]{43}/events$`)");
        assertThat(rule).contains("Path(`/vpn/peers/leave`) && Method(`POST`)");
        assertThat(rule).contains("Path(`/vpn/peers/standing`) && Method(`POST`)");

        // Nothing else on /vpn may be anonymous — most of all not the admin list, the approve or the
        // refuse, which are what decide who gets into the fleet.
        assertThat(rule).doesNotContain("approve");
        assertThat(rule).doesNotContain("PathPrefix(`/vpn");

        assertThat(labels.get("traefik.http.routers.vaier-enrolment.entrypoints")).isEqualTo("websecure");
        assertThat(labels.get("traefik.http.routers.vaier-enrolment.tls.certresolver"))
            .isEqualTo("letsencrypt");
        // Above the admin catch-all (100) and the identity tier is not involved; below oauth2 (300).
        assertThat(labels.get("traefik.http.routers.vaier-enrolment.priority")).isEqualTo("210");
    }

    @Test
    void enrolmentRouter_isRateLimitedAndNoOtherRouterIs() throws Exception {
        Map<String, String> labels = vaierLabels();

        assertThat(labels.get("traefik.http.middlewares.vaier-enrolment-ratelimit.ratelimit.average"))
            .isEqualTo("10");
        assertThat(labels.get("traefik.http.middlewares.vaier-enrolment-ratelimit.ratelimit.period"))
            .isEqualTo("1m");
        assertThat(labels.get("traefik.http.middlewares.vaier-enrolment-ratelimit.ratelimit.burst"))
            .isEqualTo("5");

        assertThat(labels.get("traefik.http.routers.vaier-enrolment.middlewares"))
            .contains("vaier-enrolment-ratelimit");
        // The limit is the anonymous tier's, and it belongs to no other router.
        for (String router : List.of("vaier", "vaier-public", "vaier-identity", "vaier-oauth2")) {
            assertThat(labels.get("traefik.http.routers." + router + ".middlewares"))
                .doesNotContain("vaier-enrolment-ratelimit");
        }
    }

    @Test
    void enrolmentRouter_carriesNoAuthMiddleware_andThePublicRouterWasNotWidened() throws Exception {
        Map<String, String> labels = vaierLabels();
        String mw = labels.get("traefik.http.routers.vaier-enrolment.middlewares");

        assertThat(mw).doesNotContain("oauth2");
        assertThat(mw).doesNotContain("authz");

        // The enrolment routes got their own router precisely so the launchpad's public allowlist
        // stays exactly as narrow as it was.
        String publicRule = labels.get("traefik.http.routers.vaier-public.rule");
        assertThat(publicRule).doesNotContain("/vpn/enrolments");
        assertThat(publicRule).doesNotContain("/vpn/peers/leave");
        assertThat(publicRule).doesNotContain("/vpn/peers/standing");
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

    @Test
    @SuppressWarnings("unchecked")
    void traefik_declaresItsDashboardPortSoVaierCanOfferItForPublishing() throws Exception {
        // Traefik runs its API/dashboard on :8080 (--api.insecure=true), and the catalogue has always
        // meant to offer exactly that port. But the upstream image only EXPOSEs 80 and 443, and Vaier
        // discovers publishable services from a container's exposed ports — so the one container of
        // Vaier's own stack worth publishing never appeared. `expose` is metadata only: it publishes
        // nothing to the host and changes no reachability, it just tells Vaier the port is there.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");

        List<Object> exposed = (List<Object>) traefik.get("expose");
        assertThat(exposed).as("Traefik must declare its dashboard port").isNotNull();
        assertThat(exposed.stream().map(Object::toString).toList()).contains("8080");

        List<Object> published = (List<Object>) traefik.get("ports");
        assertThat(published.stream().map(Object::toString).toList())
            .as("the dashboard must stay unpublished to the host — it is reached through Traefik itself")
            .noneMatch(p -> p.contains("8080"));
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
        // The sign-in buttons pass connector_id=google|github. oauth2-proxy only forwards a
        // user-supplied login param when it matches an `allow` rule — without it, Dex would show its
        // own second chooser instead of jumping straight to the picked provider. Guard the allow-list.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        String render = String.join("\n", (List<String>) init.get("command"));

        assertThat(render).as("connector_id must be an allow-listed login param")
            .contains("name: connector_id, allow:");
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

    // --- #332: each identity provider is independently optional, not both-mandatory ---

    private record DexInitResult(int exitCode, String stdout, String stderr) {}

    @SuppressWarnings("unchecked")
    private String dexInitScript() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> dexInit = (Map<String, Object>) services.get("dex-init");
        List<String> command = (List<String>) dexInit.get("command");
        // command is ["sh", "-c", "<script>"] — the script itself is the last element.
        return command.get(command.size() - 1);
    }

    // Runs the ACTUAL rendered dex-init script under sh, so these tests pin real shell behaviour
    // rather than a regex over the YAML. Two test-only substitutions make that practical without
    // requiring root in the test JVM: /dex/config is redirected to a temp dir (the real path is
    // root-owned), and `chown` is stubbed to a no-op via a PATH-prepended shim (unprivileged users
    // cannot chown to an arbitrary uid). Neither substitution touches the script's own logic.
    private DexInitResult runDexInit(Path tempDir, Map<String, String> providerEnv) throws Exception {
        // docker-compose itself collapses the $${...} escaping to a single $ before the shell ever
        // sees the command — we bypass compose entirely here, so that collapse has to happen in the
        // test too, or the shell reads a literal "$$" as its own PID special parameter.
        Path vaierConfig = Files.createDirectories(tempDir.resolve("vaier-config"));
        String script = dexInitScript()
            .replace("$$", "$")
            .replace("/dex/config", tempDir.toString())
            .replace("/vaier/config", vaierConfig.toString());

        // #264: the zero-provider branch fetches apache2-utils for bcrypt; neither apk nor htpasswd
        // exists in the test JVM's PATH, so both are shimmed — the shape of the config is what is
        // pinned here, not bcrypt itself.
        Path stubBin = Files.createDirectories(tempDir.resolve("stub-bin"));
        Path chownStub = stubBin.resolve("chown");
        Files.writeString(chownStub, "#!/bin/sh\nexit 0\n");
        chownStub.toFile().setExecutable(true);
        Path apkStub = stubBin.resolve("apk");
        Files.writeString(apkStub, "#!/bin/sh\nexit 0\n");
        apkStub.toFile().setExecutable(true);
        Path htpasswdStub = stubBin.resolve("htpasswd");
        Files.writeString(htpasswdStub, "#!/bin/sh\necho ':$2y$10$stubbedbcrypthashstubbedbcrypthashstubbedbcrypthashstub'\n");
        htpasswdStub.toFile().setExecutable(true);

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        Map<String, String> env = builder.environment();
        env.put("PATH", stubBin + File.pathSeparator + env.get("PATH"));
        env.put("VAIER_DOMAIN", "example.com");
        env.put("VAIER_ADMIN_EMAIL", providerEnv.getOrDefault("VAIER_ADMIN_EMAIL", ""));
        env.put("ACME_EMAIL", providerEnv.getOrDefault("ACME_EMAIL", ""));
        env.put("VAIER_DEX_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_DEX_CLIENT_SECRET", "dex-shared-secret"));
        env.put("VAIER_OIDC_GOOGLE_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GOOGLE_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_SECRET", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_SECRET", ""));

        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("dex-init script did not finish within 10s");
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        return new DexInitResult(process.exitValue(), stdout, stderr);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsOnlyTheGoogleConnector_whenOnlyGoogleCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        // A first-run password left over from before the provider was registered (#264): the door
        // closes the moment a provider exists, so the password DB is gone and the file with it.
        Path leftover = Files.createDirectories(tempDir.resolve("vaier-config")).resolve("first-run-password");
        Files.writeString(leftover, "you@example.com\nold-secret\n");

        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
            "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).get("type")).isEqualTo("google");
        assertThat(configYaml).doesNotContainKey("enablePasswordDB").doesNotContainKey("staticPasswords");
        assertThat(Files.exists(leftover)).as("the first-run password is withdrawn once a provider exists").isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsOnlyTheGithubConnector_whenOnlyGithubCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
            "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).get("type")).isEqualTo("github");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_emitsBothConnectors_whenBothProvidersAreSet(@TempDir Path tempDir) throws Exception {
        DexInitResult result = runDexInit(tempDir, Map.of(
            "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
            "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret",
            "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
            "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret"));

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String rendered = Files.readString(tempDir.resolve("config.yaml"));
        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(rendered);
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");

        assertThat(connectors).hasSize(2);
        assertThat(connectors.stream().map(c -> c.get("type"))).containsExactlyInAnyOrder("google", "github");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_mintsAFirstRunPassword_whenNoProviderIsConfigured(@TempDir Path tempDir) throws Exception {
        // #264: zero providers used to fail fast, which left a newcomer with no way in before an OAuth
        // registration. Now Dex opens its own local connector for exactly one account, with a password
        // Vaier prints in its log. The secret survives restarts (the file is the truth); the email
        // follows VAIER_ADMIN_EMAIL, else ACME_EMAIL (the operator's own address, so a later provider
        // sign-in under it finds the admin it already is), else a placeholder on the domain.
        DexInitResult first = runDexInit(tempDir, Map.of());
        assertThat(first.exitCode()).as("stderr: %s", first.stderr()).isEqualTo(0);

        Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(Files.readString(tempDir.resolve("config.yaml")));
        assertThat(configYaml.get("enablePasswordDB")).isEqualTo(true);
        assertThat(configYaml).as("no connectors key, so Dex has no empty list to choke on").doesNotContainKey("connectors");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) configYaml.get("staticPasswords");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).get("email")).isEqualTo("admin@example.com");
        assertThat((String) accounts.get(0).get("hash")).startsWith("$2y$");

        Path file = tempDir.resolve("vaier-config").resolve("first-run-password");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.get(0)).isEqualTo("admin@example.com");
        assertThat(lines.get(1)).as("an unguessable secret").hasSizeGreaterThanOrEqualTo(32).matches("[A-Za-z0-9_-]+");
        assertThat(Files.getPosixFilePermissions(file)).as("readable by its owner only")
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        runDexInit(tempDir, Map.of("ACME_EMAIL", "ops@example.com"));
        assertThat(Files.readAllLines(file).get(0)).as("falls back to ACME_EMAIL").isEqualTo("ops@example.com");

        DexInitResult second = runDexInit(tempDir, Map.of("VAIER_ADMIN_EMAIL", "you@example.com", "ACME_EMAIL", "ops@example.com"));
        assertThat(second.exitCode()).as("stderr: %s", second.stderr()).isEqualTo(0);
        List<String> again = Files.readAllLines(file);
        assertThat(again.get(0)).as("VAIER_ADMIN_EMAIL wins").isEqualTo("you@example.com");
        assertThat(again.get(1)).as("the secret is kept across restarts, so the log never lies").isEqualTo(lines.get(1));
    }

    // --- #264 slice 2: providers added from Settings, in the file Vaier writes ---

    private static void writeSignInSettings(Path tempDir, SignInSettings settings) throws Exception {
        Path vaierConfig = Files.createDirectories(tempDir.resolve("vaier-config"));
        new SignInSettingsFileAdapter(vaierConfig.toString()).save(settings);
    }

    private static SignInSettings googleFromSettings(boolean firstRunDoorOpen) {
        return new SignInSettings(Map.of(IdentityProvider.GOOGLE, new ProviderCredentials("file-id", "file-secret")),
            firstRunDoorOpen);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dexInit_takesAProviderFromSettings_whereDotEnvLeavesItBlank_andKeepsTheFirstRunDoorAsTheFileSays(
            @TempDir Path tempDir) throws Exception {
        record Row(String label, boolean doorOpen, Map<String, String> env, String googleClientId, boolean passwordDb) {}
        for (Row row : List.of(
                new Row("added from Settings, no admin through it yet", true, Map.of(), "file-id", true),
                new Row("an admin came through it", false, Map.of(), "file-id", false),
                new Row(".env wins", false, Map.of(
                    "VAIER_OIDC_GOOGLE_CLIENT_ID", "env-id", "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "env-secret"), "env-id", false))) {
            writeSignInSettings(tempDir, googleFromSettings(row.doorOpen()));

            DexInitResult result = runDexInit(tempDir, row.env());

            assertThat(result.exitCode()).as("%s — stderr: %s", row.label(), result.stderr()).isEqualTo(0);
            Map<String, Object> configYaml = (Map<String, Object>) new Yaml().load(Files.readString(tempDir.resolve("config.yaml")));
            List<Map<String, Object>> connectors = (List<Map<String, Object>>) configYaml.get("connectors");
            assertThat(connectors).as(row.label()).hasSize(1);
            assertThat(((Map<String, Object>) connectors.get(0).get("config")).get("clientID"))
                .as(row.label()).isEqualTo(row.googleClientId());
            assertThat(configYaml.containsKey("staticPasswords")).as(row.label()).isEqualTo(row.passwordDb());
            assertThat(Files.exists(tempDir.resolve("vaier-config/first-run-password"))).as(row.label()).isEqualTo(row.passwordDb());
        }
    }

    @Test
    void dexInit_neverEvaluatesTheSettingsFile_itRunsAsRoot(@TempDir Path tempDir) throws Exception {
        // Vaier only ever writes the safe charset; this is the renderer's own guard against a hand-edited file.
        Path pwned = tempDir.resolve("pwned");
        Files.createDirectories(tempDir.resolve("vaier-config"));
        Files.writeString(tempDir.resolve("vaier-config/sign-in-providers.env"),
            "GOOGLE_CLIENT_ID=abc$(touch " + pwned + ")\nGOOGLE_CLIENT_SECRET=s`touch " + pwned + "`\n");

        DexInitResult result = runDexInit(tempDir, Map.of());

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        assertThat(Files.exists(pwned)).isFalse();
        assertThat(Files.readString(tempDir.resolve("config.yaml"))).doesNotContain("$(").doesNotContain("`");
    }

    @Test
    void dexInit_failsFast_whenDexClientSecretIsBlank(@TempDir Path tempDir) throws Exception {
        Map<String, String> providerEnv = new LinkedHashMap<>();
        providerEnv.put("VAIER_DEX_CLIENT_SECRET", "");
        providerEnv.put("VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id");
        providerEnv.put("VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret");

        DexInitResult result = runDexInit(tempDir, providerEnv);

        assertThat(result.exitCode())
            .as("a blank shared secret must fail fast, never crash-loop Dex behind the login wall")
            .isNotEqualTo(0);
        assertThat(result.stderr()).contains("VAIER_DEX_CLIENT_SECRET");
        assertThat(Files.exists(tempDir.resolve("config.yaml"))).isFalse();
    }

    // --- #332 follow-up: the sign-in page must offer only the providers that are configured ---
    //
    // The connector list became per-provider optional, but the sign-in page kept both buttons
    // hard-coded — so on an install with only Google credentials, "Continue with GitHub" was still
    // offered and Dex answered the click with "Bad Request: Connector ID does not match a valid
    // Connector". oauth2-proxy-init now renders the template the same way dex-init renders the
    // connectors, from the same four variables, so the buttons and the connectors cannot diverge.

    private record InitResult(int exitCode, String stdout, String stderr) {}

    @SuppressWarnings("unchecked")
    private String oauth2ProxyInitScript() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> init = (Map<String, Object>) services.get("oauth2-proxy-init");
        List<String> command = (List<String>) init.get("command");
        // command is ["sh", "-c", "<script>"] — the script itself is the last element.
        return command.get(command.size() - 1);
    }

    // Runs the ACTUAL rendered oauth2-proxy-init script under sh, same substitutions as runDexInit:
    // the runtime config dir is redirected to a temp dir and chown is stubbed out. The committed
    // template mount is redirected at the repo's own oauth2/templates, so these tests render the
    // real sign-in page.
    private InitResult runOauth2ProxyInit(Path tempDir, Map<String, String> providerEnv) throws Exception {
        Path vaierConfig = Files.createDirectories(tempDir.resolve("vaier-config"));
        String script = oauth2ProxyInitScript()
            .replace("$$", "$")
            .replace("/oauth2/config", tempDir.toString())
            .replace("/vaier/config", vaierConfig.toString())
            .replace("/templates-src", Path.of("oauth2/templates").toAbsolutePath().toString());

        Path stubBin = Files.createDirectories(tempDir.resolve("stub-bin"));
        Path chownStub = stubBin.resolve("chown");
        Files.writeString(chownStub, "#!/bin/sh\nexit 0\n");
        chownStub.toFile().setExecutable(true);

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        Map<String, String> env = builder.environment();
        env.put("PATH", stubBin + File.pathSeparator + env.get("PATH"));
        env.put("VAIER_DOMAIN", "example.com");
        env.put("VAIER_DEX_CLIENT_SECRET", "dex-shared-secret");
        env.put("VAIER_OIDC_GOOGLE_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GOOGLE_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GOOGLE_CLIENT_SECRET", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_ID", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_ID", ""));
        env.put("VAIER_OIDC_GITHUB_CLIENT_SECRET", providerEnv.getOrDefault("VAIER_OIDC_GITHUB_CLIENT_SECRET", ""));

        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("oauth2-proxy-init script did not finish within 10s");
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        return new InitResult(process.exitValue(), stdout, stderr);
    }

    private static final Map<String, String> GOOGLE_ONLY = Map.of(
        "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
        "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret");

    private static final Map<String, String> GITHUB_ONLY = Map.of(
        "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
        "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret");

    private static final Map<String, String> BOTH_PROVIDERS = Map.of(
        "VAIER_OIDC_GOOGLE_CLIENT_ID", "google-id",
        "VAIER_OIDC_GOOGLE_CLIENT_SECRET", "google-secret",
        "VAIER_OIDC_GITHUB_CLIENT_ID", "github-id",
        "VAIER_OIDC_GITHUB_CLIENT_SECRET", "github-secret");

    @Test
    void signInPage_offersOnlyGoogle_whenOnlyGoogleCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        InitResult result = runOauth2ProxyInit(tempDir, GOOGLE_ONLY);

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String page = Files.readString(tempDir.resolve("templates/sign_in.html"));

        assertThat(page).contains("value=\"google\"").contains("Continue with Google");
        assertThat(page).as("a button for an unconfigured provider dead-ends in a Dex Bad Request")
            .doesNotContain("value=\"github\"").doesNotContain("Continue with GitHub")
            .doesNotContain("value=\"local\"");
    }

    @Test
    void signInPage_offersOnlyTheFirstRunPassword_whenNoProviderIsConfigured(@TempDir Path tempDir) throws Exception {
        // #264: the button pairs with dex-init's local connector; it is the only way in until a
        // provider exists, and it is gone the moment one does (the test above).
        InitResult result = runOauth2ProxyInit(tempDir, Map.of());

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String page = Files.readString(tempDir.resolve("templates/sign_in.html"));
        assertThat(page).contains("value=\"local\"").contains("first-run password")
            .doesNotContain("value=\"google\"").doesNotContain("value=\"github\"");
        assertThat(Files.readString(tempDir.resolve("alpha.yaml")))
            .as("oauth2-proxy forwards connector_id only when allow-listed").contains("allow: [{value: local}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void signInPage_keepsTheFirstRunPasswordBesideAProviderFromSettings_untilTheDoorCloses(@TempDir Path tempDir)
            throws Exception {
        // #264: the renderer reads the file Vaier writes, read-only.
        Map<String, Object> init = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")))).get("services")).get("oauth2-proxy-init");
        assertThat((List<String>) init.get("volumes")).contains("./vaier/config:/vaier/config:ro");

        writeSignInSettings(tempDir, googleFromSettings(true));
        InitResult open = runOauth2ProxyInit(tempDir, Map.of());
        assertThat(open.exitCode()).as("stderr: %s", open.stderr()).isEqualTo(0);
        String page = Files.readString(tempDir.resolve("templates/sign_in.html"));
        assertThat(page).contains("Continue with Google").contains("value=\"local\"").doesNotContain("value=\"github\"");
        assertThat(page.substring(page.indexOf("<!--provider:local-->")))
            .as("beside a provider, the first-run password is the secondary choice").contains("class=\"btn btn-secondary\"");
        assertThat(Files.readString(tempDir.resolve("alpha.yaml"))).contains("allow: [{value: local}, {value: google}]");

        writeSignInSettings(tempDir, googleFromSettings(false));
        runOauth2ProxyInit(tempDir, Map.of());
        assertThat(Files.readString(tempDir.resolve("templates/sign_in.html")))
            .contains("Continue with Google").doesNotContain("value=\"local\"");
    }

    @Test
    void signInPage_offersOnlyGithub_whenOnlyGithubCredentialsAreSet(@TempDir Path tempDir) throws Exception {
        InitResult result = runOauth2ProxyInit(tempDir, GITHUB_ONLY);

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String page = Files.readString(tempDir.resolve("templates/sign_in.html"));

        assertThat(page).contains("value=\"github\"").contains("Continue with GitHub");
        assertThat(page).doesNotContain("value=\"google\"").doesNotContain("Continue with Google");
        // GitHub is styled as the secondary choice next to Google. Left alone as the only way in,
        // it must not render muted — the lone button is the primary action.
        assertThat(page).as("the only remaining provider is the primary action")
            .doesNotContain("class=\"btn btn-secondary\"");
    }

    @Test
    void signInPage_offersBothProviders_whenBothAreConfigured(@TempDir Path tempDir) throws Exception {
        InitResult result = runOauth2ProxyInit(tempDir, BOTH_PROVIDERS);

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        String page = Files.readString(tempDir.resolve("templates/sign_in.html"));

        assertThat(page).contains("Continue with Google").contains("Continue with GitHub");
        assertThat(page).as("two choices keep their primary/secondary hierarchy")
            .contains("class=\"btn btn-secondary\"");
    }

    @Test
    void signInPage_keepsTheErrorTemplate_soBothOverridesStayInOneDir(@TempDir Path tempDir) throws Exception {
        // oauth2-proxy takes a single --custom-templates-dir. Rendering sign_in.html into it means
        // error.html has to travel along, or the branded error page silently reverts to the default.
        InitResult result = runOauth2ProxyInit(tempDir, BOTH_PROVIDERS);

        assertThat(result.exitCode()).as("stderr: %s", result.stderr()).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("templates/error.html")))
            .isEqualTo(Files.readString(Path.of("oauth2/templates/error.html")));
    }

    @Test
    void loginParamAllowList_matchesTheConfiguredProviders(@TempDir Path tempDir) throws Exception {
        // A connector_id oauth2-proxy forwards for a provider Dex has no connector for is the same
        // Bad Request by another route (a hand-crafted /oauth2/start URL), so the allow-list is
        // rendered from the same credentials as the buttons.
        InitResult googleOnly = runOauth2ProxyInit(tempDir, GOOGLE_ONLY);
        assertThat(googleOnly.exitCode()).as("stderr: %s", googleOnly.stderr()).isEqualTo(0);
        String alpha = Files.readString(tempDir.resolve("alpha.yaml"));

        assertThat(alpha).contains("name: connector_id, allow: [{value: google}]");
    }

    @Test
    void loginParamAllowList_carriesBothProviders_whenBothAreConfigured(@TempDir Path tempDir) throws Exception {
        InitResult both = runOauth2ProxyInit(tempDir, BOTH_PROVIDERS);
        assertThat(both.exitCode()).as("stderr: %s", both.stderr()).isEqualTo(0);
        String alpha = Files.readString(tempDir.resolve("alpha.yaml"));

        assertThat(alpha).contains("name: connector_id, allow: [{value: google}, {value: github}]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oauth2Proxy_readsItsTemplatesFromTheRenderedDir_notTheCommittedSource() throws Exception {
        // The committed template still carries both buttons; only the rendered copy is trimmed to
        // the configured providers. Pointing oauth2-proxy back at the source would restore the bug.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> proxy = (Map<String, Object>) services.get("oauth2-proxy");
        List<String> command = (List<String>) proxy.get("command");

        assertThat(command).contains("--custom-templates-dir=/oauth2/config/templates");
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

    @Test
    @SuppressWarnings("unchecked")
    void traefikEntrypoint_waitsForInfraDnsToResolvePublicly_beforeExecSoTheFirstAcmeAttemptDoesNotBurnLeQuota() throws Exception {
        // Fresh-install race. Vaier no longer creates any DNS record (#331) — the operator's one
        // *.<domain> wildcard answers for vaier/oauth2/dex from the moment it exists, so this wait is
        // normally satisfied instantly. It stays as a fail-open net for the install where the wildcard
        // is missing or has not propagated: Traefik, started by the same `up`, would otherwise ask Let's
        // Encrypt for certs before those names resolve. LE's validator then gets NXDOMAIN, and Traefik's
        // tight retry burst trips LE's "5 failed authorizations per hostname per hour" limit: issuance
        // locks out for an hour and the stack sits on Traefik's self-signed default cert (browsers show
        // ERR_CERT_AUTHORITY_INVALID).
        //
        // The fix lives in Traefik's OWN entrypoint: it holds until the three infra names resolve on a
        // PUBLIC resolver (the class LE queries — not the container's split-horizon/VPC view) BEFORE it
        // execs traefik, and thus before any ACME. It deliberately is NOT a separate gate service that
        // traefik depends_on: `vaier` depends_on `traefik: service_started`, which fires the instant this
        // container starts — so Vaier creates the records WHILE the entrypoint waits here. A completion-
        // gated sidecar would instead deadlock (vaier waits for traefik waits for the gate waits for the
        // records vaier never got to create). Fail-open on a timeout so broken DNS never leaves the box
        // without a reverse proxy forever.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");

        List<String> entrypoint = (List<String>) traefik.get("entrypoint");
        String script = String.join("\n", entrypoint);

        // Waits on all three infra hostnames, built from the base domain...
        assertThat(script)
            .as("entrypoint must wait on the three infra hostnames")
            .contains("vaier oauth2 dex");
        // ...against a PUBLIC resolver, so the wait matches Let's Encrypt's view, not the split-horizon VPC one.
        assertThat(script)
            .as("must query a public resolver, not the container's local/VPC resolver LE never sees")
            .contains("1.1.1.1");
        // ...and the wait must run BEFORE `exec traefik` — otherwise ACME still fires against dead DNS.
        assertThat(script.indexOf("1.1.1.1"))
            .as("the DNS wait must precede `exec traefik`, or Traefik asks ACME before the wait")
            .isGreaterThanOrEqualTo(0)
            .isLessThan(script.indexOf("exec traefik"));
        // ...and fail-open so a genuinely broken DNS never leaves the box permanently proxy-less.
        assertThat(script.toLowerCase())
            .as("the wait must fail-open on a timeout")
            .contains("timed out");

        // The entrypoint needs the base domain to build the hostnames it waits on.
        Map<String, Object> env = (Map<String, Object>) traefik.get("environment");
        assertThat(env)
            .as("traefik needs VAIER_DOMAIN in its environment to build the infra hostnames")
            .containsKey("VAIER_DOMAIN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void traefikCarriesInternalAliasesForInfraHostnames_soServiceToServiceCallsSkipPublicDnsAndItsNegativeCache() throws Exception {
        // oauth2-proxy (and Vaier) run OIDC discovery against https://dex.<domain> BY ITS PUBLIC NAME — the
        // issuer must match, so an internal http://dex:5556 shortcut is not an option. If the wildcard
        // record is missing or still propagating, a container that resolves dex.<domain> too early gets
        // NXDOMAIN and poisons its resolver's NEGATIVE cache (a zone's SOA negative TTL is commonly
        // ~15 min) — which crash-looped oauth2-proxy on "no such host" long after the name resolved.
        // Aliasing each infra
        // hostname onto Traefik makes Docker's embedded DNS answer them from its own registry (straight to
        // Traefik) before ever forwarding externally: no public DNS, no negative cache. Traefik then
        // terminates TLS with the real cert and routes on. This is the internal-resolution complement to the
        // entrypoint's public-DNS ACME wait.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> traefik = (Map<String, Object>) services.get("traefik");

        Object networks = traefik.get("networks");
        assertThat(networks)
            .as("traefik must join vaier-network in long form so it can carry aliases")
            .isInstanceOf(Map.class);
        Map<String, Object> vaierNet = (Map<String, Object>) ((Map<String, Object>) networks).get("vaier-network");
        assertThat(vaierNet).as("traefik must still be on vaier-network").isNotNull();

        List<String> aliases = (List<String>) vaierNet.get("aliases");
        assertThat(aliases)
            .as("the three infra hostnames must resolve to Traefik from inside the stack")
            .contains("vaier.${VAIER_DOMAIN}", "oauth2.${VAIER_DOMAIN}", "dex.${VAIER_DOMAIN}");
    }

    // --- #258 edge hardening: security headers + TLS options -------------------------------------
    //
    // `traefik/` is gitignored in its entirety, so the edge's security policy cannot be a committed
    // file. It is RENDERED by Traefik's own entrypoint before `exec traefik`. These tests run that
    // real entrypoint under `sh` (stubbing only the binaries a test JVM cannot have — getent, ip,
    // nslookup, traefik) and assert on the file it actually produces, rather than regexing YAML.

    @Test
    @SuppressWarnings("unchecked")
    void dockerProxy_carriesItsRulesDigest_soComposeRecreatesItWhenTheRulesChange() throws Exception {
        // Compose does not notice an edit inside an inline `configs:` template, so an upgrade kept the old
        // haproxy rules (#264: every Settings save got 403). A changed label does force the recreate.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        String template = (String) ((Map<String, Object>) ((Map<String, Object>) compose.get("configs"))
            .get("haproxy_template")).get("content");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(template.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        List<String> labels = (List<String>) ((Map<String, Object>) composeServices().get("docker-proxy")).get("labels");

        assertThat(labels).as("set the docker-proxy label to this digest").contains("vaier.rules-digest=" + digest);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> composeServices() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private List<String> traefikCommand() throws Exception {
        Map<String, Object> traefik = (Map<String, Object>) composeServices().get("traefik");
        return (List<String>) traefik.get("command");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> labelsOf(String serviceName) throws Exception {
        Map<String, Object> service = (Map<String, Object>) composeServices().get(serviceName);
        List<String> labels = (List<String>) service.get("labels");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String label : labels) {
            int eq = label.indexOf('=');
            if (eq > 0) {
                byKey.put(label.substring(0, eq), label.substring(eq + 1));
            }
        }
        return byKey;
    }

    /** The lines of {@code text} that are not comments — what a parser actually sees. */
    private String withoutComments(String text) {
        return text.lines()
            .filter(line -> !line.trim().startsWith("#"))
            .collect(Collectors.joining("\n"));
    }

    /**
     * Runs the real traefik entrypoint under {@code sh} and returns the dynamic-config directory it
     * wrote into. {@code VAIER_DOMAIN} is left empty so the ACME DNS wait short-circuits; the
     * container-only binaries are stubbed on PATH exactly the way {@link #runDexInit} stubs chown.
     */
    @SuppressWarnings("unchecked")
    private Path runTraefikEntrypoint(Path tempDir) throws Exception {
        Map<String, Object> traefik = (Map<String, Object>) composeServices().get("traefik");
        List<String> entrypoint = (List<String>) traefik.get("entrypoint");
        Path configDir = Files.createDirectories(tempDir.resolve("traefik-config"));

        // docker-compose collapses $${...} to a single $ before the shell sees it; we bypass compose
        // here, so the collapse has to happen in the test too.
        String script = entrypoint.get(entrypoint.size() - 1)
            .replace("$$", "$")
            .replace("/traefik/config", configDir.toString());

        Path stubBin = Files.createDirectories(tempDir.resolve("stub-bin"));
        for (String binary : List.of("getent", "ip", "nslookup", "traefik")) {
            Path stub = stubBin.resolve(binary);
            Files.writeString(stub, "#!/bin/sh\nexit 0\n");
            stub.toFile().setExecutable(true);
        }

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", script);
        Map<String, String> env = builder.environment();
        env.put("PATH", stubBin + File.pathSeparator + env.get("PATH"));
        env.put("VAIER_DOMAIN", "");

        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("traefik entrypoint did not finish within 10s");
        }
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertThat(process.exitValue()).as("traefik entrypoint failed. stderr: %s", stderr).isEqualTo(0);
        return configDir;
    }

    @Test
    @SuppressWarnings("unchecked")
    void traefikEntrypoint_rendersTheSafeSecurityHeadersMiddlewareBeforeTraefikStarts(@TempDir Path tempDir)
            throws Exception {
        // nosniff and a referrer policy are the two headers that are safe on a THIRD-PARTY app Vaier
        // did not write: nosniff cannot break a correctly-typed response, and
        // strict-origin-when-cross-origin is already the modern browser default, so overwriting an
        // app's own value can never break a flow that depends on the referrer.
        Path configDir = runTraefikEntrypoint(tempDir);
        Path securityFile = configDir.resolve("security.yml");

        assertThat(Files.exists(securityFile))
            .as("the edge policy must exist before traefik parses a router — traefik/ is gitignored, "
                + "so it has to be rendered at boot")
            .isTrue();

        Map<String, Object> rendered = (Map<String, Object>) new Yaml().load(Files.readString(securityFile));
        Map<String, Object> middlewares =
            (Map<String, Object>) ((Map<String, Object>) rendered.get("http")).get("middlewares");
        Map<String, Object> headers =
            (Map<String, Object>) ((Map<String, Object>) middlewares.get("vaier-security-headers")).get("headers");

        assertThat(headers.get("contentTypeNosniff")).isEqualTo(true);
        assertThat(headers.get("referrerPolicy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void safeHeaders_ridePerEntrypointSoEveryRouterCarriesThem_publishedServicesIncluded(@TempDir Path tempDir)
            throws Exception {
        // Applying the safe headers at the `websecure` ENTRYPOINT rather than per router is what makes
        // "every router" true by construction: it covers the compose-label routers, every route
        // TraefikReverseProxyAdapter generates, and any route added by hand later — with no backfill
        // and without touching remote-apps.yml, so the adapter's middleware readers cannot regress.
        assertThat(traefikCommand())
            .as("the safe headers must be bound to the entrypoint, not to individual routers")
            .contains("--entrypoints.websecure.http.middlewares=vaier-security-headers@file");

        // ...and the middleware it names has to exist, or Traefik disables every websecure router.
        Path configDir = runTraefikEntrypoint(tempDir);
        Map<String, Object> rendered = (Map<String, Object>) new Yaml()
            .load(Files.readString(configDir.resolve("security.yml")));
        assertThat((Map<String, Object>) ((Map<String, Object>) rendered.get("http")).get("middlewares"))
            .as("the entrypoint reference must resolve in the file provider")
            .containsKey("vaier-security-headers");
    }

    @Test
    @SuppressWarnings("unchecked")
    void crowdsecBouncer_isTheStreamModePlugin_andNoLongerRidesTheEntrypoint(@TempDir Path tempDir)
            throws Exception {
        // #351: on the entrypoint it judged the recovery doors too, and an entrypoint middleware
        // cannot be taken off one router. It rides each router instead (see the recovery-door guard).
        assertThat(traefikCommand())
            .filteredOn(arg -> arg.startsWith("--entrypoints."))
            .noneMatch(arg -> arg.contains("crowdsec-bouncer"));

        // The bouncer is Traefik's CrowdSec plugin in STREAM mode, not a forward-auth hop. The standalone
        // bouncer it replaced asked the Security Engine about every request, and under one page load's
        // burst that SQLite-backed API serialised to a second per request. Pinned like every upstream.
        assertThat(traefikCommand())
            .contains("--experimental.plugins.bouncer.modulename=github.com/maxlerebourg/crowdsec-bouncer-traefik-plugin")
            .anyMatch(arg -> arg.matches("--experimental\\.plugins\\.bouncer\\.version=v\\d+\\.\\d+\\.\\d+"));

        // ...and the middleware it names has to exist and be that plugin, or Traefik disables every
        // websecure router.
        Path configDir = runTraefikEntrypoint(tempDir);
        Map<String, Object> rendered = (Map<String, Object>) new Yaml()
            .load(Files.readString(configDir.resolve("security.yml")));
        Map<String, Object> middlewares =
            (Map<String, Object>) ((Map<String, Object>) rendered.get("http")).get("middlewares");
        Map<String, Object> bouncer = (Map<String, Object>) ((Map<String, Object>)
            ((Map<String, Object>) middlewares.get("crowdsec-bouncer")).get("plugin")).get("bouncer");

        assertThat(bouncer.get("crowdsecMode")).isEqualTo("stream");
        assertThat(bouncer.get("crowdsecLapiHost")).isEqualTo("crowdsec:8080");
        // An engine that cannot be reached keeps the LAST list in force rather than turning every route away.
        assertThat(bouncer.get("updateMaxFailure")).isEqualTo(-1);
        // The key reaches the plugin through the file provider's own templating, from the same
        // install.sh-generated secret crowdsec self-registers at boot — it is never written to disk.
        assertThat(bouncer.get("crowdsecLapiKey")).isEqualTo("{{ env \"CROWDSEC_BOUNCER_API_KEY\" }}");
        Map<String, Object> traefik = (Map<String, Object>) composeServices().get("traefik");
        assertThat((String) ((Map<String, Object>) traefik.get("environment")).get("CROWDSEC_BOUNCER_API_KEY"))
            .as("a blank key must stop the stack at config-parse time, as it did for the old bouncer")
            .startsWith("${VAIER_CROWDSEC_BOUNCER_KEY:?");
        // Traefik keeps the downloaded plugin here, so a restart never depends on the plugin registry.
        assertThat((List<String>) traefik.get("volumes")).contains("./traefik/plugins-storage:/plugins-storage");
        // ...and the standalone bouncer container is gone.
        assertThat(composeServices()).doesNotContainKey("crowdsec-bouncer");
    }

    /**
     * #351: the recovery doors — Vaier's own sign-in path — are the only routers the bouncer does not
     * judge, so a banned operator can still sign in and lift the ban. Every other router carries it
     * first. A router added without it fails here, so the exemption cannot quietly widen.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyComposeRouterCarriesTheBouncerFirst_exceptExactlyTheRecoveryDoors() throws Exception {
        Set<String> recoveryDoors = Set.of("vaier", "vaier-public", "vaier-identity", "vaier-oauth2",
            "vaier-offline", "oauth2-proxy", "dex");
        Pattern routerRule = Pattern.compile("traefik\\.(http|tcp)\\.routers\\.([^.]+)\\.rule");

        Map<String, String> chains = new LinkedHashMap<>();
        for (Map.Entry<String, Object> service : composeServices().entrySet()) {
            if (((Map<String, Object>) service.getValue()).get("labels") == null) continue;
            Map<String, String> labels = labelsOf(service.getKey());
            for (String key : labels.keySet()) {
                Matcher router = routerRule.matcher(key);
                if (router.matches()) {
                    chains.put(router.group(2), labels.getOrDefault(
                        "traefik." + router.group(1) + ".routers." + router.group(2) + ".middlewares", ""));
                }
            }
        }

        Set<String> withoutBouncer = new HashSet<>();
        chains.forEach((router, chain) -> {
            if (!(chain + ",").startsWith("crowdsec-bouncer@file,")) withoutBouncer.add(router);
            assertThat(chain).as("%s: the bouncer goes first or not at all", router)
                .doesNotContain(",crowdsec-bouncer");
        });
        assertThat(withoutBouncer).containsExactlyInAnyOrderElementsOf(recoveryDoors);
        // Anonymous and rate-limited, the phone's join door is exactly what CrowdSec should judge.
        assertThat(chains.get("vaier-enrolment")).startsWith("crowdsec-bouncer@file,");
    }

    @Test
    void frameProtection_neverRidesTheEntrypoint_soAPublishedThirdPartyAppIsUnaffected() throws Exception {
        // A published app may legitimately embed, or be embedded by, another site. Vaier generates
        // those routers, so a frame default would break them silently and at scale.
        String entrypointChain = traefikCommand().stream()
            .filter(arg -> arg.startsWith("--entrypoints.websecure.http.middlewares="))
            .findFirst()
            .orElse("");
        assertThat(entrypointChain)
            .as("frame protection must not be applied fleet-wide")
            .doesNotContain("vaier-frame-guard");
    }

    @Test
    @SuppressWarnings("unchecked")
    void frameGuard_isSameOriginNotDeny_becauseTheExplorerFramesItsOwnPages(@TempDir Path tempDir) throws Exception {
        // explorer-shell.js renders the not-yet-ported globals (Users, Concepts) in a same-origin
        // iframe. X-Frame-Options: DENY would blank those panes, so the guard is SAMEORIGIN.
        Path configDir = runTraefikEntrypoint(tempDir);
        Map<String, Object> rendered = (Map<String, Object>) new Yaml()
            .load(Files.readString(configDir.resolve("security.yml")));
        Map<String, Object> middlewares =
            (Map<String, Object>) ((Map<String, Object>) rendered.get("http")).get("middlewares");
        Map<String, Object> headers =
            (Map<String, Object>) ((Map<String, Object>) middlewares.get("vaier-frame-guard")).get("headers");

        assertThat(headers.get("customFrameOptionsValue")).isEqualTo("SAMEORIGIN");
        assertThat(headers.get("frameDeny"))
            .as("DENY would break the Explorer's own bridged panes")
            .isNotEqualTo(true);
    }

    @Test
    void frameGuard_ridesEveryVaierOwnedRouter() throws Exception {
        Map<String, String> vaierLabels = labelsOf("vaier");
        for (String router : List.of("vaier", "vaier-public", "vaier-identity", "vaier-oauth2",
                "vaier-enrolment")) {
            assertThat(vaierLabels.get("traefik.http.routers." + router + ".middlewares"))
                .as("%s is one of Vaier's own surfaces and must carry frame protection", router)
                .contains("vaier-frame-guard@file");
        }
        assertThat(labelsOf("oauth2-proxy").get("traefik.http.routers.oauth2-proxy.middlewares"))
            .contains("vaier-frame-guard@file");
        assertThat(labelsOf("dex").get("traefik.http.routers.dex.middlewares"))
            .contains("vaier-frame-guard@file");
        assertThat(labelsOf("vaier-offline").get("traefik.http.routers.vaier-offline.middlewares"))
            .contains("vaier-frame-guard@file");
    }

    @Test
    void frameGuard_isAppendedAfterTheAuthChain_soTheAdaptersMiddlewareReadersAreUnaffected() throws Exception {
        // TraefikReverseProxyAdapter.extractAuthInfoFromMiddlewareNames returns the FIRST auth
        // middleware on a router. The guard is not one of Vaier's auth middlewares and it is appended
        // last, so what the console reports for its own routers is byte-identical to before.
        Map<String, String> labels = labelsOf("vaier");
        String consoleChain = labels.get("traefik.http.routers.vaier.middlewares");
        assertThat(consoleChain).startsWith("oauth2-signin@file,oauth2-authn@file,vaier-authz@file,vaier-down");
        assertThat(consoleChain.indexOf("vaier-frame-guard"))
            .isGreaterThan(consoleChain.indexOf("vaier-authz@file"));

        assertThat(AuthMode.isAuthMiddlewareName("vaier-frame-guard@file"))
            .as("the guard must not read as an auth middleware, or a public route reports as gated")
            .isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("vaier-security-headers@file"))
            .isFalse();
    }

    @Test
    void edgePolicy_setsNoContentSecurityPolicy_becauseTheApplicationOwnsIt(@TempDir Path tempDir) throws Exception {
        // GET /machines/{id}/files/view serves every previewed file under its own tight, per-media-type
        // CSP (ViewableFile.SANDBOXED_POLICY / PDF_POLICY). A CSP at the edge would either overwrite
        // that one — silently weakening a security boundary — or stack with it, and a browser enforces
        // the INTERSECTION of every CSP header it receives, which breaks file viewing outright.
        String rendered = Files.readString(runTraefikEntrypoint(tempDir).resolve("security.yml"));

        assertThat(rendered.toLowerCase())
            .as("the edge must never set a CSP — the application owns it")
            .doesNotContain("contentsecuritypolicy:")
            .doesNotContain("content-security-policy:");
        assertThat(rendered)
            .as("a reader must find out WHY the CSP is absent without going digging")
            .contains("Content-Security-Policy");
    }

    @Test
    void edgePolicy_setsNoHsts_becauseItIsDeferredToItsOwnIssue(@TempDir Path tempDir) throws Exception {
        // HSTS cannot be taken back once a browser has seen it, so it is a deliberate decision of its
        // own (issue #342) rather than a side effect of this file — not even commented out.
        String rendered = Files.readString(runTraefikEntrypoint(tempDir).resolve("security.yml"));

        assertThat(withoutComments(rendered).toLowerCase())
            .doesNotContain("stsseconds")
            .doesNotContain("strict-transport-security")
            .doesNotContain("includesubdomains")
            .doesNotContain("preload");
        assertThat(rendered)
            .as("a reader must find the deferral, and where it is tracked, in the file itself")
            .contains("#342");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tlsOptions_defaultRaisesTheFloorToTls12AndPrunesWeakSuites(@TempDir Path tempDir) throws Exception {
        // `default` is Traefik's implicit option set: every router that does not name one gets it, so
        // this covers the compose-label routers AND every generated published-service router without
        // touching a single router definition.
        Map<String, Object> rendered = (Map<String, Object>) new Yaml()
            .load(Files.readString(runTraefikEntrypoint(tempDir).resolve("security.yml")));
        Map<String, Object> options =
            (Map<String, Object>) ((Map<String, Object>) rendered.get("tls")).get("options");
        Map<String, Object> defaults = (Map<String, Object>) options.get("default");

        assertThat(defaults.get("minVersion")).isEqualTo("VersionTLS12");

        List<String> suites = (List<String>) defaults.get("cipherSuites");
        assertThat(suites).isNotEmpty();
        assertThat(suites).allSatisfy(suite -> assertThat(suite)
            .as("forward secrecy only — no static RSA key exchange")
            .startsWith("TLS_ECDHE_"));
        assertThat(suites).allSatisfy(suite -> assertThat(suite)
            .as("AEAD only — CBC/RC4/3DES suites are the weak ones being pruned")
            .matches(".*(_GCM_|CHACHA20_POLY1305).*"));
        assertThat(suites).noneMatch(suite -> suite.contains("_CBC_")
            || suite.contains("_RC4_")
            || suite.contains("3DES"));
    }

    @Test
    void tlsOptions_cannotInterfereWithAcme_becauseTheHttp01ChallengeIsServedOverPlainHttp() throws Exception {
        List<String> command = traefikCommand();

        assertThat(command)
            .as("HTTP-01 stays on the plain `web` entrypoint, which terminates no TLS at all")
            .contains("--certificatesresolvers.letsencrypt.acme.httpchallenge=true")
            .contains("--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web");
        assertThat(command)
            .as("no TLS-ALPN challenge, so the pruned suites and TLS floor are not on the issuance path")
            .noneMatch(arg -> arg.contains("tlschallenge"));
        assertThat(command)
            .as("the safe headers must not be bolted onto the entrypoint that serves the ACME challenge")
            .noneMatch(arg -> arg.startsWith("--entrypoints.web.http.middlewares"));
    }

    @Test
    void edgePolicy_isRewrittenOnEveryBoot_soAStaleOrDeletedFileSelfHeals(@TempDir Path tempDir) throws Exception {
        Path configDir = runTraefikEntrypoint(tempDir);
        Path securityFile = configDir.resolve("security.yml");
        String first = Files.readString(securityFile);

        Files.writeString(securityFile, "http: {}\n");
        Path second = runTraefikEntrypoint(tempDir);

        assertThat(Files.readString(second.resolve("security.yml")))
            .as("the rendered policy is generated state, not operator state")
            .isEqualTo(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgePolicy_neverLandsInTheFileVaierOwns() throws Exception {
        // Vaier writes exactly one file into the watched directory, by atomic move. A second file is
        // safe; writing INTO remote-apps.yml would be clobbered by the next publish.
        String script = String.join("\n",
            (List<String>) ((Map<String, Object>) composeServices().get("traefik")).get("entrypoint"));
        assertThat(withoutComments(script))
            .as("the edge policy is a second file; the one Vaier owns must never be a write target here")
            .doesNotContain("remote-apps.yml");
    }

}
