package dev.otectus.mcareputation.reputation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live, immutable registry of tier ladders, swapped atomically by the datapack reload (§21.2).
 *
 * <h2>Two ids for one ladder</h2>
 *
 * <p>The canonical default ladder is {@code mcareputation:default}. MCA: Quests shipped
 * {@code mcaquests:default} first, and §32.4 requires that id to keep working — FTB Quests tasks,
 * existing packs, and saved task objects all name it. {@link #get} therefore falls back from one to
 * the other, so a pack that defines only the canonical id still satisfies a task that asks for the
 * legacy one and vice versa. A pack that defines <em>both</em> gets exactly what it asked for.
 *
 * <p>{@link #BUILTIN_DEFAULT} guarantees the system still works when a pack removes the default
 * ladder file entirely — thresholds, and therefore titles and Conversations biases, never simply
 * vanish.
 */
public final class ReputationTiers {

    /** Canonical id for the default ladder. */
    public static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("mcareputation", "default");

    /** The id MCA: Quests has always used; preserved as an alias (§32.4). */
    public static final ResourceLocation LEGACY_DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("mcaquests", "default");

    /**
     * The shipped ladder (spec Appendix A). The positive half is byte-for-byte the thresholds Quests
     * already used — 0/25/75/150/300 — so no existing world's standing changes meaning on upgrade;
     * the negative half is purely additive below zero.
     */
    public static final ReputationTierSet BUILTIN_DEFAULT = new ReputationTierSet(List.of(
            tier("infamous", -300, -4, -8, null),
            tier("hated", -150, -3, -6, null),
            tier("distrusted", -75, -2, -4, null),
            tier("wary", -25, -1, -2, null),
            tier("stranger", 0, 0, 0, null),
            tier("acquaintance", 25, 1, 2, null),
            tier("friend", 75, 2, 4, null),
            tier("honored", 150, 3, 6, ResourceLocation.fromNamespaceAndPath("mcaquests", "honored_of_village")),
            tier("revered", 300, 4, 8, ResourceLocation.fromNamespaceAndPath("mcaquests", "revered_of_village"))));

    private static volatile Map<ResourceLocation, ReputationTierSet> ladders = Map.of();

    private ReputationTiers() {
    }

    private static ReputationTier tier(String id, int threshold, int trust, int respect, ResourceLocation title) {
        return new ReputationTier(id, threshold,
                Component.translatable("mcareputation.tier." + id),
                Optional.of(Component.translatable("mcareputation.tier." + id + ".description")),
                trust, respect, Optional.ofNullable(title));
    }

    /** Replaces the live registry in one assignment; readers see either the old or the new map, never a mix. */
    public static void replaceAll(Map<ResourceLocation, ReputationTierSet> loaded) {
        ladders = Map.copyOf(loaded);
    }

    /**
     * The ladder with this id, honouring the canonical/legacy default alias described above. Empty for
     * a genuinely unknown id — callers that need a usable ladder regardless should use
     * {@link #getOrDefault}.
     */
    public static Optional<ReputationTierSet> get(ResourceLocation id) {
        if (id == null) {
            return Optional.empty();
        }
        ReputationTierSet direct = ladders.get(id);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (DEFAULT_ID.equals(id)) {
            return Optional.ofNullable(ladders.get(LEGACY_DEFAULT_ID));
        }
        if (LEGACY_DEFAULT_ID.equals(id)) {
            return Optional.ofNullable(ladders.get(DEFAULT_ID));
        }
        return Optional.empty();
    }

    /** Every ladder id currently loaded (used by the Quests FTB editor id sync). */
    public static Set<ResourceLocation> ids() {
        return ladders.keySet();
    }

    /** The active default ladder, falling back to {@link #BUILTIN_DEFAULT} when no pack supplies one. */
    public static ReputationTierSet getDefault() {
        return get(DEFAULT_ID).orElse(BUILTIN_DEFAULT);
    }

    /** The named ladder if known, otherwise the default ladder. Never null, never empty. */
    public static ReputationTierSet getOrDefault(ResourceLocation id) {
        return get(id).orElseGet(ReputationTiers::getDefault);
    }

    /**
     * Snapshot including the built-in fallback under both default ids, for validation and for the
     * {@code /mcareputation tiers} command listing.
     */
    public static Map<ResourceLocation, ReputationTierSet> allWithFallback() {
        Map<ResourceLocation, ReputationTierSet> all = new LinkedHashMap<>(ladders);
        all.putIfAbsent(DEFAULT_ID, getDefault());
        all.putIfAbsent(LEGACY_DEFAULT_ID, getDefault());
        return Map.copyOf(all);
    }
}
