package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A service's <b>free reads</b>: the GET paths the operator said Marvin may always read, one list per
 * published service, each starting empty and built from <b>Always allow</b>.
 */
class FreeReadsTest {

    private static final String OPENHAB = "openhab.colina27.example.com";
    private static final String PAPERLESS = "example.com";

    @Test
    void everyServiceStartsWithNoFreeReads_soEveryGetIsSentToCallService() {
        for (String path : new String[] { "/rest", "/rest/items", "/jc", "/" }) {
            assertThatThrownBy(() -> FreeReads.empty().read(OPENHAB, null, path)).as(path)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("propose it with call_service, method GET");
        }
        assertThat(FreeReads.empty().of(OPENHAB, null)).isEmpty();
    }

    /**
     * One yes covers the folder, not the one item: the call's parent is saved, and every path under it is free
     * with any query. A call straight under the root keeps its own path, so one yes never frees a whole service.
     */
    @Test
    void alwaysAllowSavesTheParent_andEverythingUnderItIsFree_onThatServiceOnly() {
        FreeReads reads = FreeReads.empty()
            .alwaysAllowing(PAPERLESS, "/paperless", ServiceCall.proposed("GET", "/api/documents/?query=x", null))
            .alwaysAllowing(" OpenHAB.Colina27.example.com ", null,
                ServiceCall.proposed("GET", "rest/items/Gardenlights_Terrace_Switch", null))
            .alwaysAllowing(OPENHAB, null, ServiceCall.proposed("GET", "/jc", null));

        assertThat(reads.of(PAPERLESS, "/paperless")).containsExactly("/api/");
        assertThat(reads.of(OPENHAB, null)).containsExactly("/jc", "/rest/items/");
        record Row(String host, String pathPrefix, String path, boolean free) {}
        for (Row row : new Row[] {
            new Row(PAPERLESS, "/paperless", "/api/documents/?query=y&page=2", true),
            new Row(PAPERLESS, "/paperless", "/api/documents/7/", true),
            new Row(PAPERLESS, "/paperless", "/api/tags/", true),
            new Row(PAPERLESS, "/paperless", "/api", true),
            new Row(PAPERLESS, "/paperless", "/apix/", false),               // a sibling, not under it
            new Row(PAPERLESS, "/paperless", "/", false),
            new Row(PAPERLESS, null, "/api/documents/", false),              // the host's other service
            new Row(OPENHAB, null, "/rest/items/Utelys_utekjokken/state", true),
            new Row(OPENHAB, null, "/rest/things", false),
            new Row(OPENHAB, null, "/jc?pw=x", true),
            new Row(OPENHAB, null, "/jcx", false),
        }) {
            if (row.free()) {
                assertThat(reads.read(row.host(), row.pathPrefix(), row.path()).path()).as(row.toString())
                    .isEqualTo(row.path());
            } else {
                assertThatThrownBy(() -> reads.read(row.host(), row.pathPrefix(), row.path()))
                    .as(row.toString()).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void onlyAGetMayBeAlwaysAllowed() {
        for (String write : new String[] { "POST", "PUT", "PATCH", "DELETE" }) {
            ServiceCall call = ServiceCall.proposed(write, "/rest/items/PoolPump", null);
            assertThat(call.mayBeAlwaysAllowed()).as(write).isFalse();
            assertThatThrownBy(() -> FreeReads.empty().alwaysAllowing(OPENHAB, null, call)).as(write)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only a GET");
        }
        assertThat(ServiceCall.proposed("GET", "/rest", null).mayBeAlwaysAllowed()).isTrue();
    }

    @Test
    void removingAFreeRead_sendsMarvinBackToAsking() {
        FreeReads reads = FreeReads.empty()
            .alwaysAllowing(OPENHAB, null, ServiceCall.proposed("GET", "/rest/items/A", null))
            .alwaysAllowing(OPENHAB, null, ServiceCall.proposed("GET", "/rest/things/B", null));

        assertThat(reads.without(OPENHAB, null, "/rest/items/").of(OPENHAB, null)).containsExactly("/rest/things/");
        assertThat(reads.without(OPENHAB, null, "/rest/items/").without(OPENHAB, null, "/rest/things/")
            .byService()).isEmpty();
    }

    /** A service's list goes when its route does; a sibling path route on the same host keeps its own. */
    @Test
    void unpublishingForgetsTheListsOfEveryServiceOnTheHostThatNoRouteIsLeftFor() {
        FreeReads reads = FreeReads.empty()
            .alwaysAllowing(PAPERLESS, "/paperless", ServiceCall.proposed("GET", "/api/documents/", null))
            .alwaysAllowing(PAPERLESS, "/grafana", ServiceCall.proposed("GET", "/api/health", null))
            .alwaysAllowing(OPENHAB, null, ServiceCall.proposed("GET", "/rest", null));
        ReverseProxyRoute grafana = ReverseProxyRoute.builder().name("grafana").domainName(PAPERLESS)
            .pathPrefix("/grafana").build();

        FreeReads after = reads.afterUnpublishing(PAPERLESS, List.of(grafana));

        assertThat(after.of(PAPERLESS, "/paperless")).isEmpty();
        assertThat(after.of(PAPERLESS, "/grafana")).containsExactly("/api/");
        assertThat(after.of(OPENHAB, null)).containsExactly("/rest");
    }
}
