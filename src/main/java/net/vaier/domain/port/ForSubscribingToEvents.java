package net.vaier.domain.port;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ForSubscribingToEvents {
    SseEmitter subscribe(String topic);

    /**
     * Subscribe to a topic but receive only a content-free signal: the same event names fire, with
     * an empty data payload. For the public launchpad, which only needs a "something changed" nudge
     * to re-fetch — so a service's subdomain (carried in the full stream's data) never leaks to an
     * anonymous caller.
     */
    SseEmitter subscribeSignalOnly(String topic);
}
