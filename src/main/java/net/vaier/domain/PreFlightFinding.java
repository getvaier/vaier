package net.vaier.domain;

/**
 * One thing the pre-flight found wrong (#265): which check, a sentence saying what is wrong, and one saying what
 * to do about it. A healthy server yields none.
 */
public record PreFlightFinding(Check check, String message, String remedy) {

    public enum Check { WILDCARD_DNS, CERTIFICATE, WIREGUARD, DISK }

    public PreFlightFinding {
        if (check == null) {
            throw new IllegalArgumentException("PreFlightFinding check must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("PreFlightFinding message must not be blank");
        }
        if (remedy == null || remedy.isBlank()) {
            throw new IllegalArgumentException("PreFlightFinding remedy must not be blank");
        }
    }
}
