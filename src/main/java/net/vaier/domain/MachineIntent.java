package net.vaier.domain;

/**
 * What an operator is adding, expressed as intent rather than as a routing type. The intent-first
 * "add a machine" flow asks two plain questions — is this <b>a server</b> or <b>a personal
 * device</b>, and does it run <b>Windows</b> — and Vaier maps the answers onto one of the three peer
 * {@link MachineType}s. That mapping is a business decision, so it lives here in the domain and not
 * in the browser or the web layer.
 *
 * <p>Windows changes the type only for a personal device; a server is always an
 * {@link MachineType#UBUNTU_SERVER}.</p>
 */
public enum MachineIntent {

    SERVER {
        @Override
        public MachineType toMachineType(boolean windows) {
            // Vaier adds no Windows server: a server runs Vaier's own client in Docker.
            return MachineType.UBUNTU_SERVER;
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
     * @param windows whether the machine runs Windows — it matters only for a personal device
     */
    public abstract MachineType toMachineType(boolean windows);
}
