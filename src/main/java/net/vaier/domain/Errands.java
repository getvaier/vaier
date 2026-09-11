package net.vaier.domain;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Every <b>errand</b> Vaier keeps (#360), for every operator — like {@link Memory}, one value holding the lot
 * and every decision about it.
 *
 * <p>The decisions are here: how many errands there may be (a roof that is said, never a silent dropping),
 * whose an errand is — another operator's is answered as one that does not exist, because it is none of their
 * business that it exists at all — which ones have come round, and what the model reads.
 */
public record Errands(List<Errand> errands) {

    /** A roof, so a model that misunderstands "every morning" cannot fill a file with errands. */
    public static final int MAX_ERRANDS = 50;

    public Errands {
        errands = errands == null ? List.of() : List.copyOf(errands);
    }

    public static Errands empty() {
        return new Errands(List.of());
    }

    /** One more errand, under the operator who asked for it, with its first time already worked out. */
    public Errands add(Operator operator, String instruction, Rhythm rhythm, ZonedDateTime now) {
        if (errands.size() >= MAX_ERRANDS) {
            throw new IllegalArgumentException("Vaier keeps at most " + MAX_ERRANDS
                + " errands; cancel one first.");
        }
        List<Errand> more = new ArrayList<>(errands);
        more.add(Errand.builder()
            .id(freshId())
            .operator(operator)
            .instruction(instruction)
            .rhythm(rhythm)
            .nextDue(rhythm.firstDue(now))
            .createdAtEpochMs(now.toInstant().toEpochMilli())
            .build());
        return new Errands(more);
    }

    /** Only this operator's own; anyone else's id is answered as an id Vaier does not have. */
    public Errands cancel(Operator operator, String id) {
        List<Errand> fewer = errands.stream()
            .filter(errand -> !(errand.id().equals(id) && errand.belongsTo(operator)))
            .toList();
        if (fewer.size() == errands.size()) {
            throw new NotFoundException("Vaier has no errand with the id " + id + ".");
        }
        return new Errands(fewer);
    }

    public List<Errand> of(Operator operator) {
        return errands.stream().filter(errand -> errand.belongsTo(operator)).toList();
    }

    /** Every operator's errands that have come round; whose each one is matters when it runs. */
    public List<Errand> due(Instant now) {
        return errands.stream().filter(errand -> errand.isDue(now)).toList();
    }

    /**
     * One errand, run. A repeating one is kept, moved on; a once-errand is dropped, because it is over. An id
     * that is no longer here changes nothing — an errand cancelled while it ran is not resurrected by its own
     * run finishing.
     */
    public Errands afterRun(String id, ZonedDateTime now, String outcome) {
        if (errands.stream().noneMatch(errand -> errand.id().equals(id))) {
            return this;
        }
        List<Errand> kept = new ArrayList<>();
        for (Errand errand : errands) {
            if (errand.id().equals(id)) {
                errand.ran(now, outcome).ifPresent(kept::add);
            } else {
                kept.add(errand);
            }
        }
        return new Errands(kept);
    }

    /** What the model reads: this operator's errands, with ids, so it can cancel one by name. */
    public String forPrompt(Operator operator) {
        List<Errand> mine = of(operator);
        if (mine.isEmpty()) {
            return "(none)\n";
        }
        StringBuilder lines = new StringBuilder();
        for (Errand errand : mine) {
            lines.append("- ").append(errand.describe()).append('\n');
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
        return errands.stream().anyMatch(errand -> errand.id().equals(id));
    }
}
