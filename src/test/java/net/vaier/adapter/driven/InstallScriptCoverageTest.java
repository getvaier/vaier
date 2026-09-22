package net.vaier.adapter.driven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quick-start installs Vaier with no git clone — {@code install.sh} fetches only the runtime
 * files the compose stack bind-mounts. A missing bind-mount source is silently created by dockerd
 * as an empty directory, which then fails a single-file mount (nginx's default.conf) at container
 * start. This guard keeps install.sh's fetch list in lock-step with docker-compose.yml: every
 * host bind-mount source must be classified as either a committed asset install.sh fetches, or a
 * runtime dir an init container/volume creates — a new mount that is neither fails this test.
 */
class InstallScriptCoverageTest {

    // Bind-mount sources that ship as committed content and MUST be fetched by install.sh, because
    // nothing at runtime creates them (offline nginx conf/html, oauth2-proxy templates, Dex theme).
    // These live under the roots install.sh's RUNTIME_PATHS array pulls from the release tarball.

    // Bind-mount sources that are created at runtime — by an init container, a named volume, or the
    // app itself — so install.sh must NOT fetch them (they are gitignored and absent from a checkout).
    private static final Set<String> RUNTIME_GENERATED_SOURCES = Set.of(
        "wireguard/config",
        "traefik/config",
        "traefik/acme",
        "geoip",
        "vaier/config",
        "icons",
        "oauth2/config",
        "dex/config",
        // #329: Traefik writes the access log; traefik-logrotate rotates it in place.
        "traefik/logs",
        // #329: the Security Engine's own state (hub cache, sqlite db, machine credentials).
        "crowdsec/config",
        "crowdsec/data",
        // #329: Vaier's SecurityService writes the rendered trusted-networks allowlist here.
        "crowdsec/whitelist",
        // Traefik downloads its CrowdSec bouncer plugin here on first start and keeps it.
        "traefik/plugins-storage"
    );

    // Variables the OPERATOR fills in — their domain, their identity-provider credentials, optional
    // tuning. Blank is a legitimate state for these: an unset VAIER_PUBLIC_IP means "work it out",
    // and a missing provider pair means "I don't use that provider" (dex-init says so by name).
    private static final Set<String> OPERATOR_AUTHORED_VARS = Set.of(
        "VAIER_DOMAIN",
        "ACME_EMAIL",
        "VAIER_TZ",
        "VAIER_PUBLIC_HOST",
        "VAIER_PUBLIC_IP",
        "VAIER_SERVER_LAN_CIDR",
        "VAIER_ADMIN_EMAIL",
        "VAIER_OIDC_GOOGLE_CLIENT_ID",
        "VAIER_OIDC_GOOGLE_CLIENT_SECRET",
        "VAIER_OIDC_GITHUB_CLIENT_ID",
        "VAIER_OIDC_GITHUB_CLIENT_SECRET"
    );

    // Secrets NO operator ever types — install.sh generates them from a random source. Blank is never
    // a choice here, it is damage: a .env that predates the secret, or one that was hand-edited.
    private static final Set<String> AUTO_GENERATED_SECRETS = Set.of(
        "VAIER_DEX_CLIENT_SECRET",
        "VAIER_OAUTH2_COOKIE_SECRET",
        // #329. The one that proved the point: on a .env written before CrowdSec landed this rendered
        // empty, crowdsec-bouncer exited 1, and because its forwardAuth sits on the websecure
        // entrypoint ahead of every other middleware, Traefik answered EVERY route with a bodiless
        // 500 — console included. Silence, on an update, from a variable nobody had heard of.
        "VAIER_CROWDSEC_BOUNCER_KEY"
    );

    /**
     * Every compose interpolation of a Vaier variable, mapped to the modifiers used at each site —
     * {@code ":-"}, {@code ":-UTC"}, {@code ":?msg"} or {@code ""} for a bare reference.
     *
     * <p>The negative lookbehind matters: {@code $${VAIER_X}} is an <em>escaped</em> reference compose
     * passes through verbatim for an init container's own shell to expand, not an interpolation. Those
     * are the container's business and must not be counted here.
     */
    private Map<String, List<String>> composeVariableReferences() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));
        Matcher m = Pattern.compile("(?<!\\$)\\$\\{(VAIER_[A-Z0-9_]+|ACME_EMAIL)([^}]*)}").matcher(compose);
        Map<String, List<String>> refs = new LinkedHashMap<>();
        while (m.find()) {
            refs.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(m.group(2));
        }
        return refs;
    }

    private List<String> composeBindMountSources() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));
        // Match `- ./<source>:<target>...` host bind mounts (both the read-only and writable forms).
        Matcher m = Pattern.compile("(?m)^\\s*-\\s+\\./([^:\\s]+):").matcher(compose);
        List<String> sources = new ArrayList<>();
        while (m.find()) {
            String src = m.group(1);
            if (!sources.contains(src)) {
                sources.add(src);
            }
        }
        return sources;
    }

    private List<String> installScriptRuntimePaths() throws Exception {
        String script = Files.readString(Path.of("install.sh"));
        // The single source of truth in install.sh is the RUNTIME_PATHS=( ... ) array.
        Matcher block = Pattern.compile("RUNTIME_PATHS=\\(([^)]*)\\)", Pattern.DOTALL).matcher(script);
        assertThat(block.find()).as("install.sh must declare a RUNTIME_PATHS=( ... ) array").isTrue();
        List<String> paths = new ArrayList<>();
        for (String line : block.group(1).split("\\R")) {
            String token = line.replaceAll("#.*$", "").trim();
            if (!token.isEmpty()) {
                paths.add(token);
            }
        }
        return paths;
    }

    private boolean covers(List<String> roots, String source) {
        return roots.stream().anyMatch(root -> source.equals(root) || source.startsWith(root + "/"));
    }

    @Test
    void installScriptFetchesTheComposeFileItself() throws Exception {
        assertThat(installScriptRuntimePaths())
            .as("install.sh must fetch docker-compose.yml — it's the whole point of the no-clone install")
            .contains("docker-compose.yml");
    }

    @Test
    void everyBindMountSourceIsEitherFetchedByInstallScriptOrRuntimeGenerated() throws Exception {
        List<String> fetched = installScriptRuntimePaths();
        for (String source : composeBindMountSources()) {
            boolean isFetched = covers(fetched, source);
            boolean isGenerated = covers(new ArrayList<>(RUNTIME_GENERATED_SOURCES), source);
            assertThat(isFetched ^ isGenerated)
                .as("compose bind-mount ./%s must be EITHER fetched by install.sh's RUNTIME_PATHS "
                    + "(a committed asset) OR listed as runtime-generated in this test — never both, "
                    + "never neither. Classify the new mount so install.sh stays in sync.", source)
                .isTrue();
        }
    }

    @Test
    void everyComposeVariableIsClassifiedAsOperatorAuthoredOrAutoGenerated() throws Exception {
        for (String var : composeVariableReferences().keySet()) {
            boolean operatorAuthored = OPERATOR_AUTHORED_VARS.contains(var);
            boolean autoGenerated = AUTO_GENERATED_SECRETS.contains(var);
            assertThat(operatorAuthored ^ autoGenerated)
                .as("compose variable %s must be classified in this test as EITHER operator-authored "
                    + "OR auto-generated — never both, never neither. A new one is a decision: if the "
                    + "operator never types it, install.sh has to generate it and compose has to "
                    + "demand it.", var)
                .isTrue();
        }
    }

    @Test
    void installScriptGeneratesEveryAutoGeneratedSecret_soAFreshEnvDoesNotCrashLoopDex() throws Exception {
        // Nothing in the compose stack can generate these: a .env without VAIER_DEX_CLIENT_SECRET
        // renders an empty Dex static-client secret and Dex crash-loops ("Secret ... is required for
        // client vaier-oauth2"). Derived from AUTO_GENERATED_SECRETS rather than listed by hand — a
        // hand-written list is what let #329's bouncer key ship ungenerated.
        String script = Files.readString(Path.of("install.sh"));
        for (String secret : AUTO_GENERATED_SECRETS) {
            assertThat(script)
                .as("install.sh must generate %s into .env — no operator ever types it", secret)
                .contains("ensure_secret " + secret);
        }
        assertThat(script.contains("openssl rand") || script.contains("/dev/urandom"))
            .as("install.sh must generate the secrets from a random source, not a fixed placeholder")
            .isTrue();
    }

    @Test
    void everyAutoGeneratedSecretIsMandatoryInCompose_soAnUpdateCannotSilentlyRenderItEmpty()
            throws Exception {
        // install.sh generating a secret only helps a .env that install.sh has since run against.
        // An EXISTING install updates by fetching a newer docker-compose.yml, and its .env predates
        // every secret added after it was written — so `${SECRET:-}` quietly interpolates to empty
        // and the stack comes up wrong rather than not at all. Compose's mandatory form `${SECRET:?}`
        // is the only guard that works for crowdsec-bouncer, which is distroless: no shell, so it
        // cannot fail-fast on its own the way dex-init does. It also fails during config parse,
        // which leaves a running stack running instead of half-replacing it.
        Map<String, List<String>> refs = composeVariableReferences();
        for (String secret : AUTO_GENERATED_SECRETS) {
            assertThat(refs).as("compose must reference the generated secret %s", secret)
                .containsKey(secret);
            for (String modifier : refs.get(secret)) {
                assertThat(modifier)
                    .as("compose must reference auto-generated secret %s in the mandatory form "
                        + "${%s:?message} so a .env missing it fails loudly and by name. Found "
                        + "\"${%s%s}\", which interpolates to empty and brings the stack up broken.",
                        secret, secret, secret, modifier)
                    .startsWith(":?");
                assertThat(modifier.length())
                    .as("the ${%s:?...} guard must carry a message telling the operator how to fix "
                        + "it — compose prints it verbatim and it is the only thing they will see",
                        secret)
                    .isGreaterThan(2);
            }
        }
    }

    @Test
    void committedAssetTreesAreActuallyFetched() throws Exception {
        List<String> fetched = installScriptRuntimePaths();
        // The three trees that broke a curl-only install: nginx offline page, oauth2 templates, Dex theme.
        for (String asset : List.of("offline/default.conf", "offline/html", "oauth2/templates", "dex/themes/vaier")) {
            assertThat(Files.exists(Path.of(asset)))
                .as("committed asset %s must exist in the repo", asset).isTrue();
            assertThat(covers(fetched, asset))
                .as("install.sh must fetch committed asset %s, or a no-clone install fails at container start", asset)
                .isTrue();
        }
    }
}
