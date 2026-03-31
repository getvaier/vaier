package net.vaier.application.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingPublicationsTracker {

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public void track(String address, int port) {
        pending.add(key(address, port));
    }

    public void untrack(String address, int port) {
        pending.remove(key(address, port));
    }

    public boolean isPending(String address, int port) {
        return pending.contains(key(address, port));
    }

    private String key(String address, int port) {
        return address + ":" + port;
    }
}
