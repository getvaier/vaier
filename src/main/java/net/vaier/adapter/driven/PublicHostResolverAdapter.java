package net.vaier.adapter.driven;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PublicHostResolverAdapter implements ForResolvingPublicHost {

    private static final String IMDS_BASE = "http://169.254.169.254/latest";
    private static final Duration IMDS_TIMEOUT = Duration.ofSeconds(2);

    private final Function<String, String> envLookup;
    private final HttpClient httpClient;

    public PublicHostResolverAdapter() {
        this(System::getenv, HttpClient.newBuilder().connectTimeout(IMDS_TIMEOUT).build());
    }

    PublicHostResolverAdapter(Function<String, String> envLookup, HttpClient httpClient) {
        this.envLookup = envLookup;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<PublicHost> resolve() {
        String envHost = trimToNull(envLookup.apply("VAIER_PUBLIC_HOST"));
        if (envHost != null) {
            log.info("Resolved public host from VAIER_PUBLIC_HOST: {}", envHost);
            return Optional.of(new PublicHost(envHost, DnsRecordType.CNAME));
        }
        String envIp = trimToNull(envLookup.apply("VAIER_PUBLIC_IP"));
        if (envIp != null) {
            log.info("Resolved public host from VAIER_PUBLIC_IP: {}", envIp);
            return Optional.of(new PublicHost(envIp, DnsRecordType.A));
        }
        Optional<String> ec2Hostname = fetchEc2PublicHostname();
        if (ec2Hostname.isPresent()) {
            log.info("Resolved public host from EC2 instance metadata: {}", ec2Hostname.get());
            return Optional.of(new PublicHost(ec2Hostname.get(), DnsRecordType.CNAME));
        }
        return Optional.empty();
    }

    private Optional<String> fetchEc2PublicHostname() {
        try {
            HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(IMDS_BASE + "/api/token"))
                .timeout(IMDS_TIMEOUT)
                .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (tokenResp.statusCode() != 200) {
                return Optional.empty();
            }
            String token = tokenResp.body().trim();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            HttpRequest metaReq = HttpRequest.newBuilder()
                .uri(URI.create(IMDS_BASE + "/meta-data/public-hostname"))
                .timeout(IMDS_TIMEOUT)
                .header("X-aws-ec2-metadata-token", token)
                .GET()
                .build();
            HttpResponse<String> metaResp = httpClient.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() != 200) {
                return Optional.empty();
            }
            String hostname = metaResp.body().trim();
            return hostname.isEmpty() ? Optional.empty() : Optional.of(hostname);
        } catch (Exception e) {
            log.debug("EC2 instance metadata not reachable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
