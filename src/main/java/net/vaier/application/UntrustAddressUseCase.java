package net.vaier.application;

/**
 * Undoes {@link TrustAddressUseCase} (#348): the address stops being one the fleet's bouncer must never
 * block, and goes back to being judged on its behaviour like any other.
 *
 * <p><b>It places no block itself.</b> Untrusting only forgets the operator's earlier decision; who
 * ends up kept out from there is CrowdSec's own scenarios again, or {@link BlockAddressUseCase a hand
 * block} the operator places separately (#349) — so untrusting is not the opposite of
 * {@link LiftBlockUseCase}, it is the opposite of trusting.
 *
 * <p><b>And the same restart asymmetry applies as on the way in.</b> The whitelist file is rewritten
 * without the address on the next scheduled refresh, but CrowdSec re-reads its parser files only when it
 * restarts (PRD §6.26), and Vaier deliberately does not restart it — bouncing the engine that guards the
 * edge is the operator-lockout risk #329 named first. The honest promise is: forgotten now, no longer
 * whitelisted from CrowdSec's next restart.
 *
 * <p>Untrusting an address that is not trusted is a success, not an error: the state the operator asked for
 * is the state that holds.
 *
 * @throws IllegalArgumentException if {@code sourceIp} is not a plain IPv4 address — which is also why no
 *                                  structural trusted network can be named here: every one of them is a
 *                                  prefix wider than a single host.
 */
public interface UntrustAddressUseCase {

    void untrustAddress(String sourceIp);
}
