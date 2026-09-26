package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.CallServiceUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.MailConfirmationUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.RememberActionOutcomeUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UpdateContainerImageUseCase;
import net.vaier.application.UpgradeOsUseCase;
import net.vaier.domain.ActionProposal;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.ChatAction;
import net.vaier.domain.ConflictException;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineReference;
import net.vaier.domain.MailNotSentException;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Operator;
import net.vaier.domain.PublishedServiceReference;
import net.vaier.domain.PublishedServiceReference.Candidate;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ToolOffer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every <b>Chat action</b>, wired to the use case the Explorer's own button calls. A driving-side helper like
 * {@link ChatReads}, not a service: it decides nothing. The card's click, the approval link's yes and an
 * errand's proposal all come through here, so one verb runs one way whoever said yes to it.
 */
@Component
@Slf4j
public class ChatActions {

    private final GetMachinesUseCase getMachinesUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;
    private final ApproveEnrolmentUseCase approveEnrolmentUseCase;
    private final RefuseEnrolmentUseCase refuseEnrolmentUseCase;
    private final RunBackupJobUseCase runBackupJobUseCase;
    private final UpdateContainerImageUseCase updateContainerImageUseCase;
    private final LiftBlockUseCase liftBlockUseCase;
    private final TrustAddressUseCase trustAddressUseCase;
    private final MailConfirmationUseCase mailConfirmationUseCase;
    private final UpgradeOsUseCase upgradeOsUseCase;
    private final RememberActionOutcomeUseCase rememberActionOutcomeUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final CallServiceUseCase callServiceUseCase;

    public ChatActions(GetMachinesUseCase getMachinesUseCase,
                       ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase,
                       GetBackupJobsUseCase getBackupJobsUseCase,
                       GetBackupRepositoriesUseCase getBackupRepositoriesUseCase,
                       ApproveEnrolmentUseCase approveEnrolmentUseCase,
                       RefuseEnrolmentUseCase refuseEnrolmentUseCase,
                       RunBackupJobUseCase runBackupJobUseCase,
                       UpdateContainerImageUseCase updateContainerImageUseCase,
                       LiftBlockUseCase liftBlockUseCase,
                       TrustAddressUseCase trustAddressUseCase,
                       MailConfirmationUseCase mailConfirmationUseCase,
                       UpgradeOsUseCase upgradeOsUseCase,
                       RememberActionOutcomeUseCase rememberActionOutcomeUseCase,
                       GetPublishedServicesUseCase getPublishedServicesUseCase,
                       CallServiceUseCase callServiceUseCase) {
        this.getMachinesUseCase = getMachinesUseCase;
        this.listEnrolmentRequestsUseCase = listEnrolmentRequestsUseCase;
        this.getBackupJobsUseCase = getBackupJobsUseCase;
        this.getBackupRepositoriesUseCase = getBackupRepositoriesUseCase;
        this.approveEnrolmentUseCase = approveEnrolmentUseCase;
        this.refuseEnrolmentUseCase = refuseEnrolmentUseCase;
        this.runBackupJobUseCase = runBackupJobUseCase;
        this.updateContainerImageUseCase = updateContainerImageUseCase;
        this.liftBlockUseCase = liftBlockUseCase;
        this.trustAddressUseCase = trustAddressUseCase;
        this.mailConfirmationUseCase = mailConfirmationUseCase;
        this.upgradeOsUseCase = upgradeOsUseCase;
        this.rememberActionOutcomeUseCase = rememberActionOutcomeUseCase;
        this.getPublishedServicesUseCase = getPublishedServicesUseCase;
        this.callServiceUseCase = callServiceUseCase;
    }

    /** What became of a yes: whether it ran, and the sentence to show either way. */
    public record Outcome(boolean done, String text) {}

    /**
     * Resolve what the model named to what the confirmation must say and the yes must run — the machine's id
     * beside its name, the phone's name beside its code. A name nothing has is refused in words.
     */
    public Map<String, String> canonical(ChatAction action, Map<String, String> arguments) {
        Map<String, String> canonical = new HashMap<>();
        arguments.forEach((name, value) -> canonical.put(name, value == null ? null : value.trim()));
        switch (action) {
            case LET_PHONE_IN, REFUSE_PHONE -> {
                EnrolmentRequest waiting = EnrolmentRequest.byCode(listEnrolmentRequestsUseCase.pending(),
                    canonical.get("code"));
                canonical.put("code", waiting.code());
                canonical.put("name", waiting.name());
            }
            case RUN_BACKUP, UPDATE_CONTAINER, UPGRADE_OS -> {
                Machine machine = new MachineReference(canonical.get("machine"))
                    .resolve(getMachinesUseCase.getAllMachines());
                canonical.put("machine", machine.name());
                canonical.put("machineId", machine.id().value());
            }
            case CALL_SERVICE -> {
                Candidate service = new PublishedServiceReference(canonical.get("service"))
                    .resolve(ChatReads.candidates(getPublishedServicesUseCase.getPublishedServices()));
                // Judged now, so a path that would never be sent is refused before it is proposed.
                ServiceCall call = ServiceCall.proposed(canonical.get("method"), canonical.get("path"),
                    arguments.get("body"));
                canonical.put("service", service.label());
                canonical.put("host", service.host());
                putIfPresent(canonical, "pathPrefix", service.pathPrefix());
                canonical.put("method", call.method());
                canonical.put("path", call.path());
                canonical.remove("body");
                putIfPresent(canonical, "body", call.body());
            }
            case LIFT_BLOCK, TRUST_ADDRESS -> { }
        }
        return canonical;
    }

    private static void putIfPresent(Map<String, String> arguments, String name, String value) {
        if (value != null) {
            arguments.put(name, value);
        }
    }

    /**
     * Run a proposal {@code operator} said yes to. A refusal the domain worded is shown; an unexpected failure is
     * answered in Vaier's words, because its own message can carry a host, a path or a credential. Work that
     * settles later joins that operator's thread when it does.
     */
    public Outcome run(ActionProposal proposal, Operator operator) {
        try {
            return new Outcome(true, carryOut(proposal, operator));
        } catch (IllegalArgumentException | ConflictException | NotFoundException | NoHostCredentialException refused) {
            return new Outcome(false, refused.getMessage());
        } catch (RuntimeException e) {
            log.warn("Chat could not carry out '{}': {}", proposal.sentence(), e.toString());
            return new Outcome(false, "Vaier could not do that.");
        }
    }

    /** One verb, one use case — the one the Explorer's own button calls. */
    private String carryOut(ActionProposal proposal, Operator operator) {
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
            case UPGRADE_OS -> {
                upgradeOsUseCase.upgradeOs(MachineId.of(a.get("machineId")))
                    .thenAccept(settled -> rememberActionOutcomeUseCase.remember(operator, settled.sentence()));
                yield "Installing the pending OS updates on " + a.get("machine")
                    + ". Vaier says how it went when it is done.";
            }
            case CALL_SERVICE -> callServiceUseCase.callService(operator, a.get("host"), a.get("pathPrefix"),
                    ServiceCall.proposed(a.get("method"), a.get("path"), a.get("body")))
                .outcome(a.get("service"));
        };
    }

    /**
     * Every action as an errand is offered it: resolved as the card's is, then mailed to {@code operator} as
     * a <b>Mailed confirmation</b>. Nothing runs; every way it cannot be asked is a sentence back to Marvin.
     */
    public List<ToolOffer> mailedOffers(Operator operator) {
        List<ToolOffer> offers = new ArrayList<>();
        for (ChatAction action : ChatAction.values()) {
            offers.add(new ToolOffer(action, arguments -> mail(operator, action, arguments)));
        }
        return offers;
    }

    private String mail(Operator operator, ChatAction action, Map<String, String> arguments) {
        try {
            return mailConfirmationUseCase.mail(operator, action, canonical(action, arguments)).toolResult();
        } catch (IllegalArgumentException | MailNotSentException refused) {
            return refused.getMessage();
        } catch (RuntimeException e) {
            log.warn("Marvin could not mail a proposal to {}: {}", operator.key(), e.toString());
            return "Vaier could not mail that.";
        }
    }
}
