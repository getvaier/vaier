package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AddErrandUseCase;
import net.vaier.application.CancelErrandUseCase;
import net.vaier.application.ChatUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.EmailBundleUseCase;
import net.vaier.application.ForgetConversationUseCase;
import net.vaier.application.ForgetUseCase;
import net.vaier.application.GetConversationUseCase;
import net.vaier.application.GetErrandsUseCase;
import net.vaier.application.GetMemoryUseCase;
import net.vaier.application.GetSpendUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.IsChatAvailableUseCase;
import net.vaier.application.OfferBundleUseCase;
import net.vaier.application.OpenBundleUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ChatCapability;
import net.vaier.domain.ChatTool;
import net.vaier.domain.ChatAvailability;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.Bundle;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.Errand;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineReference;
import net.vaier.domain.MailNotSentException;
import net.vaier.domain.Memory;
import net.vaier.domain.MonthSpend;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * <b>Chat</b> (#360 slice 1): the operator's questions, answered from the fleet's own facts and streamed back
 * as they are written.
 *
 * <p>The constructor is the point. Every read Chat may make is a {@code *UseCase} named in it, so what the
 * model can be told about this fleet is a list anyone can review — and it is a list of <em>reads</em>. There
 * is no verb here that runs on the model's say-so. The one use case that reaches a machine runs a
 * <b>Read-only command</b>, and what counts as one is the domain's decision ({@code ReadOnlyCommand}), taken
 * before anything is connected.
 *
 * <p>Slice 2 adds the verbs, each behind a click. An <b>Chat action</b> the model calls only puts a
 * <b>Confirmation</b> in front of the operator — a {@code confirm} event on the answer stream — and the
 * click comes back to {@link #confirm}, which takes the card once and runs the same use case the
 * Explorer's own button calls.
 *
 * <p>Slice 3 makes the conversation Vaier's to remember. Who is asking is the signed-in email oauth2-proxy
 * forwards, as {@link Operator}; the pane reads the kept conversation, sends the question alone, and
 * starts over with one DELETE.
 *
 * <p>The <b>web read</b> adds the two tools that look outwards rather than at the fleet. They are reads like
 * the rest, and they reach only the public internet — which is {@code WebAddress}'s decision, never this
 * controller's. Both answer a refusal in the domain's own words, and an unexpected failure in Vaier's.
 *
 * <p>The <b>errand</b> moved every read Marvin may make alone into {@link ChatReads}, because an errand
 * running with nobody watching needs the same ones: the projections, and the proof that none of them carries a
 * secret, live there now. What is left here is what needs somebody present — a card to click, a bundle to
 * mail, and the two errand verbs — beside the endpoints the pane calls.
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatRestController {

    /** What the pane is told when the model's turn ended before a word was said. */
    static final String NOTHING_SAID = "Marvin finished without a word. That usually means his turn was cut off "
        + "before he could speak; ask again, or ask for less at once.";

    /** Long enough for a considered answer over a slow link; the pane says nothing while it waits. */
    private static final long ANSWER_TIMEOUT_MS = 300_000L;

    private final ChatUseCase chatUseCase;
    private final IsChatAvailableUseCase isChatAvailableUseCase;
    private final GetMachinesUseCase getMachinesUseCase;
    private final ProposeActionUseCase proposeActionUseCase;
    private final TakeActionProposalUseCase takeActionProposalUseCase;
    private final GetConversationUseCase getConversationUseCase;
    private final ForgetConversationUseCase forgetConversationUseCase;
    private final RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    private final OfferBundleUseCase offerBundleUseCase;
    private final OpenBundleUseCase openBundleUseCase;
    private final ForgetUseCase forgetUseCase;
    private final GetMemoryUseCase getMemoryUseCase;
    private final GetSpendUseCase getSpendUseCase;
    private final EmailBundleUseCase emailBundleUseCase;
    private final AddErrandUseCase addErrandUseCase;
    private final CancelErrandUseCase cancelErrandUseCase;
    private final GetErrandsUseCase getErrandsUseCase;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final ChatReads chatReads;
    private final ChatActions chatActions;
    private final ObjectMapper objectMapper;

    /**
     * One thread, because a question is answered start to finish on it and Vaier answers one at a time.
     * The request thread must not be the one that waits: the answer takes tens of seconds.
     */
    private final ExecutorService answers = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "vaier-ask"));

    /**
     * The pulse that keeps a quiet stream open: a tool call or a long think can leave it silent for a
     * minute, and something on the way to the browser closes a quiet connection. Package-private so a test
     * can make it quick.
     */
    long heartbeatMs = 15_000L;
    private final ScheduledExecutorService pulse = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vaier-ask-pulse");
        thread.setDaemon(true);
        return thread;
    });

    public ChatRestController(ChatUseCase chatUseCase,
                             IsChatAvailableUseCase isChatAvailableUseCase,
                             GetMachinesUseCase getMachinesUseCase,
                             ProposeActionUseCase proposeActionUseCase,
                             TakeActionProposalUseCase takeActionProposalUseCase,
                             GetConversationUseCase getConversationUseCase,
                             ForgetConversationUseCase forgetConversationUseCase,
                             RememberActionOutcomeUseCase rememberActionOutcomeUseCase,
                             OfferBundleUseCase offerBundleUseCase,
                             OpenBundleUseCase openBundleUseCase,
                             ForgetUseCase forgetUseCase,
                             GetMemoryUseCase getMemoryUseCase,
                             GetSpendUseCase getSpendUseCase,
                             EmailBundleUseCase emailBundleUseCase,
                             AddErrandUseCase addErrandUseCase,
                             CancelErrandUseCase cancelErrandUseCase,
                             GetErrandsUseCase getErrandsUseCase,
                             ForSubscribingToEvents forSubscribingToEvents,
                             ChatReads chatReads,
                             ChatActions chatActions,
                             ObjectMapper objectMapper) {
        this.chatUseCase = chatUseCase;
        this.isChatAvailableUseCase = isChatAvailableUseCase;
        this.getMachinesUseCase = getMachinesUseCase;
        this.proposeActionUseCase = proposeActionUseCase;
        this.takeActionProposalUseCase = takeActionProposalUseCase;
        this.getConversationUseCase = getConversationUseCase;
        this.forgetConversationUseCase = forgetConversationUseCase;
        this.rememberActionOutcomeUseCase = rememberActionOutcomeUseCase;
        this.offerBundleUseCase = offerBundleUseCase;
        this.openBundleUseCase = openBundleUseCase;
        this.forgetUseCase = forgetUseCase;
        this.getMemoryUseCase = getMemoryUseCase;
        this.getSpendUseCase = getSpendUseCase;
        this.emailBundleUseCase = emailBundleUseCase;
        this.addErrandUseCase = addErrandUseCase;
        this.cancelErrandUseCase = cancelErrandUseCase;
        this.getErrandsUseCase = getErrandsUseCase;
        this.forSubscribingToEvents = forSubscribingToEvents;
        this.chatReads = chatReads;
        this.chatActions = chatActions;
        this.objectMapper = objectMapper;
    }

    /** The answering thread does not outlive Vaier. */
    @PreDestroy
    void stopAnswering() {
        answers.shutdownNow();
        pulse.shutdownNow();
    }

    /** Whether Chat is offered at all — the Explorer asks before drawing the pane in its menu. */
    @GetMapping("/availability")
    public ResponseEntity<AvailabilityResponse> availability() {
        return ResponseEntity.ok(new AvailabilityResponse(isChatAvailableUseCase.isAvailable()));
    }

    /**
     * Ask one question. The answer arrives as {@code text} events, one per piece, then {@code done}; a
     * refusal arrives as one {@code error} event carrying the sentence the operator can act on. The
     * conversation so far is Vaier's to remember, so the request carries the question alone.
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestHeader(value = "X-Auth-Request-Email", required = false) String email,
                          @RequestBody AskRequest request) {
        // Refused here, on the request thread, so a missing key is a 409 and not a stream that opens only
        // to say no.
        new ChatAvailability(isChatAvailableUseCase.isAvailable()).requireAvailable();
        Operator operator = Operator.of(email);
        SseEmitter emitter = new SseEmitter(ANSWER_TIMEOUT_MS);
        answers.submit(() -> answer(emitter, operator, request.question()));
        return emitter;
    }

    /** The kept conversation, to draw the pane from. */
    @GetMapping("/conversation")
    public ResponseEntity<ConversationResponse> conversation(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        return ResponseEntity.ok(ConversationResponse.of(getConversationUseCase.get(Operator.of(email))));
    }

    /** Start over. */
    @DeleteMapping("/conversation")
    public ResponseEntity<Void> forget(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        forgetConversationUseCase.forget(Operator.of(email));
        return ResponseEntity.noContent().build();
    }

    /** The whole of one answer, start to finish. Package-private so a test can drive it without a thread. */
    void answer(SseEmitter emitter, Operator operator, String question) {
        ScheduledFuture<?> beat = pulse.scheduleAtFixedRate(() -> send(emitter, "ping", ""),
            heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        StringBuilder said = new StringBuilder();
        try {
            chatUseCase.ask(operator, question, toolOffers(emitter, operator), text -> {
                said.append(text);
                send(emitter, "text", text);
            });
            beat.cancel(false);
            // An answer without a word is not an answer. It happens when the model's turn was cut off
            // before it spoke — a tool call too long for the room it has — and a blank thread would leave
            // the operator guessing.
            if (said.toString().isBlank()) {
                send(emitter, "error", NOTHING_SAID);
            } else {
                send(emitter, "done", "");
            }
        } catch (Exception e) {
            beat.cancel(false);
            log.warn("Chat could not answer: {}", e.toString());
            send(emitter, "error", messageFor(e));
        }
        emitter.complete();
    }

    /**
     * A refusal the domain worded is said as it stands; anything else is reported in Vaier's own words. An
     * unexpected failure's message can carry a host, a path or a credential — the same reason
     * {@link GlobalExceptionHandler} never returns one.
     */
    /**
     * The click. The card is taken once — gone or expired is refused, and nothing runs — then
     * {@link ChatActions#run} runs the use case the Explorer's button calls, and the outcome is a sentence
     * for the card.
     */
    @PostMapping("/actions/{id}")
    public ResponseEntity<ActionOutcome> confirm(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email,
            @PathVariable String id) {
        ActionProposal proposal;
        try {
            proposal = takeActionProposalUseCase.take(id);
        } catch (NotFoundException | IllegalArgumentException refused) {
            return ResponseEntity.ok(new ActionOutcome(false, refused.getMessage()));
        }
        ChatActions.Outcome ran = chatActions.run(proposal, Operator.of(email));
        ActionOutcome outcome = new ActionOutcome(ran.done(), ran.text());
        // Remembered either way, so the next question knows what was started — or what was not.
        rememberActionOutcomeUseCase.remember(Operator.of(email),
            proposal.outcomeSentence(outcome.done(), outcome.text()));
        return ResponseEntity.ok(outcome);
    }

    /** "Not now": the card is taken, so it can never run, and the refusal is remembered. */
    @DeleteMapping("/actions/{id}")
    public ResponseEntity<ActionOutcome> decline(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email,
            @PathVariable String id) {
        try {
            ActionProposal proposal = takeActionProposalUseCase.take(id);
            rememberActionOutcomeUseCase.remember(Operator.of(email), proposal.declinedSentence());
        } catch (NotFoundException | IllegalArgumentException gone) {
            // Already gone: nothing could run anyway, and there is nothing to remember about it.
        }
        return ResponseEntity.ok(new ActionOutcome(false, "Not done."));
    }

    private static String messageFor(Exception e) {
        boolean worded = e instanceof ChatUnavailableException || e instanceof IllegalArgumentException;
        return worded && e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : "Vaier could not answer that.";
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // The pane closed mid-answer, or the connection to it did. Nothing to recover — the answer is
            // still made and kept — but worth a line, since the operator will have watched the box come
            // back before Marvin was done.
            log.info("Chat: the stream to the pane closed before the answer finished ({})", e.toString());
        }
    }

    // --- the tools ------------------------------------------------------------------------------------

    /**
     * One offer per catalogue entry, reads first, in the catalogues' order. An action's offer proposes on
     * {@code emitter} — the card rides the answer stream — and runs nothing.
     */
    private List<ToolOffer> toolOffers(SseEmitter emitter, Operator operator) {
        // What Marvin can do alone is ChatReads'; what needs somebody there is this controller's — a card to
        // click, a bundle to mail, and the two errand verbs. Merged by tool so the model is offered the
        // catalogue in the catalogue's own order, reads first.
        Map<ChatCapability, Function<Map<String, String>, String>> reads = new HashMap<>();
        for (ToolOffer offer : chatReads.offers()) {
            reads.put(offer.tool(), offer.read());
        }
        reads.put(ChatTool.BUNDLE_FILES, arguments -> offerBundle(arguments, emitter));
        reads.put(ChatTool.EMAIL_BUNDLE, arguments -> emailBundle(arguments, operator));
        reads.put(ChatTool.ADD_ERRAND, arguments -> addErrand(arguments, operator));
        reads.put(ChatTool.CANCEL_ERRAND, arguments -> cancelErrand(arguments, operator));

        List<ToolOffer> offers = new ArrayList<>();
        for (ChatTool tool : ChatTool.values()) {
            // A tool in the catalogue with nothing wired to it is offered to nobody: a model calling it would
            // get a failure, and an offer Vaier cannot answer is worse than a tool the model never hears of.
            Function<Map<String, String>, String> read = reads.get(tool);
            if (read != null) {
                offers.add(new ToolOffer(tool, announced(tool, read, emitter)));
            }
        }
        for (ChatAction action : ChatAction.values()) {
            offers.add(new ToolOffer(action, announced(action, arguments -> propose(action, arguments, emitter), emitter)));
        }
        return offers;
    }

    /** The pane is told which tool is running before it runs, so a long wait says what it waits on. */
    private Function<Map<String, String>, String> announced(ChatCapability tool,
                                                            Function<Map<String, String>, String> read,
                                                            SseEmitter emitter) {
        return arguments -> {
            send(emitter, "working", tool.toolName());
            try {
                return read.apply(arguments);
            } catch (RuntimeException e) {
                log.warn("Chat tool {} failed: {}", tool.toolName(), e.toString());
                throw e;
            }
        };
    }

    // --- the actions, proposed ------------------------------------------------------------------------

    /**
     * Resolve what the model named to what the card must say and the click must run — the machine's id
     * beside its name, the phone's name beside its code — hold the proposal, hand the pane the card, and
     * tell the model it is waiting. A name nothing has is refused in words, and no card is sent.
     */
    private String propose(ChatAction action, Map<String, String> arguments, SseEmitter emitter) {
        try {
            ActionProposal proposal = proposeActionUseCase.propose(action, chatActions.canonical(action, arguments));
            send(emitter, "confirm", asJson(new ConfirmationEvent(proposal.id(), proposal.sentence())));
            return proposal.toolResult();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /**
     * Files handed over: the bundle is offered — every path stat'd now — the pane gets the download card,
     * and the model is told it is ready. A path that is not there, or a machine Vaier cannot reach, is a
     * sentence back to the model and no card.
     */
    private String offerBundle(Map<String, String> arguments, SseEmitter emitter) {
        Machine machine;
        try {
            machine = new MachineReference(arguments.get("machine")).resolve(getMachinesUseCase.getAllMachines());
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        try {
            Bundle bundle = offerBundleUseCase.offer(machine.id(), machine.name(),
                Bundle.pathsOf(arguments.get("paths")), arguments.get("name"));
            send(emitter, "bundle", asJson(BundleEvent.of(bundle)));
            return bundle.toolResult();
        } catch (IllegalArgumentException | NotFoundException refused) {
            return refused.getMessage();
        } catch (NoHostCredentialException e) {
            return "No SSH credential is stored for " + machine.name() + ", so Vaier cannot read anything there.";
        } catch (RuntimeException e) {
            log.warn("Chat could not bundle files on {}: {}", machine.name(), e.toString());
            return machine.name() + " could not be reached over SSH.";
        }
    }

    /** The link by mail, to the operator who asked; every way it cannot go is a sentence back. */
    private String emailBundle(Map<String, String> arguments, Operator operator) {
        try {
            String to = emailBundleUseCase.email(arguments.getOrDefault("id", "").trim(), operator);
            return "Mailed a link to " + to + "; it works for a day.";
        } catch (NotFoundException | IllegalArgumentException | MailNotSentException refused) {
            return refused.getMessage();
        } catch (RuntimeException e) {
            log.warn("Chat could not mail a bundle: {}", e.toString());
            return "Vaier could not send that mail.";
        }
    }

    /**
     * An <b>errand</b>, added. The rhythm is one string the model wrote, and what it may say is the domain's
     * decision — so a shape the domain will not read comes back as its own refusal, naming all four, and the
     * model can correct itself in the same turn rather than guessing again next turn.
     */
    private String addErrand(Map<String, String> arguments, Operator operator) {
        try {
            return addErrandUseCase.add(operator, arguments.get("instruction"), arguments.get("rhythm"))
                .addedSentence();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /** Only this operator's own; anybody else's id is an id Vaier does not have, in the domain's words. */
    private String cancelErrand(Map<String, String> arguments, Operator operator) {
        try {
            cancelErrandUseCase.cancel(operator, arguments.getOrDefault("id", "").trim());
            return "Cancelled.";
        } catch (NotFoundException | IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /** What Chat has cost this month, for the figure in the top bar. */
    @GetMapping("/spend")
    public ResponseEntity<SpendResponse> spend() {
        return ResponseEntity.ok(SpendResponse.of(getSpendUseCase.thisMonth()));
    }

    /** Everything Vaier remembers, for the pane that shows it. */
    @GetMapping("/memory")
    public ResponseEntity<MemoryResponse> memory() {
        return ResponseEntity.ok(MemoryResponse.of(getMemoryUseCase.getMemory()));
    }

    /** The operator's last word on what stays. */
    @DeleteMapping("/memory/{id}")
    public ResponseEntity<Void> forgetFact(@PathVariable String id) {
        forgetUseCase.forget(id);
        return ResponseEntity.noContent().build();
    }

    /** Every <b>errand</b> of this operator's, for the dialog that lists them. */
    @GetMapping("/errands")
    public ResponseEntity<List<ErrandResponse>> errands(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        return ResponseEntity.ok(getErrandsUseCase.getErrands(Operator.of(email)).stream()
            .map(ErrandResponse::of)
            .toList());
    }

    /** The operator's last word on what Marvin keeps doing. Somebody else's id is a 404, never a hint. */
    @DeleteMapping("/errands/{id}")
    public ResponseEntity<Void> cancelErrand(
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email,
            @PathVariable String id) {
        cancelErrandUseCase.cancel(Operator.of(email), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The nudge: an <b>errand</b> reported while nobody was asking, so the pane re-reads the thread and the
     * errands. The frontend never polls — this is the push that makes that possible.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return forSubscribingToEvents.subscribe("chat");
    }

    /** The card's link: the bundle streamed as one zip, exactly as an Explorer selection download is. */
    @GetMapping("/bundles/{id}")
    public ResponseEntity<StreamingResponseBody> bundle(@PathVariable String id) {
        Download download = openBundleUseCase.open(id);
        StreamingResponseBody body = download.writer()::accept;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + download.filename().replaceAll("[\"\\\\\r\n]", "_") + "\"")
            .contentType(MediaType.parseMediaType(download.contentType()));
        // A length the service knows is told, so the browser can show how far along the download is.
        if (download.sizeBytes() >= 0) {
            response = response.contentLength(download.sizeBytes());
        }
        return response.body(body);
    }

    private String asJson(Object projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            log.warn("Chat could not render a tool result", e);
            return "Vaier could not read that.";
        }
    }

    // --- what the model is told ------------------------------------------------------------------------

    record AvailabilityResponse(boolean available) {}

    /** The card, as the answer stream carries it: enough to draw it and to click it. */
    record ConfirmationEvent(String id, String sentence) {}

    /** The download card: the zip's name, what it holds, and where the click goes. */
    record BundleEvent(String id, String name, String size, String url) {
        static BundleEvent of(Bundle bundle) {
            return new BundleEvent(bundle.id(), bundle.name(), bundle.describe(), "/chat/bundles/" + bundle.id());
        }
    }

    /** What became of a click: whether it ran, and the sentence for the card either way. */
    record ActionOutcome(boolean done, String text) {}

    record AskRequest(String question) {}

    /** This month's figure, and the tokens behind it for the tooltip. */
    record SpendResponse(String month, String figure, int calls, long inputTokens, long outputTokens,
                         long cacheWriteTokens, long cacheReadTokens) {
        static SpendResponse of(MonthSpend spend) {
            return new SpendResponse(spend.month().toString(), spend.figure(), spend.calls(),
                spend.usage().inputTokens(), spend.usage().outputTokens(),
                spend.usage().cacheWriteTokens(), spend.usage().cacheReadTokens());
        }
    }

    /** What Vaier remembers, as the pane lists it. */
    record MemoryResponse(List<FactResponse> facts) {
        static MemoryResponse of(Memory memory) {
            return new MemoryResponse(memory.facts().stream().map(FactResponse::of).toList());
        }
    }

    record FactResponse(String id, String text, long rememberedAt) {
        static FactResponse of(Memory.Fact fact) {
            return new FactResponse(fact.id(), fact.text(), fact.rememberedAtEpochMs());
        }
    }

    /** The kept conversation as the pane draws it: a summary, when there is one, then the turns. */
    record ConversationResponse(String summary, List<TurnResponse> turns) {
        static ConversationResponse of(Conversation conversation) {
            return new ConversationResponse(conversation.summary(),
                conversation.turns().stream().map(TurnResponse::of).toList());
        }
    }

    record TurnResponse(String role, String text) {
        static TurnResponse of(ConversationTurn turn) {
            return new TurnResponse(turn.role().name(), turn.text());
        }
    }

    /**
     * One <b>errand</b> as the dialog lists it: when it runs in the operator's own words, what it does, and
     * when it next comes round. The times carry their offset, so the browser shows them in the reader's own
     * zone rather than in whatever zone the server happens to keep.
     */
    record ErrandResponse(String id, String instruction, String rhythm, String nextDue, String lastRunAt,
                          String lastOutcome) {
        static ErrandResponse of(Errand errand) {
            return new ErrandResponse(errand.id(), errand.instruction(), errand.rhythm().describe(),
                atLocalTime(errand.nextDue()),
                errand.lastRunAtEpochMs() == null ? null
                    : atLocalTime(Instant.ofEpochMilli(errand.lastRunAtEpochMs())),
                errand.lastOutcome());
        }

        private static String atLocalTime(Instant instant) {
            return instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
    }
}
