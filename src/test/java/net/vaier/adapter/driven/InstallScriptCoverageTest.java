package net.vaier.adapter.driven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        "dex/config"
    );

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
