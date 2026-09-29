package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a published service answered a <b>Service call</b>: its status and its words, never its headers. Text
 * and JSON are read through, binary is only measured, and a long body is cut and says so.
 */
class ServiceCallAnswerTest {

    private static final String OPENHAB = "openhab on Colina 27";

    private static ServiceCallAnswer answer(int status, String contentType, String body) {
        return new ServiceCallAnswer(status, contentType, body.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void textAndJsonAreReadThrough_binaryIsMeasured_andAnEmptyBodyIsSaid() {
        record Row(ServiceCallAnswer answer, String told) {}
        for (Row row : new Row[] {
            new Row(answer(200, "application/json", "{\"state\":\"ON\"}"),
                "openhab on Colina 27 answered 200 (application/json).\n\n{\"state\":\"ON\"}"),
            new Row(answer(200, "text/plain;charset=UTF-8", "ON"),
                "openhab on Colina 27 answered 200 (text/plain).\n\nON"),
            new Row(answer(200, "application/vnd.api+json", "{}"),
                "openhab on Colina 27 answered 200 (application/vnd.api+json).\n\n{}"),
            // No content type said: words are read as words.
            new Row(answer(200, null, "ON"), "openhab on Colina 27 answered 200.\n\nON"),
            new Row(new ServiceCallAnswer(200, "image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G' }, false),
                "openhab on Colina 27 answered 200 (image/png): binary, 4 bytes."),
            new Row(new ServiceCallAnswer(200, null, new byte[] { 0, 1, 2 }, true),
                "openhab on Colina 27 answered 200: binary, more than 3 bytes."),
            new Row(answer(204, null, ""), "openhab on Colina 27 answered 204, with no body."),
        }) {
            assertThat(row.answer().forModel(OPENHAB)).as(row.told()).isEqualTo(row.told());
        }
    }

    @Test
    void aLongBodyIsCut_andSaysItWas() {
        String told = answer(200, "application/json", "x".repeat(ServiceCallAnswer.MAX_CHARS + 5)).forModel(OPENHAB);

        assertThat(told).contains("x".repeat(ServiceCallAnswer.MAX_CHARS))
            .doesNotContain("x".repeat(ServiceCallAnswer.MAX_CHARS + 1))
            .endsWith("(The body was cut after " + ServiceCallAnswer.MAX_CHARS + " characters; the rest was not read.)");
        assertThat(new ServiceCallAnswer(200, "text/plain", "abc".getBytes(StandardCharsets.UTF_8), true)
            .forModel(OPENHAB)).endsWith("(The body was cut; the rest was not read.)");
    }

    /**
     * What the kept conversation holds of a yes: a success's answer, cut much shorter than the model's own read
     * because it is re-sent with every later question. A failure keeps nothing here; its start is in the details.
     */
    @Test
    void whatCameBack_isASuccessCutToTheKeptLength_andNothingForAFailure() {
        assertThat(answer(200, "application/json", "{\"state\":\"OFF\"}").cameBack(OPENHAB))
            .isEqualTo("openhab on Colina 27 answered 200 (application/json).\n\n{\"state\":\"OFF\"}");
        assertThat(answer(200, "text/plain", "x".repeat(ServiceCallAnswer.KEPT_CHARS + 5)).cameBack(OPENHAB))
            .contains("x".repeat(ServiceCallAnswer.KEPT_CHARS)).doesNotContain("x".repeat(ServiceCallAnswer.KEPT_CHARS + 1))
            .endsWith("(The body was cut after " + ServiceCallAnswer.KEPT_CHARS + " characters; the rest was not read.)");
        assertThat(answer(404, "application/json", "{\"error\":\"no\"}").cameBack(OPENHAB)).isNull();
    }

    /**
     * A yes is done only when the service said so. The headline says so in plain words; the status, and the
     * start of what the service said, are the details — so a read's card, and its approval page, show it.
     */
    @Test
    void theOutcomeSaysPlainlyWhetherItWorked_withTheStatusAndAShortSnippetInTheDetails() {
        ActionWording done = answer(200, "application/json", "{\"a\":1}").outcome(OPENHAB);
        assertThat(done).isEqualTo(new ActionWording("Done — openhab on Colina 27 accepted it.",
            "It answered 200: {\"a\":1}"));
        assertThat(answer(200, null, "").outcome(OPENHAB).details()).isEqualTo("It answered 200.");
        assertThat(answer(200, null, "").succeeded()).isTrue();

        record Row(ServiceCallAnswer answer, String details) {}
        for (Row row : new Row[] {
            new Row(answer(404, "application/json", "{\"error\":\n  \"Item PoolPump does not exist\"}"),
                "It answered 404: {\"error\": \"Item PoolPump does not exist\"}"),
            new Row(answer(401, "text/html", ""), "It answered 401."),
            new Row(answer(302, null, ""), "It answered 302."),
            new Row(new ServiceCallAnswer(500, "image/png", new byte[] { 0 }, false), "It answered 500."),
        }) {
            assertThat(row.answer().succeeded()).as(row.details()).isFalse();
            assertThat(row.answer().outcome(OPENHAB)).as(row.details()).isEqualTo(
                new ActionWording("That did not work — openhab on Colina 27 did not accept it.", row.details()));
        }
        assertThat(answer(500, "text/plain", "e".repeat(2000)).outcome(OPENHAB).details())
            .endsWith("…").hasSizeLessThan(ServiceCallAnswer.SNIPPET_CHARS + 60);
    }
}
