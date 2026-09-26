package net.vaier.rest;

import net.vaier.application.CallServiceUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.MailConfirmationUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.UpgradeOsUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.MailNotSentException;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.Operator;
import net.vaier.domain.OsUpgrade;
import net.vaier.domain.ReverseProxyRoute.ServiceLocation;
import net.vaier.domain.Server.State;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;
import net.vaier.domain.ToolOffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The <b>Chat action</b>s as an errand is offered them: each one resolved exactly as the card's is, then
 * mailed to the errand's operator as a <b>Mailed confirmation</b>. The card's own dispatch is covered through
 * {@code ChatRestControllerTest}, which drives it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatActionsTest {

    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock RunBackupJobUseCase runBackupJobUseCase;
    @Mock MailConfirmationUseCase mailConfirmationUseCase;
    @Mock UpgradeOsUseCase upgradeOsUseCase;
    @Mock RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock CallServiceUseCase callServiceUseCase;

    @InjectMocks ChatActions chatActions;

    private static final MachineId COLINA = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");
    private static final Operator GEIR = Operator.of("geir@example.com");

    private void fleetOf() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET", "10.13.13.3/32",
            "77.16.1.2", "51820", "0", "1.2 GiB", "3.4 GiB", "192.168.1.0/24", "192.168.1.10",
            true, 2375, DeviceCategory.SERVER, null)));
    }

    private String propose(ChatAction action, Map<String, String> arguments) {
        List<ToolOffer> offers = chatActions.mailedOffers(GEIR);
        assertThat(offers).extracting(ToolOffer::tool).containsExactly(ChatAction.values());
        return offers.stream().filter(offer -> offer.tool() == action).findFirst().orElseThrow()
            .read().apply(arguments);
    }

    @Test
    void proposingDuringAnErrand_mailsItToTheErrandsOperator_withTheCanonicalArguments_andRunsNothing() {
        fleetOf();
        when(mailConfirmationUseCase.mail(any(), any(), any())).thenAnswer(invocation -> MailedConfirmation.mint(
            ActionProposal.propose(invocation.getArgument(1), invocation.getArgument(2), 0), GEIR, 0).confirmation());

        String told = propose(ChatAction.RUN_BACKUP, Map.of("machine", "colina 27"));

        verify(mailConfirmationUseCase).mail(eq(GEIR), eq(ChatAction.RUN_BACKUP),
            eq(Map.of("machine", "Colina 27", "machineId", COLINA.value())));
        assertThat(told).contains("Back up Colina 27 now.").contains("Nothing has happened yet");
        verifyNoInteractions(runBackupJobUseCase);
    }

    /** Every way it cannot be asked is a sentence back to Marvin, never an unexpected failure's own words. */
    @Test
    void aProposalThatCannotBeMailed_isSaidInWords() {
        fleetOf();
        assertThat(propose(ChatAction.RUN_BACKUP, Map.of("machine", "Apalveien")))
            .contains("no machine called \"Apalveien\"");
        verify(mailConfirmationUseCase, never()).mail(any(), any(), any());

        record Row(RuntimeException thrown, String said) {}
        for (Row row : new Row[] {
            new Row(new IllegalArgumentException("Mail is not set up, so the operator cannot be asked."),
                "Mail is not set up, so the operator cannot be asked."),
            new Row(new MailNotSentException(), new MailNotSentException().getMessage()),
            new Row(new IllegalStateException("smtp.example.com:587 said 535"), "Vaier could not mail that."),
        }) {
            reset(mailConfirmationUseCase);
            when(mailConfirmationUseCase.mail(any(), any(), any())).thenThrow(row.thrown());
            assertThat(propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"))).as(row.said())
                .isEqualTo(row.said());
        }
    }

    /**
     * An OS upgrade takes minutes, so the yes answers at once and the settlement joins the operator's thread
     * when it lands — the thread is where Marvin will look next time.
     */
    @Test
    void upgradingTheOs_answersAtOnce_andTheSettlementJoinsTheOperatorsThread() {
        fleetOf();
        Map<String, String> canonical = chatActions.canonical(ChatAction.UPGRADE_OS, Map.of("machine", "colina 27"));
        assertThat(canonical).containsEntry("machine", "Colina 27").containsEntry("machineId", COLINA.value());
        CompletableFuture<OsUpgrade.Settlement> settling = new CompletableFuture<>();
        when(upgradeOsUseCase.upgradeOs(COLINA)).thenReturn(settling);

        ChatActions.Outcome now = chatActions.run(ActionProposal.propose(ChatAction.UPGRADE_OS, canonical, 0), GEIR);

        assertThat(now.done()).isTrue();
        assertThat(now.text()).isEqualTo("Installing the pending OS updates on Colina 27. Vaier says how it went "
            + "when it is done.");
        verifyNoInteractions(rememberActionOutcomeUseCase);

        settling.complete(new OsUpgrade.Settlement(true, "Colina 27 installed 4 package updates.", null));
        verify(rememberActionOutcomeUseCase).remember(GEIR, "Colina 27 installed 4 package updates.");
    }

    /**
     * A write to a published service's own API: the card names the service as the operator knows it, the
     * proposal keeps its address and the whole body, and the yes is done only when the service said so.
     */
    @Test
    void callingAService_resolvesItByName_carriesTheWholeBody_andIsDoneOnlyOnSuccess() {
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of(new PublishedServiceUco(
            "openhab @ Colina 27", "openhab", COLINA.value(), "Colina 27", null, ServiceLocation.PEER_SERVER, true,
            "openhab.colina27.example.com", "10.13.13.3", 8080, State.OK, true, null, false, false, null, false,
            null, null, null, null, null, "social", false, null)));
        Map<String, String> canonical = chatActions.canonical(ChatAction.CALL_SERVICE, Map.of(
            "service", "openHAB Colina 27", "method", "post", "path", "rest/items/PoolPump", "body", "ON"));
        assertThat(canonical).isEqualTo(Map.of("service", "openhab on Colina 27",
            "host", "openhab.colina27.example.com", "method", "POST", "path", "/rest/items/PoolPump", "body", "ON"));
        ActionProposal proposal = ActionProposal.propose(ChatAction.CALL_SERVICE, canonical, 0);
        assertThat(proposal.sentence()).isEqualTo("POST to openhab on Colina 27 /rest/items/PoolPump with body \"ON\".");
        ServiceCall call = ServiceCall.proposed("POST", "/rest/items/PoolPump", "ON");

        when(callServiceUseCase.callService(eq(GEIR), eq("openhab.colina27.example.com"), isNull(), eq(call)))
            .thenReturn(new ServiceCallAnswer(200, null, new byte[0], false));
        assertThat(chatActions.run(proposal, GEIR)).isEqualTo(
            new ChatActions.Outcome(true, "openhab on Colina 27 answered 200."));

        when(callServiceUseCase.callService(eq(GEIR), eq("openhab.colina27.example.com"), isNull(), eq(call)))
            .thenReturn(new ServiceCallAnswer(404, "application/json",
                "{\"error\":\"Item PoolPump does not exist\"}".getBytes(StandardCharsets.UTF_8), false));
        assertThat(chatActions.run(proposal, GEIR)).isEqualTo(new ChatActions.Outcome(false,
            "openhab on Colina 27 answered 404: {\"error\":\"Item PoolPump does not exist\"}"));
    }
}
