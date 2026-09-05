package dev.otectus.mcareputation.event;

import java.util.UUID;

/**
 * The dedupe keys the core deed hooks hand to {@code ReputationRequest.dedupeKey} (§14.2).
 *
 * <p>Split out of {@link ReputationDeedEvents} because these strings are the entire anti-farm story
 * for the rescue, cure, raid and PvP deeds, and a bucket boundary is exactly the sort of arithmetic that is worth testing
 * without a server, a level, or a registry attached. Nothing here touches Minecraft: every input is a
 * UUID, a tick count, or an id already reduced to a string.
 *
 * <p>Rescue uses a <b>time bucket</b> rather than the sliding window an assault uses. The two hooks
 * want opposite failure modes: an assault must never lose a genuine second beating, so it looks for a
 * live record; a rescue must never pay twice for the same kited mob, so a fixed bucket that a player
 * cannot restart by acting sooner is the safer arithmetic. Overpaying a rescue is farmable; missing
 * one is a shrug.
 *
 * @since MCA: Reputation 0.4.0
 */
public final class DeedKeys {

    private DeedKeys() {
    }

    /**
     * The bucket index a game time falls in.
     *
     * <p>{@code bucketTicks <= 0} gives the tick itself, which makes every deed distinct — the
     * configured way to switch coalescing off.
     */
    public static long bucket(long gameTime, int bucketTicks) {
        if (bucketTicks <= 0) {
            return gameTime;
        }
        return Math.floorDiv(gameTime, bucketTicks);
    }

    /** One credit per (player, villager) per bucket, whatever killed the mob in between. */
    public static String rescue(UUID player, UUID villager, long bucket) {
        return "rescue:" + player + ":" + villager + ":" + bucket;
    }

    /** A villager can only be cured once per curer; the zombie's UUID carries over to the villager. */
    public static String cure(UUID player, UUID villager) {
        return "cure:" + player + ":" + villager;
    }

    /**
     * Keyed on the raid's own id, not on time: {@code MobEffectEvent.Added} fires again every time
     * hero of the village is refreshed, and all of those firings are the same victory.
     */
    public static String raid(UUID player, String community, int raidId) {
        return "raid:" + player + ":" + community + ":" + raidId;
    }

    /** Exact tick, as the killing hook uses: only a duplicated death event has to be absorbed. */
    public static String pvp(UUID killer, UUID victim, long gameTime) {
        return "pvp:" + killer + ":" + victim + ":" + gameTime;
    }
}
