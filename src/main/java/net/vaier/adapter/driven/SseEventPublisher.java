package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class SseEventPublisher implements ForPublishingEvents {

    private final ConcurrentHashMap<String, List<SseEmitter>> topicEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String topic) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        topicEmitters.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(topic, emitter));
        emitter.onTimeout(() -> removeEmitter(topic, emitter));
        emitter.onError(e -> removeEmitter(topic, emitter));
        return emitter;
    }

    @Override
    public void publish(String topic, String eventName, String data) {
        List<SseEmitter> emitters = topicEmitters.getOrDefault(topic, List.of());
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

    private void removeEmitter(String topic, SseEmitter emitter) {
        List<SseEmitter> emitters = topicEmitters.get(topic);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
