package net.vaier.domain.port;

import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshTarget;

/**
 * Driven port for learning where a machine's SFTP subsystem believes the filesystem begins — its
 * {@link SftpRoot} (#326).
 *
 * <p>The answer cannot be read off a machine's configuration; it has to be asked for, down both channels at
 * once, and the asking costs two SSH connections. So it sits behind a port: the Explorer states what it needs
 * ("where does this machine's file tree begin?") and the adapter is free to remember the answer, which is the
 * only reason browsing a jailed machine does not pay for the probes on every directory click.
 *
 * <p>A machine that cannot be probed resolves to {@link SftpRoot#NONE} — never an exception, and never a
 * guess. Not knowing where a jail is leaves every path exactly as it was, which is the safe outcome; inventing
 * a prefix would silently corrupt every path on the machine, in both directions.
 */
public interface ForResolvingSftpRoots {

    /**
     * Where the file tree of the machine named {@code machineName}, reachable at {@code target}, begins.
     *
     * <p>The name is the machine's identity — what the answer is remembered under — and the target is merely
     * how to reach it. They are both passed because Vaier's canonical identity for a machine is its name, and
     * a cache keyed on anything else (an address, a credential) would go stale the moment either moved.
     */
    SftpRoot rootFor(String machineName, SshTarget target);
}
