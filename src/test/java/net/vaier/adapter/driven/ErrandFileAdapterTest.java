package net.vaier.adapter.driven;

import net.vaier.domain.Errands;
import net.vaier.domain.Operator;
import net.vaier.domain.Rhythm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Every <b>errand</b> Vaier keeps is one YAML file beside everything else it keeps. */
class ErrandFileAdapterTest {

    private static final ZonedDateTime NOW =
        ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, ZoneId.of("Europe/Oslo"));
    private static final Operator GEIR = Operator.of("geir@example.com");

    @TempDir
    Path configDir;

    private ErrandFileAdapter adapter() {
        return new ErrandFileAdapter(configDir.toString());
    }

    @Test
    void nothingSentYetIsNoErrands() {
        assertThat(adapter().load().errands()).isEmpty();
    }

    /** Every shape of rhythm survives the round trip, including one that has run and one that never has. */
    @Test
    void anErrandComesBackExactlyAsSaved() {
        Errands saved = Errands.empty()
            .add(GEIR, "Tell me if any machine has updates:\n\"every\" one.", Rhythm.parse("daily 08:00"), NOW)
            .add(GEIR, "Check the NAS.", Rhythm.parse("once 2026-09-12T08:00"), NOW)
            .add(Operator.of("someone@else.com"), "Not mine.", Rhythm.parse("weekly monday 07:30"), NOW)
            .add(GEIR, "The first of the month.", Rhythm.parse("monthly 1 09:00"), NOW);
        String ranId = saved.errands().get(0).id();
        saved = saved.afterRun(ranId, NOW, "reported");
        adapter().save(saved);

        assertThat(adapter().load()).isEqualTo(saved);
        assertThat(Files.exists(configDir.resolve("errands.yml"))).isTrue();
    }

    /** The rhythm is kept as the string it was written as, so a person can read the file. */
    @Test
    void theFileKeepsTheRhythmAsItsOwnWords() throws Exception {
        adapter().save(Errands.empty().add(GEIR, "Check the NAS.", Rhythm.parse("weekly monday 07:30"), NOW));

        assertThat(Files.readString(configDir.resolve("errands.yml")))
            .contains("weekly monday 07:30")
            .contains("geir@example.com");
    }

    @Test
    void aDamagedFileReadsAsNothingSent() throws Exception {
        Files.writeString(configDir.resolve("errands.yml"), "errands: [ {id: 1} ]\n: : :\n");

        assertThat(adapter().load().errands()).isEmpty();
    }

    /** A file from a Vaier that kept a rhythm this one cannot read is not a reason to lose the rest. */
    @Test
    void anErrandWithARhythmItCannotReadIsLeftOut() throws Exception {
        adapter().save(Errands.empty().add(GEIR, "Check the NAS.", Rhythm.parse("daily 08:00"), NOW));
        String file = Files.readString(configDir.resolve("errands.yml"));
        Files.writeString(configDir.resolve("errands.yml"),
            file + "- id: zz99zz\n  operator: geir@example.com\n  instruction: broken\n"
                + "  rhythm: every blue moon\n  nextDue: 1\n  createdAt: 1\n");

        assertThat(adapter().load().errands()).hasSize(1);
        assertThat(adapter().load().errands().get(0).instruction()).isEqualTo("Check the NAS.");
    }
}
