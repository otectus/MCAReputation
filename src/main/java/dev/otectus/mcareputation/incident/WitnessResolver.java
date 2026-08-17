package dev.otectus.mcareputation.incident;

import dev.otectus.mcareputation.McaReputation;
import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who actually saw a deed happen (spec §19.1).
 *
 * <p>This is the only place in the mod that touches the world in bulk, and it does so <b>only when a
 * relevant event fires</b> — never on a tick, never over a village, never over the save (§34). The
 * query is a bounded AABB over already-loaded entities, so its cost is proportional to how crowded the
 * scene is and nothing else.
 *
 * <p>Determinism matters as much as accuracy. Candidates are sorted by distance and then by UUID
 * before the cap is applied, so with more than {@code maxWitnesses} villagers present the same set is
 * chosen every time — otherwise the same crime committed twice could produce different histories
 * depending on entity iteration order, and the tests could not assert anything.
 */
public final class WitnessResolver {

    private WitnessResolver() {
    }

    /**
     * Resolves the witnesses to an event at {@code origin}.
     *
     * @param level        the level the event happened in
     * @param origin       where it happened
     * @param actor        the player responsible; never a witness to their own deed
     * @param victim       the villager it happened to, or {@code null}
     * @param victimSurvives whether the victim is still alive afterwards. A living victim always knows
     *                       what was done to them even if nobody else saw (§19.1 rule 8); a dead one
     *                       is excluded, which is what makes an unwitnessed murder possible at all.
     */
    public static Set<UUID> resolve(ServerLevel level, Vec3 origin, @Nullable Entity actor,
                                    @Nullable Entity victim, boolean victimSurvives) {
        Set<UUID> witnesses = new LinkedHashSet<>();
        if (level == null || origin == null) {
            return witnesses;
        }

        // The victim of a survivable assault is the one witness who cannot be blocked, distracted, or
        // out of range.
        if (victim != null && victimSurvives && McaCompat.isLivingMcaVillager(victim)) {
            witnesses.add(victim.getUUID());
        }

        int radius = McaReputationConfig.witnessRadius();
        int cap = McaReputationConfig.maxWitnesses();
        boolean requireLineOfSight = McaReputationConfig.requireWitnessLineOfSight();
        UUID actorId = actor == null ? null : actor.getUUID();
        UUID victimId = victim == null ? null : victim.getUUID();

        AABB box = new AABB(origin, origin).inflate(radius);
        List<Entity> candidates;
        try {
            candidates = new ArrayList<>(level.getEntities((Entity) null, box, entity ->
                    McaCompat.isLivingMcaVillager(entity)
                            && !entity.getUUID().equals(actorId)
                            && !entity.getUUID().equals(victimId)));
        } catch (Throwable t) {
            McaReputation.LOGGER.debug("[MCA: Reputation] witness scan failed; treating the deed as unseen", t);
            return witnesses;
        }

        candidates.sort(Comparator
                .comparingDouble((Entity entity) -> entity.position().distanceToSqr(origin))
                .thenComparing(entity -> entity.getUUID().toString()));

        for (Entity candidate : candidates) {
            if (witnesses.size() >= cap) {
                break;
            }
            if (requireLineOfSight && candidate instanceof LivingEntity observer) {
                Entity subject = victim != null ? victim : actor;
                if (subject != null && !McaCompat.hasLineOfSight(observer, subject)) {
                    continue;
                }
            }
            witnesses.add(candidate.getUUID());
        }

        if (McaReputationConfig.debugLogging()) {
            McaReputation.LOGGER.debug("[MCA: Reputation] witness scan at {} found {} of {} candidate(s) "
                            + "within {} blocks (line of sight {})",
                    origin, witnesses.size(), candidates.size(), radius,
                    requireLineOfSight ? "required" : "not required");
        }
        return witnesses;
    }

    /**
     * The witnesses to a death, unioned with whatever the preceding assault already recorded (§20.2).
     *
     * <p>The union matters because the two moments can have different audiences: someone who saw the
     * first blow may have walked away before the last one, and their testimony should not evaporate.
     * The victim is never included — they are dead — which is precisely how a killing with no third
     * party present stays hidden.
     */
    public static Set<UUID> resolveForDeath(ServerLevel level, Vec3 origin, @Nullable Entity actor,
                                            @Nullable Entity victim, Set<UUID> priorWitnesses) {
        Set<UUID> combined = new LinkedHashSet<>(resolve(level, origin, actor, victim, false));
        int cap = McaReputationConfig.maxWitnesses();
        for (UUID prior : priorWitnesses) {
            if (combined.size() >= cap) {
                break;
            }
            // A dead victim's own testimony from the assault stage does not survive them.
            if (victim == null || !prior.equals(victim.getUUID())) {
                combined.add(prior);
            }
        }
        return combined;
    }
}
