package net.vaier.domain.port;

import net.vaier.domain.DiskWatch;

import java.util.List;

/**
 * Driven port for persisting {@link DiskWatch}es — which filesystem on which machine Vaier watches, and at
 * what threshold (#325). Only the filesystems someone has actually configured are stored; everything else
 * is watched by default and needs no entry, so an empty store is the healthy first-boot state.
 */
public interface ForPersistingDiskWatches {

    /** Every stored disk watch. Empty when nobody has configured one. */
    List<DiskWatch> getAll();

    /** Persist {@code watch}, replacing any existing watch for the same machine and mount point. */
    void save(DiskWatch watch);
}
