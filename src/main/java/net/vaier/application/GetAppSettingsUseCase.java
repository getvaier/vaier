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
        boolean socialAuthAvailable
    ) {}
}
