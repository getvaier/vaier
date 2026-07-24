package net.vaier.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fleet's {@link DiskWatch}es, resolved by machine and mount point. It exists so that "what is this
 * filesystem's watch?" is answered in <b>one</b> place — and answered for a filesystem nobody has ever
 * configured, which is the common case and the one #325 is about.
 *
 * <p>{@link #forFilesystem} never returns empty: an unconfigured filesystem resolves to
 * {@link DiskWatch#watchedByDefault}. That is what makes a new mount — {@code /volume1} appearing on the
 * NAS for the first time — nag rather than hide.
 *
 * <p>Stored watches whose machine or mount point no longer exists are simply never looked up. They cost
 * nothing and they are not an error: a machine can be renamed, a volume unmounted, and Vaier keeps working.
 */
public class DiskWatches {

    private final Map<String, DiskWatch> byMachineAndMount = new HashMap<>();

    /** {@code watches} may be null or empty — then every filesystem in the fleet is watched by default. */
    public DiskWatches(List<DiskWatch> watches) {
        if (watches != null) {
            for (DiskWatch watch : watches) {
                if (watch != null) {
                    byMachineAndMount.put(key(watch.machineId(), watch.mountPoint()), watch);
                }
            }
        }
    }

    /**
     * The watch for {@code mountPoint} on {@code machineId} — the stored one when there is one, otherwise
     * the watched-by-default one. Keyed on machine <em>and</em> mount: {@code /} on the NAS and {@code /} on
     * Apalveien 5 are two different disks with two different verdicts.
     */
    public DiskWatch forFilesystem(MachineId machineId, String mountPoint) {
        DiskWatch stored = byMachineAndMount.get(key(machineId, mountPoint));
        return stored != null ? stored : DiskWatch.watchedByDefault(machineId, mountPoint);
    }

    /**
     * Machine and mount joined on NUL — a byte neither a machine name nor a POSIX path can contain, so no
     * two keys can collide. A space separator would: an id ending in {@code /a} with mount {@code b} would key
     * the same as that id with mount {@code /a b}.
     */
    private static String key(MachineId machineId, String mountPoint) {
        return machineId.value() + '\u0000' + mountPoint;
    }
}
