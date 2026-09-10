package net.vaier.application;

public interface GetAppSettingsUseCase {
    AppSettingsResult getSettings();

    record AppSettingsResult(
        String domain,
        String acmeEmail,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpSender,
        /**
         * What Vaier found when it checked the one {@code *.<domain>} record at boot, as the status
         * name — or null when the check has not run yet (#331).
         */
        String wildcardDnsStatus,
        /**
         * That verdict in the operator's words, e.g. {@code Not resolving} — null before the check has
         * run. Worded by the domain so the browser never has to know what a status name means.
         */
        String wildcardDnsLabel,
        /**
         * How much attention the verdict deserves ({@code OK}/{@code WARNING}/{@code ERROR}) — null
         * before the check has run. The domain decides; the browser only picks a style from it.
         */
        String wildcardDnsSeverity,
        /** The same verdict as a sentence the operator can act on, or null before the check has run. */
        String wildcardDnsMessage,
        int diskMonitorThresholdPercent,
        /** Whether the per-service {@code social} auth mode is offered (Google OAuth configured, #305). */
        boolean socialAuthAvailable,
        /** The hour of day (0–23) at which Vaier-owned nightly fleet-backup scheduling fires due jobs. */
        int backupScheduleHour,
        /**
         * The zone that hour is read in — the scheduler's own clock zone (e.g. {@code Europe/Oslo}), so the
         * UI can name it instead of saying "server local time" and leaving the operator to guess.
         */
        String backupScheduleZone,
        /**
         * Whether a survival kit passphrase has been chosen. Whether, never what — the value opens every
         * backup in the fleet and the browser has no use for it.
         */
        boolean hasSurvivalKitPassphrase,
        /**
         * Whether an <b>Anthropic API key</b> is stored, and therefore whether <b>Chat</b> is offered at all.
         * Whether, never what — the key opens the operator's own Claude account and the browser has no use
         * for it (#360).
         */
        boolean hasAnthropicApiKey
    ) {}
}
