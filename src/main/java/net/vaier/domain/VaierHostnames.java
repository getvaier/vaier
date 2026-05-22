package net.vaier.domain;

import net.vaier.config.ServiceNames;

/**
 * The Vaier server's own public hostnames, derived from the base domain. Replaces the
 * {@code "vaier." + domain} / {@code "login." + domain} string concatenation that was
 * duplicated across the application and adapter layers — the subdomain labels are the single
 * {@link ServiceNames#VAIER} / {@link ServiceNames#AUTH} definitions.
 */
public record VaierHostnames(String baseDomain) {

    /** The FQDN the Vaier web UI is served on, e.g. {@code vaier.example.com}. */
    public String vaierServerFqdn() {
        return ServiceNames.VAIER + "." + baseDomain;
    }

    /** The FQDN the Authelia login portal is served on, e.g. {@code login.example.com}. */
    public String autheliaHost() {
        return ServiceNames.AUTH + "." + baseDomain;
    }
}
