package net.vaier.domain.port;

import net.vaier.domain.MachineId;

import java.util.Optional;

/**
 * Driven port turning a machine's display <em>name</em> into its {@link MachineId} — the one place
 * Vaier crosses from what an operator typed to what a machine actually is.
 *
 * <p>It exists so that translation happens exactly once. Vaier's REST paths, its terminal panes and its
 * Explorer coordinates all still carry names, while the stores behind them are keyed by identity; a
 * second copy of this lookup would be a second chance for the two to disagree about which machine is
 * meant. Consumers that already hold a {@link MachineId} never come here.
 *
 * <p>Resolution spans every machine kind — VPN peers, LAN servers, and the Vaier server itself — and
 * is empty when no machine bears the name. Comparison follows the same rule as the uniqueness guard
 * that makes names safe to look up at all: case-insensitive, ignoring surrounding whitespace.
 */
public interface ForResolvingMachineIds {

    /** The id of the machine named {@code machineName}, or empty when no machine bears that name. */
    Optional<MachineId> idForName(String machineName);
}
