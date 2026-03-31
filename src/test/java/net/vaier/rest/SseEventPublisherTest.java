package net.vaier.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SseEventPublisherTest {

    SseEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SseEventPublisher();
    }

    @Test
    void subscribe_returnsNonNullEmitter() {
        SseEmitter emitter = publisher.subscribe("topic");
        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribe_differentCalls_returnDistinctEmitters() {
        SseEmitter e1 = publisher.subscribe("topic");
        SseEmitter e2 = publisher.subscribe("topic");
        assertThat(e1).isNotSameAs(e2);
    }

    @Test
    void publish_noSubscribers_doesNotThrow() {
        assertThatCode(() -> publisher.publish("topic", "event", "data"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_differentTopic_doesNotThrow() {
        publisher.subscribe("topic-a");
        // publish to topic-b should not affect topic-a subscribers
        assertThatCode(() -> publisher.publish("topic-b", "event", "data"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_withDeadEmitter_removesItAndDoesNotThrow() {
        // subscribe creates an emitter; without an HTTP response context, send() will throw
        // the publisher must catch that and remove the dead emitter
        publisher.subscribe("topic");

        // First publish: emitter is dead (no HTTP context), gets removed
        assertThatCode(() -> publisher.publish("topic", "event", "data"))
                .doesNotThrowAnyException();

        // Second publish: no subscribers left, should still be fine
        assertThatCode(() -> publisher.publish("topic", "event2", "data2"))
                .doesNotThrowAnyException();
    }

    @Test
    void afterEmitterCompleted_publishDoesNotThrow() {
        SseEmitter emitter = publisher.subscribe("topic");
        emitter.complete(); // triggers onCompletion → removes from list

        assertThatCode(() -> publisher.publish("topic", "event", "data"))
                .doesNotThrowAnyException();
    }
}
