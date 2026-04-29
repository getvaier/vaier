package net.vaier.application;

import net.vaier.domain.Machine;

import java.util.List;

public interface GetMachinesUseCase {

    List<Machine> getAllMachines();
}
