package net.vaier.domain;

import net.vaier.domain.port.ForAddingBlocks;
import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingTrustedAddresses;

/**
 * One source address the operator can act on — the {@code sourceIp} of a {@link BlockDecision}, either
 * unblocked once or trusted for good (#329 Slice 3c).
 *
 * <p><b>Why this is a type and not a {@code String}.</b> The address arrives from the browser, is
 * attacker-influenced (it is whatever CrowdSec saw knocking), and ends up as an argument to a command run
 * inside the crowdsec container and inside log lines. Validating it at construction, in the domain, means
 * there is exactly one gate — and because {@link ForLiftingBlocks} and {@link ForPersistingTrustedAddresses}
 * take this type rather than a string, no caller can route around it. The strictness is
 * {@link Cidr#isIpv4(String)}'s, the same dotted-quad-only rule written to close #195: no IPv6, no
 * hostnames, no leading zeros, and therefore no shell metacharacter, whitespace or newline anywhere.
 *
 * <p>A single host, always: {@code x.x.x.x/32} is accepted and normalised to the bare address, since that
 * is what an operator copies back out of the whitelist file, but any wider prefix is refused. Vaier's two
 * actions here are both per-host, and a range would either trust more than was meant or unblock nothing.
 *
 * <p>Whitespace is forgiven at the edges and never inside. Surrounding whitespace of any kind — the space
 * a paste brings along, the tab or newline a copied line brings — leaves a clean dotted quad once trimmed,
 * so refusing it would only be rude. Whitespace <em>within</em> the value means it was never one address,
 * and the strict pattern refuses it.
 */
public record SourceAddress(String value) {

    /** Every address the operator trusts is trusted as a single host, never a range. */
    private static final String SINGLE_HOST_PREFIX = "/32";

    /**
     * The gate, not merely a convenience on {@link #of(String)}. A record's canonical constructor is as
     * public as the record itself and cannot be narrowed, so validating only in {@code of} would leave
     * {@code new SourceAddress("$(id)")} compiling and working — and the promise this whole type makes,
     * that nothing but a dotted quad can reach a container exec argument, would be documentation rather
     * than a property of the code. {@code of} stays the only <em>normalising</em> door (spaces,
     * {@code /32}); this is the one nothing gets past.
     */
    public SourceAddress {
        if (!Cidr.isIpv4(value)) {
            throw new IllegalArgumentException("Not a valid IPv4 address");
        }
    }

    public static SourceAddress of(String value) {
        String address = withoutSingleHostPrefix(value == null ? null : value.trim());
        if (!Cidr.isIpv4(address)) {
            // Deliberately does not echo the rejected text: it is attacker-influenced, and this message is
            // rendered straight back to the browser as a 400 by GlobalExceptionHandler. It is also why a
            // value shaped like a command-line flag ("-i", "--all") never gets through: the strict
            // dotted-quad rule rejects it here, long before it could be read as an option by cscli rather
            // than as data — the one injection that survives passing arguments as an array.
            throw new IllegalArgumentException("Not a valid IPv4 address");
        }
        return new SourceAddress(address);
    }

    /**
     * Accepts the {@code x.x.x.x/32} form as the same single host as {@code x.x.x.x} — it is exactly what
     * an operator copies back out of the whitelist file — and rejects anything wider.
     *
     * <p>A range is not a source address. Both of Vaier's actions are per-host: {@code cscli decisions
     * delete -i} lifts the block on one address, and the trust store is a list of hosts. Silently accepting
     * a prefix would either trust far more than the operator meant, or fail to unblock anything at all
     * while looking like it had worked.
     */
    private static String withoutSingleHostPrefix(String value) {
        if (value == null) return null;
        int slash = value.indexOf('/');
        if (slash < 0) return value;
        if (!SINGLE_HOST_PREFIX.equals(value.substring(slash))) {
            throw new IllegalArgumentException(
                "Not a valid IPv4 address: name a single host, not a range");
        }
        return value.substring(0, slash);
    }

    /**
     * This address as a CIDR, so it can join {@link TrustedNetworks}. Normalising a bare address to a
     * single-host CIDR is the domain's call — a store or a controller that appended {@code "/32"} itself
     * would be a second place the rule could drift from this one.
     */
    public String asCidr() {
        return value + SINGLE_HOST_PREFIX;
    }

    /** Lets this address back in now. Throws {@link BlockNotLiftedException} if it could not be done. */
    public void liftBlock(ForLiftingBlocks forLiftingBlocks) {
        forLiftingBlocks.liftBlock(this);
    }

    /**
     * Records this address as permanently trusted. On its own this does <em>not</em> let it back in:
     * CrowdSec re-reads its whitelist parser only when it restarts (PRD §6.26), so trusting takes effect
     * from the next restart and the block still has to be lifted separately.
     */
    public void trust(ForPersistingTrustedAddresses store) {
        store.save(this);
    }

    /**
     * Forgets the operator's decision to trust this address (#348). Symmetrically to {@link #trust}, and for
     * the same reason, this does not take effect at the edge until CrowdSec next restarts: the whitelist
     * file is rewritten without the address within five minutes, but CrowdSec reads its parser files only at
     * startup, and Vaier deliberately does not restart it.
     *
     * <p>It also places no block itself. Untrusting only forgets the operator's own earlier decision; who
     * ends up kept out from there is CrowdSec's scenarios again, or {@link #block a hand block} the operator
     * places separately (#349) — either way, an untrusted address is simply back to being judged like any
     * other.
     *
     * <p>Untrusting can only ever reach an address, never a network. Two independent things make that true,
     * and the weaker one is the obvious one: nothing wider than a single host can become a
     * {@code SourceAddress} at all, so a structural entry of {@link TrustedNetworks} has no name in this
     * vocabulary. The <em>load-bearing</em> guarantee is the store separation behind it — the structural
     * entries are assembled from the VPN subnet, the Docker bridge and the peer configurations, none of
     * which this port can write. That is what still holds in the one case the prefix rule misses: a relay
     * whose LAN happens to be a single host <em>is</em> nameable here, and deleting it from the store would
     * still leave the structural entry exactly where it was. Do not let either guard be removed on the
     * grounds that the other one covers it.
     */
    public void untrust(ForPersistingTrustedAddresses store) {
        store.delete(this);
    }

    /**
     * Blocks this address by hand for {@code duration} (#349) — the one direction #329 originally refused
     * Vaier, now offered deliberately narrow: an expiring decision only, never a permanent one, and never
     * one that could starve the operator's own access.
     *
     * <p><b>Two refusals, both domain decisions and both checked before the port is ever called.</b> An
     * address inside {@code trustedNetworks} — the VPN subnet, the Docker bridge, every relay LAN, every
     * hand-trusted address — is refused for the same reason #329 already guards the automatic side: blocking
     * the operator's own traffic is not a false positive to fix later, it is losing the console they would
     * fix it from. An address equal to {@code requesterIp} — the admin's own current address, resolved by
     * {@code domain.CallerIp} the same way the launchpad and the forward-auth check already do — is refused
     * because nothing about the trusted networks catches an admin working from an ordinary, untrusted
     * connection who is one misclick from locking themselves out right now.
     *
     * @param requesterIp the admin's own current source address, or null when it could not be resolved —
     *                     in which case this refusal simply does not apply, the same "missing information
     *                     blocks nothing" rule {@link BlockDecision#locksOut} follows for a null allowlist
     */
    public void block(BlockDuration duration, String adminEmail, TrustedNetworks trustedNetworks,
                      String requesterIp, ForAddingBlocks forAddingBlocks) {
        if (trustedNetworks != null && trustedNetworks.contains(value)) {
            throw new IllegalArgumentException(
                "Vaier will not block " + value + ": it is inside the fleet's own trusted networks.");
        }
        if (requesterIp != null && value.equals(requesterIp)) {
            throw new IllegalArgumentException(
                "Vaier will not block " + value + ": that is your own current address.");
        }
        forAddingBlocks.blockAddress(this, duration, BlockDecision.handBlockReason(adminEmail));
    }
}
