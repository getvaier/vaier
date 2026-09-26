package net.vaier.adapter.driven;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.MailedConfirmations;
import net.vaier.domain.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every <b>Mailed confirmation</b> waiting for a yes is one YAML file beside everything else Vaier keeps. */
class MailedConfirmationFileAdapterTest {

    private static final long NOW = 1_700_000_000_000L;

    @TempDir
    Path configDir;

    private MailedConfirmationFileAdapter adapter() {
        return new MailedConfirmationFileAdapter(configDir.toString());
    }

    /**
     * Round trip, and the token itself is never written: only its digest is. A service call's body comes
     * back whole, even one YAML would read as something other than text.
     */
    @Test
    void aConfirmationComesBackExactlyAsSaved_andTheTokenIsNeverWritten() throws Exception {
        assertThat(adapter().load().held()).isEmpty();

        MailedConfirmation.Minted minted = MailedConfirmation.mint(ActionProposal.propose(ChatAction.RUN_BACKUP,
            Map.of("machine", "Colina 27", "machineId", "c0355605-e5a0-419a-8943-fdc5ec209958"), NOW),
            Operator.of("geir@example.com"), NOW);
        MailedConfirmation.Minted withBody = MailedConfirmation.mint(ActionProposal.propose(ChatAction.CALL_SERVICE,
            Map.of("service", "opensprinkler on Colina 27", "host", "opensprinkler.colina27.example.com",
                "method", "PUT", "path", "/cp?pw=x", "body", "{\n  \"en\": true,\n  \"t\": [1, 2]\n}"), NOW),
            Operator.of("geir@example.com"), NOW);
        MailedConfirmation.Minted yamlish = MailedConfirmation.mint(ActionProposal.propose(ChatAction.CALL_SERVICE,
            Map.of("service", "openhab on Colina 27", "host", "openhab.colina27.example.com",
                "method", "POST", "path", "/rest/items/Dimmer", "body", "true"), NOW),
            Operator.of("geir@example.com"), NOW);
        MailedConfirmations saved = MailedConfirmations.empty().with(minted.confirmation(), NOW)
            .with(withBody.confirmation(), NOW).with(yamlish.confirmation(), NOW);
        adapter().save(saved);

        assertThat(adapter().load()).isEqualTo(saved);
        assertThat(Files.readString(configDir.resolve("mailed-confirmations.yml"))).doesNotContain(minted.token());
    }

    /** A file that cannot be read is nothing waiting, never a Vaier that will not start. */
    @Test
    void aDamagedFileReadsAsNothingWaiting() throws Exception {
        Files.writeString(configDir.resolve("mailed-confirmations.yml"), "confirmations: [ {x: 1} ]\n: : :\n");

        assertThat(adapter().load().held()).isEmpty();
    }
}
