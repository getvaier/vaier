package net.vaier.domain;

import lombok.Builder;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * One <b>errand</b> (#360): something the operator sent Marvin to do later, once or on a <b>rhythm</b>, while
 * nobody is watching. Marvin runs it alone, with the same reads he has in Chat, and mails the operator what
 * he found.
 *
 * <p>The decisions are here: whether it has come round ({@link #isDue}), what it becomes once it has run
 * ({@link #ran} — moved on, or over), whose it is, and how it reads back. The scheduler only asks; an errand
 * that decided nothing here would have its rules spread across a clock, a service and a controller.
 *
 * <p>It is built through the builder and not positionally: eight components, three of them strings and two of
 * them times, is exactly the shape where two swapped arguments compile and then lie.
 */
@Builder(toBuilder = true)
public record Errand(String id, Operator operator, String instruction, Rhythm rhythm, Instant nextDue,
                     long createdAtEpochMs, Long lastRunAtEpochMs, String lastOutcome) {

    /** An errand is a task, not a document. Long enough to name machines and say what to look for. */
    public static final int MAX_CHARS = 1000;

    public Errand {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An errand needs an id");
        }
        if (operator == null) {
            throw new IllegalArgumentException("An errand belongs to an operator");
        }
        if (rhythm == null) {
            throw new IllegalArgumentException(Rhythm.SHAPES);
        }
        if (nextDue == null) {
            throw new IllegalArgumentException("An errand needs a time to fall due");
        }
        instruction = instruction == null ? "" : instruction.trim();
        if (instruction.isEmpty()) {
            throw new IllegalArgumentException("Say what Marvin is to do.");
        }
        if (instruction.length() > MAX_CHARS) {
            throw new IllegalArgumentException("An errand is one task, at most " + MAX_CHARS
                + " characters. Ask for less at once.");
        }
    }

    /** Come round. Exactly at its time counts: a scheduler that only ever sees "after" would skip it. */
    public boolean isDue(Instant now) {
        return !nextDue.isAfter(now);
    }

    /**
     * This errand, run. A repeating one comes back moved on to its next time, carrying when it ran and how
     * that went; a once-errand comes back as nothing, because it is over.
     */
    public Optional<Errand> ran(ZonedDateTime now, String outcome) {
        return rhythm.nextAfter(now).map(next -> toBuilder()
            .nextDue(next)
            .lastRunAtEpochMs(now.toInstant().toEpochMilli())
            .lastOutcome(outcome)
            .build());
    }

    public boolean belongsTo(Operator who) {
        return operator.equals(who);
    }

    /** How it reads in the prompt: its id, so the model can cancel it by name, then when and what. */
    public String describe() {
        return "[" + id + "] " + rhythm.describe() + ": " + instruction;
    }

    /** What the model is told the moment one is added, so it can say it back to the operator. */
    public String addedSentence() {
        return "Added [" + id + "]: " + rhythm.describe() + " — " + instruction;
    }
}
