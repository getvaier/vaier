package net.vaier.application;

import net.vaier.domain.LanServer;
import net.vaier.domain.SshCredentialDraft;
import net.vaier.domain.SshCredentialVerification;

public interface RegisterLanServerUseCase {

    /**
     * Register a server on a relay peer's LAN. {@code lanAddress} must fall inside some
     * relay peer's {@code lanCidr}, otherwise an {@link IllegalArgumentException} is thrown.
     * When {@code runsDocker} is true, {@code dockerPort} is required. Re-registering an
     * existing name replaces the prior entry.
     */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort);

    /** As above, with an optional free-text {@code description}. */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                  String description);

    /**
     * As above, with an optional device-category override (the icon hint). A null override leaves
     * the effective category to auto-detection; a non-null value is persisted as the override.
     * Lets the Add Machine modal pre-fill the category from a scan pick.
     */
    void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                  String description, net.vaier.domain.DeviceCategory deviceCategory);

    /**
     * Register as above, additionally attaching an operator-supplied SSH {@code credential} — the manual
     * "add a LAN server by address" counterpart of adopting a scanned host with a credential. Registration
     * and the credential are kept separable: the machine is always registered, and the credential is
     * re-verified server-side against the machine's LAN address and stored <em>only when it authenticates</em>.
     * A rejected or unreachable credential never rolls back the registration; the {@link RegistrationOutcome}
     * reports what happened. A {@code null} credential registers with none and reports {@code credentialStored=false}.
     */
    RegistrationOutcome register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                                 String description, net.vaier.domain.DeviceCategory deviceCategory,
                                 SshCredentialDraft credential);

    /**
     * The result of registering with a credential: the registered {@code server}, the server-side
     * {@code credentialVerification} outcome ({@code null} when no credential was supplied; never carries a
     * secret), and whether the credential was {@code credentialStored} in the vault (true only when it
     * authenticated).
     */
    record RegistrationOutcome(LanServer server, SshCredentialVerification credentialVerification,
                               boolean credentialStored) {
    }
}
