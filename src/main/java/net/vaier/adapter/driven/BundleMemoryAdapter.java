package net.vaier.adapter.driven;

import net.vaier.domain.Bundle;
import net.vaier.domain.port.ForHoldingBundles;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bundles held in memory for their hour; a new one sweeps out the ones whose hour is up. */
@Component
public class BundleMemoryAdapter implements ForHoldingBundles {

    private final Map<String, Bundle> held = new ConcurrentHashMap<>();

    @Override
    public void hold(Bundle bundle) {
        hold(bundle, System.currentTimeMillis());
    }

    void hold(Bundle bundle, long nowEpochMs) {
        held.values().removeIf(other -> other.expired(nowEpochMs));
        held.put(bundle.id(), bundle);
    }

    @Override
    public Optional<Bundle> find(String id) {
        return Optional.ofNullable(held.get(id));
    }
}
