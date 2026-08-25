package dev.otectus.mcareputation.incident;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import dev.otectus.mcareputation.util.StrictCodecs;
import net.minecraft.util.ExtraCodecs;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The datapack-authored behaviour of one kind of deed (spec §15), loaded from
 * {@code data/<namespace>/mcareputation/incidents/**&#47;*.json}. The definition's identity is the
 * resource location derived from its namespace and file path and is held by
 * {@link IncidentRegistry}, not by this record.
 *
 * <p>A definition is a <em>template</em>: it says what this kind of deed is normally worth, who
 * normally gets to know about it, and how it normally fades. The actual {@link IncidentRecord} freezes
 * those decisions at the moment the deed happens, so later editing a datapack re-describes future
 * deeds without silently rewriting a player's history.
 *
 * <p>Definitions must be parse-safe: §15 requires an invalid one to be reported and skipped rather
 * than to crash {@code /reload} or world creation. Structural invariants that cannot depend on
 * server config are enforced here in the codec; config-relative caps live in
 * {@code dev.otectus.mcareputation.data.ReputationContentValidator}.
 */
public record IncidentDefinition(
        Component display,
        int defaultDelta,
        IncidentVisibility visibility,
        IncidentSeverity severity,
        List<String> tags,
        Optional<Long> retentionTicks,
        DecayPolicy decay,
        ResolutionPolicy resolution,
        GossipSpec gossip,
        boolean pinned,
        Optional<Integer> maxOverrideAbs,
        boolean retainUnwitnessed,
        boolean allowPrivateScore) {

    /** §16: caller-override types must define safe maximums; this is the default when unstated. */
    public static final int DEFAULT_MAX_OVERRIDE_ABS = 100;

    /**
     * Structural ceiling on any authored delta, independent of server config. The configured score
     * clamp is normally far tighter; this exists purely so a malicious pack cannot put a number in the
     * file that overflows intermediate arithmetic before config validation ever sees it (§33 rule 9).
     */
    public static final int HARD_DELTA_CAP = 10_000;

    public static final int MAX_TAGS = 16;
    public static final int MAX_TAG_LENGTH = 48;

    public static final Codec<IncidentDefinition> CODEC = RecordCodecBuilder
            .<IncidentDefinition>create(instance -> instance.group(
                    // Accepts both {"translate": "..."} objects and a bare string, because
                    // Component.Serializer treats a JSON primitive as a literal component. That is
                    // the same leniency §22.1 requires of the legacy Quests tier/title `name` fields.
                    ExtraCodecs.COMPONENT.fieldOf("display").forGetter(IncidentDefinition::display),
                    Codec.INT.fieldOf("default_delta").forGetter(IncidentDefinition::defaultDelta),
                    IncidentVisibility.CODEC.fieldOf("visibility").forGetter(IncidentDefinition::visibility),
                    IncidentSeverity.CODEC.fieldOf("severity").forGetter(IncidentDefinition::severity),
                    // Strict optional fields throughout: a present-but-malformed `decay` or
                    // `resolution` block must be reported, not silently replaced by the default
                    // (§15, §21.3). See StrictCodecs for why DFU's own optionalFieldOf will not do.
                    StrictCodecs.strictOptional(Codec.STRING.listOf(), "tags", List.of())
                            .forGetter(IncidentDefinition::tags),
                    StrictCodecs.strictOptional(Codec.LONG, "retention_ticks")
                            .forGetter(IncidentDefinition::retentionTicks),
                    StrictCodecs.strictOptional(DecayPolicy.CODEC, "decay", DecayPolicy.NONE)
                            .forGetter(IncidentDefinition::decay),
                    StrictCodecs.strictOptional(ResolutionPolicy.CODEC, "resolution", ResolutionPolicy.DEFAULT)
                            .forGetter(IncidentDefinition::resolution),
                    StrictCodecs.strictOptional(GossipSpec.CODEC, "gossip", GossipSpec.NONE)
                            .forGetter(IncidentDefinition::gossip),
                    StrictCodecs.strictOptional(Codec.BOOL, "pinned", false)
                            .forGetter(IncidentDefinition::pinned),
                    StrictCodecs.strictOptional(Codec.INT, "max_override_abs")
                            .forGetter(IncidentDefinition::maxOverrideAbs),
                    StrictCodecs.strictOptional(Codec.BOOL, "retain_unwitnessed", false)
                            .forGetter(IncidentDefinition::retainUnwitnessed),
                    StrictCodecs.strictOptional(Codec.BOOL, "allow_private_score", false)
                            .forGetter(IncidentDefinition::allowPrivateScore)
            ).apply(instance, IncidentDefinition::new))
            .flatXmap(IncidentDefinition::validate, IncidentDefinition::validate);

    public IncidentDefinition {
        tags = tags == null ? List.of() : List.copyOf(tags.stream()
                .map(t -> t.strip().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty() && t.length() <= MAX_TAG_LENGTH)
                .distinct()
                .limit(MAX_TAGS)
                .toList());
        decay = decay == null ? DecayPolicy.NONE : decay;
        resolution = resolution == null ? ResolutionPolicy.DEFAULT : resolution;
        gossip = gossip == null ? GossipSpec.NONE : gossip;
        retentionTicks = retentionTicks == null ? Optional.<Long>empty() : retentionTicks;
        maxOverrideAbs = maxOverrideAbs == null ? Optional.<Integer>empty() : maxOverrideAbs;
    }

    private static DataResult<IncidentDefinition> validate(IncidentDefinition def) {
        if (Math.abs(def.defaultDelta) > HARD_DELTA_CAP) {
            return DataResult.error(() -> "default_delta magnitude must be <= " + HARD_DELTA_CAP
                    + ", got " + def.defaultDelta);
        }
        // §14.1: private incidents are narrative obligations, not standing. A private definition with
        // a non-zero default would let a pack move public standing for something nobody can know.
        if (def.visibility == IncidentVisibility.PRIVATE && def.defaultDelta != 0 && !def.allowPrivateScore) {
            return DataResult.error(() -> "a private incident must have default_delta 0 (got "
                    + def.defaultDelta + "); set \"allow_private_score\": true only for development");
        }
        if (def.maxOverrideAbs.isPresent()) {
            int cap = def.maxOverrideAbs.get();
            if (cap < 0) {
                return DataResult.error(() -> "max_override_abs must be >= 0, got " + cap);
            }
            if (cap > HARD_DELTA_CAP) {
                return DataResult.error(() -> "max_override_abs must be <= " + HARD_DELTA_CAP + ", got " + cap);
            }
        }
        if (def.retentionTicks.isPresent() && def.retentionTicks.get() < 0L) {
            return DataResult.error(() -> "retention_ticks must be >= 0, got " + def.retentionTicks.get());
        }
        return DataResult.success(def);
    }

    /** The largest magnitude a caller may request for this type via {@code deltaOverride} (§14, §15). */
    public int effectiveMaxOverrideAbs() {
        return maxOverrideAbs.orElse(DEFAULT_MAX_OVERRIDE_ABS);
    }

    /**
     * The delta this incident should actually be created with.
     *
     * <p>A caller override is clamped to {@link #effectiveMaxOverrideAbs()} rather than rejected, so a
     * generous quest reward degrades to the type's ceiling instead of failing the player's turn-in.
     * A private definition without the development override always yields {@code 0} — no override may
     * open a scoring path that the definition itself is forbidden to declare (§14.1).
     */
    public int resolveDelta(OptionalInt override) {
        if (visibility == IncidentVisibility.PRIVATE && !allowPrivateScore) {
            return 0;
        }
        if (override == null || override.isEmpty()) {
            return defaultDelta;
        }
        int cap = effectiveMaxOverrideAbs();
        int requested = override.getAsInt();
        return Math.max(-cap, Math.min(cap, requested));
    }

    public boolean hasTag(String tag) {
        return tag != null && tags.contains(tag.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * A stand-in used when an incident's definition has vanished from the datapacks (§13.6, §35.1).
     * The stored record keeps its own delta, status, and contribution; only presentation degrades, so
     * removing a pack never silently changes anybody's score.
     */
    public static IncidentDefinition unknown(net.minecraft.resources.ResourceLocation id) {
        return new IncidentDefinition(
                Component.translatable("mcareputation.incident.unknown", id.toString()),
                0,
                IncidentVisibility.VILLAGE,
                IncidentSeverity.MINOR,
                List.of(),
                Optional.empty(),
                DecayPolicy.NONE,
                ResolutionPolicy.DEFAULT,
                GossipSpec.NONE,
                false,
                Optional.of(0),
                true,
                false);
    }
}
