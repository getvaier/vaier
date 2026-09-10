package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.ChatUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.EmailBundleUseCase;
import net.vaier.application.ForgetConversationUseCase;
import net.vaier.application.ForgetUseCase;
import net.vaier.application.GetConversationUseCase;
import net.vaier.application.GetMemoryUseCase;
import net.vaier.application.GetSpendUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.IsChatAvailableUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.OfferBundleUseCase;
import net.vaier.application.OpenBundleUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.RememberUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.RunReadOnlyCommandUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UpdateContainerImageUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ChatCapability;
import net.vaier.domain.ChatTool;
import net.vaier.domain.ChatAvailability;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.Bundle;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.ConflictException;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.DockerService;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.Reachability;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineReference;
import net.vaier.domain.MailNotSentException;
import net.vaier.domain.Memory;
import net.vaier.domain.MonthSpend;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>The projections below are the whole of what leaves Vaier for the Claude API, and they are deliberately
 * small: what a person would say out loud about a machine, a service or a backup. No key, no preshared key,
 * no config text, no credential, no passphrase, no token and no <b>Enrolment ticket</b> is in any of them,
 * and {@code ChatRestControllerTest} reads every one of them back to prove it.
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
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVpnPeersUseCase getVpnPeersUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRunsUseCase getBackupRunsUseCase;
    private final GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    private final RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;
    private final ProposeActionUseCase proposeActionUseCase;
    private final TakeActionProposalUseCase takeActionProposalUseCase;
    private final ApproveEnrolmentUseCase approveEnrolmentUseCase;
    private final RefuseEnrolmentUseCase refuseEnrolmentUseCase;
    private final RunBackupJobUseCase runBackupJobUseCase;
    private final GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;
    private final UpdateContainerImageUseCase updateContainerImageUseCase;
    private final LiftBlockUseCase liftBlockUseCase;
    private final TrustAddressUseCase trustAddressUseCase;
    private final GetConversationUseCase getConversationUseCase;
    private final ForgetConversationUseCase forgetConversationUseCase;
    private final RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    private final OfferBundleUseCase offerBundleUseCase;
    private final OpenBundleUseCase openBundleUseCase;
    private final RememberUseCase rememberUseCase;
    private final ForgetUseCase forgetUseCase;
    private final GetMemoryUseCase getMemoryUseCase;
    private final GetSpendUseCase getSpendUseCase;
    private final EmailBundleUseCase emailBundleUseCase;
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
                             GetVpnPeersUseCase getVpnPeersUseCase,
                             ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase,
                             GetPublishedServicesUseCase getPublishedServicesUseCase,
                             GetBackupJobsUseCase getBackupJobsUseCase,
                             GetBackupRunsUseCase getBackupRunsUseCase,
                             GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase,
                             DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                             DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                             GetBlockDecisionsUseCase getBlockDecisionsUseCase,
                             GetLanServerReachabilityUseCase getLanServerReachabilityUseCase,
                             RunReadOnlyCommandUseCase runReadOnlyCommandUseCase,
                             ProposeActionUseCase proposeActionUseCase,
                             TakeActionProposalUseCase takeActionProposalUseCase,
                             ApproveEnrolmentUseCase approveEnrolmentUseCase,
                             RefuseEnrolmentUseCase refuseEnrolmentUseCase,
                             RunBackupJobUseCase runBackupJobUseCase,
                             GetBackupRepositoriesUseCase getBackupRepositoriesUseCase,
                             UpdateContainerImageUseCase updateContainerImageUseCase,
                             LiftBlockUseCase liftBlockUseCase,
                             TrustAddressUseCase trustAddressUseCase,
                             GetConversationUseCase getConversationUseCase,
                             ForgetConversationUseCase forgetConversationUseCase,
                             RememberActionOutcomeUseCase rememberActionOutcomeUseCase,
                             OfferBundleUseCase offerBundleUseCase,
                             OpenBundleUseCase openBundleUseCase,
                             RememberUseCase rememberUseCase,
                             ForgetUseCase forgetUseCase,
                             GetMemoryUseCase getMemoryUseCase,
                             GetSpendUseCase getSpendUseCase,
                             EmailBundleUseCase emailBundleUseCase,
                             ObjectMapper objectMapper) {
        this.getLanServerReachabilityUseCase = getLanServerReachabilityUseCase;
        this.chatUseCase = chatUseCase;
        this.isChatAvailableUseCase = isChatAvailableUseCase;
        this.getMachinesUseCase = getMachinesUseCase;
        this.getVpnPeersUseCase = getVpnPeersUseCase;
        this.listEnrolmentRequestsUseCase = listEnrolmentRequestsUseCase;
        this.getPublishedServicesUseCase = getPublishedServicesUseCase;
        this.getBackupJobsUseCase = getBackupJobsUseCase;
        this.getBackupRunsUseCase = getBackupRunsUseCase;
        this.getMachineDiskStandingsUseCase = getMachineDiskStandingsUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getBlockDecisionsUseCase = getBlockDecisionsUseCase;
        this.runReadOnlyCommandUseCase = runReadOnlyCommandUseCase;
        this.proposeActionUseCase = proposeActionUseCase;
        this.takeActionProposalUseCase = takeActionProposalUseCase;
        this.approveEnrolmentUseCase = approveEnrolmentUseCase;
        this.refuseEnrolmentUseCase = refuseEnrolmentUseCase;
        this.runBackupJobUseCase = runBackupJobUseCase;
        this.getBackupRepositoriesUseCase = getBackupRepositoriesUseCase;
        this.updateContainerImageUseCase = updateContainerImageUseCase;
        this.liftBlockUseCase = liftBlockUseCase;
        this.trustAddressUseCase = trustAddressUseCase;
        this.getConversationUseCase = getConversationUseCase;
        this.forgetConversationUseCase = forgetConversationUseCase;
        this.rememberActionOutcomeUseCase = rememberActionOutcomeUseCase;
        this.offerBundleUseCase = offerBundleUseCase;
        this.openBundleUseCase = openBundleUseCase;
        this.rememberUseCase = rememberUseCase;
        this.forgetUseCase = forgetUseCase;
        this.getMemoryUseCase = getMemoryUseCase;
        this.getSpendUseCase = getSpendUseCase;
        this.emailBundleUseCase = emailBundleUseCase;
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
     * The click. The card is taken once — gone or expired is refused, and nothing runs — then the same use
     * case the Explorer's button calls runs, and the outcome is a sentence for the card. A refusal the
     * domain worded is shown; an unexpected failure is answered in Vaier's words, for the reason
     * {@link #messageFor} gives.
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
        ActionOutcome outcome;
        try {
            outcome = new ActionOutcome(true, carryOut(proposal));
        } catch (IllegalArgumentException | ConflictException | NotFoundException | NoHostCredentialException refused) {
            outcome = new ActionOutcome(false, refused.getMessage());
        } catch (RuntimeException e) {
            log.warn("Chat could not carry out '{}': {}", proposal.sentence(), e.toString());
            outcome = new ActionOutcome(false, "Vaier could not do that.");
        }
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

    /** One verb, one use case — the one the Explorer's own button calls. */
    private String carryOut(ActionProposal proposal) {
        Map<String, String> a = proposal.arguments();
        return switch (proposal.action()) {
            case LET_PHONE_IN -> {
                approveEnrolmentUseCase.approve(a.get("code"));
                yield "Let " + a.get("name") + " in.";
            }
            case REFUSE_PHONE -> {
                refuseEnrolmentUseCase.refuse(a.get("code"));
                yield "Refused " + a.get("name") + ".";
            }
            case RUN_BACKUP -> {
                MachineId machineId = MachineId.of(a.get("machineId"));
                BackupJob job = getBackupJobsUseCase.getBackupJobs().stream()
                    .filter(j -> j.machineId().equals(machineId)).findFirst()
                    .orElseThrow(() -> new NotFoundException(a.get("machine") + " has no backup job."));
                BackupRepository repo = getBackupRepositoriesUseCase.getBackupRepositories().stream()
                    .filter(r -> r.name().equals(job.repositoryName())).findFirst()
                    .orElseThrow(() -> new NotFoundException(a.get("machine") + "'s backups have nowhere to go."));
                runBackupJobUseCase.runJob(job, repo);
                yield "Backing up " + a.get("machine") + " now. The Backups pane shows how it goes.";
            }
            case UPDATE_CONTAINER -> {
                updateContainerImageUseCase.updateContainerImage(MachineId.of(a.get("machineId")), a.get("container"));
                yield "Updating " + a.get("container") + " on " + a.get("machine")
                    + ". It is down for a moment while it restarts.";
            }
            case LIFT_BLOCK -> {
                liftBlockUseCase.liftBlock(a.get("address"));
                yield "Lifted the block on " + a.get("address") + ".";
            }
            case TRUST_ADDRESS -> {
                trustAddressUseCase.trustAddress(a.get("address"));
                yield "Trusting " + a.get("address") + " from now on.";
            }
        };
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
        Map<ChatTool, Function<Map<String, String>, String>> reads = new HashMap<>();
        reads.put(ChatTool.FLEET, arguments -> readFleet());
        reads.put(ChatTool.WAITING_TO_JOIN, arguments -> readWaitingToJoin());
        reads.put(ChatTool.PUBLISHED_SERVICES, arguments -> readPublishedServices());
        reads.put(ChatTool.BACKUPS, arguments -> readBackups());
        reads.put(ChatTool.DISKS, arguments -> readDisks());
        reads.put(ChatTool.CONTAINER_UPDATES, arguments -> readContainerUpdates());
        reads.put(ChatTool.SECURITY, arguments -> readSecurity());
        reads.put(ChatTool.RUN_ON_MACHINE, this::readRunOnMachine);
        reads.put(ChatTool.BUNDLE_FILES, arguments -> offerBundle(arguments, emitter));
        reads.put(ChatTool.EMAIL_BUNDLE, arguments -> emailBundle(arguments, operator));
        reads.put(ChatTool.REMEMBER, this::remember);
        reads.put(ChatTool.FORGET, this::forget);

        List<ToolOffer> offers = new ArrayList<>();
        for (ChatTool tool : ChatTool.values()) {
            offers.add(new ToolOffer(tool, announced(tool, reads.get(tool), emitter)));
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
            ActionProposal proposal = proposeActionUseCase.propose(action, canonical(action, arguments));
            send(emitter, "confirm", asJson(new ConfirmationEvent(proposal.id(), proposal.sentence())));
            return proposal.toolResult();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private Map<String, String> canonical(ChatAction action, Map<String, String> arguments) {
        Map<String, String> canonical = new HashMap<>();
        arguments.forEach((name, value) -> canonical.put(name, value == null ? null : value.trim()));
        switch (action) {
            case LET_PHONE_IN, REFUSE_PHONE -> {
                EnrolmentRequest waiting = EnrolmentRequest.byCode(listEnrolmentRequestsUseCase.pending(),
                    canonical.get("code"));
                canonical.put("code", waiting.code());
                canonical.put("name", waiting.name());
            }
            case RUN_BACKUP, UPDATE_CONTAINER -> {
                Machine machine = new MachineReference(canonical.get("machine"))
                    .resolve(getMachinesUseCase.getAllMachines());
                canonical.put("machine", machine.name());
                canonical.put("machineId", machine.id().value());
            }
            case LIFT_BLOCK, TRUST_ADDRESS -> { }
        }
        return canonical;
    }

    private String readFleet() {
        Map<String, VpnPeerView> peers = new HashMap<>();
        for (VpnPeerView peer : getVpnPeersUseCase.getVpnPeers()) {
            if (peer.machineId() != null) {
                peers.put(peer.machineId(), peer);
            }
        }
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        // What "reachable" means differs by kind of machine — the tunnel for a peer, the cached LAN probe for
        // a LAN server, always for the Vaier server — and the machine itself already knows. It reads the LAN
        // signal from the same cache the Explorer does; nothing is probed to answer a question.
        Map<String, Reachability> lan = new HashMap<>();
        for (Machine machine : machines) {
            if (machine.type() == MachineType.LAN_SERVER && machine.lanAddress() != null) {
                lan.put(machine.lanAddress(), getLanServerReachabilityUseCase.getReachability(machine.lanAddress()));
            }
        }
        return asJson(machines.stream()
            .map(machine -> MachineFact.of(machine, peers.get(machine.id().value()), standingOf(machine, lan)))
            .toList());
    }

    private String readWaitingToJoin() {
        long now = System.currentTimeMillis();
        return asJson(listEnrolmentRequestsUseCase.pending().stream()
            .map(request -> WaitingPhoneFact.of(request, now))
            .toList());
    }

    private String readPublishedServices() {
        return asJson(getPublishedServicesUseCase.getPublishedServices().stream()
            .map(ServiceFact::of)
            .toList());
    }

    private String readBackups() {
        Map<String, String> names = machineNames();
        return asJson(getBackupJobsUseCase.getBackupJobs().stream()
            .map(job -> BackupFact.of(job, names.get(job.machineId().value()),
                getBackupRunsUseCase.latestForMachine(job.machineId())))
            .toList());
    }

    private String readDisks() {
        Map<String, String> names = machineNames();
        return asJson(getMachineDiskStandingsUseCase.getMachineDiskStandings().stream()
            .map(standing -> DiskFact.of(standing, names.get(standing.machineId().value())))
            .toList());
    }

    /** Only what wants pulling. A list of every container the fleet runs would answer a different question. */
    private String readContainerUpdates() {
        Map<String, String> names = machineNames();
        List<ContainerUpdateFact> wanting = new ArrayList<>();
        for (PeerContainers peer : discoverPeerContainersUseCase.discoverAll()) {
            String machine = names.getOrDefault(peer.machineId(), peer.peerId());
            wanting.addAll(outdated(machine, peer.containers()));
        }
        wanting.addAll(outdated("the Vaier server", discoverVaierServerContainersUseCase.discover()));
        return asJson(wanting);
    }

    private static List<ContainerUpdateFact> outdated(String machine, List<DockerService> containers) {
        return containers == null ? List.of() : containers.stream()
            .filter(container -> container.updateAvailable().isUpdateAvailable())
            .map(container -> ContainerUpdateFact.of(machine, container))
            .toList();
    }

    private String readSecurity() {
        return asJson(getBlockDecisionsUseCase.getBlockDecisions().stream()
            .map(BlockFact::of)
            .toList());
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

    /** One fact kept; the domain's refusal is the answer when it is not one. */
    private String remember(Map<String, String> arguments) {
        try {
            Memory.Fact fact = rememberUseCase.remember(arguments.get("fact"));
            return "Remembered [" + fact.id() + "]: " + fact.text();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private String forget(Map<String, String> arguments) {
        try {
            forgetUseCase.forget(arguments.getOrDefault("id", "").trim());
            return "Forgotten.";
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

    /**
     * One command on the machine the model named. Every way this can fail is answered in a sentence the
     * model can repeat: the domain's own refusal verbatim, a name no machine has, a machine Vaier holds no
     * login for. A transport failure's own message can carry an address, a user or a path, so that one is
     * said in Vaier's words — the same reason the emitter never repeats one.
     */
    private String readRunOnMachine(Map<String, String> arguments) {
        Machine machine;
        try {
            machine = new MachineReference(arguments.get("machine")).resolve(getMachinesUseCase.getAllMachines());
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        String command = arguments.getOrDefault("command", "");
        try {
            CommandOutcome outcome = runReadOnlyCommandUseCase.runReadOnly(machine.id(), command);
            return asJson(CommandFact.of(machine, command, outcome));
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (NoHostCredentialException e) {
            return "No SSH credential is stored for " + machine.name() + ", so Vaier cannot run anything there.";
        } catch (RuntimeException e) {
            log.warn("Chat could not run a command on {}: {}", machine.name(), e.toString());
            return machine.name() + " could not be reached over SSH.";
        }
    }

    /** Machine identities to the names the Explorer shows, so every projection says what is on screen. */
    private Map<String, String> machineNames() {
        Map<String, String> names = new HashMap<>();
        for (Machine machine : getMachinesUseCase.getAllMachines()) {
            names.put(machine.id().value(), machine.name());
        }
        return names;
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
     * A machine as a person would describe it. No public key, no allowed IPs, no endpoint.
     *
     * <p>The tunnel address comes from the peer view, which the domain already derived — never re-read off
     * {@code allowedIps} here; a LAN server has no peer and so no tunnel address. Whether the machine is
     * reachable is the machine's own verdict ({@link Machine#isReachable}), so a LAN server or the Vaier
     * server is never called "not connected" for lacking a tunnel it was never meant to have.
     */
    record MachineFact(String id, String name, String type, String tunnelIp, String lanCidr, String standing) {
        static MachineFact of(Machine machine, VpnPeerView peer, String standing) {
            return new MachineFact(machine.id() == null ? null : machine.id().value(), machine.name(),
                machine.type().name(),
                peer == null ? null : peer.tunnelIp(), machine.lanCidr(), standing);
        }
    }

    /**
     * The machine's verdict on itself, in a word the model can repeat. A LAN server that has not been probed
     * yet — the minutes after a restart — is "not checked yet", never "unreachable": the fact is missing,
     * not bad, and the difference is exactly what an operator asks about.
     */
    private static String standingOf(Machine machine, Map<String, Reachability> lan) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(machine.name())) return "this server, always reachable";
        if (machine.type() == MachineType.LAN_SERVER) {
            Reachability r = lan.getOrDefault(machine.lanAddress(), Reachability.UNKNOWN);
            return r == Reachability.OK ? "reachable" : r == Reachability.DOWN ? "unreachable" : "not checked yet";
        }
        return machine.isReachable(lan) ? "connected" : "not connected";
    }

    /** A phone waiting to be let in: its name, its join code, and how long it has. Never its ticket or key. */
    record WaitingPhoneFact(String name, String joinCode, long minutesLeft) {
        static WaitingPhoneFact of(EnrolmentRequest request, long nowEpochMs) {
            return new WaitingPhoneFact(request.name(), request.code(),
                Math.round(request.secondsLeft(nowEpochMs) / 60.0));
        }
    }

    record ServiceFact(String name, String machine, String address, boolean reachable) {
        static ServiceFact of(PublishedServiceUco service) {
            return new ServiceFact(service.shortName(), service.hostName(), service.dnsAddress(),
                service.healthy());
        }
    }

    record BackupFact(String job, String machine, boolean enabled, String lastRun, String lastRunAt,
                      String lastRunNote) {
        static BackupFact of(BackupJob job, String machine, Optional<BackupRun> latest) {
            return new BackupFact(job.name(), machine, job.enabled(),
                latest.map(run -> run.status().name()).orElse("never run"),
                latest.map(BackupRun::finishedAt).map(String::valueOf).orElse(null),
                latest.map(BackupRun::summary).orElse(null));
        }
    }

    record DiskFact(String machine, String fullestFilesystem, int usedPercent, int alertsAbovePercent,
                    int filesystemsOverTheirThreshold) {
        static DiskFact of(MachineDiskStanding standing, String machine) {
            return new DiskFact(machine, standing.worstMountPoint(), standing.worstUsedPercent(),
                standing.worstThresholdPercent(), standing.breachingFilesystems());
        }
    }

    record ContainerUpdateFact(String machine, String container, String image) {
        static ContainerUpdateFact of(String machine, DockerService container) {
            return new ContainerUpdateFact(machine, container.containerName(), container.image());
        }
    }

    /** What one command printed on one machine. The command is echoed so a follow-up knows what was run. */
    record CommandFact(String machine, String command, int exitCode, boolean timedOut, String output, boolean cut) {
        static CommandFact of(Machine machine, String command, CommandOutcome outcome) {
            return new CommandFact(machine.name(), command, outcome.exitCode(), outcome.timedOut(),
                outcome.output(), outcome.cut());
        }
    }

    record BlockFact(String address, String why, String forHowLong, String country, String network) {
        static BlockFact of(BlockDecision decision) {
            return new BlockFact(decision.sourceIp(), decision.scenario(), decision.duration(),
                decision.country(), decision.asnOrg());
        }
    }
}
