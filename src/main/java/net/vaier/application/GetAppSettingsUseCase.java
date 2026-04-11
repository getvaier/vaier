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
        String smtpSender
    ) {}
}
