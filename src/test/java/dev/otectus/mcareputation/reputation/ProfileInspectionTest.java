package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.profile.ProfileQueryResult;
import dev.otectus.mcareputation.api.profile.ProfileSnapshot;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import dev.otectus.mcareputation.state.CreditWindowTrackers;
import dev.otectus.mcareputation.state.ProfileMigrationState;
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
 * The read path behind {@code /mcareputation debug profile}, {@code credit},
 * {@code profileincident} and {@code profilemigration} (§21.1).
 *
 * <p>§21.1's rule is one sentence — "use {@code INSPECT} for diagnostics that promise no mutation" —
 * and it is the whole of what these tests defend. A diagnostic that aged the evidence it was printing
 * would answer a different question every time an operator ran it, and the bug it was run to
 * investigate would move while they looked at it. The control throughout is a {@code QUERY} at the
 * same evaluation time, which demonstrably does age: a run where nothing aged at all would pass these
 * vacuously.
 *
 * <p>{@code ReputationScreen} and {@code ReputationCommand} cannot be driven without a server, so
 * what is asserted here is the exact service call each subcommand makes. {@code ProfileDiagnosticsTest}
 * is the other half: it checks the commands still make those calls and no others.
 */
class ProfileInspectionTest {

    private static final long DAY = TestFixtures.DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private final TestServiceContext ctx = new TestServiceContext();

    @BeforeEach
    void setUp() {
        ctx.policy(ReputationPolicy.defaults());
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE,
                        TestFixtures.PROFILE)));
        TestFixtures.publishProfile(TestFixtures.profile(TestFixtures.CREDIT_POLICY),
                TestFixtures.creditPolicy(), Map.of(TestFixtures.FACET, TestFixtures.facet()));
    }

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        ProfileRegistryBundle.clear();
        McaReputationConfig.TestOverrides.reset();
    }

    private UUID record(long gameTime) {
        ctx.gameTime = gameTime;
        return ReputationService.recordWith(ctx, new ReputationRequest(null, TestFixtures.PLAYER_A,
                HOME, TestFixtures.ASSAULT, TestFixtures.SOURCE, Optional.empty(), OptionalInt.empty(),
                Optional.empty(), List.of(), Set.of(), Map.of(), gameTime)).incidentId().orElseThrow();
    }

    private CommunityReputationRecord community() {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(HOME).orElseThrow();
    }

    private IncidentRecord incident(UUID id) {
        return community().incident(id).orElseThrow();
    }

    private ProfileQueryResult<ProfileSnapshot> inspect(long gameTime) {
        return ProfileService.profile(ctx.policy(), ctx.data, TestFixtures.PLAYER_A, HOME, gameTime,
                true);
    }

    /**
     * What {@code debug profile} does: a full profile answer with neither clock moved, nothing aged
     * and no write scheduled.
     */
    @Test
    void inspectingAProfileMovesNoClockAndSchedulesNoWrite() {
        UUID id = record(0L);
        long bravery = incident(id).profileEvidence().orElseThrow()
                .facet(TestFixtures.FACET).orElseThrow().current();
        long revision = community().revision();
        long profileRevision = community().profileRevision();
        ctx.data.setDirty(false);

        ProfileQueryResult<ProfileSnapshot> result = inspect(10 * DAY);

        assertTrue(result.isAvailable(), "the diagnostic still gets a real answer");
        assertEquals(0L, incident(id).profileElapsedTicks());
        assertEquals(0L, incident(id).decayElapsedTicks());
        assertEquals(0L, incident(id).lastProfileObservedGameTime());
        assertEquals(0L, community().lastReconciledGameTime());
        assertEquals(bravery, incident(id).profileEvidence().orElseThrow()
                .facet(TestFixtures.FACET).orElseThrow().current());
        assertEquals(revision, community().revision());
        assertEquals(profileRevision, community().profileRevision());
        assertFalse(ctx.data.isDirty(), "a diagnostic must not schedule a write");

        // The control: the aging it declined to do was owed, not lost.
        ProfileService.profile(ctx.policy(), ctx.data, TestFixtures.PLAYER_A, HOME, 10 * DAY, false);
        assertEquals(10 * DAY, incident(id).profileElapsedTicks(),
                "a live read at the same moment does age, so the assertions above are not vacuous");
    }

    /** Running it twice must be as harmless as running it once — an operator will. */
    @Test
    void inspectingTwiceAtTwoMomentsStillAgesNothing() {
        UUID id = record(0L);
        ctx.data.setDirty(false);

        inspect(5 * DAY);
        inspect(40 * DAY);

        assertEquals(0L, incident(id).profileElapsedTicks());
        assertFalse(ctx.data.isDirty());
    }

    /**
     * And the reported values are the stored ones, not a projection of what they will become: the
     * recognition an inspect reports equals what the raw subunits say right now.
     */
    @Test
    void anInspectReportsTheStoredValueRatherThanAnAgedOne() {
        record(0L);
        long storedSubunits = community().recognitionSubunits();

        ProfileSnapshot atOnce = inspect(0L).value().orElseThrow();
        ProfileSnapshot muchLater = inspect(40 * DAY).value().orElseThrow();

        assertEquals(atOnce.recognition().value(), muchLater.recognition().value(),
                "the answer is the stored state, so the evaluation time cannot change it");
        assertEquals(storedSubunits, community().recognitionSubunits());
    }

    /**
     * What {@code debug credit} reads: the tracker descriptions and a peek at the next window. Both
     * are reads — asking must not be able to spend an allowance (I04).
     */
    @Test
    void readingTheCreditTrackersSpendsNoAllowance() {
        record(0L);
        CreditWindowTrackers trackers = community().creditTrackers();
        Map<String, String> before = Map.copyOf(trackers.describe());
        int groups = trackers.groupCount();
        int subjects = trackers.subjectCount();
        ctx.data.setDirty(false);

        CreditWindowTrackers.CreditWindow peeked = community()
                .peekCreditWindow(TestFixtures.CREDIT_GROUP, Optional.empty(), false, 5 * DAY);
        Map<String, String> after = community().creditTrackers().describe();

        assertEquals(before, after, "describing the trackers changes none of them");
        assertEquals(groups, community().creditTrackers().groupCount());
        assertEquals(subjects, community().creditTrackers().subjectCount());
        assertEquals(1, peeked.groupOrdinal(),
                "the first service consumed occurrence zero, so the next one would be occurrence one");
        assertEquals(peeked.groupOrdinal(), community()
                        .peekCreditWindow(TestFixtures.CREDIT_GROUP, Optional.empty(), false, 5 * DAY)
                        .groupOrdinal(),
                "and peeking twice answers the same, which is the whole point of a peek");
        assertFalse(ctx.data.isDirty());
    }

    /**
     * What {@code debug profileincident} reads: the frozen payload, exactly as stored. The four
     * quantities are four different facts and none of them is recomputed here.
     */
    @Test
    void readingOneDeedsFrozenPayloadChangesNothingAboutIt() {
        UUID id = record(0L);
        ctx.data.setDirty(false);
        var payload = incident(id).profileEvidence().orElseThrow();
        var facet = payload.facet(TestFixtures.FACET).orElseThrow();

        assertEquals(facet.authored(), facet.credited(),
                "a first service takes full credit, so authored and credited coincide here");
        assertEquals(facet.credited(), facet.current(), "and nothing has aged yet");
        assertEquals(facet.credited(), facet.agedAt(0L));
        assertTrue(facet.agedAt(20 * DAY) < facet.credited(),
                "asking what it would be worth later is arithmetic, not a mutation");
        assertEquals(facet.current(), incident(id).profileEvidence().orElseThrow()
                        .facet(TestFixtures.FACET).orElseThrow().current(),
                "and the stored value is untouched by having asked");
        assertFalse(ctx.data.isDirty());
    }

    /**
     * What {@code debug profilemigration} reads. A store that never migrated reports complete
     * coverage and no pending pass, and reading the report does not start one.
     */
    @Test
    void readingTheMigrationReportStartsNoPass() {
        record(0L);
        ctx.data.setDirty(false);
        ProfileMigrationState state = ctx.data.profileMigrationState();

        assertFalse(state.pending());
        assertEquals(ProfileMigrationState.Coverage.COMPLETE, ctx.data.profileCoverage());
        assertTrue(state.report(0).contains("coverage complete"));
        assertTrue(state.report(2).contains("quarantined"),
                "a quarantined payload makes the coverage incomplete and the report says so");
        assertEquals(0, ctx.data.advanceProfileMigration(8),
                "there is nothing owed, so an explicit pass enriches nothing");
        assertFalse(ctx.data.isDirty());
    }
}
