package net.vaier.application;

import net.vaier.domain.AccessEntry;

import java.util.Optional;

/**
 * Resolves a signed-in email to its {@link AccessEntry} for read-only, viewer-adaptive rendering
 * (the launchpad topbar and per-viewer tile filtering, {@code /users/me}). Pure read: unlike the
 * forward-auth {@code /authz/verify} path, this never creates or mutates an access entry — an
 * unknown email simply resolves to empty.
 */
public interface ResolveViewerUseCase {

    /** The access entry for this email, or empty for a null/blank/unknown email. */
    Optional<AccessEntry> resolveViewer(String email);
}
