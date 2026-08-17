package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.community.CommunityKey;

import java.util.UUID;

/**
 * Who knows what, and when (spec §19.2–§19.3).
 *
 * <h2>Why this is a hash and not a data structure</h2>
 *
 * <p>The obvious implementation of "news spreads through the village" is a growing set of
 * (villager, incident) pairs, updated on a tick. That is exactly what §19.3 and §34 forbid: it costs
 * tick time proportional to population, it grows the save file without bound, and it makes a
 * villager's knowledge depend on whether their chunk happened to be loaded.
 *
 * <p>Instead, each (incident, villager, community) triple is hashed into a fixed delay inside the
 * configured rumour window. The villager knows the incident once that much time has elapsed. Nothing
 * is stored, nothing ticks, and the answer is identical on every query, on every restart, and on
 * every machine — which is also what makes it testable.
 *
 * <p>The result is <b>monotonic</b>: {@code elapsed} is the incident's own monotonic age counter, so
 * once a villager knows something they cannot un-know it, not even by winding the world clock back.
 *
 * <p>Every method here is pure and free of Minecraft types.
 */
public final class AwarenessResolver {

    private AwarenessResolver() {
    }

    /**
     * Whether {@code villager} currently knows about {@code incident}.
     *
     * @param resident whether the villager is currently a resident of the incident's community.
     *                 A villager who has moved away keeps knowledge only if they witnessed the deed
     *                 themselves (§19.3) — hearsay does not follow people between villages in 0.1.0.
     * @param gameTime current game time, used only via the incident's monotonic age
     */
    public static boolean knows(IncidentRecord incident, UUID villager, boolean resident, long gameTime,
                                int minRumorDelayTicks, int maxRumorDelayTicks) {
        if (incident == null || villager == null) {
            return false;
        }
        // A witness always knows, forever, wherever they now live.
        if (incident.isWitness(villager)) {
            return true;
        }
        return switch (incident.visibility().effective()) {
            // Private incidents never spread. Only a subject of the deed itself may recall it, which
            // is what makes a promise something between two people rather than village news.
            case PRIVATE -> isKnowingSubject(incident, villager);
            // Public business: every current resident knows at once.
            case VILLAGE -> resident;
            // Hearsay: witnesses knew immediately (handled above); everyone else waits their own
            // deterministic while.
            case WITNESSED -> resident && incident.ageTicks(gameTime)
                    >= rumorDelayTicks(incident.id(), villager, incident.community(),
                            minRumorDelayTicks, maxRumorDelayTicks);
            // GLOBAL_RESERVED collapsed to VILLAGE by effective(); this arm is unreachable.
            case GLOBAL_RESERVED -> resident;
        };
    }

    /**
     * True when the villager is one of the incident's own subjects. This is the sole route by which a
     * {@code PRIVATE} incident is knowable: the villager a promise was made to remembers it, and
     * nobody else ever hears.
     */
    public static boolean isKnowingSubject(IncidentRecord incident, UUID villager) {
        return incident.subjects().stream()
                .anyMatch(subject -> subject.uuid().map(villager::equals).orElse(false));
    }

    /**
     * The fixed delay, in ticks, before this particular villager hears this particular rumour.
     *
     * <p>Stable for a given triple and uniformly spread across {@code [min, max]}, so a village does
     * not learn en masse at one instant. Deriving it from the community key as well as the two UUIDs
     * means the same villager and the same incident in a different community — which can happen after
     * a village merge — do not reuse the same draw.
     *
     * <p>The bounds are normalised defensively: a config where {@code max < min} yields {@code min}
     * rather than an exception or a negative range (§23.1 requires max ≥ min, but manual config
     * editing is not required to honour it).
     */
    public static long rumorDelayTicks(UUID incidentId, UUID villager, CommunityKey community,
                                       int minRumorDelayTicks, int maxRumorDelayTicks) {
        long min = Math.max(0, Math.min(minRumorDelayTicks, maxRumorDelayTicks));
        long max = Math.max(0, Math.max(minRumorDelayTicks, maxRumorDelayTicks));
        long range = max - min + 1L;
        if (range <= 1L) {
            return min;
        }
        long hash = mix(incidentId, villager, community);
        return min + Math.floorMod(hash, range);
    }

    /**
     * SplixMix64-style finalizer over the two UUIDs and the community key.
     *
     * <p>A plain {@code Objects.hash} would do here in the sense that it compiles, but its low bits
     * are poorly distributed for values as structured as sequential village ids, which would cluster
     * whole villages onto the same delay. The finalizer avalanches every input bit, so adjacent
     * village ids produce unrelated delays.
     */
    private static long mix(UUID incidentId, UUID villager, CommunityKey community) {
        long h = 0x9E3779B97F4A7C15L;
        h = stir(h ^ incidentId.getMostSignificantBits());
        h = stir(h ^ incidentId.getLeastSignificantBits());
        h = stir(h ^ villager.getMostSignificantBits());
        h = stir(h ^ villager.getLeastSignificantBits());
        h = stir(h ^ (long) community.dimension().hashCode());
        h = stir(h ^ (long) community.villageId());
        return h;
    }

    private static long stir(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Whether an incident is tellable as gossip by this villager right now: they must know it, it must
     * carry a gossip phrase, and it must still be within the caller's age window.
     *
     * <p>Conversations calls this through the bridge; Reputation answers only "does the teller know
     * this fact", never "what should they say" (§19.4, §30.4).
     */
    public static boolean canTell(IncidentRecord incident, IncidentDefinition definition, UUID villager,
                                  boolean resident, long gameTime, long maxAgeTicks,
                                  int minRumorDelayTicks, int maxRumorDelayTicks) {
        if (definition == null || !definition.gossip().isTellable()) {
            return false;
        }
        if (maxAgeTicks > 0 && incident.ageTicks(gameTime) > maxAgeTicks) {
            return false;
        }
        return knows(incident, villager, resident, gameTime, minRumorDelayTicks, maxRumorDelayTicks);
    }
}
