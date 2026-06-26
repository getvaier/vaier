package net.vaier.domain.port;

import net.vaier.domain.Icon;

import java.util.Optional;

/**
 * Driven port for the persistence side of icon resolution: a resolved icon is fetched online at
 * most once, then served from disk across restarts. Only positives are persisted — a "not cached"
 * result is represented by an empty {@link Optional} from {@link #load(String)}.
 */
public interface ForStoringIcons {

    /** The icon stored under {@code key}, or empty when nothing is cached for it. */
    Optional<Icon> load(String key);

    /** Persist a resolved {@code icon} under {@code key}. Best-effort: never throws. */
    void store(String key, Icon icon);
}
