package net.vaier.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of file {@link Coordinate}s the operator picked in the Explorer's selection bar, to act on
 * together — where the {@link Clipboard} holds one coordinate, a Selection holds many, and they may span
 * machines and points in time. Today its one job is "download the whole selection as one zip".
 *
 * <p>How that zip is arranged is a business decision, so it lives here rather than as ad-hoc helpers in
 * the service or controller. A Selection yields two things: the download's {@link #downloadFilename()
 * filename} — the machine's name when everything is on one machine, a generic name when it spans machines
 * — and each coordinate's {@link Placement#entryPrefix() top-level zip-entry name}. Within one namespace
 * the top-level name is a coordinate's basename; two machines' selections are namespaced by the machine
 * name so their {@code /etc}s cannot collide; and a basename collision <em>within</em> a namespace is made
 * unique with a {@code (2)}, {@code (3)} … suffix rather than silently overwriting.
 */
public record Selection(List<Coordinate> coordinates) {

    public Selection {
        if (coordinates.isEmpty()) {
            throw new IllegalArgumentException("A selection must name at least one coordinate");
        }
        coordinates = List.copyOf(coordinates);
    }

    /**
     * One picked file or directory: the {@code machine} it lives on, its {@code path} (the machine's own
     * true coordinate), and the point in time {@code at} — {@code null} for the live filesystem, or an
     * archive id for the past, exactly like a single download's {@code at}.
     */
    public record Coordinate(String machine, String path, String at) {
    }

    /** A coordinate paired with the top-level zip-entry name it is placed under (its file, or its subtree). */
    public record Placement(Coordinate coordinate, String entryPrefix) {
    }

    /** Whether the selection touches more than one machine — the switch between the two filename and layout rules. */
    public boolean spansMultipleMachines() {
        return coordinates.stream().map(Coordinate::machine).distinct().count() > 1;
    }

    /**
     * The zip's filename: the single machine's own name when everything is on one machine (mirroring a
     * single-directory download's {@code <machine>.zip}), or a generic {@code vaier-selection.zip} when the
     * selection spans machines and no one machine names it.
     */
    public String downloadFilename() {
        String base = spansMultipleMachines() ? "vaier-selection" : coordinates.getFirst().machine();
        return base + ".zip";
    }

    /**
     * Each coordinate paired with its unique top-level zip-entry name, in selection order. On one machine
     * the name is the coordinate's basename; across machines it is prefixed by the machine so two machines'
     * same-named paths sit in their own folders. A basename that collides within its namespace gets a
     * {@code (2)}, {@code (3)} … suffix so nothing is silently overwritten.
     */
    public List<Placement> placements() {
        boolean multiMachine = spansMultipleMachines();
        Set<String> taken = new LinkedHashSet<>();
        List<Placement> placements = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            String base = basename(coordinate);
            String top = multiMachine ? coordinate.machine() + "/" + base : base;
            placements.add(new Placement(coordinate, makeUnique(top, taken)));
        }
        return placements;
    }

    /**
     * A coordinate's own name inside the zip: the last segment of its normalised path, or — for a root with
     * no basename of its own ({@code /}) — the machine's name standing in for it, exactly as a single-directory
     * download of a root falls back to the machine name.
     */
    private static String basename(Coordinate coordinate) {
        String path = FileEntry.normalisePath(coordinate.path());
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? coordinate.machine() : name;
    }

    /** {@code name} if free, else {@code name (2)}, {@code name (3)} … — the first form not already taken. */
    private static String makeUnique(String name, Set<String> taken) {
        if (taken.add(name)) {
            return name;
        }
        for (int n = 2; ; n++) {
            String candidate = name + " (" + n + ")";
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }
}
