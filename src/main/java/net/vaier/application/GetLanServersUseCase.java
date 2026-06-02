package net.vaier.application;

import net.vaier.domain.port.ForGettingLanServers.LanServerView;

import java.util.List;

public interface GetLanServersUseCase {

    List<LanServerView> getAll();
}
