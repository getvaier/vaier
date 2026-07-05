package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class SseEventPublisher implements ForPublishingEvents, ForSubscribingToEvents {

    private final ConcurrentHashMap<String, List<SseEmitter>> topicEmitters = new ConcurrentHashMap<>();
    // Signal-only subscribers get the same event names with the data stripped (see subscribeSignalOnly).
    private final ConcurrentHashMap<String, List<SseEmitter>> signalEmitters = new ConcurrentHashMap<>();

    @Override
    public SseEmitter subscribe(String topic) {
        return register(topicEmitters, topic);
    }

    @Override
    public SseEmitter subscribeSignalOnly(String topic) {
        return register(signalEmitters, topic);
    }

    private SseEmitter register(ConcurrentHashMap<String, List<SseEmitter>> registry, String topic) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        registry.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(registry, topic, emitter));
        emitter.onTimeout(() -> removeEmitter(registry, topic, emitter));
        emitter.onError(e -> removeEmitter(registry, topic, emitter));
        return emitter;
    }

    @Override
    public void publish(String topic, String eventName, String data) {
        // Full subscribers get the event with its data; signal-only subscribers get the same event
        // name with an empty payload, so the launchpad still knows to re-fetch but no subdomain leaks.
        deliver(topicEmitters, topic, eventName, data);
        deliver(signalEmitters, topic, eventName, "");
    }

    private void deliver(ConcurrentHashMap<String, List<SseEmitter>> registry, String topic,
                         String eventName, String data) {
        List<SseEmitter> emitters = registry.getOrDefault(topic, List.of());
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                log.debug("Removing dead SSE emitter on topic '{}': {}", topic, e.getMessage());
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
        }
    }

    private void removeEmitter(ConcurrentHashMap<String, List<SseEmitter>> registry, String topic,
                               SseEmitter emitter) {
        List<SseEmitter> emitters = registry.get(topic);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
