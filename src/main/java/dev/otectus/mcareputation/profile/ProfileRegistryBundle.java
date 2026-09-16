package dev.otectus.mcareputation.profile;

import dev.otectus.mcareputation.credit.CreditPolicy;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live, immutable profile content generation: facets, recognition ladders, incident profiles and
 * credit policies, published in <b>one</b> assignment (spec §9.6).
 *
 * <h2>Why one bundle instead of four registries</h2>
 *
 * <p>These four reference each other. A profile names facets and a credit policy; a facet's range
 * decides whether the profile's authored sign is even legal. Publishing them separately would make a
 * cross-reference valid or invalid depending on reload ordering, and §9.6 explicitly forbids a
 * reference-dependent profile from being half-published. One record swapped in one assignment means a
 * reader sees either the whole previous generation or the whole next one.
 *
 * <p>{@link #generation()} increments on every publication. §9.4 freezes authored quantities onto
 * accepted deeds, so nothing downstream may re-read this bundle to recompute what an old deed was
 * worth; the generation number exists so a cache or an explanation can say <em>which</em> content
 * generation it was describing, and so a stale projection is detectable rather than merely wrong.
 *
 * <p>Definitions may vanish when a pack is removed. Every lookup here is therefore an
 * {@link Optional} or a neutral stand-in: stored units survive, only presentation and interpretation
 * degrade (§9.4).
 */
public record ProfileRegistryBundle(
        long generation,
        Map<ResourceLocation, FacetDefinition> facets,
        Map<ResourceLocation, RecognitionTierSet> recognitionLadders,
        Map<ResourceLocation, IncidentProfileDefinition> profiles,
        Map<ResourceLocation, CreditPolicy> creditPolicies) {

    /** §10.5: loaded facet definitions are bounded at 64. */
    public static final int MAX_FACETS = 64;

    /** §10.5: profile definitions are bounded at 256. */
    public static final int MAX_PROFILES = 256;

    /** §10.5: credit groups in one content generation are bounded at 64. */
    public static final int MAX_CREDIT_GROUPS = 64;

    /** Structural ceiling on recognition ladders in one generation. */
    public static final int MAX_RECOGNITION_LADDERS = 64;

    /** The generation before any datapack has been read: no profile content at all. */
    public static final ProfileRegistryBundle EMPTY =
            new ProfileRegistryBundle(0L, Map.of(), Map.of(), Map.of(), Map.of());

    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile ProfileRegistryBundle live = EMPTY;

    public ProfileRegistryBundle {
        facets = sortedCopy(facets);
        recognitionLadders = sortedCopy(recognitionLadders);
        profiles = sortedCopy(profiles);
        creditPolicies = sortedCopy(creditPolicies);
    }

    /**
     * Id-sorted and order-stable. Two packs that load the same content in different file orders must
     * produce equal bundles, and anything iterating a registry — diagnostics, the validator, a
     * projection — must do so in the same order on every JVM.
     */
    private static <T> Map<ResourceLocation, T> sortedCopy(Map<ResourceLocation, T> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<ResourceLocation, T> sorted = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        source.forEach((id, value) -> {
            if (id != null && value != null) {
                sorted.put(id, value);
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /** The current generation. Never null; {@link #EMPTY} before the first reload. */
    public static ProfileRegistryBundle current() {
        return live;
    }

    /**
     * Publishes a new generation in one assignment and returns it.
     *
     * <p>The caller is responsible for having validated the bundle as a whole first: this method
     * deliberately does not repair a half-consistent input, because silently dropping a dangling
     * reference here would hide the authoring error the reload is supposed to report.
     */
    public static ProfileRegistryBundle publish(Map<ResourceLocation, FacetDefinition> facets,
                                                Map<ResourceLocation, RecognitionTierSet> ladders,
                                                Map<ResourceLocation, IncidentProfileDefinition> profiles,
                                                Map<ResourceLocation, CreditPolicy> creditPolicies) {
        ProfileRegistryBundle next = new ProfileRegistryBundle(GENERATION.incrementAndGet(),
                facets, ladders, profiles, creditPolicies);
        live = next;
        return next;
    }

    /** Test/shutdown seam: drops all profile content without disturbing the generation counter's monotonicity. */
    public static void clear() {
        live = new ProfileRegistryBundle(GENERATION.incrementAndGet(), Map.of(), Map.of(), Map.of(),
                Map.of());
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public Optional<FacetDefinition> facet(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(facets.get(id));
    }

    /** The definition, or a neutral stand-in naming the missing id (§9.4). */
    public FacetDefinition facetOrUnknown(ResourceLocation id) {
        return facet(id).orElseGet(() -> FacetDefinition.unknown(id));
    }

    public Optional<IncidentProfileDefinition> profile(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(profiles.get(id));
    }

    public Optional<CreditPolicy> creditPolicy(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(creditPolicies.get(id));
    }

    /**
     * The policy governing a credit <em>group</em>, which is the accounting key trackers are stored
     * under. Two files may legitimately share a group only if they are identical, which the content
     * validator enforces; the first match in id order is therefore canonical.
     */
    public Optional<CreditPolicy> creditPolicyForGroup(ResourceLocation group) {
        if (group == null) {
            return Optional.empty();
        }
        for (CreditPolicy policy : creditPolicies.values()) {
            if (policy.group().equals(group)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /** The recognition ladder with this id, falling back to the canonical default then the built-in. */
    public RecognitionTierSet recognitionLadderOrDefault(ResourceLocation id) {
        RecognitionTierSet direct = id == null ? null : recognitionLadders.get(id);
        if (direct != null) {
            return direct;
        }
        RecognitionTierSet canonical = recognitionLadders.get(RecognitionTierSet.DEFAULT_ID);
        return canonical != null ? canonical : RecognitionTierSet.BUILTIN_DEFAULT;
    }

    public Set<ResourceLocation> creditGroups() {
        Set<ResourceLocation> groups = new java.util.TreeSet<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        creditPolicies.values().forEach(policy -> groups.add(policy.group()));
        return Collections.unmodifiableSet(groups);
    }

    /** Whether this generation carries any profile content at all. */
    public boolean isEmpty() {
        return facets.isEmpty() && recognitionLadders.isEmpty() && profiles.isEmpty()
                && creditPolicies.isEmpty();
    }
}
