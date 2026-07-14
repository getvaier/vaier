package net.vaier.domain.port;

import net.vaier.domain.SshTarget;

/**
 * Driven port turning a machine name into a connectable {@link SshTarget}: its SSH address (by machine
 * kind — a VPN peer's tunnel IP, a LAN server's LAN address, or the Vaier server's host address), the
 * host credential held in the vault, and the host-key fingerprint previously pinned for it.
 *
 * <p>This is the single entry point to Vaier's SSH trust path. Every consumer that needs to reach a
 * machine over SSH — the web terminal, the Explorer — resolves through it, so there is exactly one copy
 * of the address rules and one copy of the trust-on-first-use lookup. The returned target carries the
 * pinned fingerprint, or {@code null} when the machine has never been pinned; the caller decides
 * whether that first connect should pin what the host presented.
 *
 * <p>Throws {@code NotFoundException} when no machine bears the name, and {@code
 * NoHostCredentialException} when the machine exists but has no credential in the vault.
 */
public interface ForResolvingSshTargets {

    /** The connectable SSH target for {@code machineName}. */
    SshTarget resolve(String machineName);
}
