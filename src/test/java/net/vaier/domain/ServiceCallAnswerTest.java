package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** A yes is done only when the service said so; otherwise the operator reads why, in the service's words. */
    @Test
    void theOutcomeIsTheStatus_andAnythingButSuccessIsRefusedWithAShortSnippet() {
        assertThat(answer(200, "application/json", "{\"a\":1}").outcome(OPENHAB))
            .isEqualTo("openhab on Colina 27 answered 200.");
        assertThat(answer(202, null, "").outcome(OPENHAB)).isEqualTo("openhab on Colina 27 answered 202.");

        record Row(ServiceCallAnswer answer, String said) {}
        for (Row row : new Row[] {
            new Row(answer(404, "application/json", "{\"error\":\n  \"Item PoolPump does not exist\"}"),
                "openhab on Colina 27 answered 404: {\"error\": \"Item PoolPump does not exist\"}"),
            new Row(answer(401, "text/html", ""), "openhab on Colina 27 answered 401."),
            new Row(answer(302, null, ""), "openhab on Colina 27 answered 302."),
            new Row(new ServiceCallAnswer(500, "image/png", new byte[] { 0 }, false),
                "openhab on Colina 27 answered 500."),
        }) {
            assertThatThrownBy(() -> row.answer().outcome(OPENHAB)).as(row.said())
                .isInstanceOf(IllegalArgumentException.class).hasMessage(row.said());
        }
        assertThatThrownBy(() -> answer(500, "text/plain", "e".repeat(2000)).outcome(OPENHAB))
            .hasMessageEndingWith("…").message().hasSizeLessThan(ServiceCallAnswer.SNIPPET_CHARS + 60);
    }
}
