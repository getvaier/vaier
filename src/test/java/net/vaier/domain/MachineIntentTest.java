package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The intent-first "add a machine" flow asks the operator two things — what a machine is for
 * (a server, or a personal device) and whether it runs Windows — and Vaier turns that into the
 * routing {@link MachineType}. That translation is a business decision, so it lives here in the
 * domain, not in the browser or the controller.
 */
class MachineIntentTest {

    @Test
    void server_onWindows_isAWindowsServer() {
        assertThat(MachineIntent.SERVER.toMachineType(true)).isEqualTo(MachineType.WINDOWS_SERVER);
    }

    @Test
    void server_notWindows_isAnUbuntuServer() {
        assertThat(MachineIntent.SERVER.toMachineType(false)).isEqualTo(MachineType.UBUNTU_SERVER);
    }

    @Test
    void personalDevice_onWindows_isAWindowsClient() {
        assertThat(MachineIntent.PERSONAL_DEVICE.toMachineType(true)).isEqualTo(MachineType.WINDOWS_CLIENT);
    }

    @Test
    void personalDevice_notWindows_isAMobileClient() {
        // A phone, a Mac and a Linux box all take the same split-tunnel mobile-client routing —
        // Windows is the only platform detail that changes the type within an intent.
        assertThat(MachineIntent.PERSONAL_DEVICE.toMachineType(false)).isEqualTo(MachineType.MOBILE_CLIENT);
    }

    @Test
    void everyIntentAndPlatformMapsToAPeerType() {
        for (MachineIntent intent : MachineIntent.values()) {
            for (boolean windows : new boolean[] {true, false}) {
                MachineType type = intent.toMachineType(windows);
                assertThat(type).isNotNull();
                assertThat(type.isVpnPeer())
                        .as("intent %s (windows=%s) must map to a VPN peer type", intent, windows)
                        .isTrue();
            }
        }
    }
}
