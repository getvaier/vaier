package net.vaier.domain.port;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ForSubscribingToEvents {
    SseEmitter subscribe(String topic);
}
