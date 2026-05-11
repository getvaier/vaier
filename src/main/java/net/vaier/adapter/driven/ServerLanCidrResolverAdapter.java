package net.vaier.adapter.driven;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.Cidr;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.springframework.stereotype.Component;

/**
 * Resolves the IPv4 CIDR of the LAN/VPC subnet the Vaier server itself sits on.
 *
 * <p>Primary mechanism: EC2 IMDSv2 — read the first network interface's MAC, then that
 * interface's {@code subnet-ipv4-cidr-block}. The {@code VAIER_SERVER_LAN_CIDR} environment
 * variable overrides this for non-EC2 installs. Anything that doesn't parse as a strict IPv4
 * CIDR ({@link Cidr#validateLanCidr}) is ignored with a warning. When neither source yields a
 * value the result is empty and the "server LAN CIDR" feature is inert.
 */
@Component
@Slf4j
public class ServerLanCidrResolverAdapter implements ForResolvingServerLanCidr {

    private static final String IMDS_BASE = "http://169.254.169.254/latest";
    private static final Duration IMDS_TIMEOUT = Duration.ofSeconds(2);

    private final Function<String, String> envLookup;
    private final HttpClient httpClient;

    // The subnet the server sits on does not change for the life of the process, and env vars are
    // a process-start snapshot — so resolve once and reuse, sparing every caller (the 30s schedulers
    // in particular) a repeated IMDS round-trip.
    private volatile Optional<String> cached;

    public ServerLanCidrResolverAdapter() {
        this(System::getenv, HttpClient.newBuilder().connectTimeout(IMDS_TIMEOUT).build());
    }

    ServerLanCidrResolverAdapter(Function<String, String> envLookup, HttpClient httpClient) {
        this.envLookup = envLookup;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<String> resolve() {
        Optional<String> c = cached;
        if (c != null) return c;
        Optional<String> result = doResolve();
        cached = result;
        return result;
    }

    private Optional<String> doResolve() {
        String envCidr = trimToNull(envLookup.apply("VAIER_SERVER_LAN_CIDR"));
        if (envCidr != null) {
            if (isValidCidr(envCidr)) {
                log.info("Resolved server LAN CIDR from VAIER_SERVER_LAN_CIDR: {}", envCidr);
                return Optional.of(envCidr);
            }
            log.warn("Ignoring VAIER_SERVER_LAN_CIDR — not a valid IPv4 CIDR: {}", envCidr);
        }
        Optional<String> discovered = fetchEc2SubnetCidr();
        if (discovered.isPresent() && isValidCidr(discovered.get())) {
            log.info("Discovered server LAN CIDR from EC2 instance metadata: {}", discovered.get());
            return discovered;
        }
        discovered.ifPresent(c -> log.warn("Ignoring EC2 subnet-ipv4-cidr-block — not a valid IPv4 CIDR: {}", c));
        return Optional.empty();
    }

    private Optional<String> fetchEc2SubnetCidr() {
        try {
            Optional<String> token = fetchToken();
            if (token.isEmpty()) return Optional.empty();
            Optional<String> macs = imdsGet("/meta-data/network/interfaces/macs/", token.get());
            String firstMac = macs.stream()
                .flatMap(String::lines)
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse(null);
            if (firstMac == null) return Optional.empty();
            String mac = firstMac.endsWith("/") ? firstMac : firstMac + "/";
            return imdsGet("/meta-data/network/interfaces/macs/" + mac + "subnet-ipv4-cidr-block", token.get());
        } catch (Exception e) {
            log.debug("EC2 instance metadata not reachable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> fetchToken() throws Exception {
        HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create(IMDS_BASE + "/api/token"))
            .timeout(IMDS_TIMEOUT)
            .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> resp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return Optional.empty();
        String token = resp.body().trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private Optional<String> imdsGet(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(IMDS_BASE + path))
            .timeout(IMDS_TIMEOUT)
            .header("X-aws-ec2-metadata-token", token)
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return Optional.empty();
        String body = resp.body().trim();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }

    private static boolean isValidCidr(String cidr) {
        try {
            Cidr.validateLanCidr(cidr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
