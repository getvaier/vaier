package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDueErrandsUseCase;
import net.vaier.application.RunErrandUseCase;
import net.vaier.domain.Errand;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs each <b>errand</b> when its time comes, and nothing else.
 *
 * <p>It is a driving adapter, exactly as {@link ImageUpdateWatcher} and {@link BackupRunner} are: the actor
 * driving it is a clock rather than a browser, so it lives in {@code rest/} and calls {@code *UseCase}s
 * freely. It is emphatically not a service, and it decides nothing — which errands have come round is the
 * domain's verdict ({@code Errands.due}), what Marvin is told is {@code ChatPrompt}'s, and whether there is
 * anything worth mailing is {@code ErrandReport}'s.
 *
 * <p><b>Every minute, and each errand on its own.</b> A minute is fine enough that "at 08:00" means 08:00,
 * and the sweep costs one file read when nothing is due. One errand that throws must not take the sweep down
 * with it: the next errand is a different operator's, and a failure that silently stopped every other watch
 * would be the worst kind of bug here — nobody is watching, so nobody would notice.
 */
@Component
@Slf4j
public class ErrandRunner {

    /** A minute: fine enough that the operator's "at 08:00" is 08:00, cheap enough to do all day. */
    private static final long EVERY_MINUTE_MS = 60_000L;

    private final GetDueErrandsUseCase getDueErrandsUseCase;
    private final RunErrandUseCase runErrandUseCase;
    private final ChatReads chatReads;

    public ErrandRunner(GetDueErrandsUseCase getDueErrandsUseCase, RunErrandUseCase runErrandUseCase,
                        ChatReads chatReads) {
        this.getDueErrandsUseCase = getDueErrandsUseCase;
        this.runErrandUseCase = runErrandUseCase;
        this.chatReads = chatReads;
    }

    @Scheduled(fixedDelay = EVERY_MINUTE_MS, initialDelay = EVERY_MINUTE_MS)
    public void runDueErrands() {
        List<Errand> due;
        try {
            due = getDueErrandsUseCase.due();
        } catch (Exception e) {
            // Even asking may fail — an unreadable file, a disk gone. A dead schedule would be the worse bug:
            // nobody is watching an errand, so nobody would notice it had stopped.
            log.warn("Vaier could not read which errands are due: {}", e.toString());
            return;
        }
        for (Errand errand : due) {
            try {
                runErrandUseCase.run(errand, chatReads.offers());
            } catch (Exception e) {
                // A missing key, a dead API, an unreachable machine: none of them may stop the next errand,
                // which belongs to somebody else.
                log.warn("Marvin could not run the errand {}: {}", errand.id(), e.toString());
            }
        }
    }
}
