package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ContainerStanding;
import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingContainerStandings;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed memory of what Vaier has seen running on the fleet (#356, #317): {@code container-standings.yml}
 * in the config dir, one block per machine, one entry per container Vaier has actually watched run. No
 * secrets, so — like {@link MissingDefaultRouteFileAdapter} — a plain, tolerant SnakeYAML round-trip with
 * default file permissions and no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> The container scrape lives in memory, so a redeploy — several a day here —
 * would wipe "I saw this running". A container that stopped during a deploy would then look like one Vaier
 * had never seen run, which is exactly the case this feature is deliberately silent about.
 *
 * <p>Tolerant on load across versions too: #356 wrote {@code notRunningSince} and knew two standings, and
 * there is one of those files on every deployed Vaier. Both key and every unknown standing name are read
 * rather than dropped — losing that memory on upgrade would lose the whole feature for a day.
 *
 * <p>Tolerant on load, and the tolerance errs quiet rather than loud: a file that will not parse means
 * Vaier re-learns what is running over the next 30 seconds and says nothing until it watches something
 * stop. A memory it cannot read must never become an inbox full of containers it never saw start.
 *
 * <p>The file is the whole state, rewritten on each machine's answered scrape — a few hundred bytes per
 * machine, so a fleet costs one small write per machine per scrape and the file never needs compacting.
 */
@Component
@Slf4j
public class ContainerStandingFileAdapter implements ForPersistingContainerStandings {

    private static final String FILE_NAME = "container-standings.yml";
    private static final String ROOT_KEY = "machines";
    private static final String BOOTED_AT = "bootedAt";
    private static final String CONTAINERS = "containers";
    private static final String STANDING = "standing";
    private static final String READING = "reading";
    private static final String LAST_SEEN_RUNNING = "lastSeenRunning";
    private static final String TROUBLED_SINCE = "troubledSince";
    /** What #356 called {@link #TROUBLED_SINCE} before there was more than one trouble. Read, never written. */
    private static final String NOT_RUNNING_SINCE = "notRunningSince";
    private static final String MISSES = "misses";

    private final String filePath;

    public ContainerStandingFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public ContainerStandingFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<MachineContainerStanding> standingsFor(MachineId machineId) {
        Map<String, Object> machines = load();
        Object machine = machines.get(machineId.value());
        return machine instanceof Map<?, ?> block ? standingsIn(machineId, block) : List.of();
    }

    @Override
    public synchronized List<MachineContainerStanding> all() {
        List<MachineContainerStanding> everything = new ArrayList<>();
        load().forEach((machineId, block) -> {
            if (block instanceof Map<?, ?> entries) {
                everything.addAll(standingsIn(MachineId.of(machineId), entries));
            }
        });
        return List.copyOf(everything);
    }

    @Override
    public synchronized void record(MachineId machineId, List<MachineContainerStanding> standings) {
        Map<String, Object> machines = load();
        Map<String, Object> block = blockFor(machines, machineId);
        if (standings == null || standings.isEmpty()) {
            block.remove(CONTAINERS);
        } else {
            Map<String, Object> containers = new LinkedHashMap<>();
            standings.forEach(standing -> containers.put(standing.containerName(), written(standing)));
            block.put(CONTAINERS, containers);
        }
        // A machine with nothing remembered and no boot instant leaves no block behind at all: an empty
        // file is the state of a fleet with nothing to say, and that is what it should look like.
        if (block.isEmpty()) {
            machines.remove(machineId.value());
        }
        write(machines);
    }

    @Override
    public synchronized void recordBoot(MachineId machineId, Instant bootedAt) {
        Map<String, Object> machines = load();
        blockFor(machines, machineId).put(BOOTED_AT, bootedAt.toString());
        write(machines);
    }

    @Override
    public synchronized Optional<Instant> bootOf(MachineId machineId) {
        Object machine = load().get(machineId.value());
        return machine instanceof Map<?, ?> block ? instant(block.get(BOOTED_AT)) : Optional.empty();
    }

    /** The machine's block, created and attached when it has none yet. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> blockFor(Map<String, Object> machines, MachineId machineId) {
        Object existing = machines.get(machineId.value());
        Map<String, Object> block = existing instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        machines.put(machineId.value(), block);
        return block;
    }

    /**
     * One machine's remembered containers, each stamped with the machine's boot instant — kept once per
     * machine in the file, since it is a fact about the machine, and read back onto every standing so the
     * card and the mail can both say the reboot explains it without asking a second port.
     */
    private List<MachineContainerStanding> standingsIn(MachineId machineId, Map<?, ?> block) {
        if (!(block.get(CONTAINERS) instanceof Map<?, ?> containers)) {
            return List.of();
        }
        Instant bootedAt = instant(block.get(BOOTED_AT)).orElse(null);
        List<MachineContainerStanding> standings = new ArrayList<>();
        containers.forEach((name, entry) -> {
            if (name != null && entry instanceof Map<?, ?> fields) {
                standings.add(read(machineId, name.toString(), fields, bootedAt));
            }
        });
        return List.copyOf(standings);
    }

    private MachineContainerStanding read(MachineId machineId, String containerName, Map<?, ?> fields,
                                          Instant bootedAt) {
        return MachineContainerStanding.builder()
            .machineId(machineId)
            .containerName(containerName)
            // What a name means is the domain's, including that an unknown one reads as RUNNING: a memory
            // written by a newer Vaier must cost a re-learn, never an inbox full of invented alerts.
            .standing(standing(fields.get(STANDING)))
            .reading(fields.containsKey(READING) ? standing(fields.get(READING)) : null)
            .lastSeenRunning(instant(fields.get(LAST_SEEN_RUNNING)).orElse(null))
            .troubledSince(instant(fields.get(TROUBLED_SINCE))
                .or(() -> instant(fields.get(NOT_RUNNING_SINCE))).orElse(null))
            .machineBootedAt(bootedAt)
            .misses(fields.get(MISSES) instanceof Number misses ? misses.intValue() : 0)
            .build();
    }

    private static ContainerStanding standing(Object value) {
        return ContainerStanding.named(value == null ? null : value.toString());
    }

    private Map<String, Object> written(MachineContainerStanding standing) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(STANDING, standing.standing().name());
        // Only when the scrapes are reading something Vaier has not said yet — the window between a
        // trouble starting and it being worth saying, which a deploy lands in often enough to matter.
        if (standing.reading() != standing.standing()) {
            fields.put(READING, standing.reading().name());
        }
        if (standing.lastSeenRunning() != null) {
            fields.put(LAST_SEEN_RUNNING, standing.lastSeenRunning().toString());
        }
        if (standing.troubledSince() != null) {
            fields.put(TROUBLED_SINCE, standing.troubledSince().toString());
        }
        if (standing.misses() > 0) {
            fields.put(MISSES, standing.misses());
        }
        return fields;
    }

    private static Optional<Instant> instant(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.toString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Every machine's block. An unreadable file means nothing is remembered yet, never an error. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        File file = new File(filePath);
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null || !(data.get(ROOT_KEY) instanceof Map<?, ?> machines)) {
                return new LinkedHashMap<>();
            }
            return new LinkedHashMap<>((Map<String, Object>) machines);
        } catch (Exception e) {
            log.warn("Failed to load container standings from {}", filePath, e);
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String, Object> machines) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, machines);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save container standings to " + filePath, e);
        }
    }
}
