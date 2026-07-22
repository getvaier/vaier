package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCategoryTest {

    @Test
    void hasTwelveValues() {
        assertThat(DeviceCategory.values()).containsExactlyInAnyOrder(
            DeviceCategory.PHONE,
            DeviceCategory.LAPTOP,
            DeviceCategory.DESKTOP,
            DeviceCategory.SERVER,
            DeviceCategory.NAS,
            DeviceCategory.PRINTER,
            DeviceCategory.ROUTER,
            DeviceCategory.GATEWAY,
            DeviceCategory.IOT,
            DeviceCategory.CAMERA,
            DeviceCategory.MEDIA,
            DeviceCategory.GENERIC
        );
    }

    // --- fromMachineType ---

    @Test
    void fromMachineType_mapsEachType() {
        assertThat(DeviceCategory.fromMachineType(MachineType.MOBILE_CLIENT)).isEqualTo(DeviceCategory.PHONE);
        assertThat(DeviceCategory.fromMachineType(MachineType.WINDOWS_CLIENT)).isEqualTo(DeviceCategory.LAPTOP);
        assertThat(DeviceCategory.fromMachineType(MachineType.UBUNTU_SERVER)).isEqualTo(DeviceCategory.SERVER);
        assertThat(DeviceCategory.fromMachineType(MachineType.WINDOWS_SERVER)).isEqualTo(DeviceCategory.SERVER);
        assertThat(DeviceCategory.fromMachineType(MachineType.LAN_SERVER)).isEqualTo(DeviceCategory.GENERIC);
    }

    @Test
    void fromMachineType_nullIsGeneric() {
        assertThat(DeviceCategory.fromMachineType(null)).isEqualTo(DeviceCategory.GENERIC);
    }

    // --- fromLanRole ---

    @Test
    void fromLanRole_mapsRoles() {
        assertThat(DeviceCategory.fromLanRole(LanMachineRole.DOCKER_HOST)).isEqualTo(DeviceCategory.SERVER);
        assertThat(DeviceCategory.fromLanRole(LanMachineRole.WEB_UI)).isEqualTo(DeviceCategory.SERVER);
        assertThat(DeviceCategory.fromLanRole(LanMachineRole.SSH_HOST)).isEqualTo(DeviceCategory.SERVER);
        assertThat(DeviceCategory.fromLanRole(LanMachineRole.PRINTER)).isEqualTo(DeviceCategory.PRINTER);
    }

    @Test
    void fromLanRole_unknownIsNull() {
        assertThat(DeviceCategory.fromLanRole(LanMachineRole.UNKNOWN)).isNull();
    }

    @Test
    void fromLanRole_nullIsNull() {
        assertThat(DeviceCategory.fromLanRole(null)).isNull();
    }

    // --- fromName ---

    @Test
    void fromName_nullOrBlankIsNull() {
        assertThat(DeviceCategory.fromName(null)).isNull();
        assertThat(DeviceCategory.fromName("")).isNull();
        assertThat(DeviceCategory.fromName("   ")).isNull();
    }

    @Test
    void fromName_noKeywordIsNull() {
        assertThat(DeviceCategory.fromName("xyzzy-12")).isNull();
    }

    @Test
    void fromName_isCaseInsensitive() {
        assertThat(DeviceCategory.fromName("My-SYNOLOGY-box")).isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void fromName_matchesNasKeywords() {
        for (String kw : new String[]{"nas", "synology", "qnap", "truenas", "freenas", "diskstation", "unraid"}) {
            assertThat(DeviceCategory.fromName("home-" + kw)).as(kw).isEqualTo(DeviceCategory.NAS);
        }
    }

    @Test
    void fromName_matchesPrinterKeywords() {
        for (String kw : new String[]{"printer", "epson", "brother", "laserjet", "officejet"}) {
            assertThat(DeviceCategory.fromName("the-" + kw)).as(kw).isEqualTo(DeviceCategory.PRINTER);
        }
    }

    @Test
    void fromName_matchesCameraKeywords() {
        for (String kw : new String[]{"camera", "ipcam", "doorbell", "reolink", "hikvision"}) {
            assertThat(DeviceCategory.fromName("front-" + kw)).as(kw).isEqualTo(DeviceCategory.CAMERA);
        }
    }

    @Test
    void fromName_matchesMediaKeywords() {
        for (String kw : new String[]{"roku", "chromecast", "appletv", "firetv", "nvidia-shield", "kodi", "plex", "smarttv"}) {
            assertThat(DeviceCategory.fromName("living-" + kw)).as(kw).isEqualTo(DeviceCategory.MEDIA);
        }
    }

    @Test
    void fromName_matchesIotKeywords() {
        for (String kw : new String[]{"iot", "sensor", "thermostat", "smartplug", "tasmota", "shelly", "esphome"}) {
            assertThat(DeviceCategory.fromName("kitchen-" + kw)).as(kw).isEqualTo(DeviceCategory.IOT);
        }
    }

    @Test
    void fromName_matchesRouterKeywords() {
        for (String kw : new String[]{"router", "openwrt", "unifi", "edgerouter", "accesspoint"}) {
            assertThat(DeviceCategory.fromName("main-" + kw)).as(kw).isEqualTo(DeviceCategory.ROUTER);
        }
    }

    @Test
    void fromName_matchesGatewayKeywords() {
        for (String kw : new String[]{"gateway", "zigbee", "zwave", "homeassistant", "hass"}) {
            assertThat(DeviceCategory.fromName("home-" + kw)).as(kw).isEqualTo(DeviceCategory.GATEWAY);
        }
    }

    @Test
    void fromName_matchesPhoneKeywords() {
        for (String kw : new String[]{"phone", "iphone", "android", "pixel", "galaxy", "oneplus"}) {
            assertThat(DeviceCategory.fromName("my-" + kw)).as(kw).isEqualTo(DeviceCategory.PHONE);
        }
    }

    @Test
    void fromName_matchesLaptopKeywords() {
        for (String kw : new String[]{"laptop", "macbook", "notebook", "thinkpad"}) {
            assertThat(DeviceCategory.fromName("work-" + kw)).as(kw).isEqualTo(DeviceCategory.LAPTOP);
        }
    }

    @Test
    void fromName_matchesDesktopKeywords() {
        for (String kw : new String[]{"desktop", "workstation", "imac"}) {
            assertThat(DeviceCategory.fromName("office-" + kw)).as(kw).isEqualTo(DeviceCategory.DESKTOP);
        }
    }

    @Test
    void fromName_matchesServerKeywords() {
        for (String kw : new String[]{"server", "proxmox", "esxi"}) {
            assertThat(DeviceCategory.fromName("big-" + kw)).as(kw).isEqualTo(DeviceCategory.SERVER);
        }
    }

    @Test
    void fromName_nasBeatsServerWhenBothMatch() {
        // "synology-server" contains both a NAS keyword and the SERVER keyword; NAS wins (earlier).
        assertThat(DeviceCategory.fromName("synology-server")).isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void fromName_printerBeatsServer() {
        assertThat(DeviceCategory.fromName("printer-server")).isEqualTo(DeviceCategory.PRINTER);
    }

    // --- detect ---

    @Test
    void detect_nameKeywordWinsOverEverything() {
        assertThat(DeviceCategory.detect("my-synology", MachineType.UBUNTU_SERVER, LanMachineRole.WEB_UI))
            .isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void detect_lanRoleWinsWhenNameHasNoKeyword() {
        assertThat(DeviceCategory.detect("box-17", MachineType.MOBILE_CLIENT, LanMachineRole.PRINTER))
            .isEqualTo(DeviceCategory.PRINTER);
    }

    @Test
    void detect_machineTypeWhenNoNameKeywordAndNoLanSignal() {
        assertThat(DeviceCategory.detect("box-17", MachineType.MOBILE_CLIENT, LanMachineRole.UNKNOWN))
            .isEqualTo(DeviceCategory.PHONE);
        assertThat(DeviceCategory.detect("box-17", MachineType.MOBILE_CLIENT, null))
            .isEqualTo(DeviceCategory.PHONE);
    }

    @Test
    void detect_genericFallbackWhenNothingSignals() {
        assertThat(DeviceCategory.detect("box-17", null, null)).isEqualTo(DeviceCategory.GENERIC);
        assertThat(DeviceCategory.detect("box-17", MachineType.LAN_SERVER, null))
            .isEqualTo(DeviceCategory.GENERIC);
    }

    @Test
    void detect_nullNameFallsThrough() {
        assertThat(DeviceCategory.detect(null, MachineType.WINDOWS_CLIENT, null))
            .isEqualTo(DeviceCategory.LAPTOP);
    }

    // --- fromString (parse override) ---

    @Test
    void fromString_parsesValidName() {
        assertThat(DeviceCategory.fromString("NAS")).isEqualTo(DeviceCategory.NAS);
        assertThat(DeviceCategory.fromString("nas")).isEqualTo(DeviceCategory.NAS);
        assertThat(DeviceCategory.fromString(" Printer ")).isEqualTo(DeviceCategory.PRINTER);
    }

    @Test
    void fromString_blankOrNullIsNull() {
        assertThat(DeviceCategory.fromString(null)).isNull();
        assertThat(DeviceCategory.fromString("  ")).isNull();
    }

    @Test
    void fromString_invalidThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DeviceCategory.fromString("BANANA"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- SSH-access seeding (#307) ---

    @Test
    void isAppliance_trueForNonShellDevices() {
        for (DeviceCategory c : new DeviceCategory[]{
                DeviceCategory.PHONE, DeviceCategory.PRINTER, DeviceCategory.ROUTER,
                DeviceCategory.GATEWAY, DeviceCategory.IOT, DeviceCategory.CAMERA, DeviceCategory.MEDIA}) {
            assertThat(c.isAppliance()).as(c.name()).isTrue();
        }
    }

    @Test
    void isAppliance_falseForShellCapableAndGeneric() {
        for (DeviceCategory c : new DeviceCategory[]{
                DeviceCategory.SERVER, DeviceCategory.NAS, DeviceCategory.DESKTOP,
                DeviceCategory.LAPTOP, DeviceCategory.GENERIC}) {
            assertThat(c.isAppliance()).as(c.name()).isFalse();
        }
    }

    @Test
    void sshCapableByDefault_trueOnlyForComputers() {
        for (DeviceCategory c : new DeviceCategory[]{
                DeviceCategory.SERVER, DeviceCategory.NAS, DeviceCategory.DESKTOP, DeviceCategory.LAPTOP}) {
            assertThat(c.sshCapableByDefault()).as(c.name()).isTrue();
        }
        for (DeviceCategory c : new DeviceCategory[]{
                DeviceCategory.PHONE, DeviceCategory.PRINTER, DeviceCategory.ROUTER, DeviceCategory.GATEWAY,
                DeviceCategory.IOT, DeviceCategory.CAMERA, DeviceCategory.MEDIA, DeviceCategory.GENERIC}) {
            assertThat(c.sshCapableByDefault()).as(c.name()).isFalse();
        }
    }

    @Test
    void isStorageClass_trueOnlyForServerAndNas() {
        assertThat(DeviceCategory.SERVER.isStorageClass()).isTrue();
        assertThat(DeviceCategory.NAS.isStorageClass()).isTrue();
        for (DeviceCategory c : new DeviceCategory[]{
                DeviceCategory.DESKTOP, DeviceCategory.LAPTOP, DeviceCategory.PHONE, DeviceCategory.PRINTER,
                DeviceCategory.ROUTER, DeviceCategory.GATEWAY, DeviceCategory.IOT, DeviceCategory.CAMERA,
                DeviceCategory.MEDIA, DeviceCategory.GENERIC}) {
            assertThat(c.isStorageClass()).as(c.name()).isFalse();
        }
    }
}
