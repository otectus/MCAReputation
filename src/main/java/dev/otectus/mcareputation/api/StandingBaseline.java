package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.Objects;
import java.util.UUID;

/** One current local standing used by explicit companion migration previews. */
public record StandingBaseline(UUID player, CommunityKey community, int score, long revision) {
    public StandingBaseline {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(community, "community");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }
}
