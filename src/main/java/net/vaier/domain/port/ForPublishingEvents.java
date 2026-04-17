package net.vaier.domain.port;

public interface ForPublishingEvents {
    void publish(String topic, String eventName, String data);
}
