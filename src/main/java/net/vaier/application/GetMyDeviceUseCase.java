package net.vaier.application;

import net.vaier.domain.MachineId;

import java.util.Optional;

public interface GetMyDeviceUseCase {

    /**
     * Which machine the calling browser has claimed, if any.
     *
     * <p>A device claim binds one <em>browser</em> to one machine, so this is a property of who is asking —
     * never of the machine, and never something to put in the shared peer feed, where it would tell every
     * other session about one browser's binding.
     *
     * <p>Empty when this browser holds no claim, or when the claim it holds was revoked or superseded. The
     * tunnel is deliberately not consulted: being on the tunnel lets a device report its position, but it
     * is not a claim, and answering otherwise would tell a browser it holds one when it does not.
     */
    Optional<MachineId> myDevice(String claimToken);

    /**
     * The machine whose tunnel the calling browser comes through, if any — so the page can tell which
     * machine it would cut itself off by removing. Unrelated to the claim above.
     */
    Optional<TunnelMachine> tunnelMachine(String callerIp);

    /**
     * Whether the calling browser comes through a full-tunnel device Vaier cannot name: its request
     * hairpinned and arrives wearing the server's own public address, never its tunnel address.
     */
    boolean behindFullTunnel(String callerIp);

    /** @param removableFromHere whether this browser may remove the machine, as the domain decides it. */
    record TunnelMachine(MachineId machineId, boolean removableFromHere) {}
}
