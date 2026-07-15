package net.vaier.domain;

/**
 * Where a {@link Transfer} is in its short life: relaying bytes ({@code RUNNING}), finished cleanly
 * ({@code DONE}), or stopped by an error ({@code FAILED}). A transfer starts RUNNING and settles once —
 * {@code DONE} and {@code FAILED} are terminal.
 */
public enum TransferState {
    RUNNING, DONE, FAILED
}
