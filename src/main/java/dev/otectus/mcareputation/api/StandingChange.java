package dev.otectus.mcareputation.api;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One canonical standing change, in one object (§6 "Standing change").
 *
 * <p>Published exactly once per semantic event, after the canonical commit, by the single dispatch
 * point in the service. Mirrors, the Forge events, and player feedback are all derived from this, so
 * they can never describe different numbers.
 *
 * @param highWaterTierId the highest tier ever reached on this ladder, or {@code null} if none
 * @param revision        the community record's in-memory revision after the change
 * @param quiet           whether player-facing deed feedback is suppressed (see {@link ChangeCause})
 * @since MCA: Reputation 0.4.1
 */
public record StandingChange(UUID player, CommunityKey community, int oldScore, int newScore,
                             String oldTierId, String newTierId, ResourceLocation ladder,
                             @Nullable String highWaterTierId, long revision, ChangeCause cause,
                             boolean quiet) {

    /** The change actually applied, after clamping. */
    public int delta() {
        return newScore - oldScore;
    }

    public boolean scoreChanged() {
        return newScore != oldScore;
    }

    public boolean tierChanged() {
        return oldTierId != null && !oldTierId.equals(newTierId);
    }
}
