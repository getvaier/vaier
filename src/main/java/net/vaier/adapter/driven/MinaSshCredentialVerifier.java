package net.vaier.adapter.driven;

import net.vaier.adapter.driven.SshConnector.Connection;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForVerifyingSshCredentials;
import org.springframework.stereotype.Component;

/**
 * Verifies an SSH credential by opening one short-lived probe connection and immediately closing it —
 * the pre-registration green-check behind the adopt sheet. It reuses the single copy of Vaier's connect
 * + host-key + authenticate logic ({@link SshConnector}), so a test authenticates exactly as a real
 * session would, and returns the fingerprint the host presented.
 *
 * <p>It writes nothing: no credential is stored and no host key is pinned (the TOFU verifier inside
 * {@link SshConnector} only records the presented fingerprint for this one connection and, with no pin
 * to compare against, accepts it without persisting anything). Transport and auth failures surface as
 * the distinct domain SSH exceptions, which the domain maps into the verification result.
 */
@Component
public class MinaSshCredentialVerifier implements ForVerifyingSshCredentials {

    @Override
    public String probe(SshTarget target) {
        try (Connection connection = SshConnector.establish(target)) {
            return connection.fingerprint();
        }
    }
}
