package net.vaier.adapter.driven;

import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.BlockNotLiftedException;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGeolocatingIps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

class CrowdSecCliAdapterTest {

    private ForExecutingInContainer forExecutingInContainer;
    private ForGeolocatingIps forGeolocatingIps;
    private MutableClock clock;
    private CrowdSecCliAdapter adapter;

    /** The house pattern for a clock a test can step (see {@code RegistryV2ImageAdapterTest}). */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T18:00:00Z");

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        forExecutingInContainer = mock(ForExecutingInContainer.class);
        forGeolocatingIps = mock(ForGeolocatingIps.class);
        when(forGeolocatingIps.locate(anyString())).thenReturn(Optional.empty());
        clock = new MutableClock();
        adapter = new CrowdSecCliAdapter(forExecutingInContainer, forGeolocatingIps, clock);
    }

    private void cscliPrints(String output) {
        when(forExecutingInContainer.execute(anyString(), any(String[].class))).thenReturn(output);
    }

    @Test
    void runsCscliInTheCrowdSecContainer() {
        cscliPrints("null");

        adapter.getActiveDecisionsOrEmpty();

        verify(forExecutingInContainer).execute("crowdsec", "cscli", "decisions", "list", "-o", "json");
    }

    @Test
    void theBlockList_isReadOnceForEveryoneInsideASweep_andAgainAfterALiftOrAFailedRead() {
        // Every page load reads this list for the Map and the Security view, and each read was a cscli
        // exec into the engine's container — a second of the boot, for a list the breach sweep already
        // reads every five minutes. Inside a sweep everyone gets the sweep's answer.
        cscliPrints("null");
        adapter.getActiveDecisionsOrFail();
        adapter.getActiveDecisionsOrEmpty();
        verify(forExecutingInContainer, times(1)).execute("crowdsec", "cscli", "decisions", "list", "-o", "json");

        clock.advance(CrowdSecCliAdapter.BLOCK_LIST_MEMO);
        adapter.getActiveDecisionsOrFail();
        verify(forExecutingInContainer, times(2)).execute("crowdsec", "cscli", "decisions", "list", "-o", "json");

        // An operator who just lifted a block is looking at the list to see it gone.
        adapter.liftBlock(SourceAddress.of("203.0.113.9"));
        adapter.getActiveDecisionsOrFail();
        verify(forExecutingInContainer, times(3)).execute("crowdsec", "cscli", "decisions", "list", "-o", "json");

        // A read that failed is not an answer anyone should be handed for the rest of the sweep.
        clock.advance(CrowdSecCliAdapter.BLOCK_LIST_MEMO);
        when(forExecutingInContainer.execute(anyString(), any(String[].class)))
            .thenThrow(new RuntimeException("cscli: unable to load config"));
        assertThatThrownBy(adapter::getActiveDecisionsOrFail).isInstanceOf(BlockDecisionsUnreadableException.class);
        cscliPrints("null");
        adapter.getActiveDecisionsOrFail();
        verify(forExecutingInContainer, times(5)).execute("crowdsec", "cscli", "decisions", "list", "-o", "json");
    }

    // The real `cscli decisions list -o json` shape: an array of ALERTS, each carrying its own enriched
    // source and a nested decisions[] — not the flat decision list LAPI's /v1/decisions returns.
    @Test
    void flattensEachAlertsDecisionsAndEnrichesThemFromItsSource() {
        cscliPrints("""
            [
              {
                "id": 7,
                "scenario": "crowdsecurity/http-probing",
                "source": {
                  "as_name": "Techoff Srv Limited", "as_number": "48090", "cn": "BG",
                  "ip": "195.178.110.155", "latitude": 42.696, "longitude": 23.332,
                  "range": "195.178.110.0/24", "scope": "Ip", "value": "195.178.110.155"
                },
                "decisions": [
                  {
                    "duration": "3h0m40s", "id": 32, "origin": "crowdsec",
                    "scenario": "crowdsecurity/http-probing", "scope": "Ip", "simulated": false,
                    "type": "ban", "value": "195.178.110.155"
                  },
                  {
                    "duration": "4h0m0s", "id": 33, "origin": "crowdsec",
                    "scenario": "crowdsecurity/http-crawl-non_statics", "scope": "Ip",
                    "simulated": false, "type": "ban", "value": "195.178.110.155"
                  }
                ]
              },
              {
                "id": 8,
                "source": {
                  "as_name": "MICROSOFT-CORP-MSN-AS-BLOCK", "cn": "NO", "scope": "Ip",
                  "value": "20.100.187.180"
                },
                "decisions": [
                  {
                    "duration": "3h59m48s", "id": 34, "origin": "crowdsec",
                    "scenario": "crowdsecurity/http-probing", "scope": "Ip", "type": "ban",
                    "value": "20.100.187.180"
                  }
                ]
              }
            ]
            """);

        List<BlockDecision> decisions = adapter.getActiveDecisionsOrEmpty();

        assertThat(decisions).containsExactly(
            BlockDecision.builder().id(32L).scenario("crowdsecurity/http-probing")
                .sourceIp("195.178.110.155").type("ban").duration("3h0m40s")
                .country("BG").asnOrg("Techoff Srv Limited").latitude(42.696).longitude(23.332).build(),
            BlockDecision.builder().id(33L).scenario("crowdsecurity/http-crawl-non_statics")
                .sourceIp("195.178.110.155").type("ban").duration("4h0m0s")
                .country("BG").asnOrg("Techoff Srv Limited").latitude(42.696).longitude(23.332).build(),
            BlockDecision.builder().id(34L).scenario("crowdsecurity/http-probing")
                .sourceIp("20.100.187.180").type("ban").duration("3h59m48s")
                .country("NO").asnOrg("MICROSOFT-CORP-MSN-AS-BLOCK").build());
    }

    @Test
    void anUnenrichedSourceStillYieldsTheDecisionWithoutCountryOrNetwork() {
        cscliPrints("""
            [
              {
                "id": 9,
                "source": { "cn": "", "as_name": "", "latitude": 0, "longitude": 0,
                            "scope": "Ip", "value": "192.168.1.10" },
                "decisions": [
                  { "duration": "1h0m0s", "id": 40, "scenario": "manual 'ban' from 'vaier'",
                    "scope": "Ip", "type": "ban", "value": "192.168.1.10" }
                ]
              }
            ]
            """);

        List<BlockDecision> decisions = adapter.getActiveDecisionsOrEmpty();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.sourceIp()).isEqualTo("192.168.1.10");
            assertThat(decision.country()).isNull();
            assertThat(decision.asnOrg()).isNull();
            // 0/0 is how CrowdSec's wire format says "could not place" — a patch of Atlantic off Ghana.
            // That is wire knowledge, so it is read as no coordinates here and the domain never meets it.
            assertThat(decision.latitude()).isNull();
            assertThat(decision.longitude()).isNull();
            assertThat(decision.locatable()).isFalse();
        });
    }

    // A genuine zero on ONE axis is a real place — the equator and the prime meridian both run through
    // inhabited land — so only the 0/0 pair is the sentinel.
    @Test
    void aZeroOnOneAxisAloneIsAReadCoordinate() {
        cscliPrints("""
            [
              {
                "id": 9,
                "source": { "cn": "GH", "latitude": 0, "longitude": -0.19, "scope": "Ip", "value": "1.2.3.4" },
                "decisions": [
                  { "duration": "1h0m0s", "id": 41, "scenario": "crowdsecurity/http-probing",
                    "scope": "Ip", "type": "ban", "value": "1.2.3.4" }
                ]
              }
            ]
            """);

        assertThat(adapter.getActiveDecisionsOrEmpty()).singleElement()
            .satisfies(decision -> assertThat(decision.locatable()).isTrue());
    }

    // #347: the same database that places machines places blocked addresses; CrowdSec keeps the ASN.
    @Test
    void decisionsArePlacedByVaiersOwnDatabase_withTheNetworkStillFromCrowdSec() {
        when(forGeolocatingIps.locate("195.178.110.155"))
            .thenReturn(Optional.of(new GeoLocation(42.7, 23.3, "Sofia", "Bulgaria")));
        cscliPrints("""
            [
              {
                "id": 9,
                "source": { "cn": "", "as_name": "Techoff Srv Limited", "latitude": 0, "longitude": 0,
                            "scope": "Ip", "value": "195.178.110.155" },
                "decisions": [
                  { "duration": "1h0m0s", "id": 42, "scenario": "crowdsecurity/http-probing",
                    "scope": "Ip", "type": "ban", "value": "195.178.110.155" }
                ]
              }
            ]
            """);

        assertThat(adapter.getActiveDecisionsOrFail()).singleElement().satisfies(decision -> {
            assertThat(decision.latitude()).isEqualTo(42.7);
            assertThat(decision.longitude()).isEqualTo(23.3);
            assertThat(decision.origin()).isEqualTo("Bulgaria · Techoff Srv Limited");
            assertThat(decision.locatable()).isTrue();
        });
    }

    // A coordinate CrowdSec wrote as something other than a number must cost the decision its place on
    // the map — not its place in the sweep.
    @Test
    void anUnreadableCoordinateLeavesTheDecisionInTheSweepButOffTheMap() {
        cscliPrints("""
            [
              {
                "id": 11,
                "source": { "cn": "BG", "latitude": "not-a-number", "scope": "Ip",
                            "value": "195.178.110.155" },
                "decisions": [
                  { "duration": "3h0m40s", "id": 60, "scenario": "crowdsecurity/http-probing",
                    "scope": "Ip", "type": "ban", "value": "195.178.110.155" }
                ]
              }
            ]
            """);

        List<BlockDecision> decisions = adapter.getActiveDecisionsOrEmpty();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.id()).isEqualTo(60L);
            assertThat(decision.country()).isEqualTo("BG");
            assertThat(decision.latitude()).isNull();
            assertThat(decision.longitude()).isNull();
        });
    }

    // The common case on a quiet stack: cscli prints the literal word null, not an empty array.
    @Test
    void aLiteralNullReadsAsNoActiveDecisions() {
        cscliPrints("null\n");

        assertThat(adapter.getActiveDecisionsOrEmpty()).isEmpty();
    }

    @Test
    void nonJsonOutputReadsAsNoActiveDecisions() {
        cscliPrints("Error: unable to load config\n");

        assertThat(adapter.getActiveDecisionsOrEmpty()).isEmpty();
    }

    @Test
    void anExecFailureReadsAsNoActiveDecisionsNeverThrows() {
        when(forExecutingInContainer.execute(anyString(), any(String[].class)))
            .thenThrow(new RuntimeException("no such container: crowdsec"));

        assertThat(adapter.getActiveDecisionsOrEmpty()).isEmpty();
    }

    // --- the loud read, for the operator's screen (#329 Slice 3) ------------------------------------
    //
    // Same command, opposite contract to the three tests above. Reproduced live: the first read after a
    // container restart failed cold while cscli listed eleven active decisions, and the security view said
    // "nobody is blocked right now".

    @Test
    void getActiveDecisionsOrFail_whenTheExecFails_saysSoRatherThanReportingNothingBlocked() {
        when(forExecutingInContainer.execute(anyString(), any(String[].class)))
            .thenThrow(new RuntimeException("no such container: crowdsec"));

        assertThatThrownBy(() -> adapter.getActiveDecisionsOrFail())
            .isInstanceOf(BlockDecisionsUnreadableException.class)
            .hasMessageContaining("CrowdSec");
    }

    @Test
    void getActiveDecisionsOrFail_whenCscliPrintsAnErrorInsteadOfJson_saysSoRatherThanReportingNothingBlocked() {
        cscliPrints("Error: unable to load config\n");

        assertThatThrownBy(() -> adapter.getActiveDecisionsOrFail())
            .isInstanceOf(BlockDecisionsUnreadableException.class);
    }

    // The quiet stack is not a failure: cscli answered, and its answer was "nobody". That has to stay an
    // ordinary empty list, or the view would show an error every day nothing is attacking the fleet.
    @Test
    void getActiveDecisionsOrFail_onAQuietStack_readsTheLiteralNullAsNothingBlocked() {
        cscliPrints("null\n");

        assertThat(adapter.getActiveDecisionsOrFail()).isEmpty();
    }

    @Test
    void getActiveDecisionsOrFail_readsTheSameDecisionsAsTheSilentRead() {
        cscliPrints("""
            [
              {
                "id": 7,
                "source": { "as_name": "Techoff Srv Limited", "cn": "BG", "latitude": 42.696,
                            "longitude": 23.332, "scope": "Ip", "value": "195.178.110.155" },
                "decisions": [
                  { "duration": "3h0m40s", "id": 60, "scenario": "crowdsecurity/http-probing",
                    "scope": "Ip", "type": "ban", "value": "195.178.110.155" }
                ]
              }
            ]
            """);

        assertThat(adapter.getActiveDecisionsOrFail())
            .isEqualTo(adapter.getActiveDecisionsOrEmpty())
            .singleElement()
            .satisfies(decision -> {
                assertThat(decision.id()).isEqualTo(60L);
                assertThat(decision.sourceIp()).isEqualTo("195.178.110.155");
                assertThat(decision.country()).isEqualTo("BG");
            });
    }

    // --- lifting a block (#329 Slice 3c) ------------------------------------------------------------

    @Test
    void liftBlock_deletesTheDecisionsOnThatAddressAsSeparateArguments() {
        cscliPrints("1 decision(s) deleted\n");

        adapter.liftBlock(SourceAddress.of("195.178.110.155"));

        // Every part its own array element: the address can never be read as anything but one argument,
        // whatever it contains. No shell, no string concatenation.
        verify(forExecutingInContainer)
            .execute("crowdsec", "cscli", "decisions", "delete", "-i", "195.178.110.155");
    }

    // Verified live: on an address that is not banned, cscli prints "0 decision(s) deleted" and exits
    // happily. Asking to unblock something already unblocked is the operator getting what they asked for.
    @Test
    void liftBlock_onAnAddressThatIsNotBannedIsAHarmlessNoOp() {
        cscliPrints("0 decision(s) deleted\n");

        assertThatCode(() -> adapter.liftBlock(SourceAddress.of("1.2.3.4"))).doesNotThrowAnyException();
    }

    /**
     * The deliberate opposite of the read path above. A read that fails reads as "no active decisions";
     * an unban that fails must never read as success, because the operator is waiting to be told whether
     * the address is back in.
     */
    @Test
    void liftBlock_reportsAFailureInsteadOfSwallowingIt() {
        when(forExecutingInContainer.execute(anyString(), any(String[].class)))
            .thenThrow(new RuntimeException("no such container: crowdsec"));

        assertThatThrownBy(() -> adapter.liftBlock(SourceAddress.of("1.2.3.4")))
            .isInstanceOf(BlockNotLiftedException.class)
            .hasMessageContaining("1.2.3.4");
    }

    // One unreadable alert must not cost the whole sweep — the same per-entry tolerance
    // DiskWatchFileAdapter applies when loading its file.
    @Test
    void aMalformedAlertIsSkippedWithoutLosingTheRestOfTheSweep() {
        cscliPrints("""
            [
              { "id": 1, "source": { "cn": "BG" }, "decisions": "not-an-array" },
              { "id": 2, "source": { "cn": "BG" } },
              { "id": 3, "source": { "cn": "US" }, "decisions": [ "not-an-object" ] },
              { "id": 4, "source": { "cn": "US" }, "decisions": [
                  { "duration": "2h0m0s", "scenario": "crowdsecurity/http-probing",
                    "type": "ban", "value": "5.6.7.8" } ] },
              { "id": 5, "source": { "cn": "US", "as_name": "Some Net" }, "decisions": [
                  { "duration": "2h0m0s", "id": 50, "scenario": "crowdsecurity/http-probing",
                    "type": "ban", "value": "5.6.7.8" } ] }
            ]
            """);

        List<BlockDecision> decisions = adapter.getActiveDecisionsOrEmpty();

        assertThat(decisions).containsExactly(
            BlockDecision.builder().id(50L).scenario("crowdsecurity/http-probing").sourceIp("5.6.7.8")
                .type("ban").duration("2h0m0s").country("US").asnOrg("Some Net").build());
    }
}
