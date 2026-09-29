package net.vaier.adapter.driven;

import net.vaier.domain.ActionWording;
import net.vaier.domain.ConfirmationRecord;
import net.vaier.domain.ConfirmationRecord.Outcome;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A conversation is kept the way everything else is kept: one YAML file per operator, no database. */
class ConversationFileAdapterTest {

    @TempDir
    Path configDir;

    private static final Operator GEIR = Operator.of("geir@example.com");

    private ConversationFileAdapter adapter() {
        return new ConversationFileAdapter(configDir.toString());
    }

    @Test
    void nothingKeptYetIsEmpty_notAnError() {
        assertThat(adapter().load(GEIR)).isEmpty();
    }

    private void keep(Conversation conversation) {
        adapter().update(conversation.operator(), ignored -> conversation);
    }

    /** Every turn comes back as it went in: when it was said, and a card record in its own shape. */
    @Test
    void aConversationComesBackExactlyAsItWasSaved() {
        Instant at = Instant.parse("2026-09-29T08:00:00Z");
        Conversation saved = new Conversation(GEIR, "Colina 27 was red: \"tunnel\" down.\nSecond line.", List.of(
            ConversationTurn.said(Role.OPERATOR, "is the nas up?", at),
            new ConversationTurn(Role.VAIER, "Yes — it answered a minute ago.\n- one\n- two"),
            ConversationTurn.recording(new ConfirmationRecord(new ActionWording("Read the batteries.", "GET /rest/items"),
                Outcome.DONE, new ActionWording("Done — openHAB answered.", null), "openHAB answered 200.\n\n[]"), at),
            ConversationTurn.recording(ConfirmationRecord.declined(new ActionWording("Back up Colina 27 now.", null)), at)));
        keep(saved);

        assertThat(adapter().load(GEIR)).contains(saved);
        assertThat(Files.exists(configDir.resolve("conversations").resolve("geir_example_com.yml"))).isTrue();
    }

    @Test
    void eachOperatorHasTheirOwnFile() {
        keep(Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "mine")));
        keep(Conversation.empty(Operator.of("other@example.com"))
            .with(new ConversationTurn(Role.OPERATOR, "theirs")));

        assertThat(adapter().load(GEIR).orElseThrow().turns()).extracting(ConversationTurn::text).containsExactly("mine");
        assertThat(adapter().load(Operator.of("other@example.com")).orElseThrow().turns())
            .extracting(ConversationTurn::text).containsExactly("theirs");
    }

    @Test
    void forgettingRemovesTheFile_andForgettingNothingIsFine() {
        keep(Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "mine")));

        adapter().forget(GEIR);
        adapter().forget(GEIR);

        assertThat(adapter().load(GEIR)).isEmpty();
    }

    /** A file somebody damaged by hand reads as nothing kept, and is said so in the log — never a crash. */
    @Test
    void aDamagedFileReadsAsNothingKept() throws Exception {
        Path dir = Files.createDirectories(configDir.resolve("conversations"));
        Files.writeString(dir.resolve("geir_example_com.yml"), "turns: [ {role: NOBODY, text: ''} ]\n: : :\n");

        assertThat(adapter().load(GEIR)).isEmpty();
    }

    /** A file kept before turns said when, with a card record as plain text, loads as it was written. */
    @Test
    void aFileKeptBeforeTurnsHadTimesOrShapesStillLoads() throws Exception {
        Path dir = Files.createDirectories(configDir.resolve("conversations"));
        String record = "Card from an action tool: Back up Colina 27 now. (done: Backing up.)";
        Files.writeString(dir.resolve("geir_example_com.yml"), "operator: geir@example.com\nturns:\n"
            + "- role: OPERATOR\n  text: back up colina\n- role: VAIER\n  text: '" + record + "'\n");

        assertThat(adapter().load(GEIR)).contains(Conversation.empty(GEIR)
            .with(new ConversationTurn(Role.OPERATOR, "back up colina"))
            .with(new ConversationTurn(Role.VAIER, record)));
    }

    /**
     * Each change is read, made and written as one step per operator. A second writer arriving while the first
     * is between its read and its write waits, rather than writing over a turn it never saw.
     */
    @Test
    void aWriteThatArrivesMidChangeIsKept() throws Exception {
        ConversationFileAdapter adapter = adapter();
        CountDownLatch firstHasRead = new CountDownLatch(1);
        Thread first = new Thread(() -> adapter.update(GEIR, c -> {
            firstHasRead.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return c.with(new ConversationTurn(Role.VAIER, "the answer"));
        }));
        first.start();
        assertThat(firstHasRead.await(5, TimeUnit.SECONDS)).isTrue();

        adapter.update(GEIR, c -> c.with(new ConversationTurn(Role.VAIER, "Errand: all well.")));
        first.join();

        assertThat(adapter.load(GEIR).orElseThrow().turns()).extracting(ConversationTurn::text)
            .containsExactlyInAnyOrder("the answer", "Errand: all well.");
    }
}
