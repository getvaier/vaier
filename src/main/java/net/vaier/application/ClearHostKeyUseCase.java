package net.vaier.application;

public interface ClearHostKeyUseCase {

    /**
     * Forget the pinned SSH host key for {@code machineName}, so the next connect re-pins on first use.
     * Used to recover after a machine is legitimately rebuilt and presents a new host key.
     */
    void clearHostKey(String machineName);
}
