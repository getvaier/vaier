package net.vaier.domain;

/**
 * Vaier's last-known belief about whether a machine has <b>a way out to the internet</b> at all — whether
 * its routing table carries a default route. Three-valued for the same reason {@link SshServerPresence}
 * is: not having asked is not an answer.
 *
 * <p>{@link #UNKNOWN} — Vaier has never managed to read this machine's networks, or the last read told it
 * nothing ({@link MachineNetworks#isUnknown()}). {@link #PRESENT} — the machine answered and named the
 * interface its default route leaves by, whatever that interface is: a full-tunnel peer routing by
 * {@code wg0} can reach the world just as well as one routing by {@code eth0}. {@link #ABSENT} — the
 * machine answered and named no default route at all.
 *
 * <p><b>#357.</b> Everything else Vaier asks a machine is answerable from inside its LAN — it is reachable
 * through its relay peer, its disks read over that same path, and its backups go to a backup server on the
 * same LAN. So a machine that loses its default route keeps passing every probe while being unable to pull
 * an image or reach anything on the internet, and reads as fully healthy. This is the one fact that tells
 * those two apart, and it was already being parsed off {@link MachineNetworks#IP_COMMAND} and thrown away.
 */
public enum DefaultRouteStanding {
    UNKNOWN,
    PRESENT,
    ABSENT
}
