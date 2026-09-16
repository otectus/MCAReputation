package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second clock, through the gate only (§12.2): what ages the profile channel, what freezes it, and
 * what a freeze costs when it is lifted.
 *
 * <p>The headline case is the one §12.2 refuses to accept a "lazy skip" answer for: profiles disabled
 * for a whole interval <b>with no intervening read</b>, then re-enabled. A record whose own clock is
 * the only bookkeeping cannot distinguish that from an interval that counted, so it pays the disabled
 * time out as catch-up aging the moment somebody looks. Every test here asserts against a control that
 * did move — the scalar clock, or an unprotected village — so a run that stopped ageing anything at
 * all would fail rather than pass vacuously.
 */
class ProfileAgingTest {

    private static final long DAY = TestFixtures.DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    /** Protected by persisted per-community immunity. */
    private static final CommunityKey SAFE = TestFixtures.NETHER_3;

    /** 8 authored bravery points in subunits, at age zero. */
    private static final long BRAVERY_FRESH = 80_000L;
    /** 6 authored recognition points in subunits, at age zero. */
    private static final long RECOGNITION_FRESH = 60_000L;

    private final TestServiceContext ctx = new TestServiceContext();

    @BeforeEach
    void setUp() {
        ctx.policy(ReputationPolicy.defaults());
        define(DecayPolicy.NONE);
        TestFixtures.publishProfile(TestFixtures.profile(null), null);
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        ProfileRegistryBundle.clear();
        McaReputationConfig.TestOverrides.reset();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A zero-delta public deed carrying a social profile: the profile channel with no scalar noise. */
    private static void define(DecayPolicy decay) {
        define(0, decay);
    }

    private static void define(int delta, DecayPolicy decay) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, IncidentVisibility.VILLAGE, decay, TestFixtures.PROFILE)));
    }

    private UUID record(CommunityKey community, long gameTime) {
        ctx.gameTime = gameTime;
        ReputationRequest request = new ReputationRequest(null, TestFixtures.PLAYER_A, community,
                TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), gameTime);
        return ReputationService.recordWith(ctx, request).incidentId().orElseThrow();
    }

    private IncidentRecord incident(CommunityKey community, UUID id) {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(community).orElseThrow()
                .incident(id).orElseThrow();
    }

    private long bravery(CommunityKey community, UUID id) {
        return incident(community, id).profileEvidence().orElseThrow()
                .facet(TestFixtures.FACET).orElseThrow().current();
    }

    /** The one way anything is allowed to age: through the gate, with an explicit intent. */
    private ReconciliationService.ReconcileOutcome gate(CommunityKey community, long gameTime,
                                                        ReconciliationService.Intent intent) {
        ctx.gameTime = gameTime;
        return ReconciliationService.reconcile(ctx.policy(), ctx.data, TestFixtures.PLAYER_A, community,
                gameTime, ChangeCause.DECAY, intent);
    }

    /** What a config reload looks like from here: the policy changes, and the change is observed. */
    private void switchProfiles(boolean enabled, long gameTime) {
        ctx.gameTime = gameTime;
        ctx.policy(ReputationPolicy.defaults().withProfilesEnabled(enabled));
        ReconciliationService.observeProfilePolicy(ctx.policy(), ctx.data, gameTime);
    }

    /** 8 bravery points aged {@code days} of its authored 28-day lifetime, in subunits. */
    private static long braveryAfter(long days) {
        return BRAVERY_FRESH * (28 - days) / 28;
    }

    /** 6 recognition points aged {@code days} of its authored 56-day lifetime, in subunits. */
    private static long recognitionAfter(long days) {
        return RECOGNITION_FRESH * (56 - days) / 56;
    }

    // ------------------------------------------------------------------
    // No catch-up (§12.2)
    // ------------------------------------------------------------------

    /**
     * The case §12.2 names explicitly: a whole disabled interval that no query observed. The profile
     * clock must show only the two active days, while the scalar clock — which profiles do not
     * govern — shows all twelve.
     */
    @Test
    void aDisabledIntervalWithNoInterveningReadIsNotPaidAsCatchUp() {
        UUID id = record(HOME, 0L);
        assertEquals(BRAVERY_FRESH, bravery(HOME, id), "the deed arrives at its authored value");

        // Two active days, observed by nothing but the config reload that switches profiles off.
        switchProfiles(false, 2 * DAY);

        // Ten days in which nobody opens a screen, files a deed or runs the sweep.
        switchProfiles(true, 12 * DAY);
        gate(HOME, 12 * DAY, ReconciliationService.Intent.QUERY);

        IncidentRecord incident = incident(HOME, id);
        assertEquals(2 * DAY, incident.profileElapsedTicks(),
                "only the interval in which profile aging was enabled may be charged");
        assertEquals(braveryAfter(2), bravery(HOME, id));
        assertEquals(12 * DAY, incident.decayElapsedTicks(),
                "the control: the scalar channel is not governed by enableProfiles and aged throughout");
        assertTrue(bravery(HOME, id) > braveryAfter(12),
                "and the profile channel is demonstrably ahead of where catch-up would leave it");
    }

    /** The same interval, observed part way through: the lazy skip and the epoch log must agree. */
    @Test
    void anObservedDisabledIntervalReachesTheSameAnswerAsAnUnobservedOne() {
        UUID id = record(HOME, 0L);
        switchProfiles(false, 2 * DAY);

        gate(HOME, 5 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(2 * DAY, incident(HOME, id).profileElapsedTicks(),
                "a read during the freeze charges the two active days and none of the frozen ones");
        assertEquals(5 * DAY, incident(HOME, id).lastProfileObservedGameTime(),
                "and the observation clock is up to date, so the skipped interval cannot be repaid");

        switchProfiles(true, 12 * DAY);
        gate(HOME, 12 * DAY, ReconciliationService.Intent.QUERY);

        assertEquals(2 * DAY, incident(HOME, id).profileElapsedTicks());
        assertEquals(braveryAfter(2), bravery(HOME, id));
    }

    @Test
    void agingResumesOnceProfilesAreBackAndIsIdempotentAtOneEvaluationTime() {
        UUID id = record(HOME, 0L);
        switchProfiles(false, 2 * DAY);
        switchProfiles(true, 12 * DAY);
        gate(HOME, 12 * DAY, ReconciliationService.Intent.QUERY);

        assertEquals(0L, gate(HOME, 12 * DAY, ReconciliationService.Intent.QUERY)
                        .profileMagnitudeDelta(),
                "running the gate twice at one evaluation time moves nothing the second time");

        gate(HOME, 15 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(5 * DAY, incident(HOME, id).profileElapsedTicks(),
                "two days before the freeze plus three after it");
        assertEquals(braveryAfter(5), bravery(HOME, id));
    }

    // ------------------------------------------------------------------
    // Freeze boundaries (§20)
    // ------------------------------------------------------------------

    @Test
    void perCommunityImmunityFreezesTheProfileChannelToo() {
        ctx.data.setDecayImmune(SAFE, true);
        UUID safeId = record(SAFE, 0L);
        UUID openId = record(HOME, 0L);

        gate(SAFE, 5 * DAY, ReconciliationService.Intent.QUERY);
        gate(HOME, 5 * DAY, ReconciliationService.Intent.QUERY);

        assertEquals(BRAVERY_FRESH, bravery(SAFE, safeId),
                "a protected village forgets nothing about the player, in either channel (§20)");
        assertEquals(braveryAfter(5), bravery(HOME, openId), "the control village aged five days");
        assertEquals(5 * DAY, incident(SAFE, safeId).lastProfileObservedGameTime(),
                "frozen means the clock moved and the evidence did not");
        assertEquals(0L, incident(SAFE, safeId).profileElapsedTicks());
    }

    @Test
    void liftingImmunityCostsNoCatchUpForTheIntervalItObserved() {
        ctx.data.setDecayImmune(SAFE, true);
        UUID id = record(SAFE, 0L);
        gate(SAFE, 5 * DAY, ReconciliationService.Intent.QUERY);

        ctx.data.setDecayImmune(SAFE, false);
        gate(SAFE, 6 * DAY, ReconciliationService.Intent.QUERY);

        assertEquals(DAY, incident(SAFE, id).profileElapsedTicks(),
                "one day of aging, not the six the record has existed for");
        assertEquals(braveryAfter(1), bravery(SAFE, id));
    }

    @Test
    void theMasterSwitchFreezesTheProfileChannelWithNoCatchUp() {
        assertGlobalSwitchFreezesProfiles(ReputationPolicy.defaults().withEnabled(false));
    }

    @Test
    void theDecaySwitchFreezesTheProfileChannelWithNoCatchUp() {
        assertGlobalSwitchFreezesProfiles(ReputationPolicy.defaults().withScoreDecayEnabled(false));
    }

    /**
     * §20: existing master enablement and global decay disablement reach profile aging through the
     * same gate, and lifting either repays nothing.
     */
    private void assertGlobalSwitchFreezesProfiles(ReputationPolicy off) {
        UUID id = record(HOME, 0L);
        gate(HOME, DAY, ReconciliationService.Intent.QUERY);
        assertEquals(braveryAfter(1), bravery(HOME, id), "one day before the switch is thrown");

        ctx.policy(off);
        ReconciliationService.observeProfilePolicy(off, ctx.data, DAY);
        gate(HOME, 20 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(braveryAfter(1), bravery(HOME, id),
                "global disablement applies to profile aging through the same gate");

        ctx.policy(ReputationPolicy.defaults());
        ReconciliationService.observeProfilePolicy(ctx.policy(), ctx.data, 20 * DAY);
        gate(HOME, 21 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(braveryAfter(2), bravery(HOME, id),
                "and the nineteen frozen days are never repaid");
    }

    // ------------------------------------------------------------------
    // INSPECT (I04, DD7)
    // ------------------------------------------------------------------

    @Test
    void inspectAdvancesNeitherClockAndDirtiesNothing() {
        UUID id = record(HOME, 0L);
        ctx.data.setDirty(false);

        ReconciliationService.ReconcileOutcome outcome =
                gate(HOME, 10 * DAY, ReconciliationService.Intent.INSPECT);

        IncidentRecord incident = incident(HOME, id);
        assertEquals(0L, incident.profileElapsedTicks());
        assertEquals(0L, incident.lastProfileObservedGameTime());
        assertEquals(0L, incident.decayElapsedTicks());
        assertEquals(0L, ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(HOME)
                .orElseThrow().lastReconciledGameTime(), "nor the community's own clock");
        assertEquals(BRAVERY_FRESH, bravery(HOME, id));
        assertFalse(outcome.profileChanged());
        assertFalse(ctx.data.isDirty(), "a diagnostic must not schedule a write");

        // And the aging it declined to do is still owed, not lost.
        gate(HOME, 10 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(10 * DAY, incident(HOME, id).profileElapsedTicks());
    }

    // ------------------------------------------------------------------
    // Profile-only change detection (P5's publication contract)
    // ------------------------------------------------------------------

    @Test
    void evidenceThatFadesWithoutMovingTheScoreIsReportableAsAProfileOnlyChange() {
        define(-8, DecayPolicy.NONE);
        UUID id = record(HOME, 0L);

        ReconciliationService.ReconcileOutcome outcome =
                gate(HOME, 3 * DAY, ReconciliationService.Intent.QUERY);

        assertFalse(outcome.scoreChanged(), "a non-decaying deed keeps its whole scalar weight");
        assertFalse(outcome.tierChanged());
        assertTrue(outcome.profileChanged());
        assertTrue(outcome.profileOnlyChange(),
                "so the only thing that moved is what the village is able to say about the player");
        assertEquals((braveryAfter(3) - BRAVERY_FRESH) + (recognitionAfter(3) - RECOGNITION_FRESH),
                outcome.profileMagnitudeDelta(),
                "reported as a magnitude change over every channel, and never positive");
        assertEquals(-8, incident(HOME, id).currentContribution());
        assertTrue(ctx.data.isDirty(), "and it is a change to the save like any other");
    }

    @Test
    void aChangeThatMovesBothChannelsIsNotAProfileOnlyChange() {
        define(-8, DecayPolicy.linearToZero(0L, 2));
        record(HOME, 0L);

        ReconciliationService.ReconcileOutcome outcome =
                gate(HOME, 3 * DAY, ReconciliationService.Intent.QUERY);

        assertTrue(outcome.scoreChanged());
        assertTrue(outcome.profileChanged());
        assertFalse(outcome.profileOnlyChange(),
                "a standing change carries its own envelope; a duplicate profile-only claim would be "
                        + "published twice");
    }

    @Test
    void aFrozenProfileChannelReportsNoProfileChange() {
        UUID id = record(HOME, 0L);
        switchProfiles(false, 0L);

        ReconciliationService.ReconcileOutcome outcome =
                gate(HOME, 5 * DAY, ReconciliationService.Intent.QUERY);

        assertTrue(outcome.profileFrozen());
        assertFalse(outcome.profileChanged());
        assertFalse(outcome.profileOnlyChange());
        assertEquals(BRAVERY_FRESH, bravery(HOME, id));
    }

    // ------------------------------------------------------------------
    // Credit windows retire on the same schedule (§10.5)
    // ------------------------------------------------------------------

    @Test
    void theGateRetiresCreditWindowsWhoseWindowHasEndedAndNothingElse() {
        TestFixtures.publishProfile(TestFixtures.profile(TestFixtures.CREDIT_POLICY),
                TestFixtures.creditPolicy());
        record(HOME, 0L);
        CommunityReputationRecord home = ctx.data.player(TestFixtures.PLAYER_A).orElseThrow()
                .community(HOME).orElseThrow();
        assertEquals(1, home.creditTrackers().groupCount(), "the accepted deed consumed an allowance");

        gate(HOME, 7 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(1, home.creditTrackers().groupCount(),
                "a window that still restricts the next operation is never retired (§10.5)");

        gate(HOME, 15 * DAY, ReconciliationService.Intent.QUERY);
        assertEquals(0, home.creditTrackers().groupCount(),
                "and one that has ended is cleaned up on the ordinary schedule");
    }
}
