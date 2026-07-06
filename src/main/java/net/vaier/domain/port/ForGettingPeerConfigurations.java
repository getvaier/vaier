package net.vaier.domain.port;

import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerId;

import java.util.List;
import java.util.Optional;

public interface ForGettingPeerConfigurations {

    Optional<PeerConfiguration> getPeerConfigByName(String peerId);

    Optional<PeerConfiguration> getPeerConfigByIp(String ipAddress);

    List<PeerConfiguration> getAllPeerConfigs();

    /**
     * A peer's persisted configuration.
     *
     * @param id   the peer's immutable identifier — its WireGuard config directory name, REST
     *             path segment, and routing key. Derived from the operator-typed name when the
     *             peer is created and never changed afterwards.
     * @param name the operator-facing display label, freely editable. Always populated: for
     *             peers created before the id/name split it falls back to {@link PeerId#display}
     *             of the id.
     * @param deviceCategory the operator's device-category override, or null when none is pinned —
     *             the effective category is then auto-detected. Orthogonal to {@code peerType}:
     *             it only picks an icon, never routing. Backward-compatible: absent in pre-feature
     *             configs, reads as null.
     */
    record PeerConfiguration(
        String id,
        String name,
        String ipAddress,
        String configContent,
        MachineType peerType,
        String lanCidr,
        String lanAddress,
        String description,
        DeviceCategory deviceCategory,
        Boolean sshAccess
    ) {
        public PeerConfiguration(String id, String ipAddress, String configContent) {
            this(id, PeerId.display(id), ipAddress, configContent, MachineType.UBUNTU_SERVER,
                null, null, null, null, null);
        }

        public PeerConfiguration(String id, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, null, null, null, null);
        }

        public PeerConfiguration(String id, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr, String lanAddress) {
            this(id, PeerId.display(id), ipAddress, configContent, peerType, lanCidr, lanAddress, null, null, null);
        }

        /** Pre-device-category constructor: no override, effective category is auto-detected. */
        public PeerConfiguration(String id, String name, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr, String lanAddress,
                                 String description) {
            this(id, name, ipAddress, configContent, peerType, lanCidr, lanAddress, description, null, null);
        }

        /** Pre-ssh-access constructor: no SSH-access override, effective access is the smart default. */
        public PeerConfiguration(String id, String name, String ipAddress, String configContent,
                                 MachineType peerType, String lanCidr, String lanAddress,
                                 String description, DeviceCategory deviceCategory) {
            this(id, name, ipAddress, configContent, peerType, lanCidr, lanAddress, description,
                deviceCategory, null);
        }

        /**
         * The category Vaier shows for this peer: the operator's {@link #deviceCategory() override}
         * when one is pinned, otherwise the category auto-detected from the display {@code name} and
         * {@code peerType} (no LAN role — peers aren't scanned). Detection runs on the live name, so
         * renaming a peer re-detects when there is no override. Never null.
         */
        public DeviceCategory effectiveDeviceCategory() {
            return deviceCategory != null
                ? deviceCategory
                : DeviceCategory.detect(name, peerType, null);
        }

        /** True when an explicit device-category override is pinned (rather than auto-detected). */
        public boolean deviceCategoryOverridden() {
            return deviceCategory != null;
        }

        /**
         * Whether Vaier offers SSH for this peer: the {@link #sshAccess() override} when set, else the
         * smart default seeded from the effective device category and peer type. Never null.
         */
        public boolean effectiveSshAccess() {
            return sshAccess != null
                ? sshAccess
                : Machine.defaultSshAccess(effectiveDeviceCategory(), peerType);
        }

        /**
         * The peer — other than {@code excludingPeerId} — that already owns {@code lanCidr}, if
         * any. A LAN CIDR may be routed by only one relay peer, so the caller rejects a non-empty
         * result. Empty when {@code lanCidr} is null.
         */
        public static Optional<PeerConfiguration> lanCidrOwner(List<PeerConfiguration> peers,
                                                               String lanCidr, String excludingPeerId) {
            if (lanCidr == null) {
                return Optional.empty();
            }
            return peers.stream()
                .filter(p -> !p.id().equals(excludingPeerId))
                .filter(p -> lanCidr.equals(p.lanCidr()))
                .findFirst();
        }
    }
}
