package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.DnsRecord.DnsRecordType;

public interface ForResolvingPublicHost {

    Optional<PublicHost> resolve();

    record PublicHost(String value, DnsRecordType type) {
        public PublicHost {
            if (type != DnsRecordType.A && type != DnsRecordType.CNAME) {
                throw new IllegalArgumentException("PublicHost type must be A or CNAME, got " + type);
            }
        }
    }
}
