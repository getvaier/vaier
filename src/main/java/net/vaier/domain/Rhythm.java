package net.vaier.domain;

import lombok.Builder;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;

/**
 * The <b>rhythm</b> of an <b>errand</b> (#360): when Marvin is to do it, written by the model as one string
 * and understood here. Four shapes and no more — once, daily, weekly, monthly — because a rhythm the
 * operator cannot read back is a rhythm they cannot cancel with confidence, and a cron expression is not
 * something anyone reads back.
 *
 * <p>Every decision about time is here: whether a named moment has already passed, when the next occurrence
 * falls, what a monthly day does in February, and how the whole thing reads in a sentence. The clock is
 * handed in and never read — "the next 08:00" is a different instant in Oslo than in Honolulu, and only the
 * caller knows which one the operator meant.
 */
@Builder
public record Rhythm(Kind kind, LocalDate onceOn, DayOfWeek weekday, int dayOfMonth, LocalTime time) {

    /** The four shapes. Anything else is refused in words that name all four. */
    public enum Kind { ONCE, DAILY, WEEKLY, MONTHLY }

    /** Said in every refusal, because the refusal goes to the model and is its only correction. */
    public static final String SHAPES = "A rhythm is one of: once 2026-09-12T08:00, daily 08:00, "
        + "weekly monday 08:00, monthly 1 08:00.";

    private static final DateTimeFormatter HOUR_AND_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_MONTH_YEAR =
        DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    public Rhythm {
        if (kind == null || time == null) {
            throw new IllegalArgumentException(SHAPES);
        }
    }

    /**
     * The one string the model writes, read. Case-insensitive and trimmed, because the model writes it by
     * hand every time; the weekday is English, as everything else the model is told is.
     */
    public static Rhythm parse(String source) {
        String[] words = (source == null ? "" : source.trim()).split("\\s+");
        String kind = words[0].toLowerCase(Locale.ROOT);
        try {
            return switch (kind) {
                case "once" -> once(words);
                case "daily" -> daily(words);
                case "weekly" -> weekly(words);
                case "monthly" -> monthly(words);
                default -> throw new IllegalArgumentException(SHAPES);
            };
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException(SHAPES);
        }
    }

    private static Rhythm once(String[] words) {
        if (words.length != 2) {
            throw new IllegalArgumentException(SHAPES);
        }
        String[] halves = words[1].toUpperCase(Locale.ROOT).split("T");
        if (halves.length != 2) {
            throw new IllegalArgumentException(SHAPES);
        }
        return Rhythm.builder().kind(Kind.ONCE)
            .onceOn(LocalDate.parse(halves[0])).time(timeOf(halves[1])).build();
    }

    private static Rhythm daily(String[] words) {
        if (words.length != 2) {
            throw new IllegalArgumentException(SHAPES);
        }
        return Rhythm.builder().kind(Kind.DAILY).time(timeOf(words[1])).build();
    }

    private static Rhythm weekly(String[] words) {
        if (words.length != 3) {
            throw new IllegalArgumentException(SHAPES);
        }
        return Rhythm.builder().kind(Kind.WEEKLY).weekday(weekdayOf(words[1])).time(timeOf(words[2])).build();
    }

    private static Rhythm monthly(String[] words) {
        if (words.length != 3) {
            throw new IllegalArgumentException(SHAPES);
        }
        int day = Integer.parseInt(words[1]);
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("A monthly errand runs on a day from 1 to 31.");
        }
        return Rhythm.builder().kind(Kind.MONTHLY).dayOfMonth(day).time(timeOf(words[2])).build();
    }

    private static LocalTime timeOf(String written) {
        return LocalTime.parse(written, HOUR_AND_MINUTE);
    }

    private static DayOfWeek weekdayOf(String written) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equalsIgnoreCase(written)) {
                return day;
            }
        }
        throw new IllegalArgumentException(SHAPES);
    }

    /**
     * When this rhythm first falls due, seen from {@code now}. A named moment already gone is refused while
     * the operator is still there to hear it; every repeating shape takes its next occurrence, strictly
     * after now, so an errand added at 08:00:30 for "daily 08:00" waits for tomorrow rather than firing
     * immediately.
     */
    public Instant firstDue(ZonedDateTime now) {
        if (kind == Kind.ONCE) {
            ZonedDateTime at = onceOn.atTime(time).atZone(now.getZone());
            if (!at.isAfter(now)) {
                throw new IllegalArgumentException("That time has passed.");
            }
            return at.toInstant();
        }
        return nextOccurrence(now);
    }

    /** When it falls due again after a run — empty for a once-errand, which is over. */
    public Optional<Instant> nextAfter(ZonedDateTime ran) {
        return repeats() ? Optional.of(nextOccurrence(ran)) : Optional.empty();
    }

    public boolean repeats() {
        return kind != Kind.ONCE;
    }

    private Instant nextOccurrence(ZonedDateTime from) {
        return switch (kind) {
            case DAILY -> strictlyAfter(from, from.toLocalDate(), from.toLocalDate().plusDays(1));
            case WEEKLY -> {
                LocalDate thisWeek = from.toLocalDate()
                    .plusDays((weekday.getValue() - from.getDayOfWeek().getValue() + 7) % 7);
                yield strictlyAfter(from, thisWeek, thisWeek.plusWeeks(1));
            }
            case MONTHLY -> {
                LocalDate thisMonth = clamped(from.toLocalDate());
                yield strictlyAfter(from, thisMonth, clamped(from.toLocalDate().withDayOfMonth(1).plusMonths(1)));
            }
            // A once-rhythm never occurs again; firstDue answers it and nextAfter refuses to ask.
            case ONCE -> onceOn.atTime(time).atZone(from.getZone()).toInstant();
        };
    }

    /** A day the month does not have is its last day — February's 28th for "monthly 31", never a skip. */
    private LocalDate clamped(LocalDate inMonth) {
        return inMonth.withDayOfMonth(Math.min(dayOfMonth, inMonth.lengthOfMonth()));
    }

    private Instant strictlyAfter(ZonedDateTime now, LocalDate candidate, LocalDate next) {
        ZonedDateTime at = candidate.atTime(time).atZone(now.getZone());
        return (at.isAfter(now) ? at : next.atTime(time).atZone(now.getZone())).toInstant();
    }

    /** How the operator reads it back, in the dialog and in the prompt. */
    public String describe() {
        String at = " at " + time.format(HOUR_AND_MINUTE);
        return switch (kind) {
            case ONCE -> "Once, on " + onceOn.format(DAY_MONTH_YEAR) + at;
            case DAILY -> "Every day" + at;
            case WEEKLY -> "Every " + weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + at;
            case MONTHLY -> "On the " + ordinal(dayOfMonth) + " of every month" + at;
        };
    }

    /** The same sentence, said mid-sentence: only the leading word loses its capital, never the weekday. */
    public String inSentence() {
        String described = describe();
        return Character.toLowerCase(described.charAt(0)) + described.substring(1);
    }

    /** What a file keeps, and what {@link #parse} reads back. */
    public String source() {
        String at = time.format(HOUR_AND_MINUTE);
        return switch (kind) {
            case ONCE -> "once " + onceOn + "T" + at;
            case DAILY -> "daily " + at;
            case WEEKLY -> "weekly " + weekday.name().toLowerCase(Locale.ROOT) + " " + at;
            case MONTHLY -> "monthly " + dayOfMonth + " " + at;
        };
    }

    private static String ordinal(int day) {
        String suffix = switch (day % 100) {
            case 11, 12, 13 -> "th";
            default -> switch (day % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return day + suffix;
    }
}
