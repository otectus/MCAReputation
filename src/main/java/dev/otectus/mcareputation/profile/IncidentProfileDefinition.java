package dev.otectus.mcareputation.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcareputation.util.EnumCodecs;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The authored social meaning of one kind of deed: how much recognition it creates, which facets it
 * evidences, how long each of those lasts, and which repeat-credit group it competes in (spec §9.3).
 * Loaded from {@code data/<namespace>/mcareputation/incident_profiles/**&#47;*.json}, selected by an
 * incident definition's optional {@code social_profile} field or by an explicit
 * {@code ProfiledDelivery} selection.
 *
 * <h2>Frozen at acceptance, not reinterpreted</h2>
 *
 * <p>These numbers are a <b>template</b>, exactly as {@code IncidentDefinition} is for standing.
 * §9.4 requires the accepted deed to freeze the quantities it was worth, so editing this file changes
 * <em>future</em> deeds and never rewrites what a player already did. Labels and observer weights
 * live in {@link FacetDefinition} and may change on reload; the quantities here may not, once
 * accepted.
 *
 * <h2>No severity-derived recognition</h2>
 *
 * <p>Recognition is authored explicitly or it is zero (§7.1). An incident with no profile contributes
 * no recognition at all rather than an automatic severity bonus, because severity describes impact
 * and impact is not publicity: a quiet catastrophe and a public scuffle are not equally noteworthy.
 */
public record IncidentProfileDefinition(
        List<ResourceLocation> allowedIncidents,
        Optional<Contribution> recognition,
        Map<ResourceLocation, Contribution> facets,
        CreditClass creditClass,
        Optional<ResourceLocation> creditPolicy,
        boolean majorEvidence,
        Optional<Long> decayStepTicks) {

    /** §10.5: at most eight facet entries in one incident. */
    public static final int MAX_FACET_ENTRIES = 8;

    /** Structural ceiling on an authored source allowlist (§9.5). */
    public static final int MAX_ALLOWED_INCIDENTS = 32;

    /**
     * How §10.4 may treat this profile's repetition.
     *
     * <p>Only {@link #COMMENDABLE} may carry a repeat-credit policy. Adverse and mixed profiles get
     * full accountability (I07): a second assault is not cheaper than the first, and a profile that
     * mixes praise and blame defaults to no discount rather than to a per-channel exception Phase 1
     * does not implement.
     */
    public enum CreditClass {
        /** Repeatable, positive, and eligible for a repeat-credit schedule. */
        COMMENDABLE,
        /** Blame. Never discounted. */
        ADVERSE,
        /** Both praise and blame in one deed. Never discounted (§10.4). */
        MIXED,
        /** Neither, e.g. a bookkeeping deed. Never discounted. */
        NEUTRAL;

        public static final Codec<CreditClass> CODEC = EnumCodecs.codec(CreditClass.class);

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether §10.4 permits a repeat-credit discount for this class. */
        public boolean discountable() {
            return this == COMMENDABLE;
        }
    }

    /** §12.1: how a resolution (apology, atonement, forgiveness, disproof) affects a contribution. */
    public enum ResolutionMode {
        /** No moral reduction; ordinary time fading continues. How widely the deed identifies you. */
        RECOGNITION,
        /** Retains the authored evidence; ordinary fading continues. Something actually demonstrated. */
        HISTORICAL,
        /** Applies the frozen authored monotonic multipliers. How a wrong reflects on you now. */
        EVALUATIVE;

        public static final Codec<ResolutionMode> CODEC = EnumCodecs.codec(ResolutionMode.class);

        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The frozen, explicitly authored monotonic multipliers an {@link ResolutionMode#EVALUATIVE}
     * contribution settles at, in basis points (§12.1).
     *
     * <p>Basis points rather than floats so the stored evidence is exact and a save round trip cannot
     * move the number. Non-increasing along the resolution progression and zero at {@code disproven},
     * because §12.1 forbids a resolution progression that would <em>increase</em> magnitude — a
     * "stronger" settlement must never resurrect a contribution a weaker one already reduced.
     */
    public record ResolutionMultipliers(int apologizedBp, int atonedBp, int forgivenBp, int disprovenBp) {

        /** §12.1's default: the existing 0.75 / 0.25 / 0 scalar policy, with disproof at zero. */
        public static final ResolutionMultipliers DEFAULT =
                new ResolutionMultipliers(7500, 2500, 0, 0);

        public static final Codec<ResolutionMultipliers> CODEC = RecordCodecBuilder
                .<ResolutionMultipliers>create(instance -> instance.group(
                        StrictCodecs.strictOptional(Codec.INT, "apologized", DEFAULT.apologizedBp())
                                .forGetter(ResolutionMultipliers::apologizedBp),
                        StrictCodecs.strictOptional(Codec.INT, "atoned", DEFAULT.atonedBp())
                                .forGetter(ResolutionMultipliers::atonedBp),
                        StrictCodecs.strictOptional(Codec.INT, "forgiven", DEFAULT.forgivenBp())
                                .forGetter(ResolutionMultipliers::forgivenBp),
                        StrictCodecs.strictOptional(Codec.INT, "disproven", DEFAULT.disprovenBp())
                                .forGetter(ResolutionMultipliers::disprovenBp)
                ).apply(instance, ResolutionMultipliers::new))
                .flatXmap(ResolutionMultipliers::validate, ResolutionMultipliers::validate);

        private static DataResult<ResolutionMultipliers> validate(ResolutionMultipliers m) {
            int[] progression = {m.apologizedBp, m.atonedBp, m.forgivenBp, m.disprovenBp};
            String[] names = {"apologized", "atoned", "forgiven", "disproven"};
            int previous = ProfileMath.FULL_BP;
            for (int i = 0; i < progression.length; i++) {
                int value = progression[i];
                String name = names[i];
                int prior = previous;
                if (value < 0 || value > ProfileMath.FULL_BP) {
                    return DataResult.error(() -> "resolution_bp." + name + " must be 0.."
                            + ProfileMath.FULL_BP + ", got " + value);
                }
                if (value > prior) {
                    return DataResult.error(() -> "resolution_bp." + name + " (" + value + ") is above "
                            + "the previous step (" + prior + "); a resolution may never increase a "
                            + "contribution's magnitude");
                }
                previous = value;
            }
            if (m.disprovenBp != 0) {
                return DataResult.error(() -> "resolution_bp.disproven must be 0: a disproven deed "
                        + "contributes nothing (§7.3, §12.1), got " + m.disprovenBp);
            }
            return DataResult.success(m);
        }
    }

    /**
     * One authored contribution: how many points, for how long, and how a resolution affects it.
     *
     * @param points          authored points; recognition is 0..100, a facet is -100..100 and must
     *                        also respect its facet's own range and sign (§9.6)
     * @param lifetimeTicks   finite positive linear lifetime, a whole multiple of the decay step
     * @param resolutionMode  §12.1's mode
     * @param resolutionBp    the frozen multipliers an {@code evaluative} contribution settles at
     */
    public record Contribution(
            int points,
            long lifetimeTicks,
            ResolutionMode resolutionMode,
            ResolutionMultipliers resolutionBp) {

        public static final Codec<Contribution> CODEC = RecordCodecBuilder
                .<Contribution>create(instance -> instance.group(
                        Codec.INT.fieldOf("points").forGetter(Contribution::points),
                        Codec.LONG.fieldOf("lifetime_ticks").forGetter(Contribution::lifetimeTicks),
                        StrictCodecs.strictOptional(ResolutionMode.CODEC, "resolution_mode",
                                        ResolutionMode.HISTORICAL)
                                .forGetter(Contribution::resolutionMode),
                        StrictCodecs.strictOptional(ResolutionMultipliers.CODEC, "resolution_bp",
                                        ResolutionMultipliers.DEFAULT)
                                .forGetter(Contribution::resolutionBp)
                ).apply(instance, Contribution::new))
                .flatXmap(Contribution::validate, Contribution::validate);

        public Contribution {
            resolutionMode = resolutionMode == null ? ResolutionMode.HISTORICAL : resolutionMode;
            resolutionBp = resolutionBp == null ? ResolutionMultipliers.DEFAULT : resolutionBp;
        }

        private static DataResult<Contribution> validate(Contribution contribution) {
            if (Math.abs(contribution.points) > ProfileMath.MAX_FACET_POINTS) {
                return DataResult.error(() -> "points must be -" + ProfileMath.MAX_FACET_POINTS + ".."
                        + ProfileMath.MAX_FACET_POINTS + ", got " + contribution.points);
            }
            if (contribution.lifetimeTicks < ProfileMath.MIN_LIFETIME_TICKS
                    || contribution.lifetimeTicks > ProfileMath.MAX_LIFETIME_TICKS) {
                return DataResult.error(() -> "lifetime_ticks must be " + ProfileMath.MIN_LIFETIME_TICKS
                        + ".." + ProfileMath.MAX_LIFETIME_TICKS + " (finite and positive), got "
                        + contribution.lifetimeTicks);
            }
            return DataResult.success(contribution);
        }

        /** The authored magnitude in internal subunits, before repeat credit (§8.3). */
        public long authoredSubunits() {
            return ProfileMath.subunits(points);
        }
    }

    public static final Codec<IncidentProfileDefinition> CODEC = RecordCodecBuilder
            .<IncidentProfileDefinition>create(instance -> instance.group(
                    StrictCodecs.strictOptional(ResourceLocation.CODEC.listOf(), "allowed_incidents",
                                    List.of())
                            .forGetter(IncidentProfileDefinition::allowedIncidents),
                    StrictCodecs.strictOptional(Contribution.CODEC, "recognition")
                            .forGetter(IncidentProfileDefinition::recognition),
                    StrictCodecs.strictOptional(
                                    Codec.unboundedMap(ResourceLocation.CODEC, Contribution.CODEC),
                                    "facets", Map.of())
                            .forGetter(IncidentProfileDefinition::facets),
                    StrictCodecs.strictOptional(CreditClass.CODEC, "credit_class", CreditClass.NEUTRAL)
                            .forGetter(IncidentProfileDefinition::creditClass),
                    StrictCodecs.strictOptional(ResourceLocation.CODEC, "credit_policy")
                            .forGetter(IncidentProfileDefinition::creditPolicy),
                    StrictCodecs.strictOptional(Codec.BOOL, "major_evidence", false)
                            .forGetter(IncidentProfileDefinition::majorEvidence),
                    StrictCodecs.strictOptional(Codec.LONG, "decay_step_ticks")
                            .forGetter(IncidentProfileDefinition::decayStepTicks)
            ).apply(instance, IncidentProfileDefinition::new))
            .flatXmap(IncidentProfileDefinition::validate, IncidentProfileDefinition::validate);

    public IncidentProfileDefinition {
        allowedIncidents = allowedIncidents == null ? List.of() : List.copyOf(allowedIncidents);
        recognition = recognition == null ? Optional.<Contribution>empty() : recognition;
        creditClass = creditClass == null ? CreditClass.NEUTRAL : creditClass;
        creditPolicy = creditPolicy == null ? Optional.<ResourceLocation>empty() : creditPolicy;
        decayStepTicks = decayStepTicks == null ? Optional.<Long>empty() : decayStepTicks;
        // Sorted by id, not by authoring order: the facet iteration order feeds the frozen payload
        // and the dominance tiebreak, so two packs that list the same facets in different orders must
        // produce byte-identical evidence.
        Map<ResourceLocation, Contribution> sorted =
                new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        if (facets != null) {
            facets.forEach((facet, contribution) -> {
                if (facet != null && contribution != null) {
                    sorted.put(facet, contribution);
                }
            });
        }
        facets = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static DataResult<IncidentProfileDefinition> validate(IncidentProfileDefinition profile) {
        if (profile.allowedIncidents.size() > MAX_ALLOWED_INCIDENTS) {
            return DataResult.error(() -> "allowed_incidents must have at most " + MAX_ALLOWED_INCIDENTS
                    + " entries, got " + profile.allowedIncidents.size());
        }
        if (profile.facets.size() > MAX_FACET_ENTRIES) {
            return DataResult.error(() -> "facets must have at most " + MAX_FACET_ENTRIES
                    + " entries, got " + profile.facets.size());
        }
        // §9.6: authored recognition points are 0..100. Negative recognition is not a concept:
        // becoming infamous makes you better known, not less known (§7.1).
        if (profile.recognition.isPresent()) {
            Contribution recognition = profile.recognition.get();
            if (recognition.points() < 0 || recognition.points() > ProfileMath.MAX_RECOGNITION_POINTS) {
                return DataResult.error(() -> "recognition.points must be 0.."
                        + ProfileMath.MAX_RECOGNITION_POINTS + ", got " + recognition.points());
            }
        }
        long step = profile.effectiveDecayStepTicks();
        if (profile.decayStepTicks.isPresent()
                && (step < ProfileMath.MIN_DECAY_STEP_TICKS || step > ProfileMath.MAX_DECAY_STEP_TICKS)) {
            return DataResult.error(() -> "decay_step_ticks must be " + ProfileMath.MIN_DECAY_STEP_TICKS
                    + ".." + ProfileMath.MAX_DECAY_STEP_TICKS + ", got " + step);
        }
        for (Map.Entry<ResourceLocation, Contribution> entry : profile.allContributions().entrySet()) {
            String channel = entry.getKey() == null ? "recognition" : "facets." + entry.getKey();
            Contribution contribution = entry.getValue();
            if (contribution.lifetimeTicks() < step) {
                return DataResult.error(() -> channel + ".lifetime_ticks (" + contribution.lifetimeTicks()
                        + ") must be at least the decay step (" + step + "); a contribution that "
                        + "expires inside its first step is never worth anything");
            }
            // §9.3: a lifetime must be a whole multiple of the step it is quantized by, or the last
            // partial step silently truncates and the authored lifetime is not the real one.
            if (contribution.lifetimeTicks() % step != 0L) {
                return DataResult.error(() -> channel + ".lifetime_ticks ("
                        + contribution.lifetimeTicks() + ") must be a whole multiple of the decay step ("
                        + step + ")");
            }
        }
        // §10.4: a policy on a class that may not be discounted is a contradiction, not a no-op.
        // Silently ignoring it would let a pack believe it had limited farming when it had not.
        if (profile.creditPolicy.isPresent() && !profile.creditClass.discountable()) {
            return DataResult.error(() -> "credit_policy is authored but credit_class is '"
                    + profile.creditClass.jsonName() + "'; only 'commendable' may be discounted (§10.4)");
        }
        // A commendable profile with adverse effects would let repetition make harm cheaper.
        if (profile.creditClass == CreditClass.COMMENDABLE) {
            for (Map.Entry<ResourceLocation, Contribution> entry : profile.facets.entrySet()) {
                if (entry.getValue().points() < 0) {
                    return DataResult.error(() -> "credit_class 'commendable' but facet "
                            + entry.getKey() + " contributes " + entry.getValue().points()
                            + "; a profile with adverse effects is 'mixed' or 'adverse' (§10.4, I07)");
                }
            }
        }
        if (profile.recognition.isEmpty() && profile.facets.isEmpty()) {
            return DataResult.error(() -> "a profile must author recognition, at least one facet, or "
                    + "both; an empty profile is indistinguishable from having none");
        }
        return DataResult.success(profile);
    }

    /** §9.3: the authored decay step, or the default whole-day step. */
    public long effectiveDecayStepTicks() {
        return decayStepTicks.orElse(ProfileMath.DEFAULT_DECAY_STEP_TICKS);
    }

    /**
     * Every authored contribution, keyed by facet id, with {@code null} standing for the recognition
     * channel. Iteration order is the recognition channel first, then facets in id order.
     */
    public Map<ResourceLocation, Contribution> allContributions() {
        Map<ResourceLocation, Contribution> all = new LinkedHashMap<>();
        recognition.ifPresent(contribution -> all.put(null, contribution));
        all.putAll(facets);
        return Collections.unmodifiableMap(all);
    }

    /** §9.5: whether this profile may be attached to {@code incident}. An empty allowlist permits any. */
    public boolean permits(ResourceLocation incident) {
        return allowedIncidents.isEmpty() || (incident != null && allowedIncidents.contains(incident));
    }

    /** Whether §10.4 permits a repeat-credit discount for this profile. */
    public boolean discountable() {
        return creditClass.discountable() && creditPolicy.isPresent();
    }
}
