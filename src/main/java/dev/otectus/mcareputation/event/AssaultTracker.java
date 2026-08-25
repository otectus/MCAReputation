package dev.otectus.mcareputation.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived, in-memory record of who hit whom recently, so the assault hook can answer one question:
 * <b>did this villager strike first?</b> (spec §20.1).
 *
 * <p>Deliberately not persisted. Self-defence is a judgement about the last few seconds, and a window
 * that survived a server restart would be meaningless — a player logging back in hours later is not
 * defending themselves from a blow struck before the restart. Losing this map on shutdown is the
 * correct behaviour, not a limitation.
 *
 * <p>Bounded with LRU eviction so a busy server cannot grow it without limit, and swept of stale
 * entries on write. Entries older than the configured window are worthless anyway.
 *
 * <p>Access is confined to the server thread (the Forge damage events that write it and the assault
 * hook that reads it all run there), so no synchronisation is needed.
 */
public final class AssaultTracker {

    /** Enough for a very busy server's recent scuffles; stale entries are swept before this bites. */
    private static final int MAX_ENTRIES = 512;

    /** villager+player pair → game time the villager last damaged that player. */
    private static final Map<String, Long> VILLAGER_STRUCK_PLAYER = new LinkedHashMap<>(64, 0.75f, true);

    private AssaultTracker() {
    }

    private static String key(UUID villager, UUID player) {
        return villager + "|" + player;
    }

    /** Records that a villager damaged a player. Called from the damage hook, server side. */
    public static void recordVillagerHitPlayer(UUID villager, UUID player, long gameTime) {
        if (villager == null || player == null) {
            return;
        }
        VILLAGER_STRUCK_PLAYER.put(key(villager, player), gameTime);
        if (VILLAGER_STRUCK_PLAYER.size() > MAX_ENTRIES) {
            // Access-ordered map: the first key is the least recently used.
            java.util.Iterator<String> it = VILLAGER_STRUCK_PLAYER.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * Whether this villager damaged this player within {@code windowTicks} of {@code gameTime}.
     *
     * <p>A window of zero disables self-defence entirely, which is what {@code selfDefenseWindowTicks=0}
     * is for. A negative elapsed time — the clock was wound back — counts as outside the window, since
     * "the villager hit me in the future" is not a defence.
     */
    public static boolean villagerStruckFirst(UUID villager, UUID player, long gameTime, int windowTicks) {
        if (villager == null || player == null || windowTicks <= 0) {
            return false;
        }
        Long when = VILLAGER_STRUCK_PLAYER.get(key(villager, player));
        if (when == null) {
            return false;
        }
        long elapsed = gameTime - when;
        return elapsed >= 0 && elapsed <= windowTicks;
    }

    /** Drops entries older than {@code windowTicks}. Called opportunistically from the periodic sweep. */
    public static void sweep(long gameTime, int windowTicks) {
        VILLAGER_STRUCK_PLAYER.entrySet().removeIf(entry -> {
            long elapsed = gameTime - entry.getValue();
            return elapsed < 0 || elapsed > windowTicks;
        });
    }

    /** Clears everything. Called on server stop so a subsequent world in the same JVM starts clean. */
    public static void clear() {
        VILLAGER_STRUCK_PLAYER.clear();
    }

    /** Test/diagnostic seam. */
    public static int size() {
        return VILLAGER_STRUCK_PLAYER.size();
    }
}
