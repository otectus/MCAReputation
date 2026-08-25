package dev.otectus.mcareputation.api.event;

import dev.otectus.mcareputation.community.CommunityKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Posted when a player's standing with a community crosses a tier boundary in either direction
 * (spec §26.2).
 *
 * <p>{@link #firstTime} distinguishes the two cases that §17.3 treats very differently: reaching a
 * tier that beats the recorded high-water mark is a genuine milestone and gets the celebratory toast
 * and the tier's title; re-entering a tier the player has stood in before is not, and gets neither.
 * Downward transitions are always {@code firstTime = false} and use restrained feedback.
 *
 * <p>MCA: Quests translates the upward, first-time case into its own long-standing
 * {@code ReputationTierReachedEvent} so existing consumers keep working — and posts it exactly once,
 * never twice for one transaction (§29.8).
 */
public final class ReputationTierChangedEvent extends ReputationEvent {

    private final ResourceLocation ladder;
    private final String oldTierId;
    private final String newTierId;
    private final int oldIndex;
    private final int newIndex;
    private final boolean firstTime;

    public ReputationTierChangedEvent(UUID playerId, @Nullable ServerPlayer player, CommunityKey community,
                                      ResourceLocation ladder, String oldTierId, String newTierId,
                                      int oldIndex, int newIndex, boolean firstTime) {
        super(playerId, player, community);
        this.ladder = ladder;
        this.oldTierId = oldTierId;
        this.newTierId = newTierId;
        this.oldIndex = oldIndex;
        this.newIndex = newIndex;
        this.firstTime = firstTime;
    }

    public ResourceLocation ladder() {
        return ladder;
    }

    public String oldTierId() {
        return oldTierId;
    }

    public String newTierId() {
        return newTierId;
    }

    public int oldIndex() {
        return oldIndex;
    }

    public int newIndex() {
        return newIndex;
    }

    public boolean upward() {
        return newIndex > oldIndex;
    }

    public boolean downward() {
        return newIndex < oldIndex;
    }

    /** True when this beats the stored high-water mark: a new personal best with this community. */
    public boolean firstTime() {
        return firstTime;
    }
}
