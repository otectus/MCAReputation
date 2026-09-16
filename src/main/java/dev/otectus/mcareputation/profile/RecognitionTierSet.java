package dev.otectus.mcareputation.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An ordered ladder of recognition tiers — <b>how well known</b> a player is, not how well liked
 * (spec §7). Loaded from {@code data/<namespace>/mcareputation/recognition_tiers/**&#47;*.json}.
 *
 * <h2>Why this is not the standing ladder</h2>
 *
 * <p>Recognition is non-negative and has no conversion from standing (§7.1). Two opposing deeds that
 * cancel each other's standing deltas still both make a player <em>more</em> widely known, so a
 * notorious villain and a celebrated hero can share a recognition tier while sitting at opposite ends
 * of the standing ladder. That is the point of having a second ladder at all, and it is why
 * {@link dev.otectus.mcareputation.reputation.ReputationTierSet} is not reused: its thresholds are
 * signed and its tiers carry Trust/Respect biases, neither of which recognition has.
 *
 * <p>Tier zero is a real answer for a new player, but it is <b>not</b> a claim that an imported
 * baseline had no history (§7.2). Incomplete historical coverage is reported separately.
 */
public record RecognitionTierSet(List<Tier> tiers) {

    /** Canonical id for the default recognition ladder. */
    public static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("mcareputation", "default");

    /** Structural ceiling on ladder length. */
    public static final int MAX_TIERS = 32;

    /**
     * The shipped ladder (§7.2), duplicated in code for the same reason
     * {@link dev.otectus.mcareputation.reputation.ReputationTiers#BUILTIN_DEFAULT} is: a pack that
     * removes the file must not make every player's recognition tierless. {@code ContentValidationTest}
     * asserts the two do not drift.
     */
    public static final RecognitionTierSet BUILTIN_DEFAULT = new RecognitionTierSet(List.of(
            builtin("unknown", 0),
            builtin("noticed", 5),
            builtin("recognized", 15),
            builtin("well_known", 40),
            builtin("renowned", 90),
            builtin("famous", 180)));

    private static Tier builtin(String id, int threshold) {
        return new Tier(id, threshold,
                Component.translatable("mcareputation.recognition." + id),
                Optional.of(Component.translatable("mcareputation.recognition." + id + ".description")));
    }

    /** One rung. {@code threshold} is the inclusive recognition value at which the tier begins. */
    public record Tier(String id, int threshold, Component name, Optional<Component> description) {

        public static final int MAX_ID_LENGTH = 48;

        public static final Codec<Tier> CODEC = RecordCodecBuilder.<Tier>create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Tier::id),
                Codec.INT.fieldOf("threshold").forGetter(Tier::threshold),
                ComponentSerialization.CODEC.fieldOf("name").forGetter(Tier::name),
                StrictCodecs.strictOptional(ComponentSerialization.CODEC, "description")
                        .forGetter(Tier::description)
        ).apply(instance, Tier::new)).flatXmap(Tier::validate, Tier::validate);

        public Tier {
            id = id == null ? "" : id.strip();
            description = description == null ? Optional.<Component>empty() : description;
        }

        private static DataResult<Tier> validate(Tier tier) {
            if (tier.id.isEmpty() || tier.id.length() > MAX_ID_LENGTH) {
                return DataResult.error(() -> "recognition tier id must be 1.." + MAX_ID_LENGTH
                        + " characters, got '" + tier.id + "'");
            }
            // §9.6: recognition tier thresholds are 0..1000.
            if (tier.threshold < 0 || tier.threshold > ProfileMath.MAX_RECOGNITION) {
                return DataResult.error(() -> "recognition tier '" + tier.id + "' threshold must be 0.."
                        + ProfileMath.MAX_RECOGNITION + ", got " + tier.threshold);
            }
            return DataResult.success(tier);
        }
    }

    public static final Codec<RecognitionTierSet> CODEC = RecordCodecBuilder
            .<RecognitionTierSet>create(instance -> instance.group(
                    Tier.CODEC.listOf().fieldOf("tiers").forGetter(RecognitionTierSet::tiers)
            ).apply(instance, RecognitionTierSet::new))
            .flatXmap(RecognitionTierSet::validate, RecognitionTierSet::validate);

    public RecognitionTierSet {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    /**
     * §9.6: thresholds are strictly increasing with a zero floor tier. The zero floor is not
     * decoration — recognition starts at zero, so a ladder whose lowest rung began above zero would
     * leave an unknown player in no tier at all and force every caller to invent a fallback.
     */
    private static DataResult<RecognitionTierSet> validate(RecognitionTierSet set) {
        if (set.tiers.isEmpty()) {
            return DataResult.error(() -> "a recognition ladder must define at least one tier");
        }
        if (set.tiers.size() > MAX_TIERS) {
            return DataResult.error(() -> "a recognition ladder must define at most " + MAX_TIERS
                    + " tiers, got " + set.tiers.size());
        }
        if (set.tiers.get(0).threshold() != 0) {
            return DataResult.error(() -> "the lowest recognition tier must have threshold 0, got "
                    + set.tiers.get(0).threshold() + " on '" + set.tiers.get(0).id() + "'");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < set.tiers.size(); i++) {
            Tier tier = set.tiers.get(i);
            if (!seen.add(tier.id())) {
                return DataResult.error(() -> "duplicate recognition tier id '" + tier.id() + "'");
            }
            if (i > 0) {
                Tier previous = set.tiers.get(i - 1);
                if (tier.threshold() <= previous.threshold()) {
                    return DataResult.error(() -> "recognition tier thresholds must ascend strictly: '"
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

    /** The tier containing {@code recognition}. Negative input resolves to the zero floor tier. */
    public Tier tierFor(int recognition) {
        Tier match = tiers.get(0);
        for (Tier tier : tiers) {
            if (recognition >= tier.threshold()) {
                match = tier;
            } else {
                break;
            }
        }
        return match;
    }

    public int indexOf(String tierId) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equals(tierId)) {
                return i;
            }
        }
        return -1;
    }

    public Optional<Tier> byId(String tierId) {
        int index = indexOf(tierId);
        return index < 0 ? Optional.empty() : Optional.of(tiers.get(index));
    }

    /** The rung above the one containing {@code recognition}, or empty at the top. */
    public Optional<Tier> nextTier(int recognition) {
        int index = indexOf(tierFor(recognition).id());
        return index + 1 < tiers.size() ? Optional.of(tiers.get(index + 1)) : Optional.empty();
    }
}
