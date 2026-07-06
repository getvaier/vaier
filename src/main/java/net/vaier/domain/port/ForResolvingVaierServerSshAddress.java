package net.vaier.domain.port;

/**
 * Driven port resolving the SSH address of the Vaier server <em>host</em> as reached from inside the
 * vaier container — the host is not a peer or LAN server, so its address can't be read from config.
 * Resolved from {@code VAIER_HOST_SSH_ADDRESS} when set, otherwise the container's default-gateway
 * host IP.
 */
public interface ForResolvingVaierServerSshAddress {

    /**
     * The host address to SSH to for the Vaier server. Throws {@code SshConnectException} when it
     * cannot be determined (no override and no default gateway).
     */
    String resolve();
}
