package net.vaier.application;

public interface ForPublishingEvents {
    void publish(String topic, String eventName, String data);
}
