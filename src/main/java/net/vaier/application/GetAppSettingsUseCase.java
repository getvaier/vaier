package net.vaier.application;

public interface GetAppSettingsUseCase {
    AppSettingsResult getSettings();

    record AppSettingsResult(
        String domain,
        String awsKeyHint,
        String acmeEmail,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpSender,
        String dnsProvider,
        int diskMonitorThresholdPercent,
        /** Whether the per-service {@code social} auth mode is offered (Google OAuth configured, #305). */
        boolean socialAuthAvailable,
        /** The hour of day (0–23) at which Vaier-owned nightly fleet-backup scheduling fires due jobs. */
        int backupScheduleHour,
        /**
         * The zone that hour is read in — the scheduler's own clock zone (e.g. {@code Europe/Oslo}), so the
         * UI can name it instead of saying "server local time" and leaving the operator to guess.
         */
        String backupScheduleZone
    ) {}
}
