package net.vaier.rest;

import net.vaier.application.OpenMailedConfirmationUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.TakeMailedConfirmationUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.MailedConfirmations;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The <b>approval link</b>: the page a <b>Mailed confirmation</b>'s link opens. Looking runs nothing, because
 * mail scanners follow links; only the page's own "Do it" runs the action, through the card's dispatch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatApprovalRestControllerTest {

    @Mock OpenMailedConfirmationUseCase openMailedConfirmationUseCase;
    @Mock TakeMailedConfirmationUseCase takeMailedConfirmationUseCase;
    @Mock RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    @Mock ChatActions chatActions;

    @InjectMocks ChatApprovalRestController controller;

    private static final String EMAIL = "geir@example.com";
    private static final Operator GEIR = Operator.of(EMAIL);
    private static final String TOKEN = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9v";

    private static MailedConfirmation lift(String address) {
        return MailedConfirmation.mint(ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", address), 0),
            GEIR, 0).confirmation();
    }

    /** The sentence, escaped, and two forms that post back; nothing is taken or run by looking. */
    @Test
    void looking_showsTheSentenceAndBothAnswers_andRunsNothing() {
        when(openMailedConfirmationUseCase.open(TOKEN, GEIR)).thenReturn(lift("<b>203.0.113.9</b>"));

        ResponseEntity<String> page = controller.look(EMAIL, TOKEN);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(page.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(page.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(page.getBody())
            .contains("Lift the block on &lt;b&gt;203.0.113.9&lt;/b&gt;.")
            .doesNotContain("<b>203.0.113.9</b>")
            .contains("<form method=\"post\" action=\"/chat/approvals/" + TOKEN + "\">")
            .contains("<form method=\"post\" action=\"/chat/approvals/" + TOKEN + "/decline\">");
        verifyNoInteractions(takeMailedConfirmationUseCase, chatActions, rememberActionOutcomeUseCase);
    }

    /** Used, expired or somebody else's: one plain page, the same for all three, and nothing runs. */
    @Test
    void aLinkThatDoesNotOpen_isOnePlainPage_andRunsNothing() {
        when(openMailedConfirmationUseCase.open(TOKEN, GEIR)).thenThrow(new NotFoundException(MailedConfirmations.GONE));
        when(takeMailedConfirmationUseCase.take(TOKEN, GEIR)).thenThrow(new NotFoundException(MailedConfirmations.GONE));

        Map<String, Supplier<ResponseEntity<String>>> routes = Map.of(
            "look", () -> controller.look(EMAIL, TOKEN),
            "yes", () -> controller.approve(EMAIL, TOKEN),
            "no", () -> controller.decline(EMAIL, TOKEN));
        routes.forEach((route, call) -> {
            ResponseEntity<String> page = call.get();
            assertThat(page.getStatusCode().value()).as(route).isEqualTo(404);
            assertThat(page.getBody()).as(route).contains(MailedConfirmations.GONE).doesNotContain("<form");
        });
        verifyNoInteractions(chatActions, rememberActionOutcomeUseCase);
    }

    /** Yes runs it through the card's own dispatch, once, and the thread remembers it; no runs nothing. */
    @Test
    void yesRunsItThroughTheCardsDispatch_andNoRunsNothing_andBothAreRemembered() {
        MailedConfirmation confirmation = lift("203.0.113.9");
        when(takeMailedConfirmationUseCase.take(TOKEN, GEIR)).thenReturn(confirmation);
        when(chatActions.run(confirmation.proposal()))
            .thenReturn(new ChatActions.Outcome(true, "Lifted the block on 203.0.113.9."));

        assertThat(controller.approve(EMAIL, TOKEN).getBody()).contains("Lifted the block on 203.0.113.9.");
        verify(rememberActionOutcomeUseCase).remember(GEIR,
            "Proposed: Lift the block on 203.0.113.9. (done: Lifted the block on 203.0.113.9.)");

        reset(chatActions, rememberActionOutcomeUseCase);
        assertThat(controller.decline(EMAIL, TOKEN).getBody()).contains("Not done.");
        verifyNoInteractions(chatActions);
        verify(rememberActionOutcomeUseCase).remember(GEIR,
            "Proposed: Lift the block on 203.0.113.9. (the operator declined)");
    }
}
