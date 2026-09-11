package net.vaier.rest;

import net.vaier.application.GetDueErrandsUseCase;
import net.vaier.application.RunErrandUseCase;
import net.vaier.domain.ChatTool;
import net.vaier.domain.Errand;
import net.vaier.domain.Operator;
import net.vaier.domain.Rhythm;
import net.vaier.domain.ToolOffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The clock-driven half of the <b>errand</b> (#360): every minute, whatever has come round, each on its own.
 * It decides nothing — which errands are due is the domain's verdict, reached through the use case — so what
 * is worth pinning is that it asks, that it hands over the reads Marvin may make alone, and that one errand's
 * failure never stops the next one. Nobody is watching, so a sweep that died quietly would stay dead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErrandRunnerTest {

    @Mock GetDueErrandsUseCase getDueErrandsUseCase;
    @Mock RunErrandUseCase runErrandUseCase;
    @Mock ChatReads chatReads;

    @InjectMocks ErrandRunner runner;

    private static final List<ToolOffer> OFFERS =
        List.of(new ToolOffer(ChatTool.FLEET, () -> "colina27 connected"));

    private static Errand errand(String id) {
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 10, 15, 59, 0, 0, ZoneId.of("Europe/Oslo"));
        Rhythm rhythm = Rhythm.parse("daily 08:00");
        return Errand.builder().id(id).operator(Operator.of("geir@example.com"))
            .instruction("Tell me if a machine has updates.").rhythm(rhythm).nextDue(rhythm.firstDue(now))
            .createdAtEpochMs(now.toInstant().toEpochMilli()).build();
    }

    @Test
    void itRunsEveryDueErrand_withTheReadsMarvinMayMakeAlone() {
        when(getDueErrandsUseCase.due()).thenReturn(List.of(errand("aaa111"), errand("bbb222")));
        when(chatReads.offers()).thenReturn(OFFERS);

        runner.runDueErrands();

        verify(runErrandUseCase).run(eq(errand("aaa111")), eq(OFFERS));
        verify(runErrandUseCase).run(eq(errand("bbb222")), eq(OFFERS));
    }

    @Test
    void nothingDueIsNothingRun() {
        when(getDueErrandsUseCase.due()).thenReturn(List.of());

        runner.runDueErrands();

        verifyNoInteractions(runErrandUseCase);
    }

    /** One errand's failure is somebody else's watch going silent, so it never stops the sweep. */
    @Test
    void oneErrandThatThrowsDoesNotStopTheNextOne() {
        when(getDueErrandsUseCase.due()).thenReturn(List.of(errand("aaa111"), errand("bbb222")));
        when(chatReads.offers()).thenReturn(OFFERS);
        doThrow(new IllegalStateException("the API would not answer"))
            .when(runErrandUseCase).run(eq(errand("aaa111")), anyList());

        runner.runDueErrands();

        verify(runErrandUseCase).run(eq(errand("bbb222")), eq(OFFERS));
    }

    /** A sweep that cannot even read the errands is a logged line, not a dead schedule. */
    @Test
    void aSweepThatCannotReadTheErrandsIsNotFatal() {
        when(getDueErrandsUseCase.due()).thenThrow(new IllegalStateException("the file is gone"));

        runner.runDueErrands();

        verify(runErrandUseCase, never()).run(any(), anyList());
    }
}
