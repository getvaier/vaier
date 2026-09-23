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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class DockerEventListener {

    private static final long RECONNECT_DELAY_SECONDS = 5;
    private static final Set<String> STATE_CHANGE_ACTIONS = Set.of("start", "stop", "die", "kill", "pause", "unpause");

    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ForPublishingEvents forPublishingEvents;
    private final EventSubscription eventSubscription;
    private final Executor reconnectExecutor;
    private volatile Closeable eventStream;
    private volatile boolean stopping;
    private volatile boolean reconnectWarned;

    @Autowired
    public DockerEventListener(PublishedServicesCacheInvalidator publishedServicesCacheInvalidator,
                               ForPublishingEvents forPublishingEvents) {
        this(publishedServicesCacheInvalidator, forPublishingEvents, DockerEventListener::subscribeToDocker,
            CompletableFuture.delayedExecutor(RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    DockerEventListener(PublishedServicesCacheInvalidator publishedServicesCacheInvalidator,
                        ForPublishingEvents forPublishingEvents, EventSubscription eventSubscription,
                        Executor reconnectExecutor) {
        this.publishedServicesCacheInvalidator = publishedServicesCacheInvalidator;
        this.forPublishingEvents = forPublishingEvents;
        this.eventSubscription = eventSubscription;
        this.reconnectExecutor = reconnectExecutor;
    }

    @PostConstruct
    void startListening() {
        if (subscribe()) {
            log.info("Docker event listener started");
        }
    }

    private boolean subscribe() {
        try {
            eventStream = eventSubscription.subscribe(new ResultCallback.Adapter<>() {
                // docker-java can report one dropped stream as both an error and a completion.
                private final AtomicBoolean ended = new AtomicBoolean();

                @Override
                public void onNext(Event event) {
                    DockerEventListener.this.onEvent(event);
                }

                @Override
                public void onError(Throwable throwable) {
                    super.onError(throwable);
                    if (ended.compareAndSet(false, true)) streamEnded("failed: " + throwable.getMessage());
                }

                @Override
                public void onComplete() {
                    super.onComplete();
                    if (ended.compareAndSet(false, true)) streamEnded("closed");
                }
            });
            return true;
        } catch (Exception e) {
            streamEnded("could not start: " + e.getMessage());
            return false;
        }
    }

    // A docker-proxy restart ends the stream; without this the listener stayed dead until Vaier restarted.
    private void streamEnded(String why) {
        if (stopping) return;
        if (!reconnectWarned) {
            log.warn("Docker event stream {} — reconnecting every {}s", why, RECONNECT_DELAY_SECONDS);
            reconnectWarned = true;
        }
        reconnectExecutor.execute(this::reconnect);
    }

    private void reconnect() {
        if (stopping || !subscribe()) return;
        reconnectWarned = false;
        log.info("Docker event listener reconnected");
        // Containers may have changed while nobody was listening.
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "service-updated", "container-state-changed");
    }

    private static Closeable subscribeToDocker(ResultCallback.Adapter<Event> callback) {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .connectionTimeout(Duration.ofSeconds(5))
            .build();
        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        return client.eventsCmd().withEventTypeFilter(EventType.CONTAINER).exec(callback);
    }

    @FunctionalInterface
    interface EventSubscription {
        Closeable subscribe(ResultCallback.Adapter<Event> callback) throws Exception;
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
        stopping = true;
        if (eventStream != null) {
            try {
                eventStream.close();
            } catch (Exception e) {
                log.debug("Error closing Docker event stream: {}", e.getMessage());
            }
        }
    }
}
