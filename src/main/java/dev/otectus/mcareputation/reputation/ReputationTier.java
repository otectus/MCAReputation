package dev.otectus.mcareputation.reputation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.util.ExtraCodecs;

import java.util.Optional;

/**
 * One named rung on a {@link ReputationTierSet} (spec §17.2, §22.1).
 *
 * <p>{@code threshold} is the inclusive minimum score for this tier; the lowest tier in a ladder acts
 * as the floor for anything beneath it. Tier {@code id}s are bare strings ({@code "friend"}), not
 * resource locations — MCA: Quests' shipped ladders and its {@code repTierHW} save tag already use
 * bare strings, and changing that would invalidate every existing tier datapack and high-water mark
 * for no benefit. The <em>ladder</em> is what carries a namespaced id.
 *
 * <h2>The biases</h2>
 *
 * <p>{@code trust_bias} and {@code respect_bias} are the only channel by which public standing reaches
 * MCA: Conversations' private disposition checks (§30.3). They are read as a small additive context
 * term — Reputation never writes a disposition axis and never grants hearts (§4). Validation keeps
 * shipped values within ±8 and rejects anything ±15 or beyond, so the term always stays smaller than
 * the 15-point tier margin in Conversations' check resolver: standing can colour a check, never decide
 * it single-handedly.
 */
public record ReputationTier(
        String id,
        int threshold,
        Component name,
        Optional<Component> description,
        int trustBias,
        int respectBias,
        Optional<ResourceLocation> grantsTitle) {

    /** §21.3: bias magnitudes at or beyond this are a validation error. */
    public static final int BIAS_HARD_LIMIT = 15;

    /** §21.3: shipped content must stay within this. */
    public static final int BIAS_SHIPPED_LIMIT = 8;

    public static final int MAX_ID_LENGTH = 48;

    public static final Codec<ReputationTier> CODEC = RecordCodecBuilder
            .<ReputationTier>create(instance -> instance.group(
                    Codec.STRING.fieldOf("id").forGetter(ReputationTier::id),
                    Codec.INT.fieldOf("threshold").forGetter(ReputationTier::threshold),
                    // Accepts both {"translate": "..."} and a bare "Friend" string, because
                    // Component.Serializer reads a JSON primitive as a literal. That is what lets the
                    // legacy mcaquests ladders (whose codec is Codec.STRING) load unchanged (§22.1).
                    ExtraCodecs.COMPONENT.fieldOf("name").forGetter(ReputationTier::name),
                    StrictCodecs.strictOptional(ExtraCodecs.COMPONENT, "description").forGetter(ReputationTier::description),
                    StrictCodecs.strictOptional(Codec.INT, "trust_bias", 0).forGetter(ReputationTier::trustBias),
                    StrictCodecs.strictOptional(Codec.INT, "respect_bias", 0).forGetter(ReputationTier::respectBias),
                    StrictCodecs.strictOptional(ResourceLocation.CODEC, "grants_title").forGetter(ReputationTier::grantsTitle)
            ).apply(instance, ReputationTier::new))
            .flatXmap(ReputationTier::validate, ReputationTier::validate);

    public ReputationTier {
        id = id == null ? "" : id.strip();
        description = description == null ? Optional.empty() : description;
        grantsTitle = grantsTitle == null ? Optional.empty() : grantsTitle;
    }

    private static DataResult<ReputationTier> validate(ReputationTier tier) {
        if (tier.id.isEmpty() || tier.id.length() > MAX_ID_LENGTH) {
            return DataResult.error(() -> "tier id must be 1.." + MAX_ID_LENGTH + " characters, got '"
                    + tier.id + "'");
        }
        if (Math.abs(tier.trustBias) >= BIAS_HARD_LIMIT) {
            return DataResult.error(() -> "tier '" + tier.id + "' trust_bias magnitude must be < "
                    + BIAS_HARD_LIMIT + ", got " + tier.trustBias);
        }
        if (Math.abs(tier.respectBias) >= BIAS_HARD_LIMIT) {
            return DataResult.error(() -> "tier '" + tier.id + "' respect_bias magnitude must be < "
                    + BIAS_HARD_LIMIT + ", got " + tier.respectBias);
        }
        return DataResult.success(tier);
    }

    /**
     * The bias for a named Conversations check axis. Only {@code trust} and {@code respect} receive a
     * non-zero term (§30.3); warmth, attraction, tension, and familiarity are private interpersonal
     * state that public standing has no business touching.
     *
     * <p>Hard-clamped here as well as at parse time, so even a definition that reached the registry
     * through some other route cannot exceed the cap Conversations relies on.
     */
    public int biasFor(String axis) {
        if (axis == null) {
            return 0;
        }
        int raw = switch (axis.toLowerCase(java.util.Locale.ROOT)) {
            case "trust" -> trustBias;
            case "respect" -> respectBias;
            default -> 0;
        };
        return Math.max(-BIAS_SHIPPED_LIMIT, Math.min(BIAS_SHIPPED_LIMIT, raw));
    }

    /** True when this tier's values are inside the range shipped content is required to respect. */
    public boolean withinShippedBiasLimit() {
        return Math.abs(trustBias) <= BIAS_SHIPPED_LIMIT && Math.abs(respectBias) <= BIAS_SHIPPED_LIMIT;
    }
}
