package net.vaier.application;

/**
 * Blocks one source address by hand for a fixed duration (#349) — the operator's answer to a source
 * CrowdSec's own scenarios have not caught. The mirror of {@link LiftBlockUseCase}, and the one direction
 * #329 originally refused Vaier: every other block decision comes from CrowdSec judging behaviour, this one
 * from a person deciding.
 *
 * <p>Two refusals are the domain's, not this interface's — see {@code SourceAddress.block}: an address
 * inside the fleet's own trusted networks, and the requesting admin's own current address. Both surface as
 * {@link IllegalArgumentException}, exactly like every other domain refusal in this project.
 *
 * <p>Idempotent by construction: blocking an address already blocked is not an error. CrowdSec's own
 * {@code cscli decisions add} does not reject a duplicate — it adds a second decision, and the source stays
 * blocked until the later of the two expires, which reads to the operator as the block having been
 * extended.
 *
 * @throws IllegalArgumentException      if {@code sourceIp} is not a plain IPv4 address, if
 *                                        {@code duration} is not one of the offered choices, or if the
 *                                        domain refuses this address
 * @throws net.vaier.domain.BlockNotPlacedException if the block could not be placed — never silence
 */
public interface BlockAddressUseCase {

    /**
     * @param sourceIp    the address to block
     * @param duration    one of {@code 1h}, {@code 4h}, {@code 24h}, {@code 7d} — see {@code BlockDuration}
     * @param adminEmail  who is blocking it, for the reason marker the read side classifies by and for the
     *                    audit log; null reads as "an admin"
     * @param requesterIp the admin's own current source address, so the domain can refuse to block it;
     *                    null skips that one refusal
     */
    void blockAddress(String sourceIp, String duration, String adminEmail, String requesterIp);
}
