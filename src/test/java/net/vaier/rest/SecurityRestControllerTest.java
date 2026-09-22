package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.GetAccessSourcesUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetTrustedAddressesUseCase;
import net.vaier.application.GetTrustedNetworksUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UntrustAddressUseCase;
import net.vaier.domain.AccessSource;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.BlockNotLiftedException;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityRestControllerTest {

    private static final BlockDecision PLACED = BlockDecision.builder()
        .id(32L).scenario("crowdsecurity/http-probing").sourceIp("195.178.110.155").type("ban")
        .duration("3h0m40s").country("BG").asnOrg("Techoff Srv Limited")
        .latitude(42.696).longitude(23.332).build();

    /** A source neither CrowdSec nor Vaier's own database could place: no coordinates at all. */
    private static final BlockDecision UNPLACED = BlockDecision.builder()
        .id(40L).scenario("crowdsecurity/ssh-bf").sourceIp("1.2.3.4").type("ban").duration("4h0m0s")
        .build();

    GetBlockDecisionsUseCase getBlockDecisions = mock(GetBlockDecisionsUseCase.class);
    LiftBlockUseCase liftBlock = mock(LiftBlockUseCase.class);
    TrustAddressUseCase trustAddress = mock(TrustAddressUseCase.class);
    GetTrustedAddressesUseCase getTrustedAddresses = mock(GetTrustedAddressesUseCase.class);
    UntrustAddressUseCase untrustAddress = mock(UntrustAddressUseCase.class);
    ForPublishingEvents forPublishingEvents = mock(ForPublishingEvents.class);
    ForSubscribingToEvents forSubscribingToEvents = mock(ForSubscribingToEvents.class);
    GetAccessSourcesUseCase getAccessSources = mock(GetAccessSourcesUseCase.class);

    SecurityRestController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new SecurityRestController(getBlockDecisions, liftBlock, trustAddress,
            getTrustedAddresses, untrustAddress, getAccessSources, forPublishingEvents,
            forSubscribingToEvents, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getDecisions_returnsTheActiveBlockDecisions() throws Exception {
        when(getBlockDecisions.getBlockDecisions()).thenReturn(List.of(PLACED));

        mvc.perform(get("/security/decisions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(32))
            .andExpect(jsonPath("$[0].sourceIp").value("195.178.110.155"))
            .andExpect(jsonPath("$[0].scenario").value("crowdsecurity/http-probing"))
            .andExpect(jsonPath("$[0].type").value("ban"))
            .andExpect(jsonPath("$[0].duration").value("3h0m40s"))
            .andExpect(jsonPath("$[0].country").value("BG"))
            .andExpect(jsonPath("$[0].asnOrg").value("Techoff Srv Limited"));
    }

    /**
     * The one thing this DTO must not do is ship raw coordinates alone. {@code 0} is falsy in JavaScript, so a
     * frontend re-deriving "is this drawable?" from latitude and longitude would drop a genuine zero on one
     * axis — the equator, the prime meridian — without anyone noticing. The domain has already decided; the
     * wire carries the decision.
     */
    @Test
    void getDecisions_shipsTheDomainsLocatableDecision_notJustRawCoordinates() throws Exception {
        when(getBlockDecisions.getBlockDecisions()).thenReturn(List.of(PLACED, UNPLACED));

        mvc.perform(get("/security/decisions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].locatable").value(true))
            .andExpect(jsonPath("$[0].enriched").value(true))
            .andExpect(jsonPath("$[1].locatable").value(false))
            .andExpect(jsonPath("$[1].enriched").value(false));
    }

    /**
     * The live defect this endpoint shipped with: the first read after a container restart failed cold,
     * the read returned an empty list, and the security view rendered the most reassuring sentence Vaier
     * owns — "Nobody is blocked right now" — while cscli listed eleven active decisions. A {@code 200 []}
     * is a claim about the fleet's safety, so a read that failed must not be able to make it.
     */
    @Test
    void getDecisions_whenCrowdSecCannotBeAsked_saysSoRatherThanReportingNobodyBlocked() throws Exception {
        when(getBlockDecisions.getBlockDecisions()).thenThrow(
            new BlockDecisionsUnreadableException("Vaier could not read who CrowdSec is blocking."));

        mvc.perform(get("/security/decisions"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("BLOCK_DECISIONS_UNREADABLE"))
            .andExpect(jsonPath("$.message").value("Vaier could not read who CrowdSec is blocking."));
    }

    @Test
    void getEvents_subscribesToTheSecurityTopic() throws Exception {
        mvc.perform(get("/security/events"));

        verify(forSubscribingToEvents).subscribe(BreachAttemptWatcher.SECURITY_TOPIC);
    }

    @Test
    void deleteDecision_liftsTheBlockOnThatAddress() throws Exception {
        mvc.perform(delete("/security/decisions/195.178.110.155"))
            .andExpect(status().isOk());

        verify(liftBlock).liftBlock("195.178.110.155");
    }

    /**
     * The sweep is five minutes wide. An operator who has just let an address back in must not watch it
     * sit in the list for another five minutes wondering whether the click worked.
     */
    @Test
    void deleteDecision_pushesTheRefreshedDecisionsAtOnce() throws Exception {
        when(getBlockDecisions.getBlockDecisions()).thenReturn(List.of(UNPLACED));

        mvc.perform(delete("/security/decisions/195.178.110.155"));

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(BreachAttemptWatcher.DECISIONS_EVENT), contains("\"sourceIp\":\"1.2.3.4\""));
    }

    @Test
    void deleteDecision_whenTheUnbanFails_saysSoRatherThanReportingSuccess() throws Exception {
        doThrow(new BlockNotLiftedException("Vaier could not lift the block on 1.2.3.4"))
            .when(liftBlock).liftBlock("1.2.3.4");

        mvc.perform(delete("/security/decisions/1.2.3.4"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("BLOCK_NOT_LIFTED"));

        // Nothing changed, so nothing is pushed — a refreshed list here would read as though it had.
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    /**
     * The read is loud now, and this is the one place that must stay deaf to it: the unban already
     * happened. Failing the response here would tell the operator their click did not work when it did.
     * The next sweep repaints the list anyway.
     */
    @Test
    void deleteDecision_whenTheRefreshReadFails_stillReportsTheCompletedUnban() throws Exception {
        when(getBlockDecisions.getBlockDecisions())
            .thenThrow(new BlockDecisionsUnreadableException("CrowdSec went away right after the unban"));

        mvc.perform(delete("/security/decisions/1.2.3.4"))
            .andExpect(status().isOk());

        verify(liftBlock).liftBlock("1.2.3.4");
        verify(forPublishingEvents, never()).publish(any(), any(), any());
    }

    @Test
    void deleteDecision_rejectsAnAddressThatIsNotAnIpv4Address() throws Exception {
        doThrow(new IllegalArgumentException("Not a valid IPv4 address"))
            .when(liftBlock).liftBlock("evil.example.com");

        mvc.perform(delete("/security/decisions/evil.example.com"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(forPublishingEvents);
    }

    @Test
    void postTrustedAddress_trustsTheAddress() throws Exception {
        mvc.perform(post("/security/trusted-addresses").contentType("application/json")
                .content("{\"sourceIp\":\"195.178.110.155\"}"))
            .andExpect(status().isOk());

        verify(trustAddress).trustAddress("195.178.110.155");
    }

    @Test
    void postTrustedAddress_pushesTheRefreshedDecisionsAtOnce() throws Exception {
        when(getBlockDecisions.getBlockDecisions()).thenReturn(List.of());

        mvc.perform(post("/security/trusted-addresses").contentType("application/json")
            .content("{\"sourceIp\":\"195.178.110.155\"}"));

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(BreachAttemptWatcher.DECISIONS_EVENT), eq("[]"));
    }

    @Test
    void postTrustedAddress_rejectsAnAddressThatIsNotAnIpv4Address() throws Exception {
        doThrow(new IllegalArgumentException("Not a valid IPv4 address"))
            .when(trustAddress).trustAddress("1.2.3.4; rm -rf /");

        mvc.perform(post("/security/trusted-addresses").contentType("application/json")
                .content("{\"sourceIp\":\"1.2.3.4; rm -rf /\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(forPublishingEvents);
    }

    // --- seeing and undoing what has been trusted (#348) ----------------------------------------------

    @Test
    void getTrustedAddresses_listsWhatTheOperatorHasTrusted() throws Exception {
        when(getTrustedAddresses.getTrustedAddresses())
            .thenReturn(List.of(SourceAddress.of("195.178.110.155"), SourceAddress.of("8.8.8.8")));

        mvc.perform(get("/security/trusted-addresses"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sourceIp").value("195.178.110.155"))
            .andExpect(jsonPath("$[1].sourceIp").value("8.8.8.8"));
    }

    /**
     * The constraint #348 turns on. The response carries exactly the store's contents — the addresses a
     * person chose — and never the structural trusted networks the whitelist is also assembled from. A
     * payload that mixed the two would put a relay's LAN one click from removal, which is the operator
     * lockout {@code LockoutWarning} exists to shout about. The controller has no way to reach them:
     * {@code GetTrustedNetworksUseCase} is deliberately not a collaborator here.
     */
    @Test
    void getTrustedAddresses_neverCarriesAStructuralTrustedNetwork() throws Exception {
        when(getTrustedAddresses.getTrustedAddresses()).thenReturn(List.of(SourceAddress.of("8.8.8.8")));

        mvc.perform(get("/security/trusted-addresses"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        assertThat(SecurityRestController.class.getDeclaredFields())
            .as("no field of this controller can read the structural trusted networks")
            .noneMatch(f -> f.getType() == GetTrustedNetworksUseCase.class);
    }

    @Test
    void deleteTrustedAddress_untrustsThatAddress() throws Exception {
        mvc.perform(delete("/security/trusted-addresses/195.178.110.155"))
            .andExpect(status().isOk());

        verify(untrustAddress).untrustAddress("195.178.110.155");
    }

    /**
     * Act, then publish — the same idiom the unban uses, for the same reason: the operator must see the
     * address leave the list on the click, not on some later read.
     */
    @Test
    void deleteTrustedAddress_pushesTheRefreshedTrustedListAtOnce() throws Exception {
        when(getTrustedAddresses.getTrustedAddresses()).thenReturn(List.of(SourceAddress.of("8.8.8.8")));

        mvc.perform(delete("/security/trusted-addresses/195.178.110.155"));

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(SecurityRestController.TRUSTED_ADDRESSES_EVENT), contains("\"sourceIp\":\"8.8.8.8\""));
    }

    @Test
    void postTrustedAddress_pushesTheRefreshedTrustedListToo() throws Exception {
        when(getTrustedAddresses.getTrustedAddresses())
            .thenReturn(List.of(SourceAddress.of("195.178.110.155")));

        mvc.perform(post("/security/trusted-addresses").contentType("application/json")
            .content("{\"sourceIp\":\"195.178.110.155\"}"));

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(SecurityRestController.TRUSTED_ADDRESSES_EVENT), contains("\"sourceIp\":\"195.178.110.155\""));
    }

    /**
     * Idempotent, per #348: untrusting an address that is not in the list is the state the operator asked
     * for, so it is a success. Anything else would turn a double-click, or two admins on the same screen,
     * into an error about a decision that has already been carried out.
     */
    @Test
    void deleteTrustedAddress_thatWasNeverTrusted_isNotAnError() throws Exception {
        mvc.perform(delete("/security/trusted-addresses/8.8.8.8"))
            .andExpect(status().isOk());

        verify(untrustAddress).untrustAddress("8.8.8.8");
    }

    @Test
    void deleteTrustedAddress_rejectsAnAddressThatIsNotAnIpv4Address() throws Exception {
        doThrow(new IllegalArgumentException("Not a valid IPv4 address"))
            .when(untrustAddress).untrustAddress("evil.example.com");

        mvc.perform(delete("/security/trusted-addresses/evil.example.com"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(forPublishingEvents);
    }

    // --- GET /security/access-sources ---

    private static final Instant FIRST = Instant.parse("2026-07-10T08:00:00Z");
    private static final Instant LAST = Instant.parse("2026-08-09T12:31:00Z");

    private static final AccessSource OSLO = AccessSource.builder()
        .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
        .count(412).firstSeen(FIRST).lastSeen(LAST)
        .people(List.of("geir.eilertsen@gmail.com")).build();

    @Test
    void getAccessSources_returnsThePlacesAllowedAccessesCameFrom() throws Exception {
        when(getAccessSources.getAccessSources()).thenReturn(List.of(OSLO));

        mvc.perform(get("/security/access-sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].city").value("Oslo"))
            .andExpect(jsonPath("$[0].country").value("Norway"))
            .andExpect(jsonPath("$[0].latitude").value(59.91))
            .andExpect(jsonPath("$[0].longitude").value(10.75))
            .andExpect(jsonPath("$[0].count").value(412))
            .andExpect(jsonPath("$[0].firstSeen").value("2026-07-10T08:00:00Z"))
            .andExpect(jsonPath("$[0].lastSeen").value("2026-08-09T12:31:00Z"))
            .andExpect(jsonPath("$[0].people[0]").value("geir.eilertsen@gmail.com"));
    }

    /**
     * The place name is the domain's too, and shipped for the same reason a block decision's origin is: a
     * browser that joins city and country itself has taken a copy of the rule, and the copy is what drifts
     * — a different separator on the map from the one in a mail, and nobody notices for a year.
     */
    @Test
    void getAccessSources_namesThePlaceAsTheDomainNamesIt() throws Exception {
        when(getAccessSources.getAccessSources()).thenReturn(List.of(OSLO,
            AccessSource.firstAccessFrom(null, "geir.eilertsen@gmail.com", LAST)));

        mvc.perform(get("/security/access-sources"))
            .andExpect(jsonPath("$[0].place").value("Oslo, Norway"))
            .andExpect(jsonPath("$[1].place").value("Not placeable"));
    }

    /**
     * Shipped pre-decided, exactly like a block decision's. {@code 0} is falsy in JavaScript, so a browser
     * that re-derived this from the coordinates with {@code if (lat)} would silently delete the equator.
     */
    @Test
    void getAccessSources_shipsTheLocatableDecisionAlreadyMade() throws Exception {
        AccessSource onTheEquator = AccessSource.builder()
            .city("Quito").country("Ecuador").latitude(0.0).longitude(-78.5)
            .count(3).firstSeen(FIRST).lastSeen(LAST).people(List.of()).build();
        when(getAccessSources.getAccessSources()).thenReturn(List.of(OSLO, onTheEquator));

        mvc.perform(get("/security/access-sources"))
            .andExpect(jsonPath("$[0].locatable").value(true))
            .andExpect(jsonPath("$[1].locatable").value(true))
            .andExpect(jsonPath("$[1].latitude").value(0.0));
    }

    /** The accesses Vaier could not place are one element with nothing in it but a count. */
    @Test
    void getAccessSources_carriesTheUnplaceableBucketAsOneNullElement() throws Exception {
        when(getAccessSources.getAccessSources()).thenReturn(List.of(
            AccessSource.firstAccessFrom(null, "geir.eilertsen@gmail.com", LAST)));

        mvc.perform(get("/security/access-sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].locatable").value(false))
            .andExpect(jsonPath("$[0].city").doesNotExist())
            .andExpect(jsonPath("$[0].country").doesNotExist())
            .andExpect(jsonPath("$[0].latitude").doesNotExist())
            .andExpect(jsonPath("$[0].longitude").doesNotExist())
            .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    void getAccessSources_withNobodyLetInYet_isAnEmptyArray() throws Exception {
        when(getAccessSources.getAccessSources()).thenReturn(List.of());

        mvc.perform(get("/security/access-sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }
}
