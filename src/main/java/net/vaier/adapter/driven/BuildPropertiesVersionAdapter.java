package net.vaier.adapter.driven;

import net.vaier.domain.port.ForReadingAppVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Reads the deployed version from Spring's {@link BuildProperties}, which is generated into the
 * jar at package time from the Maven {@code project.version} (see the {@code build-info} goal in
 * {@code pom.xml}). When no build metadata is present — e.g. running straight from the IDE without
 * the goal — it falls back to a stable placeholder so the UI never shows a blank version.
 */
@Component
public class BuildPropertiesVersionAdapter implements ForReadingAppVersion {

    static final String UNKNOWN_VERSION = "dev";

    private final ObjectProvider<BuildProperties> buildProperties;

    public BuildPropertiesVersionAdapter(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Override
    public String currentVersion() {
        BuildProperties bp = buildProperties.getIfAvailable();
        if (bp == null) {
            return UNKNOWN_VERSION;
        }
        String version = bp.getVersion();
        return (version == null || version.isBlank()) ? UNKNOWN_VERSION : version;
    }
}
