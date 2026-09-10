package net.vaier.domain;

import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A <b>Bundle</b> (#360): files on one machine that Chat offers the operator as a download card. Nothing is
 * copied or written anywhere — the download is the Explorer's own selection zip, built while it streams —
 * so the whole of what Vaier holds is the list of paths, what they add up to, and an hour.
 *
 * <p>The decisions are here: that every path is absolute and never climbs, what the zip is called, how
 * the size is said (and that a directory inside makes it "at least"), and how long the link lives.
 */
public record Bundle(String id, MachineId machineId, String machineLabel, List<String> paths, String name,
                     int fileCount, long totalBytes, boolean holdsADirectory, long offeredAtEpochMs) {

    public static final Duration TTL = Duration.ofHours(1);

    public Bundle {
        if (machineId == null) {
            throw new IllegalArgumentException("A bundle is on one machine");
        }
        paths = List.copyOf(paths);
    }

    public static Bundle offer(MachineId machineId, String machineLabel, List<String> paths, String name,
                               long nowEpochMs) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String raw : paths == null ? List.<String>of() : paths) {
            String path = raw == null ? "" : raw.trim();
            if (path.isEmpty()) {
                continue;
            }
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Every path must be absolute, as run_on_machine printed it: "
                    + path + " is not.");
            }
            if (List.of(path.split("/")).contains("..")) {
                throw new IllegalArgumentException("A path may not climb: " + path + ".");
            }
            cleaned.add(path);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Say which files.");
        }
        return new Bundle(UUID.randomUUID().toString(), machineId, machineLabel, new ArrayList<>(cleaned),
            zipName(name, machineLabel), 0, 0, false, nowEpochMs);
    }

    /** The model hands paths one per line. */
    public static List<String> pathsOf(String lines) {
        List<String> paths = new ArrayList<>();
        if (lines != null) {
            for (String line : lines.split("\n")) {
                if (!line.isBlank()) {
                    paths.add(line.trim());
                }
            }
        }
        return paths;
    }

    /** What the paths add up to, from one stat each. A directory is one entry whose tree is not walked here. */
    public Bundle sized(List<RemoteStat> stats) {
        long bytes = 0;
        boolean directory = false;
        for (RemoteStat stat : stats) {
            if (stat.directory()) {
                directory = true;
            } else {
                bytes += stat.sizeBytes();
            }
        }
        return new Bundle(id, machineId, machineLabel, paths, name, stats.size(), bytes, directory, offeredAtEpochMs);
    }

    public boolean expired(long nowEpochMs) {
        return nowEpochMs - offeredAtEpochMs >= TTL.toMillis();
    }

    public Bundle requireLive(long nowEpochMs) {
        if (expired(nowEpochMs)) {
            throw new NotFoundException("That download has expired; ask again.");
        }
        return this;
    }

    /** The download is the Explorer's own selection zip, so a bundle is a selection, in the present. */
    public List<Selection.Coordinate> coordinates() {
        return paths.stream().map(path -> new Selection.Coordinate(machineId, machineLabel, path, null)).toList();
    }

    /** What the card says it holds. */
    public String describe() {
        if (holdsADirectory) {
            return fileCount + (fileCount == 1 ? " entry" : " entries") + ", one of them a whole directory, at least "
                + human(totalBytes);
        }
        return fileCount + (fileCount == 1 ? " file, " : " files, ") + human(totalBytes);
    }

    /** What the model is told: offered, ready, and nothing written anywhere. */
    public String toolResult() {
        return "Offered to the operator as a download card: " + name + " (" + describe() + "). Tell them it is "
            + "ready to download; the card carries the link, which lives for an hour. Nothing was copied or "
            + "written anywhere.";
    }

    private static String zipName(String name, String machineLabel) {
        String base = name == null || name.isBlank() ? machineLabel + " files" : name;
        base = base.trim().replaceAll("\\.zip$", "");
        String safe = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return (safe.isEmpty() ? "files" : safe) + ".zip";
    }

    private static String human(long bytes) {
        if (bytes < 1000) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
