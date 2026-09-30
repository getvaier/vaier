package net.vaier.application;

import net.vaier.domain.ConflictException;
import net.vaier.domain.EnrolmentRequest;

/**
 * A device — a phone or a Windows computer — asks to join the fleet and then waits (#359 slice 1b).
 *
 * <p>The one anonymous write in Vaier: the phone has no session, and the point of the slice is that
 * it never needs one — the operator approves it from whatever device they are already signed in on.
 * What comes back is a {@code Join code} to show its owner and an {@code Enrolment ticket} to hold.
 * Nothing about the fleet is created or revealed until an admin approves.
 */
public interface RequestEnrolmentUseCase {

    /**
     * Opens an enrolment request for a device that minted its own keypair.
     *
     * @param platform {@code "windows"}, or {@code null} for a phone
     * @throws IllegalArgumentException if the key is not a WireGuard key, the name slugs to nothing or
     *                                  the platform is unknown
     * @throws ConflictException if {@link EnrolmentRequest#MAX_PENDING} devices are already waiting
     */
    EnrolmentRequest request(String name, String publicKey, String platform);
}
