package net.vaier.domain;

import net.vaier.domain.port.ForVerifyingSshCredentials;

/**
 * The outcome of testing an SSH credential against a machine's address, before the machine is
 * registered: whether the host was {@code reachable} at all, whether the credential {@code
 * authenticated}, and the host-key {@code fingerprint} it presented (for display only — a
 * pre-registration test pins nothing). This is the green-check the operator sees in the adopt sheet.
 *
 * <p>It carries no secret material, so it is inherently safe to leave the process. Whether the
 * credential works — the "is this authenticated?" decision — is made here, in the domain, by mapping
 * the driven port's outcome: a returned fingerprint is a success, a rejected credential
 * ({@link SshAuthException}) is reachable-but-not-authenticated, and a transport failure
 * ({@link SshConnectException}) is not reachable. None of these are errors; they are results.
 */
public record SshCredentialVerification(boolean reachable, boolean authenticated, String fingerprint) {

    /**
     * Run the probe through {@code prober} and map its outcome. The port persists nothing, so a test
     * never stores a credential nor pins a host key. The rule and the port call stay together here so
     * every caller (the verify use case, adoption's server-side re-verify) maps the outcome identically.
     */
    public static SshCredentialVerification probe(SshTarget target, ForVerifyingSshCredentials prober) {
        try {
            String fingerprint = prober.probe(target);
            return new SshCredentialVerification(true, true, fingerprint);
        } catch (SshAuthException reachableButRejected) {
            return new SshCredentialVerification(true, false, null);
        } catch (SshConnectException unreachable) {
            return new SshCredentialVerification(false, false, null);
        }
    }
}
