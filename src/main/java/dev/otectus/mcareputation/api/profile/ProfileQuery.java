package dev.otectus.mcareputation.api.profile;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * An authored predicate over a public profile (§14.5).
 *
 * <p>Every clause is ANDed, and the semantics are deliberately conservative:
 *
 * <ul>
 *   <li><b>Unknown ids fail closed.</b> A facet or recognition tier this build has never heard of
 *       cannot be satisfied — a typo in a datapack must not open a gate.</li>
 *   <li><b>Unobserved is not negative evidence.</b> A facet predicate requires at least one live
 *       evidence item by default, so "nonviolent" is not satisfied by a community that has simply
 *       never seen the player. {@link FacetPredicate#allowUnobserved} is the named escape hatch for
 *       the authors who really do mean "no contrary evidence is known".</li>
 *   <li><b>Invalid queries fail closed.</b> Inverted bounds, blank ids, duplicate facets and
 *       over-long lists make {@link #valid()} false, and an invalid query answers unavailable rather
 *       than matching.</li>
 *   <li><b>Recognition zero is a legitimate answer.</b> A complete-history stranger really does have
 *       zero recognition, and {@code max_recognition: 0} is the way to ask for one.</li>
 * </ul>
 *
 * <p>{@link #allowPartialHistory} decides what happens on a save whose history is not complete
 * (§19.3). The clauses that cannot survive missing history are the ones that rely on an upper bound
 * or on {@code allowUnobserved}: legacy history can only ever <em>hide</em> evidence, so an upper
 * bound or an absence claim may be wrong while an observed lower bound is still real evidence.
 * {@link #dependsOnCompleteHistory()} names exactly those clauses; a query containing one refuses on
 * partial coverage unless it opts in. (The residual case an author should know about: a bipolar facet
 * whose hidden adverse evidence would have lowered a value that a lower bound now passes. §14.5
 * treats a positive observed-evidence gate as opt-in-able anyway, and this implementation follows
 * it.)
 *
 * @since MCA: Reputation 0.6.0
 */
public record ProfileQuery(OptionalInt minRecognition, OptionalInt maxRecognition,
                           Optional<String> minRecognitionTier, List<FacetPredicate> facets,
                           boolean allowPartialHistory) {

    /** Bounded authoring surface: one condition cannot ask about more facets than exist in a deed set. */
    public static final int MAX_FACET_PREDICATES = 16;

    /** The largest evidence requirement a predicate may state, matching the retained-ledger bound. */
    public static final int MAX_MIN_EVIDENCE = 64;

    public ProfileQuery {
        minRecognition = minRecognition == null ? OptionalInt.empty() : minRecognition;
        maxRecognition = maxRecognition == null ? OptionalInt.empty() : maxRecognition;
        minRecognitionTier = minRecognitionTier == null ? Optional.empty() : minRecognitionTier;
        facets = sorted(facets);
    }

    private static List<FacetPredicate> sorted(List<FacetPredicate> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<FacetPredicate> copy = new ArrayList<>(raw);
        copy.sort(Comparator.comparing(predicate -> predicate.facet() == null
                ? ""
                : predicate.facet().toString()));
        return List.copyOf(copy);
    }

    /** Matches everything: the query an authored condition with no profile clause produces. */
    public static ProfileQuery any() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * One facet clause.
     *
     * @param facet          the facet id; an unknown one fails closed
     * @param min            inclusive lower bound on the facet's display value
     * @param max            inclusive upper bound
     * @param minEvidence    how many live evidence items the facet must carry; {@code 1} by default,
     *                       because a value with nothing behind it describes nobody (§8.2)
     * @param allowUnobserved whether a facet with no evidence at all may satisfy this clause, which
     *                       is how "no contrary evidence is known" is expressed
     */
    public record FacetPredicate(ResourceLocation facet, OptionalInt min, OptionalInt max,
                                 int minEvidence, boolean allowUnobserved) {

        public FacetPredicate {
            min = min == null ? OptionalInt.empty() : min;
            max = max == null ? OptionalInt.empty() : max;
            minEvidence = Math.max(0, minEvidence);
        }

        /** The default clause: a bound, one evidence item, and no absence escape hatch. */
        public static FacetPredicate atLeast(ResourceLocation facet, int min) {
            return new FacetPredicate(facet, OptionalInt.of(min), OptionalInt.empty(), 1, false);
        }

        public static FacetPredicate atMost(ResourceLocation facet, int max) {
            return new FacetPredicate(facet, OptionalInt.empty(), OptionalInt.of(max), 1, false);
        }

        boolean valid() {
            if (facet == null || facet.getPath().isBlank() || facet.getNamespace().isBlank()) {
                return false;
            }
            if (minEvidence > MAX_MIN_EVIDENCE) {
                return false;
            }
            return !(min.isPresent() && max.isPresent() && min.getAsInt() > max.getAsInt());
        }

        /** Whether this clause can be wrong on a save with missing history. */
        boolean dependsOnCompleteHistory() {
            return max.isPresent() || allowUnobserved;
        }
    }

    /**
     * Whether this query is well formed. An invalid query never matches; it answers unavailable, so
     * the authored fallback runs instead of silently failing open.
     */
    public boolean valid() {
        if (facets.size() > MAX_FACET_PREDICATES) {
            return false;
        }
        if (minRecognition.isPresent() && minRecognition.getAsInt() < 0) {
            return false;
        }
        if (maxRecognition.isPresent() && maxRecognition.getAsInt() < 0) {
            return false;
        }
        if (minRecognition.isPresent() && maxRecognition.isPresent()
                && minRecognition.getAsInt() > maxRecognition.getAsInt()) {
            return false;
        }
        if (minRecognitionTier.isPresent() && minRecognitionTier.get().isBlank()) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (FacetPredicate predicate : facets) {
            if (predicate == null || !predicate.valid()) {
                return false;
            }
            if (!seen.add(predicate.facet().toString())) {
                return false;
            }
        }
        return true;
    }

    /** Whether any clause in this query relies on history this save may not have (§14.5). */
    public boolean dependsOnCompleteHistory() {
        if (maxRecognition.isPresent()) {
            return true;
        }
        for (FacetPredicate predicate : facets) {
            if (predicate.dependsOnCompleteHistory()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this query states no clause at all, and therefore matches any available profile. */
    public boolean isEmpty() {
        return minRecognition.isEmpty() && maxRecognition.isEmpty() && minRecognitionTier.isEmpty()
                && facets.isEmpty();
    }

    /** Fluent builder; every setter is optional and the result is immutable. */
    public static final class Builder {

        private OptionalInt minRecognition = OptionalInt.empty();
        private OptionalInt maxRecognition = OptionalInt.empty();
        private Optional<String> minRecognitionTier = Optional.empty();
        private final List<FacetPredicate> facets = new ArrayList<>();
        private boolean allowPartialHistory;

        private Builder() {
        }

        public Builder minRecognition(int value) {
            this.minRecognition = OptionalInt.of(value);
            return this;
        }

        public Builder maxRecognition(int value) {
            this.maxRecognition = OptionalInt.of(value);
            return this;
        }

        public Builder minRecognitionTier(String tierId) {
            this.minRecognitionTier = Optional.ofNullable(tierId);
            return this;
        }

        public Builder facet(FacetPredicate predicate) {
            if (predicate != null) {
                facets.add(predicate);
            }
            return this;
        }

        public Builder facet(ResourceLocation facet, int min, int minEvidence) {
            return facet(new FacetPredicate(facet, OptionalInt.of(min), OptionalInt.empty(),
                    minEvidence, false));
        }

        public Builder allowPartialHistory(boolean value) {
            this.allowPartialHistory = value;
            return this;
        }

        public ProfileQuery build() {
            return new ProfileQuery(minRecognition, maxRecognition, minRecognitionTier,
                    List.copyOf(facets), allowPartialHistory);
        }
    }
}
