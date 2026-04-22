package net.vaier.adapter.driven;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudflareDnsAdapterTest {

    @Mock HttpClient httpClient;
    @Mock ConfigResolver configResolver;

    CloudflareDnsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CloudflareDnsAdapter(configResolver, httpClient);
    }

    // --- No token configured ---

    @Test
    void getDnsZones_returnsEmptyWhenNoTokenConfigured() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn(null);

        assertThat(adapter.getDnsZones()).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    void addDnsRecord_throwsWhenNoTokenConfigured() {
        when(configResolver.getCloudflareToken()).thenReturn(null);

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                new DnsZone("example.com")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudflare");
    }

    // --- getDnsZones ---

    @Test
    void getDnsZones_parsesZonesFromCloudflareResponse() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponse(200, """
                {
                  "result": [
                    {"id": "zone-id-1", "name": "example.com", "status": "active"},
                    {"id": "zone-id-2", "name": "vaier.net", "status": "active"}
                  ],
                  "result_info": {"page": 1, "total_pages": 1, "count": 2},
                  "success": true
                }
                """);

        List<DnsZone> result = adapter.getDnsZones();

        assertThat(result).containsExactly(new DnsZone("example.com"), new DnsZone("vaier.net"));
    }

    @Test
    void getDnsZones_sendsBearerTokenAuthHeader() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("abc-secret-token");
        stubHttpResponse(200, """
                {"result": [], "result_info": {"page": 1, "total_pages": 1, "count": 0}, "success": true}
                """);

        adapter.getDnsZones();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer abc-secret-token");
    }

    // --- getDnsRecords ---

    @Test
    void getDnsRecords_returnsEmptyWhenZoneNotFound() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponse(200, """
                {"result": [], "result_info": {"page": 1, "total_pages": 1, "count": 0}, "success": true}
                """);

        assertThat(adapter.getDnsRecords(new DnsZone("missing.com"))).isEmpty();
    }

    @Test
    void getDnsRecords_groupsMultipleValuesIntoSingleRecord() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        // First call: lookup zone id for example.com
        // Second call: list records returns two separate records with same name+type
        stubHttpResponses(
                """
                {"result": [{"id": "zid", "name": "example.com"}], "result_info": {"page": 1, "total_pages": 1, "count": 1}, "success": true}
                """,
                """
                {"result": [
                    {"id": "r1", "type": "NS", "name": "example.com", "content": "ns1.cloudflare.com", "ttl": 86400},
                    {"id": "r2", "type": "NS", "name": "example.com", "content": "ns2.cloudflare.com", "ttl": 86400}
                ], "result_info": {"page": 1, "total_pages": 1, "count": 2}, "success": true}
                """
        );

        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().type()).isEqualTo(DnsRecordType.NS);
        assertThat(records.getFirst().values()).containsExactlyInAnyOrder("ns1.cloudflare.com", "ns2.cloudflare.com");
    }

    @Test
    void getDnsRecords_followsPaginationAcrossMultiplePages() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponses(
                // zone lookup
                """
                {"result": [{"id": "zid", "name": "example.com"}], "result_info": {"page": 1, "total_pages": 1, "count": 1}, "success": true}
                """,
                // records page 1
                """
                {"result": [{"id": "r1", "type": "A", "name": "a.example.com", "content": "1.1.1.1", "ttl": 300}],
                 "result_info": {"page": 1, "total_pages": 2, "count": 1}, "success": true}
                """,
                // records page 2
                """
                {"result": [{"id": "r2", "type": "A", "name": "b.example.com", "content": "2.2.2.2", "ttl": 300}],
                 "result_info": {"page": 2, "total_pages": 2, "count": 1}, "success": true}
                """
        );

        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).hasSize(2);
        assertThat(records).extracting(DnsRecord::name)
                .containsExactlyInAnyOrder("a.example.com", "b.example.com");
    }

    // --- addDnsRecord ---

    @Test
    void addDnsRecord_sendsOnePostPerValue() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponses(
                // zone lookup
                """
                {"result": [{"id": "zid", "name": "example.com"}], "result_info": {"page": 1, "total_pages": 1, "count": 1}, "success": true}
                """,
                // two POSTs
                "{\"success\": true, \"result\": {\"id\": \"new1\"}}",
                "{\"success\": true, \"result\": {\"id\": \"new2\"}}"
        );

        adapter.addDnsRecord(
                new DnsRecord("api.example.com", DnsRecordType.A, 300L, List.of("1.1.1.1", "2.2.2.2")),
                new DnsZone("example.com")
        );

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).send(requestCaptor.capture(), any());
        List<HttpRequest> allRequests = requestCaptor.getAllValues();
        // First request: zone lookup (GET)
        assertThat(allRequests.get(0).method()).isEqualTo("GET");
        // Next two: POSTs to create records
        assertThat(allRequests.get(1).method()).isEqualTo("POST");
        assertThat(allRequests.get(2).method()).isEqualTo("POST");
        assertThat(allRequests.get(1).uri().toString()).contains("/zones/zid/dns_records");
    }

    @Test
    void addDnsRecord_throwsWhenZoneNotFound() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponse(200, """
                {"result": [], "result_info": {"page": 1, "total_pages": 1, "count": 0}, "success": true}
                """);

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                new DnsZone("missing.com")
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cloudflare zone not found");
    }

    // --- deleteDnsRecord ---

    @Test
    void deleteDnsRecord_deletesAllMatchingRecordsByNameAndType() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponses(
                // zone lookup
                """
                {"result": [{"id": "zid", "name": "example.com"}], "result_info": {"page": 1, "total_pages": 1, "count": 1}, "success": true}
                """,
                // record listing — three records, two matching
                """
                {"result": [
                    {"id": "rec-a", "type": "NS", "name": "example.com", "content": "ns1", "ttl": 86400},
                    {"id": "rec-b", "type": "NS", "name": "example.com", "content": "ns2", "ttl": 86400},
                    {"id": "rec-c", "type": "A", "name": "example.com", "content": "1.2.3.4", "ttl": 300}
                ], "result_info": {"page": 1, "total_pages": 1, "count": 3}, "success": true}
                """,
                // two DELETEs
                "{\"success\": true}",
                "{\"success\": true}"
        );

        adapter.deleteDnsRecord("example.com", DnsRecordType.NS, new DnsZone("example.com"));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(4)).send(requestCaptor.capture(), any());
        List<HttpRequest> all = requestCaptor.getAllValues();
        assertThat(all.get(2).method()).isEqualTo("DELETE");
        assertThat(all.get(3).method()).isEqualTo("DELETE");
        assertThat(all.get(2).uri().toString()).contains("/dns_records/rec-a");
        assertThat(all.get(3).uri().toString()).contains("/dns_records/rec-b");
    }

    // --- Zone creation unsupported ---

    @Test
    void addDnsZone_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> adapter.addDnsZone(new DnsZone("new.com")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updateDnsZone_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> adapter.updateDnsZone(new DnsZone("example.com")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- HTTP error handling ---

    @Test
    void getDnsZones_throwsOnNon2xxResponse() throws Exception {
        when(configResolver.getCloudflareToken()).thenReturn("test-token");
        stubHttpResponse(401, """
                {"success": false, "errors": [{"code": 10000, "message": "Authentication error"}]}
                """);

        assertThatThrownBy(() -> adapter.getDnsZones())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cloudflare");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private void stubHttpResponse(int statusCode, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private void stubHttpResponses(String... bodies) throws IOException, InterruptedException {
        HttpResponse<String>[] responses = new HttpResponse[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            HttpResponse<String> r = mock(HttpResponse.class);
            when(r.statusCode()).thenReturn(200);
            when(r.body()).thenReturn(bodies[i]);
            responses[i] = r;
        }
        Object[] rest = new Object[Math.max(0, bodies.length - 1)];
        System.arraycopy(responses, 1, rest, 0, rest.length);
        doReturn(responses[0], rest).when(httpClient).send(any(HttpRequest.class), any());
    }
}
