package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.GetTrustedNetworksUseCase;
import net.vaier.application.NotifyAdminsOfBreachAttemptUseCase;
import net.vaier.application.NotifyAdminsOfLockoutWarningUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.BreachAttemptRollup;
import net.vaier.domain.LockoutWarning;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BreachAttemptWatcherTest {

    /** Blind internet scanning — the overwhelming majority of what the edge blocks. Never mailed. */
    private static final BlockDecision SCANNER = BlockDecision.builder()
        .id(1L).scenario("crowdsecurity/http-probing").sourceIp("1.2.3.4").type("ban").duration("3h59m48s")
        .build();

    /** Somebody grinding SSH logins — targeted, actionable, and still worth waking an admin for. */
    private static final BlockDecision CREDENTIAL_ATTACK = BlockDecision.builder()
        .id(2L).scenario("crowdsecurity/ssh-bf").sourceIp("5.6.7.8").type("ban").duration("4h0m0s")
        .build();

    /** A ban on the operator's own VPN peer: the allowlist has failed and they are about to be locked out. */
    private static final BlockDecision OWN_VPN_PEER = BlockDecision.builder()
        .id(3L).scenario("crowdsecurity/http-probing").sourceIp("10.13.13.6").type("ban").duration("4h0m0s")
        .build();

    private static final TrustedNetworks TRUSTED =
        TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of("192.168.3.0/24"));

    ForDetectingIntrusions forDetectingIntrusions;
    NotifyAdminsOfBreachAttemptUseCase notifier;
    NotifyAdminsOfLockoutWarningUseCase lockoutNotifier;
    GetTrustedNetworksUseCase getTrustedNetworks;
    ForPublishingEvents forPublishingEvents;
    BreachAttemptWatcher watcher;

    @BeforeEach
    void setUp() {
        forDetectingIntrusions = mock(ForDetectingIntrusions.class);
        notifier = mock(NotifyAdminsOfBreachAttemptUseCase.class);
        lockoutNotifier = mock(NotifyAdminsOfLockoutWarningUseCase.class);
        getTrustedNetworks = mock(GetTrustedNetworksUseCase.class);
        when(getTrustedNetworks.getTrustedNetworks()).thenReturn(TRUSTED);
        forPublishingEvents = mock(ForPublishingEvents.class);
        watcher = new BreachAttemptWatcher(forDetectingIntrusions, notifier, lockoutNotifier,
            getTrustedNetworks, forPublishingEvents, new ObjectMapper());
    }

    // --- the inbox stays empty ----------------------------------------------------------------------

    /**
     * The operator's own live edge, day one: thirteen active decisions, every one of them blind HTTP
     * scanning. This is what "i get too many breach mails and i cannot act on them so useless" looked
     * like — twenty-four mails that first day, then more every five minutes.
     *
     * <p>Not one of them reaches the inbox now. A scanner being banned is CrowdSec working correctly, and
     * the standing rule is that a notification means something is wrong or about to go wrong. Every one of
     * these thirteen is still pushed to the Security view in the same sweep — the assertion below on
     * {@code forPublishingEvents} is half the point: silent is not invisible.
     */
    @Test
    void aSweepOfNothingButBlindScanningMailsNothingAtAll() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(theLiveThirteen());

        watcher.checkForBreachAttempts();

        verifyNoInteractions(notifier);
        verifyNoInteractions(lockoutNotifier);
        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(BreachAttemptWatcher.DECISIONS_EVENT), contains("\"sourceIp\":\"195.178.110.155\""));
    }

    /** And it stays empty, sweep after sweep, for as long as the internet keeps knocking. */
    @Test
    void repeatedSweepsOfBlindScanningNeverStartMailing() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(theLiveThirteen());

        watcher.checkForBreachAttempts();
        watcher.checkForBreachAttempts();
        watcher.checkForBreachAttempts();

        verifyNoInteractions(notifier);
        verifyNoInteractions(lockoutNotifier);
    }

    @Test
    void noActiveDecisionsNeverNotifies() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of());

        watcher.checkForBreachAttempts();

        verifyNoInteractions(notifier);
        verifyNoInteractions(lockoutNotifier);
    }

    // --- what still earns a mail --------------------------------------------------------------------

    @Test
    void aCredentialAttackOnTheFirstSweepNotifiesOnce() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty())
            .thenReturn(List.of(SCANNER, CREDENTIAL_ATTACK));

        watcher.checkForBreachAttempts();

        ArgumentCaptor<BreachAttemptRollup> rollup = ArgumentCaptor.forClass(BreachAttemptRollup.class);
        verify(notifier).notifyAdminsOfBreachAttempt(rollup.capture());
        // The scanner in the same sweep is not in the mail, only the credential attack.
        assertThat(rollup.getValue().decisions()).containsExactly(CREDENTIAL_ATTACK);
    }

    @Test
    void theSameCredentialAttackOnASecondSweepDoesNotReNotify() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(CREDENTIAL_ATTACK));
        watcher.checkForBreachAttempts();

        watcher.checkForBreachAttempts();

        verify(notifier, times(1)).notifyAdminsOfBreachAttempt(any());
    }

    // --- the lockout alarm --------------------------------------------------------------------------

    /**
     * The one genuinely predictive threat-detection mail: the allowlist has stopped protecting the
     * operator's own networks, and the console they would fix it from is next. It gets its own
     * notification — folding it into something subject-lined "Breach attempt" would tell them they are
     * under attack when in fact their own whitelist has failed.
     */
    @Test
    void aBanOnTheOperatorsOwnNetworkRaisesALockoutWarningAndNeverABreachAttempt() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(OWN_VPN_PEER));

        watcher.checkForBreachAttempts();

        ArgumentCaptor<LockoutWarning> warning = ArgumentCaptor.forClass(LockoutWarning.class);
        verify(lockoutNotifier).notifyAdminsOfLockoutWarning(warning.capture());
        assertThat(warning.getValue().decisions()).containsExactly(OWN_VPN_PEER);
        verifyNoInteractions(notifier);
    }

    /**
     * Even when the scenario itself is one that would otherwise mail. Whose address it is decides which
     * alarm this is; the scenario does not get a vote.
     */
    @Test
    void aCredentialAttackScenarioOnTheOperatorsOwnNetworkIsStillOnlyALockout() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(
            BlockDecision.builder().id(9L).scenario("crowdsecurity/ssh-bf").sourceIp("192.168.3.40")
                .type("ban").duration("4h0m0s").build()));

        watcher.checkForBreachAttempts();

        verify(lockoutNotifier).notifyAdminsOfLockoutWarning(any());
        verifyNoInteractions(notifier);
    }

    /** A standing lockout is one mail, not one every five minutes — the same rule as every other sweep. */
    @Test
    void aStandingLockoutDoesNotMailEverySweep() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(OWN_VPN_PEER));
        watcher.checkForBreachAttempts();

        watcher.checkForBreachAttempts();
        watcher.checkForBreachAttempts();

        verify(lockoutNotifier, times(1)).notifyAdminsOfLockoutWarning(any());
    }

    @Test
    void bothAlarmsCanFireInOneSweepWithoutEitherSwallowingTheOther() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty())
            .thenReturn(List.of(SCANNER, CREDENTIAL_ATTACK, OWN_VPN_PEER));

        watcher.checkForBreachAttempts();

        verify(lockoutNotifier).notifyAdminsOfLockoutWarning(any());
        verify(notifier).notifyAdminsOfBreachAttempt(any());
    }

    // --- failures inside the sweep ------------------------------------------------------------------

    /**
     * The sweep keeps the silent read, and this test exists so that nobody "fixes" the asymmetry with the
     * security view's loud read later. Mailing on a failed read would be a breach that never happened;
     * throwing would cost the sweep and, with it, the mail this watcher exists to send. Silence is the only
     * answer here that neither lies nor alarms.
     */
    @Test
    void theSweepTakesTheSilentReadSoAnOutageNeitherMailsNorThrows() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of());
        when(forDetectingIntrusions.getActiveDecisionsOrFail())
            .thenThrow(new BlockDecisionsUnreadableException("CrowdSec is unreachable"));

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();

        verify(forDetectingIntrusions, never()).getActiveDecisionsOrFail();
        verifyNoInteractions(notifier);
    }

    /**
     * Without the allowlist there is no way to tell a stranger's ban from the operator's own, so the sweep
     * reports nothing — and, crucially, does not mark the decisions as told-about. The next sweep that can
     * read the allowlist still delivers them.
     */
    @Test
    void anUnreadableAllowlistDefersTheSweepRatherThanSwallowingIt() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(CREDENTIAL_ATTACK));
        doThrow(new IllegalStateException("no peers")).when(getTrustedNetworks).getTrustedNetworks();

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();
        verifyNoInteractions(notifier);

        doReturn(TRUSTED).when(getTrustedNetworks).getTrustedNetworks();
        watcher.checkForBreachAttempts();

        verify(notifier).notifyAdminsOfBreachAttempt(any());
    }

    @Test
    void aFailedNotificationSendDoesNotPropagate() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(CREDENTIAL_ATTACK));
        doThrow(new RuntimeException("smtp down")).when(notifier).notifyAdminsOfBreachAttempt(any());

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();
    }

    /** A failing breach mail must not cost the lockout warning, which is the more urgent of the two. */
    @Test
    void aFailedBreachSendStillLetsTheLockoutWarningOut() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty())
            .thenReturn(List.of(CREDENTIAL_ATTACK, OWN_VPN_PEER));
        doThrow(new RuntimeException("smtp down")).when(notifier).notifyAdminsOfBreachAttempt(any());

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();

        verify(lockoutNotifier).notifyAdminsOfLockoutWarning(any());
    }

    @Test
    void aFailedLockoutSendDoesNotPropagate() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(OWN_VPN_PEER));
        doThrow(new RuntimeException("smtp down")).when(lockoutNotifier)
            .notifyAdminsOfLockoutWarning(any());

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();
    }

    // --- pushing the sweep to the browser (#329 Slice 3b) --------------------------------------------

    /**
     * The watcher already holds the poll result every cycle, so the security view is fed from the sweep
     * rather than from a second reader — and the browser never polls (that is the standing rule).
     */
    @Test
    void everySweepPushesTheActiveDecisionsToTheSecurityTopic() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(SCANNER));

        watcher.checkForBreachAttempts();

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(BreachAttemptWatcher.DECISIONS_EVENT), contains("\"sourceIp\":\"1.2.3.4\""));
    }

    /** Including the sweep that finds nothing: that is how a lifted block leaves the view. */
    @Test
    void aSweepWithNothingActivePushesAnEmptyListRatherThanNothingAtAll() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of());

        watcher.checkForBreachAttempts();

        verify(forPublishingEvents).publish(BreachAttemptWatcher.SECURITY_TOPIC,
            BreachAttemptWatcher.DECISIONS_EVENT, "[]");
    }

    /**
     * The pushed decision carries the domain's {@code locatable} verdict, not just raw coordinates — the
     * same shape the REST read returns, so the view cannot end up re-deriving "drawable" in JavaScript
     * where {@code 0} is falsy and a real zero on one axis would vanish.
     */
    @Test
    void thePushedDecisionCarriesTheDomainsLocatableVerdict() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(
            BlockDecision.builder().id(2L).sourceIp("5.6.7.8").type("ban").build()));

        watcher.checkForBreachAttempts();

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(BreachAttemptWatcher.DECISIONS_EVENT), contains("\"locatable\":false"));
    }

    @Test
    void aFailedPushDoesNotPropagateOutOfTheScheduledSweep() {
        when(forDetectingIntrusions.getActiveDecisionsOrEmpty()).thenReturn(List.of(CREDENTIAL_ATTACK));
        doThrow(new RuntimeException("no subscribers")).when(forPublishingEvents)
            .publish(any(), any(), any());

        assertThatCode(() -> watcher.checkForBreachAttempts()).doesNotThrowAnyException();
        // …and the notification still goes out: a broken SSE topic must not cost the operator their email.
        verify(notifier).notifyAdminsOfBreachAttempt(any());
    }

    /**
     * The shape of the real sweep the operator complained about: thirteen active bans, every scenario
     * blind HTTP scanning, several of them enriched with country and ASN.
     */
    private static List<BlockDecision> theLiveThirteen() {
        String[][] live = {
            {"crowdsecurity/http-probing", "195.178.110.155", "BG", "Techoff Srv Limited"},
            {"crowdsecurity/http-probing", "45.135.232.71", "NL", "Blue Layer Telekomunikasyon"},
            {"crowdsecurity/http-wordpress-scan", "185.220.101.4", "DE", "Zwiebelfreunde e.V."},
            {"crowdsecurity/http-wordpress-scan", "104.152.52.176", "US", "Rapid7"},
            {"crowdsecurity/http-backdoors-attempts", "89.248.165.53", "NL", "Bitsight"},
            {"crowdsecurity/http-backdoors-attempts", "162.216.150.9", "US", "Censys"},
            {"crowdsecurity/http-bad-user-agent", "205.210.31.14", "US", "Palo Alto Networks"},
            {"crowdsecurity/http-bad-user-agent", "167.94.138.60", "US", "Censys"},
            {"crowdsecurity/http-crawl-non_statics", "47.128.36.202", "SG", "Amazon"},
            {"crowdsecurity/http-crawl-non_statics", "3.129.111.220", "US", "Amazon"},
            {"crowdsecurity/http-path-traversal-probing", "80.94.95.116", null, null},
            {"crowdsecurity/http-sensitive-files", "141.98.11.32", "LT", "UAB Host Baltic"},
            {"crowdsecurity/CVE-2017-9841", "193.32.162.157", null, null}};

        List<BlockDecision> decisions = new ArrayList<>();
        for (int i = 0; i < live.length; i++) {
            decisions.add(BlockDecision.builder()
                .id((long) (100 + i)).scenario(live[i][0]).sourceIp(live[i][1]).type("ban")
                .duration("3h59m48.13179286s").country(live[i][2]).asnOrg(live[i][3]).build());
        }
        return decisions;
    }
}
