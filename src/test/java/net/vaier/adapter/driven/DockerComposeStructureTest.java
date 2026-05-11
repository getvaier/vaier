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
    void vaierInit_dependsOnInitsThatWriteIntoChownedDirs() throws Exception {
        // vaier-init chowns ./authelia/config (and friends) to UID 1000. authelia-init
        // and redis-init both write into ./authelia/config as root. With no explicit
        // depends_on, vaier-init can win the race, chown an empty dir, and then the
        // root-written files land un-chowned — which crash-loops vaier on the next
        // AutheliaAssetsAdapter.publishAssets() with AccessDeniedException.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> vaierInit = (Map<String, Object>) services.get("vaier-init");
        Map<String, Object> dependsOn = (Map<String, Object>) vaierInit.get("depends_on");

        assertThat(dependsOn)
            .as("vaier-init must wait for inits that write into the dirs it chowns")
            .isNotNull()
            .containsKey("authelia-init")
            .containsKey("redis-init");

        Map<String, Object> autheliaCond = (Map<String, Object>) dependsOn.get("authelia-init");
        Map<String, Object> redisCond = (Map<String, Object>) dependsOn.get("redis-init");
        assertThat(autheliaCond).containsEntry("condition", "service_completed_successfully");
        assertThat(redisCond).containsEntry("condition", "service_completed_successfully");
    }

    @Test
    @SuppressWarnings("unchecked")
    void authelia_runsAsUid1000_soItDoesNotReRootTheSharedConfigDir() throws Exception {
        // The authelia/authelia image entrypoint, when it runs as root, does
        //   chown -R "${PUID}:${PGID}" /config
        // and PUID/PGID default to 0 — so every (re)start re-roots ./authelia/config,
        // which the vaier container (UID 1000) shares with it. vaier then can't write
        // the bootstrap password file or the users database. Pinning PUID/PGID to 1000
        // makes the entrypoint chown /config to 1000 and drop privileges to 1000.
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> authelia = (Map<String, Object>) services.get("authelia");
        List<String> environment = (List<String>) authelia.get("environment");

        assertThat(environment)
            .as("authelia must run as UID 1000 so it stops re-rooting the config dir vaier shares")
            .contains("PUID=1000", "PGID=1000");
    }
}
