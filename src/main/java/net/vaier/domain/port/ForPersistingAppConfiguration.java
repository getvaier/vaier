package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.VaierConfig;

public interface ForPersistingAppConfiguration {

    Optional<VaierConfig> load();

    void save(VaierConfig config);

    boolean exists();
}
