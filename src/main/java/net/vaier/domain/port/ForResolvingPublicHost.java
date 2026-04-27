package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.DnsRecord.DnsRecordType;

public interface ForResolvingPublicHost {

    Optional<PublicHost> resolve();

    /**
     * Returns the server's public IP if known, regardless of how `resolve()` chose to represent
     * the public host. On EC2 this returns the value from IMDS `public-ipv4`, which differs from
     * resolving the EC2 public hostname inside the VPC (split-horizon DNS would yield the private
     * IP). Used for IP geolocation, where a CNAME or split-horizon address won't do.
     */
    default Optional<String> resolvePublicIp() {
        return Optional.empty();
    }

    record PublicHost(String value, DnsRecordType type) {
        public PublicHost {
            if (type != DnsRecordType.A && type != DnsRecordType.CNAME) {
                throw new IllegalArgumentException("PublicHost type must be A or CNAME, got " + type);
            }
        }
    }
}
