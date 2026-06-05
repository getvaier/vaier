package net.vaier.application;

import net.vaier.domain.Edition;

import java.time.Instant;

/**
 * Surfaces the current licensing state for display — what edition is active, whether a valid
 * Enterprise licence is installed, and (when a licence is present) who it was issued to and when
 * it lapses. The launchpad/settings UI reads this to decide whether to render Enterprise features.
 */
public interface GetLicenseStatusUseCase {

    LicenseStatus status();

    /**
     * @param edition   the edition the instance effectively runs as right now
     * @param licensed  true when a valid Enterprise licence is currently in force
     * @param customer  who the installed licence was issued to, or {@code null} when none is installed
     * @param expiresAt when the installed licence lapses ({@code null} = none installed, or perpetual)
     */
    record LicenseStatus(Edition edition, boolean licensed, String customer, Instant expiresAt) {}
}
