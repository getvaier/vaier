package net.vaier.domain.port;

import java.util.Set;

public interface ForManagingIgnoredServices {
    Set<String> getIgnoredServiceKeys();
    void ignoreService(String key);
    void unignoreService(String key);
}
