package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.application.ForPublishingEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DockerEventListenerTest {

    @Test
    void containerStartEvent_invalidatesCacheAndPublishesSseEvent() {
        ForInvalidatingPublishedServicesCache cache = mock(ForInvalidatingPublishedServicesCache.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.CONTAINER);
        when(event.getAction()).thenReturn("start");

        listener.onEvent(event);

        verify(cache).invalidatePublishedServicesCache();
        verify(events).publish("published-services", "service-updated", "container-state-changed");
    }

    @Test
    void containerStopEvent_invalidatesCacheAndPublishesSseEvent() {
        ForInvalidatingPublishedServicesCache cache = mock(ForInvalidatingPublishedServicesCache.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.CONTAINER);
        when(event.getAction()).thenReturn("stop");

        listener.onEvent(event);

        verify(cache).invalidatePublishedServicesCache();
        verify(events).publish("published-services", "service-updated", "container-state-changed");
    }

    @Test
    void containerDieEvent_invalidatesCacheAndPublishesSseEvent() {
        ForInvalidatingPublishedServicesCache cache = mock(ForInvalidatingPublishedServicesCache.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.CONTAINER);
        when(event.getAction()).thenReturn("die");

        listener.onEvent(event);

        verify(cache).invalidatePublishedServicesCache();
        verify(events).publish("published-services", "service-updated", "container-state-changed");
    }

    @Test
    void irrelevantContainerEvent_doesNotInvalidateCache() {
        ForInvalidatingPublishedServicesCache cache = mock(ForInvalidatingPublishedServicesCache.class);
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
        ForInvalidatingPublishedServicesCache cache = mock(ForInvalidatingPublishedServicesCache.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);

        DockerEventListener listener = new DockerEventListener(cache, events);

        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.IMAGE);

        listener.onEvent(event);

        verifyNoInteractions(cache, events);
    }
}
