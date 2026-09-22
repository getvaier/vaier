package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.RunningShell;

import java.util.List;

/**
 * The persistent shells already running on a machine — Vaier's own, never the operator's tmux sessions.
 *
 * <p>Vaier creates persistent shells but for a long time could not enumerate them, so a pane id lost anywhere —
 * a cleared browser, another device, a tab that died before the id was kept — was a shell lost forever, still
 * running whatever was inside it (#322). This is the read that closes the blind spot.
 *
 * <p>Runs on the open path of a terminal window, so it never throws: an unreachable host or a machine without
 * tmux reads as no shells.
 */
public interface ListPersistentShellsUseCase {

    List<RunningShell> listShells(MachineId machineId);
}
