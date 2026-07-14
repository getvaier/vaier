package net.vaier.domain;

/**
 * Vaier could not read a machine's disk: the {@code df} did not run (a sleeping machine, a non-zero exit,
 * a timeout) or its output could not be parsed.
 *
 * <p>This exists so that "cannot tell" is never rendered as a number. A disk Vaier failed to read is not a
 * disk with room on it, and reporting {@code 0%} — or falling through to the generic 500 "An unexpected
 * error occurred" — would be Vaier claiming to know something it does not. It carries the machine's name so
 * the Explorer can say which machine went quiet, in the operator's own terms.
 */
public class DiskUnreadableException extends RuntimeException {

    public DiskUnreadableException(String machineName) {
        super("Vaier could not read the disk on \"" + machineName + "\". The machine may be asleep, or its "
            + "SSH user may not be able to run df.");
    }
}
