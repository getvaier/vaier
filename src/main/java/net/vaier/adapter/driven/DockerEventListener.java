package net.vaier.adapter.driven;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Component
@Slf4j
public class DockerEventListener {

    private static final Set<String> STATE_CHANGE_ACTIONS = Set.of("start", "stop", "die", "kill", "pause", "unpause");

    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ForPublishingEvents forPublishingEvents;
    private volatile Closeable eventStream;

    public DockerEventListener(PublishedServicesCacheInvalidator publishedServicesCacheInvalidator,
                               ForPublishingEvents forPublishingEvents) {
        this.publishedServicesCacheInvalidator = publishedServicesCacheInvalidator;
        this.forPublishingEvents = forPublishingEvents;
    }

    @PostConstruct
    void startListening() {
        try {
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
            DockerClient client = DockerClientImpl.getInstance(config, httpClient);

            eventStream = client.eventsCmd()
                .withEventTypeFilter(EventType.CONTAINER)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Event event) {
                        DockerEventListener.this.onEvent(event);
                    }
                });
            log.info("Docker event listener started");
        } catch (Exception e) {
            log.warn("Failed to start Docker event listener: {}", e.getMessage());
        }
    }

    void onEvent(Event event) {
        if (event.getType() != EventType.CONTAINER) return;
        if (!STATE_CHANGE_ACTIONS.contains(event.getAction())) return;

        log.info("Container state changed: {} (action={})", event.getAction(), event.getAction());
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "service-updated", "container-state-changed");
    }

    @PreDestroy
    void stopListening() {
        if (eventStream != null) {
            try {
                eventStream.close();
            } catch (Exception e) {
                log.debug("Error closing Docker event stream: {}", e.getMessage());
            }
        }
    }
}
