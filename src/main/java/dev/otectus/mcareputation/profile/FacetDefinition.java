package dev.otectus.mcareputation.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One facet of a public profile — what a player is <em>known for</em>, as distinct from how well they
 * are liked (spec §8). Loaded from {@code data/<namespace>/mcareputation/facets/**&#47;*.json}; the
 * definition's identity is its resource location and is held by {@link ProfileRegistryBundle}.
 *
 * <h2>Unipolar and bipolar facets</h2>
 *
 * <p>The {@link Range} decides which one this is, and it changes the <em>meaning of zero</em>. For a
 * bipolar facet ({@code min < 0}) zero is a genuine balance of supporting and opposing evidence. For
 * a unipolar facet ({@code min >= 0}) zero never implies the opposite trait (§8.1): an unproven
 * bravery is not cowardice, and an unobserved violence is not innocence. That distinction is why the
 * range is authored data rather than assumed, and why {@code negative_label} may not be authored for
 * a facet that can never hold a negative value.
 *
 * <h2>What a facet is not</h2>
 *
 * <p>Facets are public interpretation, not spendable currency (§8.1). There is no write path here:
 * a facet value exists only as the aggregate of evidence frozen onto accepted deeds, so nothing in
 * this record can move a player's standing on its own.
 */
public record FacetDefinition(
        Component name,
        Optional<Component> description,
        Range range,
        Component positiveLabel,
        Optional<Component> negativeLabel,
        int displayOrder,
        int labelMinMagnitude,
        int labelMinEvidence,
        int opinionWeightBp,
        Map<String, Integer> personalityOverrides) {

    /** §9.6: opinion weights are -20000..20000 basis points. */
    public static final int MAX_OPINION_WEIGHT_BP = 20_000;

    /** Structural ceiling on {@code display_order}; purely to keep the sort key sane. */
    public static final int MAX_DISPLAY_ORDER = 10_000;

    /** §8.2's default: a label needs real magnitude behind it. */
    public static final int DEFAULT_LABEL_MIN_MAGNITUDE = 10;

    /** §8.2's default: at least two distinct credited incidents, unless one is major evidence. */
    public static final int DEFAULT_LABEL_MIN_EVIDENCE = 2;

    /** A label cannot require more evidence than one community can retain. */
    public static final int MAX_LABEL_MIN_EVIDENCE = 64;

    /** Bounded authoring surface for personality sensitivity (§9.2, §13.2). */
    public static final int MAX_PERSONALITY_OVERRIDES = 32;
    public static final int MAX_PERSONALITY_ID_LENGTH = 48;

    /**
     * The inclusive display range of a facet value. §9.6: a subrange of -100..100 that contains zero,
     * so every facet has a meaningful "no evidence" point.
     */
    public record Range(int min, int max) {

        public static final Codec<Range> CODEC = RecordCodecBuilder.<Range>create(instance -> instance.group(
                Codec.INT.fieldOf("min").forGetter(Range::min),
                Codec.INT.fieldOf("max").forGetter(Range::max)
        ).apply(instance, Range::new)).flatXmap(Range::validate, Range::validate);

        private static DataResult<Range> validate(Range range) {
            if (range.min > range.max) {
                return DataResult.error(() -> "range is inverted: min " + range.min
                        + " is above max " + range.max);
            }
            if (range.min < -ProfileMath.MAX_FACET_POINTS || range.max > ProfileMath.MAX_FACET_POINTS) {
                return DataResult.error(() -> "range must sit inside -" + ProfileMath.MAX_FACET_POINTS
                        + ".." + ProfileMath.MAX_FACET_POINTS + ", got " + range.min + ".." + range.max);
            }
            if (range.min > 0 || range.max < 0) {
                return DataResult.error(() -> "range must contain zero, got " + range.min + ".."
                        + range.max);
            }
            if (range.max == 0 && range.min == 0) {
                return DataResult.error(() -> "range 0..0 leaves the facet no values to hold");
            }
            return DataResult.success(range);
        }

        /** True when this facet can hold negative values, i.e. when zero is a real balance. */
        public boolean bipolar() {
            return min < 0;
        }

        /** The largest magnitude the facet can display, used to normalize dominance (§8.2). */
        public int magnitude() {
            return Math.max(Math.abs(min), Math.abs(max));
        }

        /** Whether an authored point value respects this facet's sign and extent (§9.6). */
        public boolean permits(int points) {
            return points >= min && points <= max;
        }
    }

    public static final Codec<FacetDefinition> CODEC = RecordCodecBuilder
            .<FacetDefinition>create(instance -> instance.group(
                    ComponentSerialization.CODEC.fieldOf("name").forGetter(FacetDefinition::name),
                    StrictCodecs.strictOptional(ComponentSerialization.CODEC, "description")
                            .forGetter(FacetDefinition::description),
                    Range.CODEC.fieldOf("range").forGetter(FacetDefinition::range),
                    ComponentSerialization.CODEC.fieldOf("positive_label").forGetter(FacetDefinition::positiveLabel),
                    StrictCodecs.strictOptional(ComponentSerialization.CODEC, "negative_label")
                            .forGetter(FacetDefinition::negativeLabel),
                    StrictCodecs.strictOptional(Codec.INT, "display_order", 0)
                            .forGetter(FacetDefinition::displayOrder),
                    StrictCodecs.strictOptional(Codec.INT, "label_min_magnitude", DEFAULT_LABEL_MIN_MAGNITUDE)
                            .forGetter(FacetDefinition::labelMinMagnitude),
                    StrictCodecs.strictOptional(Codec.INT, "label_min_evidence", DEFAULT_LABEL_MIN_EVIDENCE)
                            .forGetter(FacetDefinition::labelMinEvidence),
                    StrictCodecs.strictOptional(Codec.INT, "opinion_weight_bp", 0)
                            .forGetter(FacetDefinition::opinionWeightBp),
                    StrictCodecs.strictOptional(Codec.unboundedMap(Codec.STRING, Codec.INT),
                                    "personality_overrides", Map.of())
                            .forGetter(FacetDefinition::personalityOverrides)
            ).apply(instance, FacetDefinition::new))
            .flatXmap(FacetDefinition::validate, FacetDefinition::validate);

    public FacetDefinition {
        description = description == null ? Optional.<Component>empty() : description;
        negativeLabel = negativeLabel == null ? Optional.<Component>empty() : negativeLabel;
        // Sorted, not insertion-ordered: two packs that author the same overrides in different orders
        // must produce the same definition, and the iteration order feeds diagnostics output.
        Map<String, Integer> normalized = new TreeMap<>();
        if (personalityOverrides != null) {
            personalityOverrides.forEach((personality, weight) -> {
                if (personality != null && weight != null) {
                    normalized.put(personality.strip().toLowerCase(Locale.ROOT), weight);
                }
            });
        }
        // unmodifiableMap over a LinkedHashMap, not Map.copyOf: Map.copyOf leaves iteration order
        // unspecified, which would reintroduce the cross-JVM instability this normalization removes.
        personalityOverrides = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    private static DataResult<FacetDefinition> validate(FacetDefinition facet) {
        if (Math.abs(facet.displayOrder) > MAX_DISPLAY_ORDER) {
            return DataResult.error(() -> "display_order magnitude must be <= " + MAX_DISPLAY_ORDER
                    + ", got " + facet.displayOrder);
        }
        if (facet.labelMinMagnitude < 1 || facet.labelMinMagnitude > facet.range.magnitude()) {
            return DataResult.error(() -> "label_min_magnitude must be 1.." + facet.range.magnitude()
                    + " (the facet's own range), got " + facet.labelMinMagnitude);
        }
        if (facet.labelMinEvidence < 1 || facet.labelMinEvidence > MAX_LABEL_MIN_EVIDENCE) {
            return DataResult.error(() -> "label_min_evidence must be 1.." + MAX_LABEL_MIN_EVIDENCE
                    + ", got " + facet.labelMinEvidence);
        }
        if (Math.abs(facet.opinionWeightBp) > MAX_OPINION_WEIGHT_BP) {
            return DataResult.error(() -> "opinion_weight_bp must be -" + MAX_OPINION_WEIGHT_BP + ".."
                    + MAX_OPINION_WEIGHT_BP + ", got " + facet.opinionWeightBp);
        }
        // §8.1: a facet that can never be negative must not author a negative label, or the screen
        // would hold a phrase describing a state the data cannot reach.
        if (facet.negativeLabel.isPresent() && !facet.range.bipolar()) {
            return DataResult.error(() -> "negative_label is authored but the range " + facet.range.min()
                    + ".." + facet.range.max() + " can never be negative; a unipolar facet's zero is "
                    + "not the opposite trait");
        }
        if (facet.negativeLabel.isEmpty() && facet.range.bipolar()) {
            return DataResult.error(() -> "a bipolar facet (range " + facet.range.min() + ".."
                    + facet.range.max() + ") must author a negative_label for its negative half");
        }
        if (facet.personalityOverrides.size() > MAX_PERSONALITY_OVERRIDES) {
            return DataResult.error(() -> "personality_overrides must have at most "
                    + MAX_PERSONALITY_OVERRIDES + " entries, got " + facet.personalityOverrides.size());
        }
        for (Map.Entry<String, Integer> entry : facet.personalityOverrides.entrySet()) {
            String personality = entry.getKey();
            if (personality.isEmpty() || personality.length() > MAX_PERSONALITY_ID_LENGTH) {
                return DataResult.error(() -> "personality_overrides key '" + personality
                        + "' must be 1.." + MAX_PERSONALITY_ID_LENGTH + " characters");
            }
            if (Math.abs(entry.getValue()) > MAX_OPINION_WEIGHT_BP) {
                return DataResult.error(() -> "personality_overrides['" + personality + "'] must be -"
                        + MAX_OPINION_WEIGHT_BP + ".." + MAX_OPINION_WEIGHT_BP + ", got "
                        + entry.getValue());
            }
        }
        return DataResult.success(facet);
    }

    /**
     * The interpretation weight an observer with this personality applies, in basis points.
     *
     * <p>§9.2, §13.2: an unresolved or unknown personality uses the default weight. Neutral fallback
     * is mandatory — a villager whose traits cannot be read must not be given a fabricated opinion.
     */
    public int opinionWeightFor(String personality) {
        if (personality == null || personality.isBlank()) {
            return opinionWeightBp;
        }
        return personalityOverrides.getOrDefault(personality.strip().toLowerCase(Locale.ROOT),
                opinionWeightBp);
    }

    /** The label for a value's sign, or empty at zero — zero is never described as either trait. */
    public Optional<Component> labelFor(int value) {
        if (value > 0) {
            return Optional.of(positiveLabel);
        }
        if (value < 0) {
            return negativeLabel;
        }
        return Optional.empty();
    }

    /**
     * A stand-in for a facet whose definition has vanished from the datapacks (§9.4). Stored units
     * survive a pack removal; only presentation degrades, and the weight is neutral so a removed
     * definition cannot change any villager's opinion.
     */
    public static FacetDefinition unknown(net.minecraft.resources.ResourceLocation id) {
        return new FacetDefinition(
                Component.literal(id.toString()),
                Optional.empty(),
                new Range(-ProfileMath.MAX_FACET_POINTS, ProfileMath.MAX_FACET_POINTS),
                Component.literal(id.getPath()),
                Optional.of(Component.literal(id.getPath())),
                MAX_DISPLAY_ORDER,
                DEFAULT_LABEL_MIN_MAGNITUDE,
                DEFAULT_LABEL_MIN_EVIDENCE,
                0,
                Map.of());
    }
}
