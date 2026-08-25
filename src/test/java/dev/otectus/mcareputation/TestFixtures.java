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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
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
