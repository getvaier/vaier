package net.vaier.application;

import java.util.Set;
import net.vaier.config.ServiceNames;

public final class PublishingConstants {

    private PublishingConstants() {}

    /**
     * The infrastructure subdomains Vaier publishes for itself — currently just its own web UI.
     * They are created at setup and must never be deletable or listed as ordinary published
     * services. (The Authelia login portal used to live here too, but Authelia is decommissioned.)
     */
    private static final Set<String> MANDATORY_SUBDOMAINS = Set.of(ServiceNames.VAIER);

    /**
     * Whether {@code fqdn} is one of Vaier's mandatory infrastructure hostnames — exactly
     * {@code vaier.<baseDomain>}. The match is on the whole FQDN, so {@code vaier-test.example.com}
     * is not mistaken for the mandatory {@code vaier.example.com}.
     */
    public static boolean isMandatory(String fqdn, String baseDomain) {
        return MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> (sub + "." + baseDomain).equals(fqdn));
    }
}
