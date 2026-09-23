package net.vaier.domain;

import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Every service credential Vaier holds, keyed by the published service's host — the key its access rule
 * uses — and, for a personal one, by the person's email. Immutable: each change returns a new set.
 */
@Value
public class ServiceCredentials {

    /** One service's credentials: the shared one (or null), and the personal ones by email. */
    public record Entry(ServiceCredential shared, Map<String, ServiceCredential> personal) {
        public Entry {
            personal = Collections.unmodifiableMap(new TreeMap<>(personal));
        }

        boolean isEmpty() {
            return shared == null && personal.isEmpty();
        }
    }

    Map<String, Entry> byService;

    private ServiceCredentials(Map<String, Entry> byService) {
        this.byService = Collections.unmodifiableMap(new TreeMap<>(byService));
    }

    public static ServiceCredentials empty() {
        return new ServiceCredentials(Map.of());
    }

    /** As read back from a store; keys are normalised the way every lookup normalises them. */
    public static ServiceCredentials of(Map<String, Entry> byService) {
        ServiceCredentials credentials = empty();
        for (Map.Entry<String, Entry> service : byService.entrySet()) {
            credentials = credentials.changed(service.getKey(), entry -> service.getValue());
        }
        return credentials;
    }

    /** What the service is handed for this person: their own credential, else the shared one. */
    public Optional<ServiceCredential> credentialFor(String host, String email) {
        Entry entry = host == null ? null : byService.get(normalise(host));
        if (entry == null) {
            return Optional.empty();
        }
        ServiceCredential personal = email == null ? null : entry.personal().get(normalise(email));
        return Optional.ofNullable(personal != null ? personal : entry.shared());
    }

    /** Whether the service at {@code host} carries any credential, shared or personal. */
    public boolean hasAnyFor(String host) {
        return host != null && byService.containsKey(normalise(host));
    }

    public ServiceCredentials withShared(String host, ServiceCredential credential, List<ReverseProxyRoute> routes) {
        requireSocialService(host, routes);
        return changed(host, entry -> new Entry(credential, entry.personal()));
    }

    public ServiceCredentials withoutShared(String host) {
        return changed(host, entry -> new Entry(null, entry.personal()));
    }

    public ServiceCredentials withPersonal(String host, String email, ServiceCredential credential,
                                           List<ReverseProxyRoute> routes, List<AccessEntry> people) {
        requireSocialService(host, routes);
        String person = normalise(email);
        if (people.stream().noneMatch(p -> person.equals(p.getEmail()))) {
            throw new IllegalArgumentException(email + " has no access entry, so Vaier would never let them in.");
        }
        return changed(host, entry -> {
            Map<String, ServiceCredential> personal = new TreeMap<>(entry.personal());
            personal.put(person, credential);
            return new Entry(entry.shared(), personal);
        });
    }

    public ServiceCredentials withoutPersonal(String host, String email) {
        return changed(host, entry -> withoutPersonIn(entry, normalise(email)));
    }

    /** A revoked person's credentials go with them, on every service. */
    public ServiceCredentials withoutPerson(String email) {
        String person = normalise(email);
        ServiceCredentials result = this;
        for (String host : byService.keySet()) {
            result = result.changed(host, entry -> withoutPersonIn(entry, person));
        }
        return result;
    }

    /** A service is forgotten once no route is left on its host; a sibling path route keeps it. */
    public ServiceCredentials afterUnpublishing(String host, List<ReverseProxyRoute> remainingRoutes) {
        String key = normalise(host);
        boolean stillPublished = remainingRoutes.stream()
            .filter(r -> !r.isOauth2EndpointsRouter())
            .anyMatch(r -> r.getDomainName() != null && key.equals(normalise(r.getDomainName())));
        return stillPublished ? this : changed(host, entry -> new Entry(null, Map.of()));
    }

    /** Only a route behind social login runs Vaier's check, so only there is anyone to hand a credential for. */
    private static void requireSocialService(String host, List<ReverseProxyRoute> routes) {
        String key = normalise(host);
        boolean social = routes.stream()
            .filter(ReverseProxyRoute::isVaierManaged)
            .anyMatch(r -> r.getDomainName() != null && key.equals(normalise(r.getDomainName()))
                && r.authMode().isSocial());
        if (!social) {
            throw new IllegalArgumentException("Only a published service behind social login can carry a "
                + "service credential, and " + host + " is not one.");
        }
    }

    private static Entry withoutPersonIn(Entry entry, String person) {
        Map<String, ServiceCredential> personal = new TreeMap<>(entry.personal());
        personal.remove(person);
        return new Entry(entry.shared(), personal);
    }

    private ServiceCredentials changed(String host, UnaryOperator<Entry> change) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        String key = normalise(host);
        Entry after = change.apply(byService.getOrDefault(key, new Entry(null, Map.of())));
        Map<String, Entry> next = new TreeMap<>(byService);
        if (after.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, after);
        }
        return new ServiceCredentials(next);
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
