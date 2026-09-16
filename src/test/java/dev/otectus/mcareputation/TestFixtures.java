package dev.otectus.mcareputation;

import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.incident.DecayPolicy;
import dev.otectus.mcareputation.incident.GossipSpec;
import dev.otectus.mcareputation.incident.IncidentDefinition;
import dev.otectus.mcareputation.incident.IncidentRecord;
import dev.otectus.mcareputation.incident.IncidentSeverity;
import dev.otectus.mcareputation.incident.IncidentSubject;
import dev.otectus.mcareputation.incident.IncidentVisibility;
import dev.otectus.mcareputation.incident.ResolutionPolicy;
import dev.otectus.mcareputation.credit.CreditPolicy;
import dev.otectus.mcareputation.profile.IncidentProfileDefinition;
import dev.otectus.mcareputation.profile.ProfileRegistryBundle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared builders for the pure-domain tests.
 *
 * <p>Everything here is constructible without a running game: no server, no level, no registries. That
 * is the point of §11's rule that score, tier, decay, awareness, NBT, and validation logic must be
 * testable outside Minecraft — these fixtures are what makes the rule checkable.
 */
public final class TestFixtures {

    public static final CommunityKey OVERWORLD_3 =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 3);
    public static final CommunityKey NETHER_3 =
            new CommunityKey(new ResourceLocation("minecraft", "the_nether"), 3);
    public static final CommunityKey OVERWORLD_7 =
            new CommunityKey(new ResourceLocation("minecraft", "overworld"), 7);

    public static final ResourceLocation ASSAULT = new ResourceLocation("mcareputation", "villager_assaulted");
    public static final ResourceLocation SOURCE = new ResourceLocation("mcareputation", "core");

    public static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    public static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    public static final UUID VILLAGER_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID VILLAGER_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private TestFixtures() {
    }

    /** An incident definition with every field at a sane default; override by copying the record. */
    public static IncidentDefinition definition(int delta, IncidentVisibility visibility,
                                                DecayPolicy decay) {
        return new IncidentDefinition(
                Component.translatable("test.incident"),
                delta,
                visibility,
                IncidentSeverity.MODERATE,
                List.of("test"),
                Optional.empty(),
                decay,
                ResolutionPolicy.DEFAULT,
                GossipSpec.NONE,
                false,
                Optional.of(100),
                false,
                false);
    }

    /** The same definition with a {@code social_profile} attached: what a profiled deed looks like. */
    public static IncidentDefinition definition(int delta, IncidentVisibility visibility,
                                                DecayPolicy decay, ResourceLocation socialProfile) {
        IncidentDefinition base = definition(delta, visibility, decay);
        return new IncidentDefinition(base.display(), base.defaultDelta(), base.visibility(),
                base.severity(), base.tags(), base.retentionTicks(), base.decay(), base.resolution(),
                base.gossip(), base.pinned(), base.maxOverrideAbs(), base.retainUnwitnessed(),
                base.allowPrivateScore(), Optional.ofNullable(socialProfile));
    }

    // --- profile content ----------------------------------------------------

    public static final ResourceLocation PROFILE = new ResourceLocation("mcareputation", "test_profile");
    public static final ResourceLocation FACET = new ResourceLocation("mcareputation", "bravery");
    public static final ResourceLocation CREDIT_GROUP =
            new ResourceLocation("mcareputation", "test_service");
    public static final ResourceLocation CREDIT_POLICY =
            new ResourceLocation("mcareputation", "test_policy");

    /** Whole in-game days, which is what the default decay step quantizes profile age to. */
    public static final long DAY = 24_000L;

    /**
     * A commendable profile worth 6 recognition and 8 bravery, both {@code historical}, both with a
     * 28-day lifetime — the §17 shipped shape, small enough to assert on exactly.
     */
    public static IncidentProfileDefinition profile(ResourceLocation creditPolicy) {
        return new IncidentProfileDefinition(
                List.of(),
                Optional.of(new IncidentProfileDefinition.Contribution(6, 56 * DAY,
                        IncidentProfileDefinition.ResolutionMode.RECOGNITION,
                        IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                Map.of(FACET, new IncidentProfileDefinition.Contribution(8, 28 * DAY,
                        IncidentProfileDefinition.ResolutionMode.HISTORICAL,
                        IncidentProfileDefinition.ResolutionMultipliers.DEFAULT)),
                IncidentProfileDefinition.CreditClass.COMMENDABLE,
                Optional.ofNullable(creditPolicy),
                false,
                Optional.empty());
    }

    /** 100%, 100%, 50%, 25%, then nothing: §23.1's group-credit fixture schedule. */
    public static CreditPolicy creditPolicy() {
        return new CreditPolicy(CREDIT_GROUP, 14 * DAY, List.of(10000, 10000, 5000, 2500, 0), 0,
                CreditPolicy.Scope.PLAYER_COMMUNITY, Optional.empty());
    }

    /** The same, with a subject ceiling of 100%, 50%, 0% on the {@code beneficiary} role. */
    public static CreditPolicy creditPolicyWithSubject() {
        return new CreditPolicy(CREDIT_GROUP, 14 * DAY, List.of(10000, 10000, 5000, 2500, 0), 0,
                CreditPolicy.Scope.PLAYER_COMMUNITY,
                Optional.of(new CreditPolicy.SubjectLimit("beneficiary", List.of(10000, 5000, 0), 0)));
    }

    /** Publishes one profile generation. Call {@link ProfileRegistryBundle#clear()} afterwards. */
    public static void publishProfile(IncidentProfileDefinition profile, CreditPolicy creditPolicy) {
        ProfileRegistryBundle.publish(Map.of(), Map.of(), Map.of(PROFILE, profile),
                creditPolicy == null ? Map.of() : Map.of(CREDIT_POLICY, creditPolicy));
    }

    public static IncidentRecord record(int delta) {
        return record(delta, IncidentVisibility.VILLAGE, 0L);
    }

    public static IncidentRecord record(int delta, IncidentVisibility visibility, long createdGameTime) {
        return IncidentRecord.create(UUID.randomUUID(), ASSAULT, PLAYER_A, OVERWORLD_3, createdGameTime,
                SOURCE, Optional.empty(), delta, visibility, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(VILLAGER_1, "Anna", "victim")));
    }

    public static IncidentRecord record(UUID id, int delta, IncidentVisibility visibility,
                                        long createdGameTime, String dedupeKey) {
        return IncidentRecord.create(id, ASSAULT, PLAYER_A, OVERWORLD_3, createdGameTime, SOURCE,
                Optional.ofNullable(dedupeKey), delta, visibility, IncidentSeverity.MODERATE,
                List.of(IncidentSubject.villager(VILLAGER_1, "Anna", "victim")));
    }
}
