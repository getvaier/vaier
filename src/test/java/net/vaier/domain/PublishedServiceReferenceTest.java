package net.vaier.domain;

import net.vaier.domain.PublishedServiceReference.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which published service the model meant. Two houses both run an openHAB, so a bare name can be shared;
 * the address never is, and a shared name is refused with the addresses to choose from.
 */
class PublishedServiceReferenceTest {

    private static final Candidate OPENHAB_COLINA =
        new Candidate("openhab", "Colina 27", "openhab.colina27.example.com", null);
    private static final Candidate OPENHAB_APALVEIEN =
        new Candidate("openhab", "Apalveien 5", "openhab.apalveien5.example.com", null);
    private static final Candidate PAPERLESS =
        new Candidate("paperless", "Apalveien 5", "paperless.example.com", null);
    private static final Candidate GRAFANA_PATH =
        new Candidate("grafana", "Vaier server", "tools.example.com", "/grafana");
    private static final List<Candidate> PUBLISHED =
        List.of(OPENHAB_COLINA, OPENHAB_APALVEIEN, PAPERLESS, GRAFANA_PATH);

    @Test
    void resolvesByAddress_byNameAndMachine_orByANameOnlyOneServiceHas() {
        record Row(String said, Candidate meant) {}
        for (Row row : new Row[] {
            new Row("openhab.colina27.example.com", OPENHAB_COLINA),
            new Row("https://OpenHAB.Apalveien5.example.com/", OPENHAB_APALVEIEN),
            new Row("openHAB Colina 27", OPENHAB_COLINA),
            new Row("openhab @ Apalveien 5", OPENHAB_APALVEIEN),
            new Row("openhab on Colina 27", OPENHAB_COLINA),
            new Row("Paperless", PAPERLESS),
            new Row("tools.example.com/grafana", GRAFANA_PATH),
        }) {
            assertThat(new PublishedServiceReference(row.said()).resolve(PUBLISHED)).as(row.said())
                .isEqualTo(row.meant());
        }
        assertThat(OPENHAB_COLINA.label()).isEqualTo("openhab on Colina 27");
    }

    @Test
    void aSharedNameIsRefusedWithTheAddresses_andANameNothingHasIsRefused() {
        assertThatThrownBy(() -> new PublishedServiceReference("openhab").resolve(PUBLISHED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("2 published services are called \"openhab\"; say which by address: "
                + "openhab.colina27.example.com, openhab.apalveien5.example.com");
        for (String said : new String[] { "portainer", "evil.example.com", " ", null }) {
            assertThatThrownBy(() -> new PublishedServiceReference(said).resolve(PUBLISHED)).as(String.valueOf(said))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
