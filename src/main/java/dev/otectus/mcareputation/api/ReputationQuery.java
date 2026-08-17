package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * A standing test, as authored in a dialogue or quest condition (spec §30.2).
 *
 * <p>All fields are optional and ANDed. An empty query matches any snapshot, which makes the
 * "reputation block present but empty" case harmless rather than a parse error.
 *
 * <p>Tier bounds are given by id and compared by <em>ladder index</em>, not by threshold, so a pack
 * that inserts a new rung does not silently change what {@code min_tier: "friend"} means. An unknown
 * tier id makes the query fail to match rather than throwing — §30.2 requires an unknown tier, title,
 * or community to return a safe zero so the authored disabled-context fallback fires.
 */
public record ReputationQuery(
        OptionalInt min,
        OptionalInt max,
        Optional<String> minTier,
        Optional<String> maxTier,
        Optional<ResourceLocation> hasTitle) {

    public static final ReputationQuery ANY =
            new ReputationQuery(OptionalInt.empty(), OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());

    public ReputationQuery {
        min = min == null ? OptionalInt.empty() : min;
        max = max == null ? OptionalInt.empty() : max;
        minTier = minTier == null ? Optional.empty() : minTier;
        maxTier = maxTier == null ? Optional.empty() : maxTier;
        hasTitle = hasTitle == null ? Optional.empty() : hasTitle;
    }

    public boolean isEmpty() {
        return min.isEmpty() && max.isEmpty() && minTier.isEmpty() && maxTier.isEmpty() && hasTitle.isEmpty();
    }

    /**
     * Whether a snapshot satisfies this query.
     *
     * @param ladderIndexOf resolves a tier id to its ladder index, or {@code -1} when unknown.
     *                      Supplied by the caller so this record stays free of registry lookups and
     *                      remains unit-testable.
     */
    public boolean matches(ReputationSnapshot snapshot, java.util.function.ToIntFunction<String> ladderIndexOf) {
        if (snapshot == null) {
            return false;
        }
        if (min.isPresent() && snapshot.score() < min.getAsInt()) {
            return false;
        }
        if (max.isPresent() && snapshot.score() > max.getAsInt()) {
            return false;
        }
        int currentIndex = ladderIndexOf.applyAsInt(snapshot.tierId());
        if (minTier.isPresent()) {
            int bound = ladderIndexOf.applyAsInt(minTier.get());
            if (bound < 0 || currentIndex < bound) {
                return false;
            }
        }
        if (maxTier.isPresent()) {
            int bound = ladderIndexOf.applyAsInt(maxTier.get());
            if (bound < 0 || currentIndex > bound) {
                return false;
            }
        }
        if (hasTitle.isPresent()) {
            ResourceLocation title = hasTitle.get();
            return snapshot.villageTitles().contains(title) || snapshot.globalTitles().contains(title);
        }
        return true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private OptionalInt min = OptionalInt.empty();
        private OptionalInt max = OptionalInt.empty();
        private Optional<String> minTier = Optional.empty();
        private Optional<String> maxTier = Optional.empty();
        private Optional<ResourceLocation> hasTitle = Optional.empty();

        public Builder min(int value) {
            this.min = OptionalInt.of(value);
            return this;
        }

        public Builder max(int value) {
            this.max = OptionalInt.of(value);
            return this;
        }

        public Builder minTier(String tierId) {
            this.minTier = Optional.ofNullable(tierId).filter(s -> !s.isBlank());
            return this;
        }

        public Builder maxTier(String tierId) {
            this.maxTier = Optional.ofNullable(tierId).filter(s -> !s.isBlank());
            return this;
        }

        public Builder hasTitle(ResourceLocation title) {
            this.hasTitle = Optional.ofNullable(title);
            return this;
        }

        public ReputationQuery build() {
            return new ReputationQuery(min, max, minTier, maxTier, hasTitle);
        }
    }
}
