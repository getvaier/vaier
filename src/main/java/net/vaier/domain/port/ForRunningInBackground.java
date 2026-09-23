package net.vaier.domain.port;

/** Hands work off the caller's thread; the caller neither waits for it nor hears of its failure. */
public interface ForRunningInBackground {
    void run(Runnable task);
}
