package dev.otectus.mcareputation.state;

import dev.otectus.mcareputation.TestFixtures;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.credit.CreditDecision;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.IncidentProfileEvidence;
import dev.otectus.mcareputation.profile.ProfileMath;
import dev.otectus.mcareputation.reputation.ReputationPolicy;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §12.3: capacity cleanup protects the evidence, not the displayed integer (I06).
 *
 * <p>The failure this guards is subtle and permanent. A facet whose aggregated value rounds or clamps
 * to zero still holds the subunits a later opposing deed has to be weighed against (§8.3), and a deed
 * whose scalar contribution has decayed to nothing may still be the only reason the village can say
 * what the player is known for. A cap sweep that consults the display drops exactly those records, and
 * no later event can reconstruct them.
 *
 * <p>Both cap paths are exercised — the per-community one and the whole-player one — because they are
 * two implementations of the same promise and only one of them used to be given the operation's own
 * rules.
 */
class ProfileRetentionTest {

    private static final int MIN = -1000;
    private static final int MAX = 1000;
    private static final long DAY = 24_000L;
    private static final CommunityKey HOME = TestFixtures.OVERWORLD_3;
    private static final CommunityKey AWAY = TestFixtures.NETHER_3;
    private static final ResourceLocation BRAVERY = ResourceLocation.fromNamespaceAndPath("mcareputation", "bravery");

    @AfterEach
    void tearDown() {
        IncidentRegistry.replaceAll(Map.of());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A zero-delta public deed: nothing left in the scalar channel, so the only thing that can protect
     * it from the cap is its profile evidence.
     *
     * @param currentSubunits what the facet is worth now; {@code 4000} is four tenths of one authored
     *                        point, which {@link ProfileMath#publicValue} displays as a flat zero
     */
    private static IncidentRecord deed(CommunityKey community, long createdGameTime,
                                       long currentSubunits) {
        IncidentRecord record = IncidentRecord.create(UUID.randomUUID(), TestFixtures.ASSAULT,
                TestFixtures.PLAYER_A, community, createdGameTime, TestFixtures.SOURCE,
                Optional.empty(), 0, IncidentVisibility.VILLAGE, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(TestFixtures.VILLAGER_1, "Anna", "victim")));
        if (currentSubunits > 0L) {
            record.attachProfileEvidence(IncidentProfileEvidence.of(
                    IncidentProfileEvidence.Origin.LIVE, TestFixtures.PROFILE, 7L, 1L,
                    Optional.empty(),
                    List.of(new IncidentProfileEvidence.Channel(Optional.of(BRAVERY), 80_000L, 80_000L,
                            currentSubunits, 28 * DAY, DAY,
                            IncidentProfileDefinition.ResolutionMode.HISTORICAL,
                            IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                    CreditDecision.unlimited(CreditDecision.Reason.NO_POLICY)));
            record.initializeProfileClock(0L, createdGameTime);
        }
        return record;
    }

    private static AdmissionPreflight preflight(int communityCap, long gameTime) {
        return AdmissionPreflight.of(ReputationPolicy.defaults().toBuilder()
                .maxIncidentsPerCommunity(communityCap)
                .maxIncidentsPerPlayer(communityCap)
                .build(), gameTime);
    }

    // ------------------------------------------------------------------
    // Per-community cap
    // ------------------------------------------------------------------

    @Test
    void aDisplayClampedFacetStillProtectsItsEvidenceFromPruning() {
        CommunityReputationRecord community = new CommunityReputationRecord(HOME);
        IncidentRecord protectedDeed = deed(HOME, 0L, 4_000L);
        community.addIncident(protectedDeed);
        List<IncidentRecord> ordinary = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            IncidentRecord plain = deed(HOME, (i + 1) * 100L, 0L);
            ordinary.add(plain);
            community.addIncident(plain);
        }

        assertEquals(0, ProfileMath.publicValue(4_000L, 0, 100),
                "the fixture's premise: this facet displays as zero");
        assertTrue(protectedDeed.hasLiveProfileEvidence());
        assertFalse(protectedDeed.contributes(), "and it has nothing left in the scalar channel");

        // The profiled deed is the oldest, so the final oldest-first pass reaches it first.
        List<IncidentRecord> removed = community.prune(MIN, MAX, preflight(2, 10 * DAY));

        assertEquals(2, removed.size());
        assertFalse(removed.contains(protectedDeed),
                "live subunits are future-relevant evidence whatever the display says (I06)");
        assertTrue(removed.contains(ordinary.get(0)));
        assertTrue(community.incident(protectedDeed.id()).isPresent());
    }

    @Test
    void aLedgerOfLiveProfileEvidenceRefusesAdmissionRatherThanDiscardingIt() {
        CommunityReputationRecord community = new CommunityReputationRecord(HOME);
        for (int i = 0; i < 3; i++) {
            community.addIncident(deed(HOME, i * 100L, 4_000L));
        }
        AdmissionPreflight full = preflight(3, 10 * DAY);

        assertEquals(0, community.evictableIncidentCount(full));
        assertFalse(community.canAdmit(full),
                "the refusal is the honest answer: losing the explanation is acceptable, losing the "
                        + "evidence is not (§5 F09)");
        assertEquals(0, community.prune(MIN, MAX, full).size(), "and the sweep drops nothing either");
        assertEquals(3, community.incidentCount());
    }

    @Test
    void onceTheEvidenceIsSpentTheSameLedgerPrunesAndAdmitsAgain() {
        CommunityReputationRecord community = new CommunityReputationRecord(HOME);
        for (int i = 0; i < 3; i++) {
            community.addIncident(deed(HOME, i * 100L, 4_000L));
        }
        AdmissionPreflight full = preflight(3, 40 * DAY);
        assertFalse(community.canAdmit(full), "still live at this point");

        // Time passes through the one path allowed to age the profile channel.
        CommunityReputationRecord.ProfileReconcileResult aged =
                community.reconcileProfiles(40 * DAY, null);

        assertTrue(aged.unitsMoved());
        assertEquals(0, community.incidents().stream()
                        .filter(IncidentRecord::hasLiveProfileEvidence).count(),
                "every authored lifetime is finite, so the retention pressure is temporary (§12.3)");
        assertTrue(community.canAdmit(full));
        assertEquals(1, community.prune(MIN, MAX, preflight(2, 40 * DAY)).size());
    }

    @Test
    void theProtectionIsReadFromTheSnapshotRatherThanAssumed() {
        CommunityReputationRecord community = new CommunityReputationRecord(HOME);
        for (int i = 0; i < 3; i++) {
            community.addIncident(deed(HOME, i * 100L, 4_000L));
        }
        AdmissionPreflight unprotected = AdmissionPreflight.of(ReputationPolicy.defaults().toBuilder()
                .maxIncidentsPerCommunity(2)
                .protectProfileEvidence(false)
                .build(), 10 * DAY);

        assertTrue(unprotected.maxIncidentsPerCommunity() == 2);
        assertFalse(unprotected.protectsLiveProfileEvidence());
        assertEquals(1, community.prune(MIN, MAX, unprotected).size(),
                "the cap paths consult the operation's own rules, not a constant of their own");
    }

    // ------------------------------------------------------------------
    // Whole-player cap
    // ------------------------------------------------------------------

    @Test
    void theWholePlayerSweepProtectsLiveProfileEvidenceToo() {
        PlayerReputationRecord player = new PlayerReputationRecord(TestFixtures.PLAYER_A);
        CommunityReputationRecord home = player.getOrCreate(HOME);
        IncidentRecord protectedDeed = deed(HOME, 0L, 4_000L);
        home.addIncident(protectedDeed);
        IncidentRecord spent = deed(HOME, 100L, 0L);
        home.addIncident(spent);
        CommunityReputationRecord away = player.getOrCreate(AWAY);
        away.addIncident(deed(AWAY, 200L, 0L));

        int pruned = player.enforcePlayerIncidentCap(MIN, MAX,
                AdmissionPreflight.of(ReputationPolicy.defaults().toBuilder()
                        .maxIncidentsPerPlayer(2)
                        .build(), 10 * DAY));

        assertEquals(1, pruned);
        assertTrue(home.incident(protectedDeed.id()).isPresent(),
                "the fullest community is visited first, and its live evidence is still refused");
        assertFalse(home.incident(spent.id()).isPresent(), "the spent neighbour paid for the room");
        assertEquals(2, player.totalIncidentCount());
    }
}
