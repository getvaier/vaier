package net.vaier.domain;

import java.util.List;
import java.util.Locale;

/**
 * Which published service the model meant. A name is how the published services read names a service, but
 * two houses may each run one of the same name; the address is never shared, so it is accepted too, and a
 * shared name is refused with the addresses to choose from.
 */
public record PublishedServiceReference(String said) {

    /** One published service as it can be named: its name, its machine, and where it is published. */
    public record Candidate(String name, String machine, String host, String pathPrefix) {

        /** How the service is named to the operator. */
        public String label() {
            return machine == null || machine.isBlank() ? name : name + " on " + machine;
        }

        public String address() {
            return host + (pathPrefix == null ? "" : pathPrefix);
        }
    }

    public Candidate resolve(List<Candidate> published) {
        String wanted = said == null ? "" : said.trim();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Say which published service.");
        }
        String address = asAddress(wanted);
        for (Candidate candidate : published) {
            if (candidate.address().toLowerCase(Locale.ROOT).equals(address)) {
                return candidate;
            }
        }
        List<Candidate> named = published.stream()
            .filter(c -> key(wanted).equals(key(c.name() + c.machine())) || key(wanted).equals(key(c.label())))
            .toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        named = published.stream().filter(c -> key(wanted).equals(key(c.name()))).toList();
        if (named.size() == 1) {
            return named.get(0);
        }
        if (named.isEmpty()) {
            throw new IllegalArgumentException("Vaier publishes no service called \"" + wanted
                + "\". Name it as published_services does, or by its address.");
        }
        throw new IllegalArgumentException(named.size() + " published services are called \"" + wanted
            + "\"; say which by address: " + String.join(", ", named.stream().map(Candidate::address).toList()));
    }

    private static String asAddress(String said) {
        String address = said.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
