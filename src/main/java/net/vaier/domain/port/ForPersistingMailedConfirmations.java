package net.vaier.domain.port;

import net.vaier.domain.MailedConfirmations;

/** Driven port keeping every <b>Mailed confirmation</b> in one file, so a link survives a redeploy. */
public interface ForPersistingMailedConfirmations {

    MailedConfirmations load();

    void save(MailedConfirmations confirmations);
}
