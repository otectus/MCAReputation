package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ReputationIncidentView;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T23: a deed carries the time it <em>happened</em>, not the time it was filed (§5 F10, DD4).
 *
 * <p>A producer that recovers a case after the fact delivers it late. What the village then feels is
 * the deed's present weight — aged for the interval that has already passed — and the ledger reads in
 * the order things happened, not in the order they arrived.
 */
class BackdatedDeliveryTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private final TestServiceContext ctx = new TestServiceContext();

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
    }

    /** -40, half of it gone after a day: a deed a day late is worth -20 when it lands. */
    private void defineDecaying() {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-40, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 20))));
    }

    private ReputationResult record(long occurredAt) {
        return ReputationService.recordWith(ctx, new ReputationRequest(null, TestFixtures.PLAYER_A, HOME,
                TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), occurredAt));
    }

    private CommunityReputationRecord home() {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(HOME).orElseThrow();
    }

    @Test
    void aDeedDeliveredADayLateContributesItsPresentValue() {
        defineDecaying();
        ctx.gameTime = DAY;
        ReputationResult live = record(DAY);
        assertEquals(-40, live.newScore(), "the live deed is worth its full delta");

        ctx.posted.clear();
        ReputationResult late = record(0L);

        assertEquals(-20, late.appliedDelta(), "one day of decay was already spent when it arrived");
        assertEquals(-60, late.newScore());
        assertEquals(-60, home().score());
        assertEquals(DAY, home().lastReconciledGameTime(), "the community clock never regresses");

        IncidentRecord backdated = home().incident(late.incidentId().orElseThrow()).orElseThrow();
        assertEquals(0L, backdated.createdGameTime(), "occurrence time is the time it happened");
        assertEquals(DAY, backdated.appliedGameTime(), "application time is when it entered the ledger");
        assertEquals(-20, backdated.currentContribution());

        for (ReputationTierChangedEvent tier : ctx.posted(ReputationTierChangedEvent.class)) {
            assertEquals(ReputationService.currentTierId(-60), tier.newTierId(),
                    "any tier event describes the aged value, not the value it once had");
        }
    }

    @Test
    void historyReadsByOccurrenceNotByArrival() {
        defineDecaying();
        ctx.gameTime = DAY;
        UUID newer = record(DAY).incidentId().orElseThrow();
        UUID older = record(0L).incidentId().orElseThrow();

        List<ReputationIncidentView> views =
                ReputationService.recentIncidentsWith(ctx, TestFixtures.PLAYER_A, HOME, 10, DAY);

        assertEquals(2, views.size());
        assertEquals(newer, views.get(0).id(), "the deed that happened later is listed first");
        assertEquals(older, views.get(1).id());
    }

    @Test
    void aFutureOccurrenceTimeClampsToNow() {
        defineDecaying();
        ctx.gameTime = DAY;

        ReputationResult result = record(5 * DAY);

        IncidentRecord incident = home().incident(result.incidentId().orElseThrow()).orElseThrow();
        assertEquals(DAY, incident.createdGameTime(), "a clock ahead of the world's is clamped to now");
        assertEquals(DAY, incident.appliedGameTime());
        assertEquals(-40, incident.currentContribution(), "and nothing is aged backwards");
        assertTrue(result.applied());
    }
}
