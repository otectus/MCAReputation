package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One durable, ordered standing change for companion consumers. */
public record StandingEnvelope(UUID epoch, long sequence, UUID eventId, UUID player,
                               CommunityKey community, long communityRevision,
                               int oldScore, int newScore, ChangeCause cause, boolean quiet,
                               ResourceLocation source, Optional<UUID> incidentId,
                               Optional<ResourceLocation> incidentType, long gameTime) {
    public StandingEnvelope {
        Objects.requireNonNull(epoch, "epoch");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(community, "community");
        if (communityRevision < 0) throw new IllegalArgumentException("communityRevision must be non-negative");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(source, "source");
        incidentId = incidentId == null ? Optional.empty() : incidentId;
        incidentType = incidentType == null ? Optional.empty() : incidentType;
    }

    public int delta() {
        return newScore - oldScore;
    }

    public static UUID eventId(UUID epoch, long sequence) {
        return UUID.nameUUIDFromBytes(("mcareputation/" + epoch + "/" + sequence)
                .getBytes(StandardCharsets.UTF_8));
    }
}
