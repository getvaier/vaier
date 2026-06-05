package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildPropertiesVersionAdapterTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<BuildProperties> provider(BuildProperties value) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private BuildProperties buildProperties(String version) {
        Properties props = new Properties();
        if (version != null) props.setProperty("version", version);
        return new BuildProperties(props);
    }

    @Test
    void returnsTheVersionFromBuildMetadata() {
        var adapter = new BuildPropertiesVersionAdapter(provider(buildProperties("1.2.3")));

        assertThat(adapter.currentVersion()).isEqualTo("1.2.3");
    }

    @Test
    void fallsBackToDevWhenNoBuildMetadataIsPresent() {
        var adapter = new BuildPropertiesVersionAdapter(provider(null));

        assertThat(adapter.currentVersion()).isEqualTo("dev");
    }

    @Test
    void fallsBackToDevWhenTheVersionIsBlank() {
        var adapter = new BuildPropertiesVersionAdapter(provider(buildProperties("   ")));

        assertThat(adapter.currentVersion()).isEqualTo("dev");
    }
}
