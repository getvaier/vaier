package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Service call</b>: one request to a published service's own API. The path stays inside the service,
 * a read is a GET and a write is anything else, and the sentence is what the operator says yes to.
 */
class ServiceCallTest {

    @Test
    void aPathStaysInsideTheService_andALeadingSlashIsAddedWhenMissing() {
        record Row(String said, String path) {}
        for (Row row : new Row[] {
            new Row("rest/items", "/rest/items"),
            new Row("/rest/items/PoolPump/state", "/rest/items/PoolPump/state"),
            new Row(" /api/v1/status?verbose=true&x=1 ", "/api/v1/status?verbose=true&x=1"),
        }) {
            assertThat(ServiceCall.proposed("GET", row.said(), null).path()).as(row.said()).isEqualTo(row.path());
        }
        for (String bad : new String[] { null, "", "  ", "http://evil.example/x", "//evil.example/x",
            "/a/../b", "..", "/a/./b", "/%2e%2e/etc", "/a%2fb", "/a\\b", "/a b", "/a#top", "/a\nb",
            "/" + "a".repeat(ServiceCall.MAX_PATH_CHARS) }) {
            assertThatThrownBy(() -> ServiceCall.proposed("GET", bad, null)).as(String.valueOf(bad))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * A <b>free read</b> is a GET Vaier knows changes nothing: openHAB's REST reads and OpenSprinkler's status
     * pages. Any other GET might - OpenSprinkler switches a station on with one - so it waits for a yes.
     */
    @Test
    void onlyAGetOnTheFreeReadListIsFree_andAnyOtherIsSentToCallService() {
        for (String free : new String[] { "/rest", "/rest/items/PoolPump/state", "rest/things?summary=true",
            "/jc", "/jo?pw=x", "/js", "/jp", "/jn?pw=a&b=1" }) {
            assertThat(ServiceCall.read(free).method()).as(free).isEqualTo("GET");
        }
        for (String unlisted : new String[] { "/", "/restart", "/api/documents/", "/cm?sid=1&en=1", "/jcx",
            "/jc/extra", "/cv?rsn=1" }) {
            assertThatThrownBy(() -> ServiceCall.read(unlisted)).as(unlisted)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("propose it with call_service");
        }
    }

    @Test
    void aProposedCallIsGetPostPutPatchOrDelete_andAGetCarriesNoBody() {
        for (String method : new String[] { "GET", "POST", "put", " Patch ", "DELETE" }) {
            assertThat(ServiceCall.proposed(method, "/rest", null).method()).as(method)
                .isEqualTo(method.trim().toUpperCase());
        }
        for (String method : new String[] { null, "", "HEAD", "OPTIONS", "TRACE", "CONNECT", "POSTX" }) {
            assertThatThrownBy(() -> ServiceCall.proposed(method, "/rest", null)).as(String.valueOf(method))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET, POST, PUT, PATCH or DELETE");
        }
        assertThatThrownBy(() -> ServiceCall.proposed("GET", "/cm?sid=1", "ON"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ServiceCall.proposed("GET", "/cm?sid=1&en=1", null).sentence("irrigation on Colina 27"))
            .isEqualTo("GET to irrigation on Colina 27 /cm?sid=1&en=1.");
    }

    /** openHAB takes a command as plain text and most APIs take JSON; the body's shape says which. */
    @Test
    void theBodysShapeSaysItsContentType_aBlankBodyIsNone_andAnOversizedOneIsRefused() {
        record Row(String body, String contentType) {}
        for (Row row : new Row[] {
            new Row("ON", "text/plain; charset=utf-8"),
            new Row("{\"state\": \"ON\"}", "application/json"),
            new Row(" [1, 2] ", "application/json"),
            new Row("{not closed", "text/plain; charset=utf-8"),
            new Row("  ", null),
            new Row(null, null),
        }) {
            assertThat(ServiceCall.proposed("POST", "/rest", row.body()).contentType()).as(String.valueOf(row.body()))
                .isEqualTo(row.contentType());
        }
        assertThat(ServiceCall.proposed("POST", "/rest", "  ").body()).isNull();
        assertThatThrownBy(() -> ServiceCall.proposed("POST", "/rest", "x".repeat(ServiceCall.MAX_BODY_CHARS + 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The whole of what the operator reads before saying yes; a long body is cut there, never in the call. */
    @Test
    void theSentenceSaysTheMethodTheServiceThePathAndTheBody() {
        assertThat(ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON").sentence("openhab on Colina 27"))
            .isEqualTo("POST to openhab on Colina 27 /rest/items/PoolPump with body \"ON\".");
        assertThat(ServiceCall.proposed("DELETE", "/api/tags/7", null).sentence("paperless on Apalveien 5"))
            .isEqualTo("DELETE to paperless on Apalveien 5 /api/tags/7.");

        String longBody = "{\"program\": \"" + "z".repeat(1000) + "\"}";
        ServiceCall call = ServiceCall.proposed("PUT", "/p", longBody);
        assertThat(call.body()).isEqualTo(longBody);
        assertThat(call.sentence("opensprinkler on Colina 27"))
            .startsWith("PUT to opensprinkler on Colina 27 /p with body \"{\"program\"")
            .contains("… (" + longBody.length() + " characters)")
            .hasSizeLessThan(ServiceCall.SENTENCE_BODY_CHARS + 120);
    }
}
