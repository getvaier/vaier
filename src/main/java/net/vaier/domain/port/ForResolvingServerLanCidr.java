package net.vaier.domain.port;

import java.util.Optional;

/**
 * Resolves the IPv4 CIDR of the LAN/VPC subnet the Vaier server itself sits on (for example,
 * an AWS VPC subnet). The value is <em>discovered</em> from EC2 instance metadata
 * ({@code network/interfaces/macs/<mac>/subnet-ipv4-cidr-block}); {@code VAIER_SERVER_LAN_CIDR}
 * is an override for non-EC2 installs. When it cannot be determined the result is empty and the
 * "server LAN CIDR" feature is simply inert — LAN servers must then sit behind a relay peer.
 */
public interface ForResolvingServerLanCidr {

    Optional<String> resolve();
}
