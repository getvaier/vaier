package net.vaier.domain.port;

import net.vaier.domain.LanDockerHost;

import java.util.List;

public interface ForPersistingLanDockerHosts {

    void save(LanDockerHost host);

    List<LanDockerHost> getAll();

    void deleteByName(String name);
}
