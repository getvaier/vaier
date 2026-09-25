package net.vaier.rest;

import net.vaier.application.OpenMailedConfirmationUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.TakeMailedConfirmationUseCase;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The <b>approval link</b> a <b>Mailed confirmation</b> carries, behind the same sign-in as every other
 * {@code /chat} route. Looking runs nothing — mail scanners follow links — so the page's own "Do it" is the
 * only thing that runs the action, through {@link ChatActions#run}, the card's own dispatch. Whether the link
 * opens, and for whom, is the domain's decision.
 */
@RestController
@RequestMapping("/chat/approvals")
public class ChatApprovalRestController {

    private static final String EMAIL_HEADER = "X-Auth-Request-Email";

    private final OpenMailedConfirmationUseCase openMailedConfirmationUseCase;
    private final TakeMailedConfirmationUseCase takeMailedConfirmationUseCase;
    private final RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    private final ChatActions chatActions;

    public ChatApprovalRestController(OpenMailedConfirmationUseCase openMailedConfirmationUseCase,
                                      TakeMailedConfirmationUseCase takeMailedConfirmationUseCase,
                                      RememberActionOutcomeUseCase rememberActionOutcomeUseCase,
                                      ChatActions chatActions) {
        this.openMailedConfirmationUseCase = openMailedConfirmationUseCase;
        this.takeMailedConfirmationUseCase = takeMailedConfirmationUseCase;
        this.rememberActionOutcomeUseCase = rememberActionOutcomeUseCase;
        this.chatActions = chatActions;
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> look(@RequestHeader(value = EMAIL_HEADER, required = false) String email,
                                       @PathVariable String token) {
        MailedConfirmation confirmation;
        try {
            confirmation = openMailedConfirmationUseCase.open(token, Operator.of(email));
        } catch (NotFoundException gone) {
            return page(HttpStatus.NOT_FOUND, gone.getMessage(), "");
        }
        String action = "/chat/approvals/" + HtmlUtils.htmlEscape(token);
        return page(HttpStatus.OK, confirmation.proposal().sentence(),
            "<div class=\"answers\">"
                + "<form method=\"post\" action=\"" + action + "\"><button class=\"yes\">Do it</button></form>"
                + "<form method=\"post\" action=\"" + action + "/decline\"><button>No</button></form>"
                + "</div>");
    }

    @PostMapping("/{token}")
    public ResponseEntity<String> approve(@RequestHeader(value = EMAIL_HEADER, required = false) String email,
                                          @PathVariable String token) {
        Operator operator = Operator.of(email);
        MailedConfirmation confirmation;
        try {
            confirmation = takeMailedConfirmationUseCase.take(token, operator);
        } catch (NotFoundException gone) {
            return page(HttpStatus.NOT_FOUND, gone.getMessage(), "");
        }
        ChatActions.Outcome outcome = chatActions.run(confirmation.proposal());
        rememberActionOutcomeUseCase.remember(operator,
            confirmation.proposal().outcomeSentence(outcome.done(), outcome.text()));
        return page(HttpStatus.OK, outcome.text(), "");
    }

    @PostMapping("/{token}/decline")
    public ResponseEntity<String> decline(@RequestHeader(value = EMAIL_HEADER, required = false) String email,
                                          @PathVariable String token) {
        Operator operator = Operator.of(email);
        MailedConfirmation confirmation;
        try {
            confirmation = takeMailedConfirmationUseCase.take(token, operator);
        } catch (NotFoundException gone) {
            return page(HttpStatus.NOT_FOUND, gone.getMessage(), "");
        }
        rememberActionOutcomeUseCase.remember(operator, confirmation.proposal().declinedSentence());
        return page(HttpStatus.OK, "Not done.", "");
    }

    /** A tiny self-contained page; the token rides the URL, so it is never cached or sent on as a referrer. */
    private static ResponseEntity<String> page(HttpStatus status, String sentence, String answers) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>Marvin asks</title><style>"
            + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:#15171b;color:#e9eaec;font:16px/1.5 system-ui,-apple-system,sans-serif}"
            + "main{box-sizing:border-box;width:100%;max-width:28rem;margin:16px;padding:24px;"
            + "background:#22262d;border-radius:14px}"
            + "h1{margin:0 0 8px;font-size:.85rem;font-weight:600;color:#8f959e;text-transform:uppercase;"
            + "letter-spacing:.06em}p{margin:0;font-size:1.15rem}"
            + ".answers{display:flex;gap:8px;margin-top:20px}form{margin:0}"
            + "button{font:inherit;padding:8px 18px;border-radius:8px;border:1px solid #3a4049;"
            + "background:transparent;color:#e9eaec;cursor:pointer}"
            + "button.yes{background:#4cc9e6;border-color:#4cc9e6;color:#15171b;font-weight:600}"
            + "</style></head><body><main><h1>Marvin asks</h1><p>" + HtmlUtils.htmlEscape(sentence) + "</p>"
            + answers + "</main></body></html>";
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_HTML)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .body(html);
    }
}
