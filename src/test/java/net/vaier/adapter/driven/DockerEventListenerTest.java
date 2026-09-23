package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DockerEventListenerTest {

    @Test
    void containerStartStopOrDieEvent_invalidatesCacheAndPublishesSseEvent() {
        for (String action : new String[] { "start", "stop", "die" }) {
            PublishedServicesCacheInvalidator cache = mock(PublishedServicesCacheInvalidator.class);
            ForPublishingEvents events = mock(ForPublishingEvents.class);

            DockerEventListener listener = new DockerEventListener(cache, events);

            Event event = mock(Event.class);
            when(event.getType()).thenReturn(EventType.CONTAINER);
            when(event.getAction()).thenReturn(action);

            listener.onEvent(event);

            verify(cache, description(action)).invalidatePublishedServicesCache();
            verify(events, description(action)).publish("published-services", "service-updated",
                "container-state-changed");
        }
    }

    @Test
    void irrelevantContainerEvent_doesNotInvalidateCache() {
        PublishedServicesCacheInvalidator cache = mock(PublishedServicesCacheInvalidator.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.CONTAINER);
        when(event.getAction()).thenReturn("exec_start");

        listener.onEvent(event);

        verifyNoInteractions(cache, events);
    }

    @Test
    void nonContainerEvent_doesNotInvalidateCache() {
        PublishedServicesCacheInvalidator cache = mock(PublishedServicesCacheInvalidator.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.IMAGE);

        listener.onEvent(event);

        verifyNoInteractions(cache, events);
    }

    @Test
    void aStreamThatEnds_isResubscribed_andTheGapIsTreatedAsAChange() {
        // A docker-proxy restart ended the stream for good: live container state went quiet until Vaier restarted.
        record Row(String how, Consumer<ResultCallback.Adapter<Event>> end) {}
        for (Row row : List.of(
                new Row("error", callback -> callback.onError(new IOException("proxy restarted"))),
                new Row("completion", ResultCallback.Adapter::onComplete),
                // docker-java reports a dropped proxy as both; two reconnects doubled the streams live.
                new Row("error then completion", callback -> {
                    callback.onError(new IOException("proxy restarted"));
                    callback.onComplete();
                }))) {
            PublishedServicesCacheInvalidator cache = mock(PublishedServicesCacheInvalidator.class);
            ForPublishingEvents events = mock(ForPublishingEvents.class);
            List<ResultCallback.Adapter<Event>> subscriptions = new ArrayList<>();
            DockerEventListener listener = new DockerEventListener(cache, events,
                callback -> { subscriptions.add(callback); return () -> { }; }, Runnable::run);

            listener.startListening();
            row.end().accept(subscriptions.get(0));

            assertThat(subscriptions).as(row.how()).hasSize(2);
            verify(cache, description(row.how())).invalidatePublishedServicesCache();
        }
    }

    @Test
    void aStreamEndedByShutdown_isNotResubscribed() {
        List<ResultCallback.Adapter<Event>> subscriptions = new ArrayList<>();
        DockerEventListener listener = new DockerEventListener(mock(PublishedServicesCacheInvalidator.class),
            mock(ForPublishingEvents.class), callback -> { subscriptions.add(callback); return () -> { }; },
            Runnable::run);

        listener.startListening();
        listener.stopListening();
        subscriptions.get(0).onComplete();

        assertThat(subscriptions).hasSize(1);
    }
}
