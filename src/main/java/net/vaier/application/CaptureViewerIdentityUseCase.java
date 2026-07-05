package net.vaier.application;

import net.vaier.domain.AccessEntry;

import java.util.Optional;

/**
 * Captures a signed-in viewer's presented identity (display name + last-used provider) into their
 * existing {@link AccessEntry}, then returns it — the write-through companion to
 * {@link ResolveViewerUseCase}. The launchpad's {@code /users/me} calls this on every load, so a
 * user's name and provider get persisted even when they only ever use the launchpad and never open a
 * {@code /authz/verify}-gated service.
 *
 * <p>Unlike the forward-auth {@code /authz/verify} path this never creates a pending entry for an
 * unknown email — first-sighting stays on the service path. An unknown or blank email resolves to
 * empty, and a blank/absent header never wipes a stored value (the capture rules live on
 * {@link AccessEntry}).
 */
public interface CaptureViewerIdentityUseCase {

    /**
     * Persist any newly-presented name/provider/providerUserId onto this email's existing access
     * entry and return the (possibly refreshed) entry; empty for a null/blank/unknown email.
     */
    Optional<AccessEntry> captureIdentity(String email, String name, String provider, String providerUserId);
}
