package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.api.ImportResult;
import dev.otectus.mcareputation.api.LegacyImportRequest;
import dev.otectus.mcareputation.api.ReputationMirror;
import dev.otectus.mcareputation.api.ReputationRequest;
import dev.otectus.mcareputation.api.ReputationResult;
import dev.otectus.mcareputation.api.ResolutionResult;
import dev.otectus.mcareputation.api.event.ReputationChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentCreatedEvent;
import dev.otectus.mcareputation.api.event.ReputationIncidentResolvedEvent;
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import dev.otectus.mcareputation.api.event.ReputationTitleGrantedEvent;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.BuiltinIncidents;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentStatus;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.state.CommunityReputationRecord;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * The transaction itself (spec §18), run for real against an in-memory store through the
 * {@link ServiceContext} seam: ordering, dedupe, the unwitnessed branches, containment of broken
 * listeners and mirrors, exact landing of administrative writes at the clamp, and legacy import.
 */
class ReputationServiceTest {

    private static final long DAY = DecayPolicy.TICKS_PER_DAY;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;

    private final TestServiceContext ctx = new TestServiceContext();
    private final List<ReputationMirror> registeredMirrors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
        McaReputationConfig.TestOverrides.reset();
        registeredMirrors.forEach(ReputationService::unregisterMirror);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void define(int delta, IncidentVisibility visibility, DecayPolicy decay) {
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT,
                TestFixtures.definition(delta, visibility, decay)));
    }

    private static ReputationRequest request(String dedupeKey, Set<UUID> witnesses, long gameTime) {
        return new ReputationRequest(null, TestFixtures.PLAYER_A, HOME, TestFixtures.ASSAULT,
                TestFixtures.SOURCE, Optional.ofNullable(dedupeKey), OptionalInt.empty(),
                Optional.empty(), List.of(), witnesses, Map.of(), gameTime);
    }

    private ReputationResult record(String dedupeKey, Set<UUID> witnesses, long gameTime) {
        return ReputationService.recordWith(ctx, request(dedupeKey, witnesses, gameTime));
    }

    private CommunityReputationRecord home() {
        return ctx.data.player(TestFixtures.PLAYER_A).orElseThrow().community(HOME).orElseThrow();
    }

    private TraceMirror registerTraceMirror(List<String> trace) {
        TraceMirror mirror = new TraceMirror(trace);
        ReputationService.registerMirror(mirror);
        registeredMirrors.add(mirror);
        return mirror;
    }

    private static class TraceMirror implements ReputationMirror {

        final List<String> trace;

        TraceMirror(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public void mirrorScore(UUID player, CommunityKey community, int score,
                                ResourceLocation ladder, String highWaterTierId) {
            trace.add("mirrorScore:" + score + ":" + highWaterTierId);
        }

        @Override
        public void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title) {
            trace.add("mirrorVillageTitle:" + title);
        }

        @Override
        public void mirrorGlobalTitle(UUID player, ResourceLocation title) {
            trace.add("mirrorGlobalTitle:" + title);
        }

        @Override
        public String mirrorName() {
            return "trace";
        }
    }

    // ------------------------------------------------------------------
    // Ordering (§18: mirrors after the commit, events after mirrors)
    // ------------------------------------------------------------------

    @Test
    void mirrorsAreNotifiedBeforeEventsAndEventsKeepTheirOrder() {
        define(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        List<String> trace = new ArrayList<>();
        registerTraceMirror(trace);
        ctx.listener = event -> trace.add(event.getClass().getSimpleName());

        ReputationResult result = record(null, Set.of(), 0L);

        assertTrue(result.applied());
        assertEquals(30, result.newScore());
        assertEquals(List.of("mirrorScore:30:acquaintance",
                        "ReputationIncidentCreatedEvent",
                        "ReputationChangedEvent",
                        "ReputationTierChangedEvent"),
                trace, "the §18 order: canonical commit, mirrors, then events");
    }

    @Test
    void aTierTitleGrantIsCommittedAndMirroredWithTheTransaction() {
        define(200, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        List<String> trace = new ArrayList<>();
        registerTraceMirror(trace);
        ctx.listener = event -> trace.add(event.getClass().getSimpleName());

        ReputationResult result = record(null, Set.of(), 0L);

        assertTrue(result.applied());
        assertTrue(result.newHighWater(), "0 -> 200 reaches honored for the first time");
        assertTrue(home().hasTitle(new ResourceLocation("mcaquests", "honored_of_village")));
        assertEquals(List.of("ReputationTitleGrantedEvent",
                        "mirrorScore:200:honored",
                        "mirrorVillageTitle:mcaquests:honored_of_village",
                        "ReputationIncidentCreatedEvent",
                        "ReputationChangedEvent",
                        "ReputationTierChangedEvent"),
                trace);
    }

    // ------------------------------------------------------------------
    // Containment (§18: a broken add-on cannot unwind a commit)
    // ------------------------------------------------------------------

    @Test
    void aThrowingListenerCannotAbortACommittedTransaction() {
        define(200, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ctx.listener = event -> {
            throw new IllegalStateException("broken add-on");
        };

        ReputationResult result = record(null, Set.of(), 0L);

        assertTrue(result.applied(), "the commit already happened; a listener cannot make it ERROR");
        assertEquals(200, result.newScore());
        assertEquals(200, home().score());
        assertEquals(1, home().incidentCount());
        assertTrue(home().hasTitle(new ResourceLocation("mcaquests", "honored_of_village")),
                "the title grant survives a listener that throws on its event");
        assertEquals(4, ctx.posted.size(),
                "every event was still posted: title, created, changed, tier");
        assertTrue(ctx.data.isDirty());
    }

    @Test
    void aThrowingMirrorCannotAbortACommittedTransaction() {
        define(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ReputationMirror broken = new TraceMirror(new ArrayList<>()) {
            @Override
            public void mirrorScore(UUID player, CommunityKey community, int score,
                                    ResourceLocation ladder, String highWaterTierId) {
                throw new IllegalStateException("broken mirror");
            }
        };
        ReputationService.registerMirror(broken);
        registeredMirrors.add(broken);

        ReputationResult result = record(null, Set.of(), 0L);

        assertTrue(result.applied());
        assertEquals(30, home().score());
        assertEquals(3, ctx.posted.size(), "events still fire after a mirror throws");
    }

    // ------------------------------------------------------------------
    // Dedupe (§14.2)
    // ------------------------------------------------------------------

    @Test
    void aDuplicateKeyIsRefusedWithoutMutatingAnything() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        assertTrue(record("k1", Set.of(), 0L).applied());
        ctx.posted.clear();

        ReputationResult second = record("k1", Set.of(), 10L);

        assertFalse(second.applied());
        assertEquals(ReputationResult.Reason.DUPLICATE, second.reason());
        assertEquals(1, home().incidentCount());
        assertTrue(ctx.posted.isEmpty(), "a refused duplicate posts nothing");
    }

    /** The DUPLICATE answer must report the community's current standing, not a pre-decay one. */
    @Test
    void theDuplicateShortCircuitReportsTheReconciledScore() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 8));
        assertEquals(-8, record("k1", Set.of(), 0L).newScore());

        ReputationResult duplicate = record("k1", Set.of(), 2 * DAY);

        assertEquals(ReputationResult.Reason.DUPLICATE, duplicate.reason());
        assertEquals(0, duplicate.oldScore(),
                "two days of decay have run the -8 to zero, and the refusal must say so");
    }

    /**
     * The refusal must name the incident the first attempt created (integration spec §7.5).
     *
     * <p>This is the whole recovery story for a cross-mod write. A companion commits its own record,
     * crashes before it can store our incident id, and on restart replays the same dedupe key. If the
     * refusal is anonymous the companion has exactly two options — lose the link permanently, or
     * record a second incident purely to obtain an id — and both are worse than the crash was.
     */
    @Test
    void aDuplicateKeyReturnsTheOriginalIncidentId() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ReputationResult first = record("k1", Set.of(), 0L);
        UUID original = first.incidentId().orElseThrow();

        ReputationResult second = record("k1", Set.of(), 10L);

        assertEquals(ReputationResult.Reason.DUPLICATE, second.reason());
        assertFalse(second.applied(), "recovering the id must not be mistaken for a second write");
        assertEquals(0, second.appliedDelta());
        assertEquals(Optional.of(original), second.incidentId());
        assertEquals(1, home().incidentCount());
    }

    /** A refusal that never reached an existing record still has no id to offer. */
    @Test
    void aNonDuplicateRefusalStillCarriesNoIncidentId() {
        define(-8, IncidentVisibility.WITNESSED, DecayPolicy.NONE);

        ReputationResult unwitnessed = record("k1", Set.of(), 0L);

        assertFalse(unwitnessed.applied());
        assertTrue(unwitnessed.incidentId().isEmpty());
    }

    // ------------------------------------------------------------------
    // Unwitnessed (§19.1)
    // ------------------------------------------------------------------

    @Test
    void anUnwitnessedDeedThatIsNotRetainedIsDroppedEntirely() {
        define(-8, IncidentVisibility.WITNESSED, DecayPolicy.NONE);

        ReputationResult result = record(null, Set.of(), 0L);

        assertFalse(result.applied());
        assertEquals(ReputationResult.Reason.UNWITNESSED, result.reason());
        assertEquals(0, home().incidentCount(), "not retained means no record at all");
        assertTrue(ctx.posted.isEmpty());
    }

    @Test
    void anUnwitnessedRetainedDeedIsKeptAsHiddenZeroWeightHistory() {
        IncidentDefinition definition = TestFixtures.definition(-40, IncidentVisibility.WITNESSED,
                DecayPolicy.NONE);
        IncidentRegistry.replaceAll(Map.of(TestFixtures.ASSAULT, new IncidentDefinition(
                definition.display(), definition.defaultDelta(), definition.visibility(),
                definition.severity(), definition.tags(), definition.retentionTicks(),
                definition.decay(), definition.resolution(), definition.gossip(), definition.pinned(),
                definition.maxOverrideAbs(), true, definition.allowPrivateScore())));

        ReputationResult result = record(null, Set.of(), 0L);

        assertTrue(result.applied(), "retained: the world remembers even though nobody saw it");
        assertEquals(0, result.appliedDelta());
        assertEquals(0, home().score());
        assertEquals(1, home().incidentCount());
        IncidentRecord stored = home().incidentsNewestFirst().get(0);
        assertEquals(IncidentVisibility.PRIVATE, stored.visibility(), "§19.1: retained as hidden");
        assertFalse(stored.contributes());
        assertEquals(1, ctx.posted(ReputationIncidentCreatedEvent.class).size());
        assertTrue(ctx.posted(ReputationChangedEvent.class).isEmpty(),
                "a zero-delta record announces no score change");
    }

    @Test
    void aWitnessedDeedWithWitnessesScoresNormally() {
        define(-8, IncidentVisibility.WITNESSED, DecayPolicy.NONE);
        ReputationResult result = record(null, Set.of(TestFixtures.VILLAGER_1), 0L);
        assertTrue(result.applied());
        assertEquals(-8, result.appliedDelta());
        assertEquals(-8, home().score());
    }

    // ------------------------------------------------------------------
    // Administrative writes (§17.1)
    // ------------------------------------------------------------------

    /** The regression that motivated {@code contributionSum}: /set must land exactly, clamp or no. */
    @Test
    void setScoreLandsExactlyOnTargetWhenTheLedgerSaturatesTheClamp() {
        define(100, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        for (int i = 0; i < 14; i++) {
            assertTrue(record(null, Set.of(), i).applied());
        }
        assertEquals(1000, home().score(), "the +1400 ledger saturates the +1000 ceiling");

        ReputationResult result = ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME,
                500, true, TestFixtures.SOURCE, 100L);

        assertTrue(result.applied());
        assertEquals(500, result.newScore(), "set means set, whatever the ledger sums to");
        assertEquals(500, home().score());
    }

    @Test
    void addScoreShiftsTheUnderlyingTotal() {
        ReputationResult result = ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME,
                25, false, TestFixtures.SOURCE, 0L);
        assertTrue(result.applied());
        assertEquals(25, home().score());
        assertEquals(1, ctx.posted(ReputationChangedEvent.class).size());
    }

    // ------------------------------------------------------------------
    // Decay guard (§15.1: enableScoreDecay=false stops every decay path)
    // ------------------------------------------------------------------

    @Test
    void disablingDecayStopsItOnTheResolveAndAdminPathsToo() {
        McaReputationConfig.TestOverrides.scoreDecay = false;
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.linearToZero(0, 8));
        ReputationResult recorded = record(null, Set.of(), 0L);
        UUID incidentId = recorded.incidentId().orElseThrow();

        // Admin path far in the future: with decay off, the -8 must still be fully present.
        ReputationResult added = ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME,
                10, false, TestFixtures.SOURCE, 5 * DAY);
        assertEquals(2, added.newScore(), "-8 + 10, with no decay sneaking in on the admin path");

        // Resolve path far in the future: the resolution scales the undecayed value.
        ResolutionResult resolved = ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, HOME,
                incidentId, IncidentStatus.APOLOGIZED, TestFixtures.SOURCE, 6 * DAY);
        assertTrue(resolved.applied());
        IncidentRecord incident = home().incident(incidentId).orElseThrow();
        assertEquals(-6, incident.currentContribution(),
                "-8 apologized at 0.75 is -6; decay must not have run first");
    }

    // ------------------------------------------------------------------
    // Tier high-water (§17.3)
    // ------------------------------------------------------------------

    @Test
    void returningToYourStartingTierAfterADipIsNotAFirstTime() {
        define(-30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ReputationResult dip = record(null, Set.of(), 0L);
        assertTrue(dip.tierChanged());
        assertFalse(dip.newHighWater(), "falling to wary is no milestone");

        ReputationResult back = ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME,
                0, true, TestFixtures.SOURCE, 10L);

        assertTrue(back.tierChanged(), "wary back up to stranger crosses a boundary");
        assertFalse(back.newHighWater(),
                "the tier you started in is not a new personal best when you return to it");
        List<ReputationTierChangedEvent> tierEvents = ctx.posted(ReputationTierChangedEvent.class);
        assertEquals(2, tierEvents.size());
        assertFalse(tierEvents.get(0).firstTime());
        assertFalse(tierEvents.get(1).firstTime());
    }

    @Test
    void aGenuinelyNewTierIsCelebratedExactlyOnce() {
        define(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        assertTrue(record(null, Set.of(), 0L).newHighWater(), "first time at acquaintance");

        ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME, 0, true,
                TestFixtures.SOURCE, 10L);
        ReputationResult again = ReputationService.adjustBaseline(ctx, TestFixtures.PLAYER_A, HOME,
                30, true, TestFixtures.SOURCE, 20L);

        assertTrue(again.tierChanged());
        assertFalse(again.newHighWater(), "re-entering acquaintance is not a second milestone");
    }

    // ------------------------------------------------------------------
    // Resolve (§15.2)
    // ------------------------------------------------------------------

    @Test
    void resolvingPostsResolvedThenChangedAfterTheMirrors() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        UUID incidentId = record(null, Set.of(), 0L).incidentId().orElseThrow();
        List<String> trace = new ArrayList<>();
        registerTraceMirror(trace);
        ctx.listener = event -> trace.add(event.getClass().getSimpleName());

        ResolutionResult result = ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, HOME,
                incidentId, IncidentStatus.FORGIVEN, TestFixtures.SOURCE, 10L);

        assertTrue(result.applied());
        assertEquals(0, home().score(), "forgiven runs the -8 to zero");
        assertEquals(List.of("mirrorScore:0:stranger",
                        "ReputationIncidentResolvedEvent",
                        "ReputationChangedEvent",
                        "ReputationTierChangedEvent"),
                trace, "resolving back across a boundary announces the tier change last");
        assertEquals(1, ctx.posted(ReputationIncidentResolvedEvent.class).size());
    }

    @Test
    void aWeakerResolutionIsRefusedIdempotently() {
        define(-8, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        UUID incidentId = record(null, Set.of(), 0L).incidentId().orElseThrow();
        assertTrue(ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, HOME, incidentId,
                IncidentStatus.ATONED, TestFixtures.SOURCE, 10L).applied());

        ResolutionResult weaker = ReputationService.resolveWith(ctx, TestFixtures.PLAYER_A, HOME,
                incidentId, IncidentStatus.APOLOGIZED, TestFixtures.SOURCE, 20L);

        assertFalse(weaker.applied());
        assertEquals(ResolutionResult.Reason.NOT_STRONGER, weaker.reason());
    }

    // ------------------------------------------------------------------
    // Legacy import (§32.2)
    // ------------------------------------------------------------------

    private LegacyImportRequest importRequest(Map<CommunityKey, Integer> baselines,
                                             Map<CommunityKey, Map<ResourceLocation, String>> highWater,
                                             boolean dryRun) {
        return new LegacyImportRequest(null, TestFixtures.PLAYER_A, "mcaquests:legacy_reputation_v1",
                "1", baselines, highWater, Map.of(), Set.of(), Map.of(), dryRun);
    }

    @Test
    void aDryRunImportIsPureAndLeavesThePlayerImportable() {
        ImportResult preview = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 40), Map.of(), true));

        assertEquals(ImportResult.Reason.DRY_RUN, preview.reason());
        assertEquals(40, preview.baselineTotal());
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty(),
                "a preview must not create the player record");

        ImportResult real = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 40), Map.of(), false));
        assertTrue(real.applied(), "the dry run must not have consumed the one-time migration");
        assertEquals(40, home().score());
    }

    @Test
    void aDryRunWithNothingToImportDoesNotMarkThePlayerMigrated() {
        ImportResult preview = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(), Map.of(), true));
        assertEquals(ImportResult.Reason.NOTHING_TO_IMPORT, preview.reason());
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty());
    }

    @Test
    void aRealImportPostsEventsGrantsTheTierAndWritesTheMarkerLine() {
        IncidentRegistry.replaceAll(Map.of(BuiltinIncidents.LEGACY_BALANCE,
                TestFixtures.definition(0, IncidentVisibility.PRIVATE, DecayPolicy.NONE)));

        ImportResult result = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 200), Map.of(), false));

        assertTrue(result.applied());
        assertEquals(200, home().score());

        List<ReputationChangedEvent> changed = ctx.posted(ReputationChangedEvent.class);
        assertEquals(1, changed.size(), "ReputationChangedEvent documents that it fires for imports");
        assertEquals(0, changed.get(0).oldScore());
        assertEquals(200, changed.get(0).newScore());
        assertEquals(BuiltinIncidents.SOURCE_MIGRATION, changed.get(0).source());

        List<ReputationTierChangedEvent> tier = ctx.posted(ReputationTierChangedEvent.class);
        assertEquals(1, tier.size());
        assertTrue(tier.get(0).firstTime(), "no imported high-water: honored is genuinely new");
        assertTrue(home().hasTitle(new ResourceLocation("mcaquests", "honored_of_village")));

        List<IncidentRecord> ledger = home().incidentsNewestFirst();
        assertEquals(1, ledger.size());
        assertEquals(BuiltinIncidents.LEGACY_BALANCE, ledger.get(0).type());
        assertFalse(ledger.get(0).contributes(), "the marker explains the baseline, it adds nothing");
        assertEquals(Optional.of("200"), ledger.get(0).context("amount"));
    }

    @Test
    void importedHighWaterSuppressesTheReCelebration() {
        ImportResult result = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 200),
                        Map.of(HOME, Map.of(ReputationTiers.DEFAULT_ID, "honored")), false));

        assertTrue(result.applied());
        List<ReputationTierChangedEvent> tier = ctx.posted(ReputationTierChangedEvent.class);
        assertEquals(1, tier.size());
        assertFalse(tier.get(0).firstTime(),
                "the legacy system already celebrated honored; the import must not repeat it");
        assertFalse(home().hasTitle(new ResourceLocation("mcaquests", "honored_of_village")),
                "no first-time crossing, no tier title");
    }

    @Test
    void importIsExactlyOnce() {
        assertTrue(ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 40), Map.of(), false)).applied());

        ImportResult second = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 40), Map.of(), false));

        assertEquals(ImportResult.Reason.ALREADY_MIGRATED, second.reason());
        assertEquals(40, home().score(), "legacy standing is never added twice");
    }

    @Test
    void importRespectsTheConfigSwitch() {
        McaReputationConfig.TestOverrides.migrateLegacyQuestsData = false;
        ImportResult result = ReputationService.importLegacyWith(ctx,
                importRequest(Map.of(HOME, 40), Map.of(), false));
        assertEquals(ImportResult.Reason.DISABLED, result.reason());
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty());
    }

    // ------------------------------------------------------------------
    // Disabled / off-thread guards
    // ------------------------------------------------------------------

    @Test
    void aDisabledModRefusesWithoutWriting() {
        McaReputationConfig.TestOverrides.enabled = false;
        define(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ReputationResult result = record(null, Set.of(), 0L);
        assertEquals(ReputationResult.Reason.DISABLED, result.reason());
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty());
    }

    @Test
    void anOffThreadWriteIsRefused() {
        ctx.serverThread = false;
        define(30, IncidentVisibility.VILLAGE, DecayPolicy.NONE);
        ReputationResult result = record(null, Set.of(), 0L);
        assertEquals(ReputationResult.Reason.INVALID, result.reason());
        assertTrue(ctx.data.player(TestFixtures.PLAYER_A).isEmpty());
    }

    @Test
    void anUnknownIncidentTypeIsRefused() {
        ReputationResult result = record(null, Set.of(), 0L);
        assertEquals(ReputationResult.Reason.UNKNOWN_INCIDENT, result.reason());
    }
}
