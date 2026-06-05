package net.vaier.domain;

import java.time.Instant;
import java.util.Set;

/**
 * An authenticated Enterprise licence — the decoded, signature-verified claims of a licence token.
 * Producing a {@code License} is the adapter's job (verifying the Ed25519 signature and parsing the
 * payload); deciding what an authentic licence <em>grants right now</em> is the domain's job and
 * lives here. The application service never re-implements expiry or edition checks — it asks the
 * {@code License}.
 *
 * @param customer  the licensee the token was minted for (display/audit only)
 * @param edition   the edition the licence grants when valid
 * @param issuedAt  when the licence was minted
 * @param expiresAt when the licence lapses; {@code null} means a perpetual licence that never expires
 * @param features  the named Enterprise features the licence unlocks (empty for an edition-only licence)
 */
public record License(
    String customer,
    Edition edition,
    Instant issuedAt,
    Instant expiresAt,
    Set<String> features
) {
    /** True once {@code now} is past {@link #expiresAt}. A perpetual ({@code null} expiry) licence never expires. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /** True while the licence is still within its validity window. */
    public boolean isValidAt(Instant now) {
        return !isExpired(now);
    }

    /** True only for a valid licence whose edition is {@link Edition#ENTERPRISE}. */
    public boolean grantsEnterprise(Instant now) {
        return edition == Edition.ENTERPRISE && isValidAt(now);
    }

    /**
     * The edition the instance effectively runs as under this licence: the licensed edition while
     * valid, falling back to {@link Edition#COMMUNITY} once it has expired. An expired Enterprise
     * licence never silently keeps Enterprise features alive.
     */
    public Edition effectiveEdition(Instant now) {
        return isValidAt(now) ? edition : Edition.COMMUNITY;
    }

    /** True when this licence currently grants Enterprise <em>and</em> unlocks the named feature. */
    public boolean hasFeature(String feature, Instant now) {
        return grantsEnterprise(now) && features != null && features.contains(feature);
    }
}
