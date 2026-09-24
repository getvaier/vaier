package net.vaier.domain.port;

import net.vaier.domain.BlockDuration;
import net.vaier.domain.SourceAddress;

/**
 * Places a block on one source address by hand — the operator's answer to #349, the mirror image of
 * {@link ForLiftingBlocks}.
 *
 * <p><b>Deliberately the operator's own action, never CrowdSec's.</b> Every other block decision the fleet
 * ever sees comes from a CrowdSec scenario judging behaviour; this is the one path where a person, not the
 * engine, decides. That is why every call carries a {@code reason} — see
 * {@link net.vaier.domain.BlockDecision#handBlockReason}, the marker that lets the read side tell a hand
 * block from CrowdSec's own in the same list, since CrowdSec's wire format carries no field of its own for
 * "who asked for this".
 *
 * <p>Like {@link ForLiftingBlocks}, a failure here must <b>not</b> be swallowed: an operator who just clicked
 * "Block" is waiting to learn whether the address is actually kept out. Implementations throw
 * {@link net.vaier.domain.BlockNotPlacedException} rather than returning quietly.
 *
 * <p>The address and duration are typed, never bare strings, so no caller can hand an implementation
 * anything that has not already passed the domain's own gates — {@link SourceAddress#of} and
 * {@link BlockDuration#of} — before either ends up as an argument to a command run inside the crowdsec
 * container.
 *
 * <p>Calling this twice on the same address is not an error: {@code cscli decisions add} does not reject a
 * duplicate, it simply adds a second active decision alongside the first. The source stays blocked until
 * the later of the two expires — which is exactly "extend the block" in effect, without either the adapter
 * or the domain having to special-case an address that is already blocked.
 */
public interface ForAddingBlocks {

    void blockAddress(SourceAddress address, BlockDuration duration, String reason);
}
