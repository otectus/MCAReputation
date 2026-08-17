package dev.otectus.mcareputation.reputation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An ordered ladder of {@link ReputationTier}s (spec §17.2, §22.1). Every lookup here is pure, so
 * {@code TierTest} can exercise all the boundaries — including the negative ones — with no game
 * running.
 *
 * <p>Thresholds must ascend strictly and ids must be unique; the codec enforces both, so a ladder that
 * reaches the registry is always well formed and the lookups below need no defensive re-sorting.
 * §21.3 additionally requires the floor to sit at or below zero, which
 * {@code dev.otectus.mcareputation.data.ReputationContentValidator} checks against the configured
 * minimum score.
 */
public record ReputationTierSet(List<ReputationTier> tiers) {

    public static final Codec<ReputationTierSet> CODEC = RecordCodecBuilder
            .<ReputationTierSet>create(instance -> instance.group(
                    ReputationTier.CODEC.listOf().fieldOf("tiers").forGetter(ReputationTierSet::tiers)
            ).apply(instance, ReputationTierSet::new))
            .flatXmap(ReputationTierSet::validate, ReputationTierSet::validate);

    public ReputationTierSet {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    private static DataResult<ReputationTierSet> validate(ReputationTierSet set) {
        if (set.tiers.isEmpty()) {
            return DataResult.error(() -> "a reputation ladder must define at least one tier");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < set.tiers.size(); i++) {
            ReputationTier tier = set.tiers.get(i);
            if (!seen.add(tier.id())) {
                return DataResult.error(() -> "duplicate tier id '" + tier.id() + "'");
            }
            if (i > 0) {
                ReputationTier previous = set.tiers.get(i - 1);
                if (tier.threshold() <= previous.threshold()) {
                    return DataResult.error(() -> "tier thresholds must ascend strictly: '"
                            + previous.id() + "' (" + previous.threshold() + ") is not below '"
                            + tier.id() + "' (" + tier.threshold() + ")");
                }
            }
        }
        return DataResult.success(set);
    }

    public boolean isEmpty() {
        return tiers.isEmpty();
    }

    public int size() {
        return tiers.size();
    }

    /**
     * The tier containing {@code score}. The lowest tier is the floor, so a score below every
     * threshold still resolves to a real tier rather than to nothing — a player at {-500} on the
     * default ladder is Infamous, not tierless.
     */
    public ReputationTier tierFor(int score) {
        ReputationTier match = tiers.get(0);
        for (ReputationTier tier : tiers) {
            if (score >= tier.threshold()) {
                match = tier;
            } else {
                break;
            }
        }
        return match;
    }

    /** Ladder index of a tier id, or {@code -1} when this ladder has no such tier. */
    public int indexOf(String tierId) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equals(tierId)) {
                return i;
            }
        }
        return -1;
    }

    public Optional<ReputationTier> byId(String tierId) {
        int index = indexOf(tierId);
        return index < 0 ? Optional.empty() : Optional.of(tiers.get(index));
    }

    /** The rung above the one containing {@code score}, or empty at the top of the ladder. */
    public Optional<ReputationTier> nextTier(int score) {
        int index = indexOf(tierFor(score).id());
        return index + 1 < tiers.size() ? Optional.of(tiers.get(index + 1)) : Optional.empty();
    }

    /** The rung below the one containing {@code score}, or empty at the floor. */
    public Optional<ReputationTier> previousTier(int score) {
        int index = indexOf(tierFor(score).id());
        return index > 0 ? Optional.of(tiers.get(index - 1)) : Optional.empty();
    }

    /**
     * Whether a move from {@code oldScore} to {@code newScore} crosses a tier boundary, and in which
     * direction. Pure, and the single source of truth for §17.3's transition rules — the celebratory
     * toast, the subdued downward message, and the {@code ReputationTierChangedEvent} all read the
     * same answer, so they can never disagree about whether something happened.
     */
    public Transition transition(int oldScore, int newScore) {
        ReputationTier from = tierFor(oldScore);
        ReputationTier to = tierFor(newScore);
        if (from.id().equals(to.id())) {
            return new Transition(from, to, 0);
        }
        return new Transition(from, to, Integer.compare(indexOf(to.id()), indexOf(from.id())));
    }

    /**
     * The result of {@link #transition}. {@code direction} is {@code +1} upward, {@code -1} downward,
     * {@code 0} for no change.
     */
    public record Transition(ReputationTier from, ReputationTier to, int direction) {

        public boolean changed() {
            return direction != 0;
        }

        public boolean upward() {
            return direction > 0;
        }

        public boolean downward() {
            return direction < 0;
        }
    }

    /**
     * Whether reaching {@code newTierId} beats the recorded high-water mark, i.e. whether this is the
     * first time the player has ever stood this well with this community on this ladder.
     *
     * <p>This is what stops the celebratory toast and the tier title from replaying every time a
     * player oscillates around a threshold (§17.3, §17.4). An unknown high-water id — a tier that a
     * datapack has since removed — is treated as "never reached", which errs toward re-granting a
     * title the player has already earned rather than withholding one they have not.
     */
    public boolean isNewHighWater(String newTierId, String highWaterTierId) {
        int newIndex = indexOf(newTierId);
        if (newIndex < 0) {
            return false;
        }
        int highWaterIndex = highWaterTierId == null ? -1 : indexOf(highWaterTierId);
        return newIndex > highWaterIndex;
    }
}
