package net.vaier.domain;

import lombok.Builder;
import lombok.Data;

import java.util.Optional;

@Data
@Builder(toBuilder = true)
public class VaierConfig {

    private String domain;
    private String acmeEmail;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpSender;
    private String smtpPassword;
    private Integer diskMonitorThresholdPercent;
    /**
     * The hour of day (0–23, in the Vaier server's clock zone) at which Vaier-owned nightly fleet-backup
     * scheduling fires due jobs, or null when unset (the effective value then falls back to the default).
     */
    private Integer backupScheduleHour;
    /**
     * Whether Vaier offers SSH for the Vaier-server host itself (#311) — the explicit operator
     * override, or null when unset (the effective value then falls back to the server default: on).
     * The Vaier server is neither a peer nor a LAN server, so its SSH-access override lives here.
     */
    private Boolean vaierServerSshAccess;

    /**
     * The {@link MachineId} of the Vaier-server machine (#311), as its canonical string. The Vaier
     * server is neither a peer nor a LAN server, so — like its SSH-access override — its identity has
     * nowhere else to live. Null on a Vaier that has not yet been assigned one; it is minted once, on
     * first use, and persisted here.
     */
    private String vaierServerMachineId;

    /**
     * The passphrase that opens this fleet's {@link SurvivalKit}, or null until the operator has chosen one.
     *
     * <p>Vaier holding it looks wrong for a second — it is the one secret the design says the operator keeps
     * in their head. It costs nothing: anyone who can read this field already has {@code vault.key}, and with
     * it every repository passphrase directly, so the kit tells them nothing new. What holding it buys is the
     * thing the whole feature exists for — Vaier can <em>rewrite</em> the kit when a passphrase changes,
     * where a Vaier that could not would be left nagging about a kit going stale in the operator's absence.
     * The operator still keeps it in their head, for the day there is no vault left to read it from.
     */
    private String survivalKitPassphrase;

    /**
     * A fingerprint of what the kits on the fleet say, recorded when they were last written, or null when
     * they never have been.
     *
     * <p>Not a secret and not encrypted — it is a digest of a page whose every line Vaier already stores
     * here, and it has to be readable to be compared. What it buys is the thing a dirty flag cannot: it
     * survives a restart, it cannot be missed by a write path that forgot to set it, and it is meaningful
     * about kits written before anything was watching.
     */
    private String survivalKitFingerprint;

    /**
     * The operator's own <b>Anthropic API key</b> (#360), or null when none is stored. Encrypted at rest
     * beside the SMTP password, and never read back to the browser: {@link #hasAnthropicApiKey()} is the
     * only question anything outside this file is allowed to ask about it.
     */
    private String anthropicApiKey;

    /**
     * The Vaier server's own identity, or empty when it has not been assigned one yet or the stored value
     * is unusable.
     *
     * <p>Empty, never a substitute, and never minted here. A hand-edited id that no longer parses must not
     * quietly become a <em>different</em> machine — everything keyed to the old one would be orphaned in
     * silence — and deciding to assign a fresh id is a write, which is not a read's business. The one
     * caller that may mint (`MachineService`, on first use) does so explicitly and persists it.
     */
    public Optional<MachineId> vaierServerIdentity() {
        if (vaierServerMachineId == null || vaierServerMachineId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MachineId.of(vaierServerMachineId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * A copy with the survival kit passphrase replaced; every other field carries over unchanged.
     *
     * <p>Blank is refused rather than stored. A kit encrypted with nothing is indistinguishable on its face
     * from a protected one — same header, same marker, same instructions — so the operator would go on
     * believing the copies on their fleet were safe to leave lying there.
     */
    public VaierConfig withSurvivalKitPassphrase(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException("The survival kit passphrase must not be blank");
        }
        return toBuilder()
            .survivalKitPassphrase(passphrase)
            // Forget what was written. Every copy on the fleet still opens with the OLD passphrase, and it
            // says the same words as a kit written under the new one — so the contents cannot reveal the
            // staleness and nothing else would. Cleared here, at the one place that knows, it reads
            // downstream as "never written" and the fleet is rewritten on the next sweep.
            .survivalKitFingerprint(null)
            .build();
    }

    /** A copy recording the fingerprint of the kits just written; every other field carries over. */
    public VaierConfig withSurvivalKitFingerprint(String fingerprint) {
        return toBuilder()
            .survivalKitFingerprint(fingerprint)
            .build();
    }

    /**
     * A copy with the <b>Anthropic API key</b> replaced; every other field carries over unchanged. A blank
     * key is stored as none — clearing the field is how the operator turns <b>Chat</b> off again.
     */
    public VaierConfig withAnthropicApiKey(String apiKey) {
        return toBuilder()
            .anthropicApiKey(apiKey == null || apiKey.isBlank() ? null : apiKey)
            .build();
    }

    /** Whether an <b>Anthropic API key</b> is stored — the one thing that makes <b>Chat</b> available. */
    public boolean hasAnthropicApiKey() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    /** Whether a kit passphrase has been chosen — asked before a rollout, and answered for the browser. */
    public boolean hasSurvivalKitPassphrase() {
        return survivalKitPassphrase != null && !survivalKitPassphrase.isBlank();
    }

    /** The default host-disk alert threshold when none is configured: notify above 85% used. */
    public static final int DEFAULT_DISK_MONITOR_THRESHOLD_PERCENT = 85;

    /** The default nightly fleet-backup schedule hour when none is configured: 2am. */
    public static final int DEFAULT_BACKUP_SCHEDULE_HOUR = 2;

    /**
     * A copy with the SMTP settings replaced; every other field carries over unchanged. The password
     * is persisted here, in this owner-only-readable config file — it is Vaier's own store for the
     * notifier credentials.
     */
    public VaierConfig withSmtpSettings(String newSmtpHost, int newSmtpPort,
                                        String newSmtpUsername, String newSmtpSender,
                                        String newSmtpPassword) {
        return toBuilder()
            .smtpHost(newSmtpHost)
            .smtpPort(newSmtpPort)
            .smtpUsername(newSmtpUsername)
            .smtpSender(newSmtpSender)
            .smtpPassword(newSmtpPassword)
            .build();
    }

    /**
     * A copy with the Vaier-server SSH-access override replaced (#311); every other field carries over
     * unchanged. A null value clears the override, reverting the effective state to the default (on).
     */
    public VaierConfig withVaierServerSshAccess(Boolean sshAccess) {
        return toBuilder()
            .vaierServerSshAccess(sshAccess)
            .build();
    }

    /** A copy with the host-disk alert threshold replaced; every other field carries over unchanged. */
    public VaierConfig withDiskMonitorThreshold(int thresholdPercent) {
        validateThreshold(thresholdPercent);
        return toBuilder()
            .diskMonitorThresholdPercent(thresholdPercent)
            .build();
    }

    /** The effective alert threshold: the configured value, or {@link #DEFAULT_DISK_MONITOR_THRESHOLD_PERCENT}. */
    public int effectiveDiskMonitorThresholdPercent() {
        return diskMonitorThresholdPercent != null
            ? diskMonitorThresholdPercent
            : DEFAULT_DISK_MONITOR_THRESHOLD_PERCENT;
    }

    private static void validateThreshold(int thresholdPercent) {
        if (thresholdPercent < 1 || thresholdPercent > 99) {
            throw new IllegalArgumentException("diskMonitorThresholdPercent must be between 1 and 99");
        }
    }

    /** A copy with the nightly backup schedule hour replaced; every other field carries over unchanged. */
    public VaierConfig withBackupScheduleHour(int hour) {
        validateBackupScheduleHour(hour);
        return toBuilder()
            .backupScheduleHour(hour)
            .build();
    }

    /** The effective nightly backup schedule hour: the configured value, or {@link #DEFAULT_BACKUP_SCHEDULE_HOUR}. */
    public int effectiveBackupScheduleHour() {
        return backupScheduleHour != null ? backupScheduleHour : DEFAULT_BACKUP_SCHEDULE_HOUR;
    }

    private static void validateBackupScheduleHour(int hour) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("backupScheduleHour must be between 0 and 23");
        }
    }

    /** Whether SMTP is configured enough to send mail — both a host and a username are set. */
    public boolean isSmtpConfigured() {
        return smtpHost != null && !smtpHost.isBlank()
            && smtpUsername != null && !smtpUsername.isBlank();
    }

    /**
     * The effective SMTP password: the freshly provided one when non-blank, otherwise the
     * previously stored one. Throws when neither is available.
     */
    public static String resolveSmtpPassword(String provided, Optional<String> stored) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return stored
            .filter(p -> !p.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("SMTP password is required"));
    }

}
