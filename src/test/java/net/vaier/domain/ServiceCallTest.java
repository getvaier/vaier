package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Service call</b>: one request to a published service's own API. The path stays inside the service,
 * and the details are what the operator says yes to.
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
        assertThat(ServiceCall.proposed("GET", "/cm?sid=1&en=1", null).details("irrigation on Colina 27"))
            .isEqualTo("Sends GET /cm?sid=1&en=1 to irrigation on Colina 27.");
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

    /**
     * The exact call under the card's plain headline, and what the operator really says yes to; a long body is
     * cut there, never in the call.
     */
    @Test
    void theDetailsSayTheMethodThePathTheServiceAndTheBody() {
        assertThat(ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON").details("openhab on Colina 27"))
            .isEqualTo("Sends POST /rest/items/PoolPump to openhab on Colina 27, with \"ON\".");
        assertThat(ServiceCall.proposed("DELETE", "/api/tags/7", null).details("paperless on Apalveien 5"))
            .isEqualTo("Sends DELETE /api/tags/7 to paperless on Apalveien 5.");

        String longBody = "{\"program\": \"" + "z".repeat(1000) + "\"}";
        ServiceCall call = ServiceCall.proposed("PUT", "/p", longBody);
        assertThat(call.body()).isEqualTo(longBody);
        assertThat(call.details("opensprinkler on Colina 27"))
            .startsWith("Sends PUT /p to opensprinkler on Colina 27, with \"{\"program\"")
            .contains("… (" + longBody.length() + " characters)")
            .hasSizeLessThan(ServiceCall.DETAILS_BODY_CHARS + 120);
    }

    /** An Always allow says, under the service's own answer, which folder is now free. */
    @Test
    void anAlwaysAllowedOutcomeSaysTheFolderSaved_underTheServicesAnswer() {
        ServiceCall read = ServiceCall.proposed("GET", "/api/documents/?query=x", null);

        assertThat(read.alwaysAllowed(new ActionWording("Done — paperless accepted it.", "It answered 200."),
            "paperless on Apalveien 5")).isEqualTo(new ActionWording("Done — paperless accepted it.",
            "It answered 200. Marvin reads everything under /api/ on paperless on Apalveien 5 without asking "
                + "from now on."));
    }

    /** What Always allow saves: the call's parent folder, or the call itself when it sits right under the root. */
    @Test
    void theAllowanceIsTheParentFolder_neverTheWholeService() {
        record Row(String path, String allowance) {}
        for (Row row : new Row[] {
            new Row("/rest/items/Gardenlights_Terrace_Switch", "/rest/items/"),
            new Row("/rest/items/X/state?x=1", "/rest/items/X/"),
            new Row("/api/documents/?query=x", "/api/"),
            new Row("/rest/items", "/rest/"),
            new Row("/jc", "/jc"),
            new Row("/", "/"),
        }) {
            assertThat(ServiceCall.proposed("GET", row.path(), null).allowance()).as(row.path())
                .isEqualTo(row.allowance());
        }
    }
}
