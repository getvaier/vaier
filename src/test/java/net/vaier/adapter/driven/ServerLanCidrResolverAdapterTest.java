package net.vaier.adapter.driven;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerLanCidrResolverAdapterTest {

    @Test
    void resolvesFromEnvVaierServerLanCidr() {
        var env = Map.of("VAIER_SERVER_LAN_CIDR", "172.31.0.0/16");
        var adapter = new ServerLanCidrResolverAdapter(env::get, mock(HttpClient.class));

        assertThat(adapter.resolve()).contains("172.31.0.0/16");
    }

    @Test
    void ignoresMalformedEnvAndFallsBackToImdsThenEmpty() throws Exception {
        var env = Map.of("VAIER_SERVER_LAN_CIDR", "172.31.0.0/16; rm -rf /");
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("no route"));
        var adapter = new ServerLanCidrResolverAdapter(env::get, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    @Test
    void emptyEnvValueIsTreatedAsAbsent() throws Exception {
        var env = Map.of("VAIER_SERVER_LAN_CIDR", "   ");
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("no route"));
        var adapter = new ServerLanCidrResolverAdapter(env::get, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoEnvAndImdsUnreachable() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("no route to host"));
        var adapter = new ServerLanCidrResolverAdapter(k -> null, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void discoversSubnetCidrFromEc2Imds() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse token = stringResponse(200, "TOKEN_VALUE");
        HttpResponse macs = stringResponse(200, "0a:1b:2c:3d:4e:5f/\n");
        HttpResponse cidr = stringResponse(200, "172.31.16.0/20");
        when(httpClient.send(argThat(isPutToken()), any())).thenReturn(token);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/")), any())).thenReturn(macs);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/0a:1b:2c:3d:4e:5f/subnet-ipv4-cidr-block")), any()))
            .thenReturn(cidr);

        var adapter = new ServerLanCidrResolverAdapter(k -> null, httpClient);

        assertThat(adapter.resolve()).contains("172.31.16.0/20");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ignoresMalformedImdsCidr() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse token = stringResponse(200, "TOKEN_VALUE");
        HttpResponse macs = stringResponse(200, "0a:1b:2c:3d:4e:5f/");
        HttpResponse cidr = stringResponse(200, "not-a-cidr");
        when(httpClient.send(argThat(isPutToken()), any())).thenReturn(token);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/")), any())).thenReturn(macs);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/0a:1b:2c:3d:4e:5f/subnet-ipv4-cidr-block")), any()))
            .thenReturn(cidr);

        var adapter = new ServerLanCidrResolverAdapter(k -> null, httpClient);

        assertThat(adapter.resolve()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resolvesOnceAndCachesTheResult() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse token = stringResponse(200, "TOKEN_VALUE");
        HttpResponse macs = stringResponse(200, "0a:1b:2c:3d:4e:5f/");
        HttpResponse cidr = stringResponse(200, "172.31.16.0/20");
        when(httpClient.send(argThat(isPutToken()), any())).thenReturn(token);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/")), any())).thenReturn(macs);
        when(httpClient.send(argThat(isGet("/meta-data/network/interfaces/macs/0a:1b:2c:3d:4e:5f/subnet-ipv4-cidr-block")), any()))
            .thenReturn(cidr);
        var adapter = new ServerLanCidrResolverAdapter(k -> null, httpClient);

        assertThat(adapter.resolve()).contains("172.31.16.0/20");
        assertThat(adapter.resolve()).contains("172.31.16.0/20");

        // token + macs + cidr = 3 IMDS requests, made only once across the two resolve() calls.
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void envOverrideTakesPrecedenceOverImds() throws Exception {
        var env = Map.of("VAIER_SERVER_LAN_CIDR", "10.0.0.0/8");
        HttpClient httpClient = mock(HttpClient.class);
        // Even if IMDS would answer, the env override wins and IMDS is never consulted.
        var adapter = new ServerLanCidrResolverAdapter(env::get, httpClient);

        assertThat(adapter.resolve()).contains("10.0.0.0/8");
    }

    private static ArgumentMatcher<HttpRequest> isPutToken() {
        return req -> req != null && "PUT".equals(req.method()) && req.uri().getPath().endsWith("/api/token");
    }

    private static ArgumentMatcher<HttpRequest> isGet(String pathSuffix) {
        return req -> req != null && "GET".equals(req.method()) && req.uri().getPath().endsWith(pathSuffix);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> stringResponse(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
