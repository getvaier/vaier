package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Vaier's <b>Memory</b> (#360): facts worth keeping across conversations, for the whole fleet — where the
 * photos live, which machine is the relay, what the operator prefers — whether the operator said them or
 * Chat found them by looking. Every fact is visible on the Chat pane and removable there, so nothing can be
 * planted in it unseen and the operator, not the model, has the last word on what stays.
 *
 * <p>The decisions are here: what counts as a fact (one, short, not already known), how many there may be
 * (a roof that is said, never a silent forgetting), and how the model reads them (with ids, so it can
 * forget one by name).
 */
public record Memory(List<Fact> facts) {

    public static final int MAX_FACTS = 200;
    public static final int MAX_CHARS = 500;

    /** One thing remembered: a short id the model can name it by, the fact, and when it was kept. */
    public record Fact(String id, String text, long rememberedAtEpochMs) {}

    public Memory {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static Memory empty() {
        return new Memory(List.of());
    }

    public Memory remember(String text, long nowEpochMs) {
        String fact = text == null ? "" : text.trim();
        if (fact.isEmpty()) {
            throw new IllegalArgumentException("Say what to remember.");
        }
        if (fact.length() > MAX_CHARS) {
            throw new IllegalArgumentException("A memory is one fact, at most " + MAX_CHARS + " characters.");
        }
        if (facts.stream().anyMatch(known -> known.text().equalsIgnoreCase(fact))) {
            return this;
        }
        if (facts.size() >= MAX_FACTS) {
            throw new IllegalArgumentException("Vaier's memory is full; forget something first.");
        }
        List<Fact> more = new ArrayList<>(facts);
        more.add(new Fact(freshId(), fact, nowEpochMs));
        return new Memory(more);
    }

    public Memory forget(String id) {
        List<Fact> fewer = facts.stream().filter(fact -> !fact.id().equals(id)).toList();
        if (fewer.size() == facts.size()) {
            throw new NotFoundException("Vaier has no memory with the id " + id + ".");
        }
        return new Memory(fewer);
    }

    /** What the model reads: every fact with its id. */
    public String forPrompt() {
        if (facts.isEmpty()) {
            return "(nothing yet)\n";
        }
        StringBuilder lines = new StringBuilder();
        for (Fact fact : facts) {
            lines.append("- [").append(fact.id()).append("] ").append(fact.text()).append('\n');
        }
        return lines.toString();
    }

    private String freshId() {
        String id;
        do {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
        } while (hasId(id));
        return id;
    }

    private boolean hasId(String id) {
        return facts.stream().anyMatch(fact -> fact.id().equals(id));
    }
}
