package dev.otectus.mcareputation.reputation;

import dev.otectus.mcareputation.api.ChangeCause;
import dev.otectus.mcareputation.api.ReputationQuery;
import dev.otectus.mcareputation.community.CommunityKey;
import dev.otectus.mcareputation.state.ReputationSavedData;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.UUID;

/**
 * The §5 F14 truth table, in one place.
 *
 * <p>"What is this player's standing here" has five honest answers, and before this class each
 * consumer invented its own: a loot condition treated an absent record as "matches only an empty
 * query", the API gated the same question on a dialogue mod's config switch, and a disabled master
 * switch meant different things on different paths. The table:
 *
 * <table border="1">
 *   <caption>Effective standing</caption>
 *   <tr><th>State</th><th>Answer</th></tr>
 *   <tr><td>enabled, valid community, no record</td><td>{@link State#NEUTRAL_NEW}: score 0, neutral
 *       tier, and <b>no record is created</b></td></tr>
 *   <tr><td>enabled, existing record</td><td>{@link State#AVAILABLE}: the current reconciled
 *       standing</td></tr>
 *   <tr><td>invalid or unresolved community</td><td>{@link State#UNAVAILABLE_COMMUNITY}: no invented
 *       community, and requirements fail closed</td></tr>
 *   <tr><td>master switch off</td><td>{@link State#MASTER_DISABLED}: the neutral value, with retained
 *       state still inspectable through the diagnostics path</td></tr>
 * </table>
 *
 * <p>{@link State#FEATURE_DISABLED} is the same neutral answer for a feature that is switched off
 * rather than the whole mod — opinion, today.
 */
public final class StandingAvailability {

    /** Which row of the truth table an answer came from. */
    public enum State {

        /** A real, reconciled standing. */
        AVAILABLE,

        /** A valid community this player has no record with: a stranger, stored nowhere. */
        NEUTRAL_NEW,

        /** No community could be resolved; nothing may be invented for it. */
        UNAVAILABLE_COMMUNITY,

        /** The mod is switched off; gameplay influence is neutral. */
        MASTER_DISABLED,

        /** The specific feature asked about is switched off; standing itself is unaffected. */
        FEATURE_DISABLED;

        /** Whether a real, stored standing backs this answer. */
        public boolean isAvailable() {
            return this == AVAILABLE;
        }

        /** Whether numeric predicates evaluate against the effective neutral value. */
        public boolean isNeutral() {
            return this == NEUTRAL_NEW || this == MASTER_DISABLED || this == FEATURE_DISABLED;
        }
    }

    /** One row of the table, resolved. {@code score} and {@code tierId} are always usable. */
    public record EffectiveStanding(State state, int score, String tierId) {
    }

    private StandingAvailability() {
    }

    static EffectiveStanding of(ServiceContext ctx, ReputationSavedData data, UUID player,
                                CommunityKey community, long gameTime) {
        return of(ctx.policy(), data, player, community, gameTime);
    }

    /**
     * The effective standing for one player and community. Reconciles through the gate with
     * {@link ReconciliationService.Intent#QUERY}, so an existing record is current and an absent one
     * stays absent.
     */
    public static EffectiveStanding of(ReputationPolicy policy, ReputationSavedData data, UUID player,
                                       CommunityKey community, long gameTime) {
        if (player == null || community == null || data == null) {
            return new EffectiveStanding(State.UNAVAILABLE_COMMUNITY, 0, neutralTierId());
        }
        if (policy == null || !policy.enabled()) {
            return new EffectiveStanding(State.MASTER_DISABLED, 0, neutralTierId());
        }
        boolean known = data.player(player).flatMap(record -> record.community(community)).isPresent();
        if (!known) {
            // Everyone starts a stranger, and asking the question must not write that down.
            return new EffectiveStanding(State.NEUTRAL_NEW, 0, neutralTierId());
        }
        ReconciliationService.ReconcileOutcome outcome = ReconciliationService.reconcile(policy, data,
                player, community, gameTime, ChangeCause.DECAY, ReconciliationService.Intent.QUERY);
        return new EffectiveStanding(State.AVAILABLE, outcome.newScore(), outcome.newTierId());
    }

    /**
     * Evaluates an authored standing query against an effective standing (§5 F14).
     *
     * <p>Fails closed when no community could be resolved; everywhere else the numeric and tier
     * predicates evaluate against the effective value, neutral included, so a {@code min: 0} condition
     * answers the same before and after the player has a record. Title predicates test the real title
     * set when standing is {@link State#AVAILABLE} and an empty one when it is neutral — a title the
     * player has not earned is absent, not unspecified.
     */
    public static boolean matches(EffectiveStanding standing, ReputationQuery query,
                                  Set<ResourceLocation> villageTitles,
                                  Set<ResourceLocation> globalTitles) {
        if (standing == null || query == null) {
            return false;
        }
        if (standing.state() == State.UNAVAILABLE_COMMUNITY) {
            return false;
        }
        ReputationTierSet ladder = ReputationTiers.getDefault();
        if (query.min().isPresent() && standing.score() < query.min().getAsInt()) {
            return false;
        }
        if (query.max().isPresent() && standing.score() > query.max().getAsInt()) {
            return false;
        }
        int currentIndex = ladder.indexOf(standing.tierId());
        if (query.minTier().isPresent()) {
            int bound = ladder.indexOf(query.minTier().get());
            if (bound < 0 || currentIndex < bound) {
                return false;
            }
        }
        if (query.maxTier().isPresent()) {
            int bound = ladder.indexOf(query.maxTier().get());
            if (bound < 0 || currentIndex > bound) {
                return false;
            }
        }
        if (query.hasTitle().isPresent()) {
            ResourceLocation title = query.hasTitle().get();
            return standing.state().isAvailable()
                    && (villageTitles.contains(title) || globalTitles.contains(title));
        }
        return true;
    }

    /** The tier a score of zero falls in: what a stranger reads as on the default ladder. */
    public static String neutralTierId() {
        return ReputationTiers.getDefault().tierFor(0).id();
    }
}
