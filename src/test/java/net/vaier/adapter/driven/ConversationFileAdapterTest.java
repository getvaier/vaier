package net.vaier.adapter.driven;

import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void aConversationComesBackExactlyAsItWasSaved() {
        Conversation saved = new Conversation(GEIR, "Colina 27 was red: \"tunnel\" down.\nSecond line.", List.of(
            new ConversationTurn(Role.OPERATOR, "is the nas up?"),
            new ConversationTurn(Role.VAIER, "Yes — it answered a minute ago.\n- one\n- two")));
        adapter().save(saved);

        assertThat(adapter().load(GEIR)).contains(saved);
        assertThat(Files.exists(configDir.resolve("conversations").resolve("geir_example_com.yml"))).isTrue();
    }

    @Test
    void eachOperatorHasTheirOwnFile() {
        adapter().save(Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "mine")));
        adapter().save(Conversation.empty(Operator.of("other@example.com"))
            .with(new ConversationTurn(Role.OPERATOR, "theirs")));

        assertThat(adapter().load(GEIR).orElseThrow().turns()).extracting(ConversationTurn::text).containsExactly("mine");
        assertThat(adapter().load(Operator.of("other@example.com")).orElseThrow().turns())
            .extracting(ConversationTurn::text).containsExactly("theirs");
    }

    @Test
    void forgettingRemovesTheFile_andForgettingNothingIsFine() {
        adapter().save(Conversation.empty(GEIR).with(new ConversationTurn(Role.OPERATOR, "mine")));

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
}
