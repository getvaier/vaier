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
     * One machine's running containers, so the sweep can scope each verdict to the host it runs on.
     *
     * @param machine    the machine's name — {@code LanAnchor.VAIER_SERVER_NAME} for the Vaier server's own
     *                   containers, or a server peer's name
     * @param containers the containers scraped from that machine
     */
    public record MachineContainers(String machine, List<DockerService> containers) {}

    /**
     * Judge every container's image, keyed by the {@link ScopedImage} it is — image <b>and</b> the machine it
     * runs on, so the same tag on two hosts is two verdicts rather than one last-wins collapse.
     *
     * <p>Containers that are not running are left out entirely: a stopped container is not serving anything,
     * so "there is a newer image" is not news the operator can act on. Images that cannot drift — pinned by
     * digest, built locally, an unparseable name — are reported {@link UpdateAvailability#UNKNOWN} without the
     * registry ever being asked.
     */
    public static Map<ScopedImage, UpdateAvailability> sweep(List<MachineContainers> machines,
                                                             ForResolvingRegistryDigest registry) {
        return sweep(machines, registry, false);
    }

    /**
     * The sweep the <b>operator asked for</b>: same rules, but no remembered registry answer is allowed.
     *
     * <p>They pulled an image and want Vaier to agree. A remembered answer is exactly what cannot be trusted
     * to settle that: it holds what the registry said <em>before</em> they pulled, so if upstream moved in the
     * interim the sweep would compare their new local digest against an old registry one, find a difference,
     * and report an update available on the image they just updated. The daily sweep tolerates a remembered
     * answer because nothing is riding on the minute; this one cannot.
     *
     * <p>Every other rule is deliberately identical to {@link #sweep} — one question per distinct image (which
     * matters more here, not less, since nothing else stands between an impatient click and the rate limit),
     * nothing asked about an image that cannot drift, and a failure ruled unknown rather than outdated.
     */
    public static Map<ScopedImage, UpdateAvailability> sweepFresh(List<MachineContainers> machines,
                                                                  ForResolvingRegistryDigest registry) {
        return sweep(machines, registry, true);
    }

    private static Map<ScopedImage, UpdateAvailability> sweep(List<MachineContainers> machines,
                                                              ForResolvingRegistryDigest registry,
                                                              boolean fresh) {
        Map<ScopedImage, UpdateAvailability> verdicts = new LinkedHashMap<>();
        if (machines == null || machines.isEmpty()) {
            return verdicts;
        }

        // One question per distinct image STRING, never per (machine, image): the same tag resolves to the
        // same digest everywhere, so scoping the question to a machine would only multiply the rate-limited
        // requests by the fleet size. The per-machine granularity comes from comparing each container's own
        // local digest against this one shared registry answer.
        Map<String, String> registryDigests = new LinkedHashMap<>();
        Set<String> resolved = new LinkedHashSet<>();

        for (MachineContainers machine : machines) {
            for (DockerService container : machine.containers()) {
                if (!container.isRunning()) {
                    continue;
                }
                String image = container.image();
                ScopedImage scoped = new ScopedImage(machine.machine(), image);
                var reference = ImageReference.parse(image);
                if (reference.isEmpty() || container.imageDigest() == null) {
                    verdicts.put(scoped, UpdateAvailability.UNKNOWN);
                    continue;
                }
                if (resolved.add(image)) {
                    resolveInto(registryDigests, image, reference.get(), registry, fresh);
                }
                verdicts.put(scoped,
                    UpdateAvailability.compare(container.imageDigest(), registryDigests.get(image)));
            }
        }
        return verdicts;
    }

    /**
     * Ask one registry, and never let it take the sweep down with it. A throw and an empty answer are the same
     * fact — Vaier could not find out — and both leave no digest recorded, which reads as unknown.
     */
    private static void resolveInto(Map<String, String> digests, String image, ImageReference reference,
                                    ForResolvingRegistryDigest registry, boolean fresh) {
        try {
            var digest = fresh ? registry.resolveDigestNow(reference) : registry.resolveDigest(reference);
            digest.ifPresent(d -> digests.put(image, d));
        } catch (Exception e) {
            // Unreachable, rate-limited, unauthorized, no egress: unknown, and on to the next image.
        }
    }
}
