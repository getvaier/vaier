package net.vaier.adapter.driven;

import net.vaier.domain.Bundle;
import net.vaier.domain.Operator;
import net.vaier.domain.port.ForHoldingBundles;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bundles held in memory for their hour; a new one sweeps out the ones whose hour is up. */
@Component
public class BundleMemoryAdapter implements ForHoldingBundles {

    private record Held(Operator operator, Bundle bundle) {}

    private final Map<String, Held> held = new ConcurrentHashMap<>();

    @Override
    public void hold(Operator operator, Bundle bundle) {
        hold(operator, bundle, System.currentTimeMillis());
    }

    void hold(Operator operator, Bundle bundle, long nowEpochMs) {
        held.values().removeIf(other -> other.bundle().expired(nowEpochMs));
        held.put(bundle.id(), new Held(operator, bundle));
    }

    @Override
    public Optional<Bundle> find(String id) {
        return Optional.ofNullable(held.get(id)).map(Held::bundle);
    }

    @Override
    public List<Bundle> heldFor(Operator operator) {
        return held.values().stream().filter(entry -> entry.operator().equals(operator)).map(Held::bundle).toList();
    }
}
