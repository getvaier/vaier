package net.vaier.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kind of device a machine is, used purely to pick an icon for the operator. It is an
 * <em>orthogonal, presentation-only</em> concept: unlike {@link MachineType} — which is the routing
 * decision that drives WireGuard client/server config — a device category never changes how Vaier
 * routes, keys, or exposes a machine. A NAS and a desktop may both be {@code UBUNTU_SERVER} peers;
 * the device category is what tells them apart on the operator's screen.
 *
 * <p>The category is normally auto-detected from the signals Vaier already has (the machine name,
 * its {@link MachineType}, and — for scanned hosts — its guessed {@link LanMachineRole}). An
 * operator may pin an explicit override; the <em>effective</em> category is the override when set,
 * otherwise the detected one. {@link #GENERIC} is the fallback when nothing signals.
 */
public enum DeviceCategory {
    PHONE,
    LAPTOP,
    DESKTOP,
    SERVER,
    NAS,
    PRINTER,
    ROUTER,
    GATEWAY,
    IOT,
    CAMERA,
    MEDIA,
    GENERIC;

    /**
     * Name-keyword rules, in priority order — the first category whose keyword list the (lowercased)
     * name contains wins. A {@link LinkedHashMap} preserves the declared order; the order is the
     * decision (e.g. NAS before SERVER, so "synology-server" is a NAS).
     */
    private static final Map<DeviceCategory, List<String>> NAME_KEYWORDS = buildNameKeywords();

    private static Map<DeviceCategory, List<String>> buildNameKeywords() {
        Map<DeviceCategory, List<String>> m = new LinkedHashMap<>();
        m.put(NAS, List.of("nas", "synology", "qnap", "truenas", "freenas", "diskstation", "unraid"));
        m.put(PRINTER, List.of("printer", "epson", "brother", "laserjet", "officejet"));
        m.put(CAMERA, List.of("camera", "ipcam", "doorbell", "reolink", "hikvision"));
        m.put(MEDIA, List.of("roku", "chromecast", "appletv", "firetv", "nvidia-shield", "kodi", "plex", "smarttv"));
        m.put(IOT, List.of("iot", "sensor", "thermostat", "smartplug", "tasmota", "shelly", "esphome"));
        m.put(ROUTER, List.of("router", "openwrt", "unifi", "edgerouter", "accesspoint"));
        m.put(GATEWAY, List.of("gateway", "zigbee", "zwave", "homeassistant", "hass"));
        m.put(PHONE, List.of("phone", "iphone", "android", "pixel", "galaxy", "oneplus"));
        m.put(LAPTOP, List.of("laptop", "macbook", "notebook", "thinkpad"));
        m.put(DESKTOP, List.of("desktop", "workstation", "imac"));
        m.put(SERVER, List.of("server", "proxmox", "esxi"));
        return m;
    }

    /**
     * The category implied by a peer's {@link MachineType}. {@code LAN_SERVER} carries no device
     * signal of its own (a LAN server may be anything), so it maps to {@link #GENERIC}; a null
     * type is also {@link #GENERIC}.
     */
    public static DeviceCategory fromMachineType(MachineType t) {
        if (t == null) return GENERIC;
        return switch (t) {
            case MOBILE_CLIENT -> PHONE;
            case WINDOWS_CLIENT -> LAPTOP;
            case UBUNTU_SERVER, WINDOWS_SERVER -> SERVER;
            case LAN_SERVER -> GENERIC;
        };
    }

    /**
     * The category implied by a scanned host's guessed {@link LanMachineRole}, or {@code null} when
     * the role carries no signal ({@link LanMachineRole#UNKNOWN} or a null role) — so callers can
     * fall through to the next signal rather than freezing on a guess.
     */
    public static DeviceCategory fromLanRole(LanMachineRole r) {
        if (r == null) return null;
        return switch (r) {
            case DOCKER_HOST, WEB_UI, SSH_HOST -> SERVER;
            case PRINTER -> PRINTER;
            case UNKNOWN -> null;
        };
    }

    /**
     * The category implied by keyword-matching the machine name (case-insensitive {@code contains}),
     * or {@code null} when no keyword matches. Priority follows the declared keyword order; the
     * first matching category wins.
     */
    public static DeviceCategory fromName(String name) {
        if (name == null || name.isBlank()) return null;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<DeviceCategory, List<String>> entry : NAME_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The auto-detected category from all available signals, in priority order:
     * name keyword &rarr; LAN role &rarr; machine type &rarr; {@link #GENERIC}. The first
     * non-null signal wins; never returns null.
     */
    public static DeviceCategory detect(String name, MachineType machineType, LanMachineRole lanRole) {
        DeviceCategory byName = fromName(name);
        if (byName != null) return byName;
        DeviceCategory byRole = fromLanRole(lanRole);
        if (byRole != null) return byRole;
        return fromMachineType(machineType);
    }

    /**
     * True for device kinds that don't run a general-purpose OS shell — a phone, printer, router,
     * gateway, IoT device, camera, or media box. An appliance never offers SSH by default and vetoes
     * the server-type SSH-access fallback (a LAN server that's really a printer stays SSH-off).
     * Presentation-derived only: this seeds the SSH-access default, it is not the authoritative flag.
     */
    public boolean isAppliance() {
        return switch (this) {
            case PHONE, PRINTER, ROUTER, GATEWAY, IOT, CAMERA, MEDIA -> true;
            default -> false;
        };
    }

    /**
     * True for the computer-like categories that host an SSH daemon in the common case — server, NAS,
     * desktop, laptop. Seeds the SSH-access default; {@link #GENERIC} is deliberately excluded so a
     * generic client stays SSH-off unless its machine type is a server type.
     */
    public boolean sshCapableByDefault() {
        return switch (this) {
            case SERVER, NAS, DESKTOP, LAPTOP -> true;
            default -> false;
        };
    }

    /**
     * True for the machine kinds that can host a backup server — a NAS, or a general-purpose
     * {@link #SERVER}. These are the "storage-class" categories: a machine that plausibly has the disk
     * capacity and always-on posture to hold the fleet's borg repositories. Seeds the
     * "designate a backup server" nudge; presentation-derived, like the SSH-access seeds above.
     */
    public boolean isStorageClass() {
        return switch (this) {
            case SERVER, NAS -> true;
            default -> false;
        };
    }

    /**
     * Parses a stored/override category name (trimmed, case-insensitive), or {@code null} when the
     * value is null or blank (meaning "no override"). Throws {@link IllegalArgumentException} for a
     * non-blank value that is not a valid category — callers surface that as a 400.
     */
    public static DeviceCategory fromString(String value) {
        if (value == null || value.isBlank()) return null;
        return DeviceCategory.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
