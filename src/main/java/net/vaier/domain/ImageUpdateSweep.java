package net.vaier.domain;

import net.vaier.domain.port.ForResolvingRegistryDigest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The update-available sweep: given the containers Vaier can see and a way to ask registries, decide what is
 * out of date.
 *
 * <p>The sweep is a <b>domain operation that receives the driven port and calls it</b> — the service passes
 * {@link ForResolvingRegistryDigest} in and orchestrates nothing else, because every judgement here is a
 * business rule rather than plumbing: which containers are worth asking about (running ones, on a tag that can
 * actually drift), how many times a registry may be asked (once per distinct image, never once per container —
 * anonymous Docker Hub allows roughly 100 manifest requests per six hours), and what a failure means
 * ({@link UpdateAvailability#UNKNOWN}, never outdated).
 *
 * <p><b>It is total.</b> A registry that throws, times out, rate-limits, or has no route from the Vaier
 * container at all degrades that one image to unknown and the sweep carries on. One dead registry must never
 * cost the operator the rest of the fleet's verdicts.
 */
public final class ImageUpdateSweep {

    private ImageUpdateSweep() {}

    /**
     * Judge every container's image, keyed by the image string as the host reports it.
     *
     * <p>Containers that are not running are left out entirely: a stopped container is not serving anything,
     * so "there is a newer image" is not news the operator can act on. Images that cannot drift — pinned by
     * digest, built locally, an unparseable name — are reported {@link UpdateAvailability#UNKNOWN} without the
     * registry ever being asked.
     */
    public static Map<String, UpdateAvailability> sweep(List<DockerService> containers,
                                                        ForResolvingRegistryDigest registry) {
        Map<String, UpdateAvailability> verdicts = new LinkedHashMap<>();
        if (containers == null || containers.isEmpty()) {
            return verdicts;
        }

        // One question per distinct image, not per container: rate limits are counted in requests.
        Map<String, String> registryDigests = new LinkedHashMap<>();
        Set<String> resolved = new LinkedHashSet<>();

        for (DockerService container : containers) {
            if (!container.isRunning()) {
                continue;
            }
            String image = container.image();
            var reference = ImageReference.parse(image);
            if (reference.isEmpty() || container.imageDigest() == null) {
                verdicts.put(image, UpdateAvailability.UNKNOWN);
                continue;
            }
            if (resolved.add(image)) {
                resolveInto(registryDigests, image, reference.get(), registry);
            }
            verdicts.put(image, UpdateAvailability.compare(container.imageDigest(), registryDigests.get(image)));
        }
        return verdicts;
    }

    /**
     * Ask one registry, and never let it take the sweep down with it. A throw and an empty answer are the same
     * fact — Vaier could not find out — and both leave no digest recorded, which reads as unknown.
     */
    private static void resolveInto(Map<String, String> digests, String image, ImageReference reference,
                                    ForResolvingRegistryDigest registry) {
        try {
            registry.resolveDigest(reference).ifPresent(digest -> digests.put(image, digest));
        } catch (Exception e) {
            // Unreachable, rate-limited, unauthorized, no egress: unknown, and on to the next image.
        }
    }
}
