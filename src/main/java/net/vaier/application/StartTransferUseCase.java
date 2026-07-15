package net.vaier.application;

import net.vaier.domain.Transfer;

/**
 * Start a cross-machine copy — the Clipboard's paste onto another machine (#321, slice 2). The source is a
 * coordinate (machine, path, and optionally a point-in-time archive for a restore); the destination is a
 * live machine and a directory the item is copied into. Returns at once with the {@code RUNNING}
 * {@link Transfer} — the relay runs on a background executor and its progress is pushed over SSE.
 */
public interface StartTransferUseCase {

    /**
     * Begin relaying {@code sourcePath} from {@code sourceMachine} (at archive {@code at}, or the live
     * present when {@code at} is null) into the directory {@code destPath} on {@code destMachine}. The
     * destination is always the present — you cannot paste into the past.
     *
     * @throws IllegalArgumentException when a path is not absolute, or the transfer is a no-op onto its own
     *                                  live file (surfaces as a 400)
     */
    Transfer startTransfer(String sourceMachine, String sourcePath, String at,
                           String destMachine, String destPath);
}
