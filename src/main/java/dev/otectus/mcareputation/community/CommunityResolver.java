package dev.otectus.mcareputation.community;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.compat.McaCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns a villager or a place into the community whose opinion is at stake (spec §12.2).
 *
 * <p>The single most important behaviour here is what happens when nothing resolves: the answer is
 * {@link Optional#empty()}, and the caller records nothing. §12.2 forbids inventing an anchor
 * community in 0.1.0, and for good reason — attaching a deed to whichever village happened to be
 * nearest would let a player build or destroy standing with people who have no connection to what
 * they did.
 *
 * <p>Resolution never retains a live MCA object (§34): it extracts an id and a dimension and lets the
 * village go, so a cached {@link CommunityKey} cannot pin a level or an entity in memory.
 */
public final class CommunityResolver {

    private CommunityResolver() {
    }

    /**
     * The community a villager belongs to.
     *
     * <ol>
     *   <li>their home village, in the dimension they are currently in;</li>
     *   <li>failing that, the nearest village within the configured fallback radius.</li>
     * </ol>
     *
     * <p>The dimension always comes from the <em>villager's current level</em>, not from wherever the
     * village was first registered, because MCA allocates village ids per level and a villager can
     * only be resident in a village of the level they are in.
     */
    public static Optional<CommunityKey> resolve(Entity villager) {
        if (villager == null || !(villager.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        if (!McaCompat.isMcaVillager(villager)) {
            return Optional.empty();
        }
        OptionalInt home = McaCompat.homeVillageId(villager);
        if (home.isPresent()) {
            return CommunityKey.of(level.dimension(), home.getAsInt());
        }
        return resolveNearest(level, villager.blockPosition(), McaReputationConfig.villageSearchRadius());
    }

    /** The nearest community to a position within {@code radius}. */
    public static Optional<CommunityKey> resolveNearest(ServerLevel level, BlockPos pos, int radius) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        OptionalInt nearest = McaCompat.nearestVillageId(level, pos, Math.max(1, radius));
        return nearest.isPresent()
                ? CommunityKey.of(level.dimension(), nearest.getAsInt())
                : Optional.empty();
    }

    /** The nearest community to a position, using the configured fallback radius. */
    public static Optional<CommunityKey> resolveNearest(ServerLevel level, BlockPos pos) {
        return resolveNearest(level, pos, McaReputationConfig.villageSearchRadius());
    }

    /** The community around an entity's own position — the "here" of the command tree. */
    public static Optional<CommunityKey> resolveAround(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return resolveNearest(level, entity.blockPosition());
    }

    /**
     * Freshly resolved presentation facts for a community, or {@link CommunityMetadata#EMPTY} when the
     * village is gone. Callers merge this over what they already cached rather than replacing it, so a
     * deleted village keeps the name it had (§35.1).
     */
    public static CommunityMetadata readMetadata(ServerLevel level, CommunityKey key, long gameTime) {
        if (level == null || key == null || !key.dimension().equals(level.dimension().location())) {
            return CommunityMetadata.EMPTY;
        }
        Optional<String> name = McaCompat.villageName(level, key.villageId());
        Optional<BlockPos> center = McaCompat.villageCenter(level, key.villageId());
        if (name.isEmpty() && center.isEmpty()) {
            return CommunityMetadata.EMPTY;
        }
        return new CommunityMetadata(name.orElse(""), center, gameTime);
    }

    /** The level a community lives in, or empty when that dimension is not loaded. */
    public static Optional<ServerLevel> levelOf(net.minecraft.server.MinecraftServer server, CommunityKey key) {
        if (server == null || key == null) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(key.dimension())) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a villager currently belongs to a community, which is what a non-witness needs in order
     * to hear a rumour (§19.3). Falls back to "not a resident" when the dimension is unloaded — an
     * unknown answer must not manufacture knowledge.
     */
    public static boolean isResident(net.minecraft.server.MinecraftServer server, CommunityKey community,
                                     java.util.UUID villagerUuid) {
        return levelOf(server, community)
                .map(level -> McaCompat.isResident(level, community.villageId(), villagerUuid))
                .orElse(false);
    }
}
