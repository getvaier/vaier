package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.ApproveEnrolmentUseCase.ApprovedEnrolmentUco;
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
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.IsChatAvailableUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.OfferBundleUseCase;
import net.vaier.application.OpenBundleUseCase;
import net.vaier.application.ProposeActionUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.TakeActionProposalUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UpdateContainerImageUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ChatCapability;
import net.vaier.domain.ChatTool;
import net.vaier.domain.ChatUnavailableException;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.ConflictException;
import net.vaier.domain.Bundle;
import net.vaier.domain.Conversation;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.Errand;
import net.vaier.domain.Rhythm;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.MachineId;
import net.vaier.domain.MailNotSentException;
import net.vaier.domain.Memory;
import net.vaier.domain.ModelUsage;
import net.vaier.domain.Spend;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.port.ForSubscribingToEvents;
import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The <b>Chat</b> endpoint (#360 slice 1). Its constructor is the honest list of everything Chat may read, and
 * the projections here are the whole of what leaves Vaier for the Claude API — so the test that matters most
 * is the one that reads every one of them and finds no secret in any.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatRestControllerTest {

    @Mock ChatUseCase chatUseCase;
    @Mock IsChatAvailableUseCase isChatAvailableUseCase;
    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    @Mock GetBackupJobsUseCase getBackupJobsUseCase;
    @Mock ProposeActionUseCase proposeActionUseCase;
    @Mock TakeActionProposalUseCase takeActionProposalUseCase;
    @Mock ApproveEnrolmentUseCase approveEnrolmentUseCase;
    @Mock RefuseEnrolmentUseCase refuseEnrolmentUseCase;
    @Mock RunBackupJobUseCase runBackupJobUseCase;
    @Mock GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;
    @Mock UpdateContainerImageUseCase updateContainerImageUseCase;
    @Mock LiftBlockUseCase liftBlockUseCase;
    @Mock TrustAddressUseCase trustAddressUseCase;
    @Mock GetConversationUseCase getConversationUseCase;
    @Mock ForgetConversationUseCase forgetConversationUseCase;
    @Mock RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    @Mock OfferBundleUseCase offerBundleUseCase;
    @Mock OpenBundleUseCase openBundleUseCase;
    @Mock ForgetUseCase forgetUseCase;
    @Mock GetMemoryUseCase getMemoryUseCase;
    @Mock GetSpendUseCase getSpendUseCase;
    @Mock EmailBundleUseCase emailBundleUseCase;
    @Mock AddErrandUseCase addErrandUseCase;
    @Mock CancelErrandUseCase cancelErrandUseCase;
    @Mock GetErrandsUseCase getErrandsUseCase;
    @Mock ForSubscribingToEvents forSubscribingToEvents;

    /** The reads Marvin may make alone live in their own component now; {@code ChatReadsTest} covers them. */
    @Mock ChatReads chatReads;

    private ChatRestController controller;

    private static final MachineId COLINA = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");
    private static final String EMAIL = "geir@example.com";
    private static final Operator GEIR = Operator.of(EMAIL);

    @BeforeEach
    void setUp() {
        controller = new ChatRestController(chatUseCase, isChatAvailableUseCase, getMachinesUseCase,
            listEnrolmentRequestsUseCase, getBackupJobsUseCase, proposeActionUseCase,
            takeActionProposalUseCase, approveEnrolmentUseCase, refuseEnrolmentUseCase, runBackupJobUseCase,
            getBackupRepositoriesUseCase, updateContainerImageUseCase, liftBlockUseCase, trustAddressUseCase,
            getConversationUseCase, forgetConversationUseCase, rememberActionOutcomeUseCase, offerBundleUseCase,
            openBundleUseCase, forgetUseCase, getMemoryUseCase, getSpendUseCase, emailBundleUseCase,
            addErrandUseCase, cancelErrandUseCase, getErrandsUseCase, forSubscribingToEvents, chatReads,
            new ObjectMapper());
    }

    // --- is Ask offered at all -------------------------------------------------------------------------

    @Test
    void availability_answersWhetherAskMayBeUsed() {
        when(isChatAvailableUseCase.isAvailable()).thenReturn(true);

        ResponseEntity<ChatRestController.AvailabilityResponse> response = controller.availability();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().available()).isTrue();
    }

    @Test
    void availability_saysNoWhenNoAnthropicApiKeyIsStored() {
        when(isChatAvailableUseCase.isAvailable()).thenReturn(false);

        assertThat(controller.availability().getBody().available()).isFalse();
    }

    // --- the answer, streamed --------------------------------------------------------------------------

    @Test
    void answer_sendsEachPieceOfTheAnswerAsATextEventThenDone() throws IOException {
        answering("Colina", " is red.");
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, GEIR, "which machine is red?");

        assertThat(sentEvents(emitter)).containsExactly(
            "event:text\ndata:Colina\n\n",
            "event:text\ndata: is red.\n\n",
            "event:done\ndata:\n\n");
        verify(emitter).complete();
    }

    /** Who asked and what; the conversation so far is Vaier's to remember, not the browser's to send. */
    @Test
    void ask_passesTheOperatorAndTheQuestion() {
        when(isChatAvailableUseCase.isAvailable()).thenReturn(true);
        answering("yes.");

        controller.ask(EMAIL, new ChatRestController.AskRequest("and colina27?"));

        verify(chatUseCase, timeout(2000)).ask(eq(GEIR), eq("and colina27?"), anyList(), any());
    }

    /** A refusal reaches the pane as a sentence, not as a dropped stream the browser has to guess about. */
    @Test
    void answer_sendsTheRefusalAsAnErrorEventAndClosesCleanly() throws IOException {
        doThrow(new ChatUnavailableException("Chat needs an Anthropic API key."))
            .when(chatUseCase).ask(any(), anyString(), anyList(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, GEIR, "anything?");

        assertThat(sentEvents(emitter))
            .containsExactly("event:error\ndata:Chat needs an Anthropic API key.\n\n");
        verify(emitter).complete();
    }

    // --- the tools ------------------------------------------------------------------------------------

    @Test
    void itOffersOneToolPerCatalogueEntry_readsFirstThenActions() {
        answering("ok");

        controller.answer(mock(SseEmitter.class), GEIR, "anything?");

        List<String> expected = new ArrayList<>(List.of(ChatTool.values()).stream().map(ChatTool::toolName).toList());
        expected.addAll(List.of(ChatAction.values()).stream().map(ChatAction::toolName).toList());
        assertThat(offeredTools()).extracting(offer -> offer.tool().toolName()).containsExactlyElementsOf(expected);
    }

    /** A turn that ends without a word is said as an error, never as a silent done. */
    @Test
    void answer_thatSaysNothing_isAnError_notASilentDone() throws IOException {
        answering();
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, GEIR, "anything?");

        assertThat(sentEvents(emitter)).containsExactly("event:error\ndata:" + ChatRestController.NOTHING_SAID + "\n\n");
        verify(emitter).complete();
    }

    // --- while the answer is being made: working events, and a heartbeat -----------------------------

    /** Every tool call is announced to the pane first, so a long wait says what it is waiting on. */
    @Test
    void everyToolCall_isAnnouncedToThePaneBeforeItRuns() throws IOException {
        answering("ok");
        fleetOf();
        SseEmitter emitter = mock(SseEmitter.class);

        read(emitter, ChatTool.FLEET, Map.of());

        assertThat(sentEvents(emitter)).contains("event:working\ndata:fleet\n\n");
    }

    /**
     * A tool call or a long think can leave the stream silent for a minute, and something on the way to
     * the browser closes a quiet connection. So the answer keeps a pulse: a ping every so often, for as
     * long as it takes, and never after it is done.
     */
    @Test
    void theAnswerKeepsAPulse_whileItIsBeingMade() throws IOException {
        controller.heartbeatMs = 10;
        doAnswer(invocation -> {
            Thread.sleep(80);
            Consumer<String> onText = invocation.getArgument(3);
            onText.accept("late");
            return null;
        }).when(chatUseCase).ask(any(), anyString(), anyList(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, GEIR, "anything?");

        List<String> events = sentEvents(emitter);
        assertThat(events).contains("event:ping\ndata:\n\n");
        assertThat(events.get(events.size() - 1)).isEqualTo("event:done\ndata:\n\n");
    }

    // --- actions: proposed as a card, run on the click (#360 slice 2) ----------------------------------

    private static final long NOW = 1_700_000_000_000L;

    /** The propose use case, answered by the domain so the test reads a real proposal back. */
    private void proposing() {
        when(proposeActionUseCase.propose(any(), any())).thenAnswer(invocation ->
            ActionProposal.propose(invocation.getArgument(0), invocation.getArgument(1), NOW));
    }

    @Test
    void everyActionIsOfferedAlongsideTheReads() {
        answering("ok");
        controller.answer(mock(SseEmitter.class), GEIR, "anything?");

        assertThat(offeredTools()).extracting(ToolOffer::tool)
            .contains(ChatAction.values())
            .contains(ChatTool.values());
    }

    /**
     * Proposing runs nothing. It resolves the machine, holds the proposal, sends the pane one
     * {@code confirm} event carrying the card, and tells the model it is waiting for a click.
     */
    @Test
    void proposingABackup_holdsTheProposal_sendsTheCard_andRunsNothing() throws IOException {
        answering("ok");
        fleetOf();
        proposing();
        SseEmitter emitter = mock(SseEmitter.class);

        String told = read(emitter, ChatAction.RUN_BACKUP, Map.of("machine", "colina 27"));

        ArgumentCaptor<Map<String, String>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(proposeActionUseCase).propose(eq(ChatAction.RUN_BACKUP), arguments.capture());
        assertThat(arguments.getValue()).containsEntry("machine", "Colina 27")
            .containsEntry("machineId", COLINA.value());
        assertThat(sentEvents(emitter)).anySatisfy(event ->
            assertThat(event).startsWith("event:confirm\ndata:").contains("Back up Colina 27 now."));
        assertThat(told).contains("Nothing has happened yet");
        verifyNoInteractions(runBackupJobUseCase);
    }

    @Test
    void proposingForAMachineNobodyHas_isRefusedInWords_andNoCardIsSent() throws IOException {
        answering("ok");
        fleetOf();
        SseEmitter emitter = mock(SseEmitter.class);

        String told = read(emitter, ChatAction.RUN_BACKUP, Map.of("machine", "Apalveien"));

        assertThat(told).contains("no machine called \"Apalveien\"");
        assertThat(sentEvents(emitter)).noneSatisfy(event -> assertThat(event).contains("event:confirm"));
        verifyNoInteractions(proposeActionUseCase);
    }

    /** The phone is named on the card, from the pending list, so the operator knows whom they are letting in. */
    @Test
    void proposingToLetAPhoneIn_namesThePhoneOnTheCard() {
        answering("ok");
        proposing();
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(
            new EnrolmentRequest("4417", "TICKET-SECRET", "Ruten", "PUBLICKEY-SECRET",
                System.currentTimeMillis() + 300_000, "CONFIGFILE-SECRET")));

        String told = read(ChatAction.LET_PHONE_IN, Map.of("code", "4417"));

        assertThat(told).contains("Let Ruten in (join code 4417).").doesNotContain("TICKET-SECRET");
        assertThat(read(ChatAction.LET_PHONE_IN, Map.of("code", "9999")))
            .isEqualTo("No phone is waiting with join code 9999.");
    }

    @Test
    void confirming_takesTheCardAndRunsTheBackup() {
        fleetOf();
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP,
            Map.of("machine", "Colina 27", "machineId", COLINA.value()), NOW);
        when(takeActionProposalUseCase.take("p1")).thenReturn(proposal);
        BackupJob job = new BackupJob("colina-27", COLINA, "colina-27", List.of("/home"), List.of(),
            7, 4, 6, "zstd", true, false);
        BackupRepository repo = new BackupRepository("colina-27", "nas", "/home/borg/backups/colina-27",
            "PASSPHRASE-SECRET", true);
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of(job));
        when(getBackupRepositoriesUseCase.getBackupRepositories()).thenReturn(List.of(repo));

        ChatRestController.ActionOutcome outcome = controller.confirm(EMAIL, "p1").getBody();

        verify(runBackupJobUseCase).runJob(job, repo);
        assertThat(outcome.done()).isTrue();
        assertThat(outcome.text()).isEqualTo("Backing up Colina 27 now. The Backups pane shows how it goes.");
    }

    @Test
    void confirming_runsEachOfTheOtherVerbsThroughItsOwnUseCase() {
        when(takeActionProposalUseCase.take("in")).thenReturn(ActionProposal.propose(ChatAction.LET_PHONE_IN,
            Map.of("code", "4417", "name", "Ruten"), NOW));
        when(approveEnrolmentUseCase.approve("4417")).thenReturn(mock(ApprovedEnrolmentUco.class));
        assertThat(controller.confirm(EMAIL, "in").getBody().text()).isEqualTo("Let Ruten in.");
        verify(approveEnrolmentUseCase).approve("4417");

        when(takeActionProposalUseCase.take("out")).thenReturn(ActionProposal.propose(ChatAction.REFUSE_PHONE,
            Map.of("code", "4417", "name", "Ruten"), NOW));
        assertThat(controller.confirm(EMAIL, "out").getBody().text()).isEqualTo("Refused Ruten.");
        verify(refuseEnrolmentUseCase).refuse("4417");

        when(takeActionProposalUseCase.take("up")).thenReturn(ActionProposal.propose(ChatAction.UPDATE_CONTAINER,
            Map.of("machine", "Colina 27", "machineId", COLINA.value(), "container", "mosquitto"), NOW));
        assertThat(controller.confirm(EMAIL, "up").getBody().text())
            .isEqualTo("Updating mosquitto on Colina 27. It is down for a moment while it restarts.");
        verify(updateContainerImageUseCase).updateContainerImage(COLINA, "mosquitto");

        when(takeActionProposalUseCase.take("lift")).thenReturn(ActionProposal.propose(ChatAction.LIFT_BLOCK,
            Map.of("address", "203.0.113.9"), NOW));
        assertThat(controller.confirm(EMAIL, "lift").getBody().text()).isEqualTo("Lifted the block on 203.0.113.9.");
        verify(liftBlockUseCase).liftBlock("203.0.113.9");

        when(takeActionProposalUseCase.take("trust")).thenReturn(ActionProposal.propose(ChatAction.TRUST_ADDRESS,
            Map.of("address", "203.0.113.9"), NOW));
        assertThat(controller.confirm(EMAIL, "trust").getBody().text()).isEqualTo("Trusting 203.0.113.9 from now on.");
        verify(trustAddressUseCase).trustAddress("203.0.113.9");
    }

    /** A card that is gone or expired is said so, and nothing runs. */
    @Test
    void confirming_aCardThatIsGone_runsNothing() {
        when(takeActionProposalUseCase.take("p1")).thenThrow(new NotFoundException("That card is gone; ask again."));

        ChatRestController.ActionOutcome outcome = controller.confirm(EMAIL, "p1").getBody();

        assertThat(outcome.done()).isFalse();
        assertThat(outcome.text()).isEqualTo("That card is gone; ask again.");
        verifyNoInteractions(runBackupJobUseCase, approveEnrolmentUseCase, liftBlockUseCase);
    }

    /** A refusal worded by the domain is shown; an unexpected failure is answered in Vaier's words. */
    @Test
    void confirming_showsAWordedRefusal_butNeverAnUnexpectedFailuresOwnMessage() {
        when(takeActionProposalUseCase.take("up")).thenReturn(ActionProposal.propose(ChatAction.UPDATE_CONTAINER,
            Map.of("machine", "Colina 27", "machineId", COLINA.value(), "container", "mosquitto"), NOW));
        doThrow(new ConflictException("mosquitto is already on the newest image."))
            .when(updateContainerImageUseCase).updateContainerImage(any(), anyString());
        ChatRestController.ActionOutcome refused = controller.confirm(EMAIL, "up").getBody();
        assertThat(refused.done()).isFalse();
        assertThat(refused.text()).isEqualTo("mosquitto is already on the newest image.");

        when(takeActionProposalUseCase.take("lift")).thenReturn(ActionProposal.propose(ChatAction.LIFT_BLOCK,
            Map.of("address", "203.0.113.9"), NOW));
        doThrow(new IllegalStateException("cscli at /usr/local/bin failed as root"))
            .when(liftBlockUseCase).liftBlock(anyString());
        ChatRestController.ActionOutcome failed = controller.confirm(EMAIL, "lift").getBody();
        assertThat(failed.done()).isFalse();
        assertThat(failed.text()).isEqualTo("Vaier could not do that.");
    }

    // --- the kept conversation (#360 slice 3) --------------------------------------------------------

    @Test
    void conversation_isTheOperatorsOwn_withItsSummaryAndTurns() {
        when(getConversationUseCase.get(GEIR)).thenReturn(new Conversation(GEIR, "the gist", List.of(
            new ConversationTurn(Role.OPERATOR, "is the nas up?"),
            new ConversationTurn(Role.VAIER, "yes."))));

        ChatRestController.ConversationResponse response = controller.conversation(EMAIL).getBody();

        assertThat(response.summary()).isEqualTo("the gist");
        assertThat(response.turns()).extracting(ChatRestController.TurnResponse::role, ChatRestController.TurnResponse::text)
            .containsExactly(tuple("OPERATOR", "is the nas up?"), tuple("VAIER", "yes."));
    }

    @Test
    void forgetting_dropsTheOperatorsConversation() {
        assertThat(controller.forget(EMAIL).getStatusCode().value()).isEqualTo(204);

        verify(forgetConversationUseCase).forget(GEIR);
    }

    /** What became of a card is remembered, so the next question knows the backup was started. */
    @Test
    void confirming_remembersWhatBecameOfTheCard() {
        when(takeActionProposalUseCase.take("lift")).thenReturn(ActionProposal.propose(ChatAction.LIFT_BLOCK,
            Map.of("address", "203.0.113.9"), NOW));

        controller.confirm(EMAIL, "lift");

        verify(rememberActionOutcomeUseCase).remember(GEIR,
            "Proposed: Lift the block on 203.0.113.9. (done: Lifted the block on 203.0.113.9.)");
    }

    /** "Not now" takes the card too — it can never run afterwards — and is remembered as declined. */
    @Test
    void declining_takesTheCardSoItCannotRun_andIsRemembered() {
        when(takeActionProposalUseCase.take("lift")).thenReturn(ActionProposal.propose(ChatAction.LIFT_BLOCK,
            Map.of("address", "203.0.113.9"), NOW));

        ChatRestController.ActionOutcome outcome = controller.decline(EMAIL, "lift").getBody();

        assertThat(outcome.done()).isFalse();
        assertThat(outcome.text()).isEqualTo("Not done.");
        verifyNoInteractions(liftBlockUseCase);
        verify(rememberActionOutcomeUseCase).remember(GEIR,
            "Proposed: Lift the block on 203.0.113.9. (the operator declined)");
    }

    @Test
    void declining_aCardThatIsAlreadyGone_isStillNotDone() {
        when(takeActionProposalUseCase.take("gone")).thenThrow(new NotFoundException("That card is gone; ask again."));

        assertThat(controller.decline(EMAIL, "gone").getBody().text()).isEqualTo("Not done.");
        verifyNoInteractions(rememberActionOutcomeUseCase);
    }

    // --- bundle_files: files handed over as a download card (#360) ---------------------------------------

    @Test
    void bundlingFiles_offersThem_sendsTheDownloadCard_andSaysNothingWasWritten() throws IOException {
        answering("ok");
        fleetOf();
        Bundle bundle = Bundle.offer(COLINA, "Colina 27", List.of("/home/geir/a.jpg", "/home/geir/b.jpg"),
            "pictures-2025-09-10", NOW).sized(List.of(new RemoteStat(false, 1_000_000), new RemoteStat(false, 1_000_000)));
        when(offerBundleUseCase.offer(COLINA, "Colina 27", List.of("/home/geir/a.jpg", "/home/geir/b.jpg"),
            "pictures-2025-09-10")).thenReturn(bundle);
        SseEmitter emitter = mock(SseEmitter.class);

        String told = read(emitter, ChatTool.BUNDLE_FILES, Map.of("machine", "colina 27",
            "paths", "/home/geir/a.jpg\n/home/geir/b.jpg", "name", "pictures-2025-09-10"));

        assertThat(sentEvents(emitter)).anySatisfy(event -> assertThat(event)
            .startsWith("event:bundle\ndata:").contains("pictures-2025-09-10.zip").contains("2 files, 2.0 MB")
            .contains("/chat/bundles/" + bundle.id()));
        assertThat(told).contains("pictures-2025-09-10.zip").contains("Nothing was copied or written");
    }

    @Test
    void bundlingFiles_thatAreNotThere_isRefusedInWords_andNoCardIsSent() throws IOException {
        answering("ok");
        fleetOf();
        when(offerBundleUseCase.offer(any(), anyString(), anyList(), any()))
            .thenThrow(new NotFoundException("/home/geir/gone.jpg is not on Colina 27."));
        SseEmitter emitter = mock(SseEmitter.class);

        String told = read(emitter, ChatTool.BUNDLE_FILES, Map.of("machine", "Colina 27", "paths", "/home/geir/gone.jpg"));

        assertThat(told).isEqualTo("/home/geir/gone.jpg is not on Colina 27.");
        assertThat(sentEvents(emitter)).noneSatisfy(event -> assertThat(event).contains("event:bundle"));
    }

    /** The card's link: the bundle streamed as a zip under its own name, like any Explorer download. */
    @Test
    void downloadingABundle_streamsTheZipUnderItsName() {
        when(openBundleUseCase.open("b1")).thenReturn(new Download("pictures-2025-09-10.zip", -1,
            "application/zip", out -> { }));

        ResponseEntity<StreamingResponseBody> response = controller.bundle("b1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
            .isEqualTo("attachment; filename=\"pictures-2025-09-10.zip\"");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1);
    }

    /** A length the service knows is told to the browser, so it can show how far along the download is. */
    @Test
    void downloadingABundle_tellsTheBrowserTheLengthWhenItIsKnown() {
        when(openBundleUseCase.open("b2")).thenReturn(new Download("pictures.zip", 302_000_000L,
            "application/zip", out -> { }));

        ResponseEntity<StreamingResponseBody> response = controller.bundle("b2");

        assertThat(response.getHeaders().getContentLength()).isEqualTo(302_000_000L);
    }

    // --- memory: what Vaier keeps across conversations (#360) --------------------------------------------

    /** The pane shows every memory, so nothing can be planted in it unseen; each one can be removed. */
    @Test
    void memory_isListedForThePane_andRemovableFromIt() {
        when(getMemoryUseCase.getMemory()).thenReturn(Memory.empty().remember("Photos live under /volume1/photo.", NOW));

        ChatRestController.MemoryResponse response = controller.memory().getBody();

        assertThat(response.facts()).extracting(ChatRestController.FactResponse::text)
            .containsExactly("Photos live under /volume1/photo.");
        assertThat(response.facts().get(0).id()).matches("[a-z0-9]{6}");

        assertThat(controller.forgetFact("ab12cd").getStatusCode().value()).isEqualTo(204);
        verify(forgetUseCase).forget("ab12cd");
    }

    // --- spend: the figure in the top bar (#360) ---------------------------------------------------------

    @Test
    void spend_isThisMonthsFigure_withTheTokensBehindIt() {
        when(getSpendUseCase.thisMonth()).thenReturn(Spend.empty()
            .record(new ModelUsage("claude-opus-5", 1_000_000, 100_000, 200_000, 3_000_000), YearMonth.of(2026, 9))
            .month(YearMonth.of(2026, 9)));

        ChatRestController.SpendResponse response = controller.spend().getBody();

        assertThat(response.month()).isEqualTo("2026-09");
        assertThat(response.figure()).isEqualTo("$10.25");
        assertThat(response.calls()).isEqualTo(1);
        assertThat(response.inputTokens()).isEqualTo(1_000_000);
        assertThat(response.outputTokens()).isEqualTo(100_000);
        assertThat(response.cacheReadTokens()).isEqualTo(3_000_000);
    }

    // --- email_bundle: the link by mail, to the operator who asked (#360) ----------------------------------

    @Test
    void mailingABundle_goesToTheOperatorWhoAsked_andSaysSo() {
        answering("ok");
        when(emailBundleUseCase.email("b1", GEIR)).thenReturn("geir@example.com");

        assertThat(read(ChatTool.EMAIL_BUNDLE, Map.of("id", "b1")))
            .isEqualTo("Mailed a link to geir@example.com; it works for a day.");
    }

    @Test
    void mailingABundle_thatIsGone_orWithoutMailSetUp_isSaidInWords() {
        answering("ok");
        when(emailBundleUseCase.email("gone", GEIR)).thenThrow(new NotFoundException("That download is gone; ask again."));
        assertThat(read(ChatTool.EMAIL_BUNDLE, Map.of("id", "gone"))).isEqualTo("That download is gone; ask again.");

        when(emailBundleUseCase.email("b2", GEIR))
            .thenThrow(new IllegalArgumentException("Vaier could not send mail; check the SMTP settings."));
        assertThat(read(ChatTool.EMAIL_BUNDLE, Map.of("id", "b2")))
            .isEqualTo("Vaier could not send mail; check the SMTP settings.");

        // A server that would not take it just now is said as that — never as a settings problem.
        when(emailBundleUseCase.email("b3", GEIR)).thenThrow(new MailNotSentException());
        assertThat(read(ChatTool.EMAIL_BUNDLE, Map.of("id", "b3")))
            .isEqualTo("The mail server would not take the mail just now; ask again in a minute.");
    }

    // --- errands: what Marvin is sent off to do later (#360 slice 2) ------------------------------------

    private static Errand anErrand() {
        Rhythm rhythm = Rhythm.parse("daily 08:00");
        return Errand.builder().id("ab12cd").operator(GEIR)
            .instruction("Tell me if any machine has operating system updates.")
            .rhythm(rhythm).nextDue(Instant.parse("2026-09-11T06:00:00Z"))
            .createdAtEpochMs(NOW).build();
    }

    /** The tool answers in the domain's own sentence, so Marvin can repeat it to the operator. */
    @Test
    void addingAnErrand_answersWithTheDomainsOwnSentence() {
        answering("ok");
        when(addErrandUseCase.add(GEIR, "Tell me if any machine has operating system updates.", "daily 08:00"))
            .thenReturn(anErrand());

        assertThat(read(ChatTool.ADD_ERRAND, Map.of(
            "instruction", "Tell me if any machine has operating system updates.", "rhythm", "daily 08:00")))
            .isEqualTo("Added [ab12cd]: Every day at 08:00 — Tell me if any machine has operating system "
                + "updates.");
    }

    /** A rhythm the domain will not read comes back as its refusal, naming the shapes, and nothing is kept. */
    @Test
    void addingAnErrandWithARhythmTheDomainWillNotRead_isRefusedInItsOwnWords() {
        answering("ok");
        when(addErrandUseCase.add(any(), anyString(), anyString()))
            .thenThrow(new IllegalArgumentException(Rhythm.SHAPES));

        assertThat(read(ChatTool.ADD_ERRAND, Map.of("instruction", "anything", "rhythm", "every blue moon")))
            .contains("once 2026-09-12T08:00").contains("monthly 1 08:00");
    }

    @Test
    void cancellingAnErrand_cancelsTheOperatorsOwn_orSaysItWasNeverThere() {
        answering("ok");

        assertThat(read(ChatTool.CANCEL_ERRAND, Map.of("id", "ab12cd"))).isEqualTo("Cancelled.");
        verify(cancelErrandUseCase).cancel(GEIR, "ab12cd");

        doThrow(new NotFoundException("Vaier has no errand with the id nope."))
            .when(cancelErrandUseCase).cancel(GEIR, "nope");
        assertThat(read(ChatTool.CANCEL_ERRAND, Map.of("id", "nope")))
            .isEqualTo("Vaier has no errand with the id nope.");
    }

    /** The dialog lists this operator's own, with the times carrying their offset for the reader's own zone. */
    @Test
    void errands_areListedForTheDialog_withTheirRhythmInWords() {
        when(getErrandsUseCase.getErrands(GEIR)).thenReturn(List.of(anErrand()));

        List<ChatRestController.ErrandResponse> listed = controller.errands(EMAIL).getBody();

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).id()).isEqualTo("ab12cd");
        assertThat(listed.get(0).rhythm()).isEqualTo("Every day at 08:00");
        assertThat(listed.get(0).instruction()).isEqualTo("Tell me if any machine has operating system updates.");
        assertThat(listed.get(0).nextDue()).startsWith("2026-09-11T").contains(":00");
        assertThat(listed.get(0).lastRunAt()).isNull();
        assertThat(listed.get(0).lastOutcome()).isNull();
    }

    @Test
    void anErrandThatHasRun_saysWhenAndHowItWent() {
        when(getErrandsUseCase.getErrands(GEIR)).thenReturn(List.of(anErrand().toBuilder()
            .lastRunAtEpochMs(Instant.parse("2026-09-10T06:00:00Z").toEpochMilli())
            .lastOutcome("reported").build()));

        ChatRestController.ErrandResponse listed = controller.errands(EMAIL).getBody().get(0);

        assertThat(listed.lastRunAt()).startsWith("2026-09-10T");
        assertThat(listed.lastOutcome()).isEqualTo("reported");
    }

    /** The operator's last word on what Marvin keeps doing. */
    @Test
    void errands_areCancelledFromTheDialog() {
        assertThat(controller.cancelErrand(EMAIL, "ab12cd").getStatusCode().value()).isEqualTo(204);

        verify(cancelErrandUseCase).cancel(GEIR, "ab12cd");
    }

    /** The push that lets the pane show a report nobody asked for, without ever polling for it. */
    @Test
    void thePane_subscribesToTheChatTopicForErrandReports() {
        SseEmitter subscription = new SseEmitter();
        when(forSubscribingToEvents.subscribe("chat")).thenReturn(subscription);

        assertThat(controller.events()).isSameAs(subscription);
    }

    // --- fixtures and plumbing -------------------------------------------------------------------------

    /** A one-machine fleet, so a card and a bundle have a machine to name. */
    private void fleetOf() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET", "10.13.13.3/32",
            "77.16.1.2", "51820", String.valueOf(System.currentTimeMillis() / 1000 - 30), "1.2 GiB", "3.4 GiB",
            "192.168.1.0/24", "192.168.1.10",
            true, 2375, DeviceCategory.SERVER, null)));
    }

    /** Answers the given chunks, so the tool offers are captured on a path that actually completes. */
    private void answering(String... chunks) {
        // The reads Marvin may make alone come from ChatReads — stubbed as itself answering, since this
        // controller's job is only to merge them with its own four and announce every one of them.
        when(chatReads.offers()).thenReturn(ChatTool.whileNobodyIsWatching().stream()
            .map(tool -> new ToolOffer(tool, () -> "[]"))
            .toList());
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(3);
            for (String chunk : chunks) {
                onText.accept(chunk);
            }
            return null;
        }).when(chatUseCase).ask(any(), anyString(), anyList(), any());
    }

    private List<ToolOffer> offeredTools() {
        ArgumentCaptor<List<ToolOffer>> tools = ArgumentCaptor.forClass(List.class);
        verify(chatUseCase, atLeastOnce()).ask(any(), anyString(), tools.capture(), any());
        return tools.getValue();
    }

    /** Runs one question and reads back what the named tool would answer with. */
    private String read(ChatTool tool) {
        controller.answer(mock(SseEmitter.class), GEIR, "anything?");
        return offeredTools().stream()
            .filter(offer -> offer.tool() == tool)
            .findFirst().orElseThrow()
            .read().apply(Map.of());
    }

    /** As {@link #read(ChatTool)}, with what the model said. */
    private String read(ChatCapability tool, Map<String, String> args) {
        return read(mock(SseEmitter.class), tool, args);
    }

    /** As above, on an emitter the test keeps, so what a tool sent the pane can be read back. */
    private String read(SseEmitter emitter, ChatCapability tool, Map<String, String> args) {
        controller.answer(emitter, GEIR, "anything?");
        return offeredTools().stream()
            .filter(offer -> offer.tool() == tool)
            .findFirst().orElseThrow()
            .read().apply(args);
    }

    /** Every event the emitter was sent, exactly as it goes on the wire. */
    private List<String> sentEvents(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEventBuilder> events = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(events.capture());
        return events.getAllValues().stream().map(ChatRestControllerTest::render).toList();
    }

    private static String render(SseEventBuilder event) {
        return event.build().stream()
            .map(DataWithMediaType::getData)
            .map(String::valueOf)
            .collect(Collectors.joining());
    }

    /**
     * Only a refusal the domain worded reaches the pane verbatim. An unexpected failure's message can carry
     * a host, a path or a credential — the same reason {@code GlobalExceptionHandler} never returns one —
     * so it is logged and answered in Vaier's own words.
     */
    @Test
    void answer_neverRepeatsAnUnexpectedFailuresOwnMessage() throws IOException {
        doThrow(new IllegalStateException("connect to 10.13.13.3:8022 as borg failed"))
            .when(chatUseCase).ask(any(), anyString(), anyList(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, GEIR, "anything?");

        assertThat(sentEvents(emitter))
            .containsExactly("event:error\ndata:Vaier could not answer that.\n\n");
    }

    @Test
    void ask_withoutAKey_isRefusedBeforeAnyStreamOpens() {
        // A missing key is a 409 on the request, not a stream that opens only to say no.
        when(isChatAvailableUseCase.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> controller.ask(EMAIL, new ChatRestController.AskRequest("anything?")))
            .isInstanceOf(ChatUnavailableException.class);
        verifyNoInteractions(chatUseCase);
    }
}
