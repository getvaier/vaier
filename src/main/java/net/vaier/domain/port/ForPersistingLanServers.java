package net.vaier.domain.port;

import net.vaier.domain.LanServer;

import java.util.List;

public interface ForPersistingLanServers {

    void save(LanServer server);

    List<LanServer> getAll();

    void deleteByName(String name);
}
