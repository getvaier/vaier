package net.vaier.domain;

import net.vaier.domain.port.ForHoldingEffectiveUserIds;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@link EffectiveUser} in numbers: the uid and the group ids the machine gives Vaier's sign-in, which
 * is what its kernel actually checks a write against. Read on the fleet's rounds in front of their
 * {@code df}, on the same sign-in, and held in memory; a machine not read yet has none.
 */
public record EffectiveUserIds(int uid, Set<Integer> groups) {

    private static final String UID_MARKER = "VAIER-UID=";
    private static final String GIDS_MARKER = "VAIER-GIDS=";
    private static final Pattern UID_LINE = Pattern.compile("^" + UID_MARKER + "(\\d+)$", Pattern.MULTILINE);
    private static final Pattern GIDS_LINE = Pattern.compile("^" + GIDS_MARKER + "(\\d+(?: \\d+)*)$", Pattern.MULTILINE);

    /** Marker lines a {@code df} row cannot be mistaken for; silent on stderr. */
    private static final String READ = "{ echo " + UID_MARKER + "$(id -u); echo " + GIDS_MARKER + "$(id -G); } 2>/dev/null";

    public EffectiveUserIds {
        groups = Set.copyOf(groups);
    }

    public boolean root() {
        return uid == 0;
    }

    /** {@code command} with the read run ahead of it, as one command for one connection. */
    public static String readAheadOf(String command) {
        return READ + "; " + command;
    }

    /** What {@code id} said, or empty when it did not say both halves. */
    public static Optional<EffectiveUserIds> readFrom(CommandResult result) {
        if (result == null || result.stdout() == null) return Optional.empty();
        Matcher uid = UID_LINE.matcher(result.stdout());
        Matcher gids = GIDS_LINE.matcher(result.stdout());
        if (!uid.find() || !gids.find()) return Optional.empty();
        Set<Integer> groups = Arrays.stream(gids.group(1).split(" ")).map(Integer::valueOf).collect(Collectors.toSet());
        return Optional.of(new EffectiveUserIds(Integer.parseInt(uid.group(1)), groups));
    }

    /** Keep what this trip learned; a trip that learned nothing leaves the last answer standing. */
    public static void retain(MachineId machineId, CommandResult result, ForHoldingEffectiveUserIds holder) {
        readFrom(result).ifPresent(ids -> holder.record(machineId, ids));
    }
}
