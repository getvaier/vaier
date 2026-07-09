package net.vaier.application;

import net.vaier.domain.port.ForOpeningSshSessions.SshSession;

/**
 * Send a machine's stored SSH password straight from Vaier into an open terminal session, so the
 * password travels from the vault to the SSH server without ever reaching the browser. It is written
 * only when the remote is actually awaiting a password (see {@link net.vaier.domain.PasswordPrompt}) —
 * otherwise the keystrokes would echo into the terminal and could be recorded or executed.
 */
public interface SendHostPasswordUseCase {

    /**
     * Write the stored password for {@code machineName} into {@code session}, but only when
     * {@code recentOutput} shows the remote is at a password prompt. The secret is never returned,
     * logged, or exposed in any way.
     *
     * @param session      the live SSH session — a domain port type, so the secret stays in-process
     * @param recentOutput the tail of recent PTY output, used to confirm a live password prompt
     */
    SendPasswordResult sendPassword(String machineName, SshSession session, String recentOutput);

    /**
     * The outcome of a send attempt. {@code SENT} wrote the password; {@code NOT_AT_PROMPT} declined
     * because the remote was not asking for one; {@code NO_PASSWORD_CREDENTIAL} means the machine has no
     * stored password (absent, or key-based auth); {@code FAILED} means the write itself errored.
     */
    enum SendPasswordResult { SENT, NOT_AT_PROMPT, NO_PASSWORD_CREDENTIAL, FAILED }
}
