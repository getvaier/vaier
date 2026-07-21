package net.vaier.domain;

/**
 * What an operator is adding, expressed as intent rather than as a routing type. The intent-first
 * "add a machine" flow asks two plain questions — is this <b>a server</b> or <b>a personal
 * device</b>, and does it run <b>Windows</b> — and Vaier maps the answers onto one of the four peer
 * {@link MachineType}s. That mapping is a business decision, so it lives here in the domain and not
 * in the browser or the web layer.
 *
 * <p>Windows is the only platform detail that changes the type within an intent: an Ubuntu server,
 * a phone, a Mac and a Linux laptop all take their intent's WireGuard-native default (an
 * {@link MachineType#UBUNTU_SERVER} or a {@link MachineType#MOBILE_CLIENT} respectively), so the
 * second question reduces to a single Windows-or-not distinction.</p>
 */
public enum MachineIntent {

    SERVER {
        @Override
        public MachineType toMachineType(boolean windows) {
            return windows ? MachineType.WINDOWS_SERVER : MachineType.UBUNTU_SERVER;
        }
    },

    PERSONAL_DEVICE {
        @Override
        public MachineType toMachineType(boolean windows) {
            return windows ? MachineType.WINDOWS_CLIENT : MachineType.MOBILE_CLIENT;
        }
    };

    /**
     * The routing {@link MachineType} for this intent on the given platform.
     *
     * @param windows whether the machine runs Windows — the only platform detail that changes the
     *                type within an intent
     */
    public abstract MachineType toMachineType(boolean windows);
}
